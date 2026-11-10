/*
 * Copyright 2022 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.engine.ReproductionMessageHandler;
import uk.co.real_logic.artio.messages.ConnectDecoder;
import uk.co.real_logic.artio.util.CharFormatter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collections;
import java.util.List;

import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;

public class ReproductionTcpChannelSupplier extends TcpChannelSupplier
{
    private final CharFormatter missedEvent = new CharFormatter("Missed BP event: conn=%s,index=%s-%s");

    private final Long2ObjectHashMap<ReproductionTcpChannel> connectionIdToChannel = new Long2ObjectHashMap<>();

    private final ReproductionMessageHandler reproductionMessageHandler;
    private final ReproductionLog reproductionLog;

    private long connectionId;
    private String address;
    private Runnable endOperation;

    public ReproductionTcpChannelSupplier(
        final ReproductionMessageHandler reproductionMessageHandler, final ReproductionLog reproductionLog)
    {
        this.reproductionMessageHandler = reproductionMessageHandler;
        this.reproductionLog = reproductionLog;
    }

    public void registerEndOperation(final Runnable endOperation)
    {
        this.endOperation = endOperation;
    }

    class ReproductionTcpChannel extends TcpChannel
    {
        private final ExpandableArrayBuffer reproductionBuffer = new ExpandableArrayBuffer();

        private final List<ConnectionBackPressureEvent> events;
        private int nextEvent = 0;

        private final long connectionId;
        private int length;
        private boolean isResendRequest;

        ReproductionTcpChannel(final long connectionId, final List<ConnectionBackPressureEvent> events)
            throws IOException
        {
            super(address);
            this.connectionId = connectionId;
            this.events = events;
        }

        @Override
        public SelectionKey register(final Selector sel, final int ops, final Object att)
            throws ClosedChannelException
        {
            return null; // we null-check elsewhere so this is safe
        }

        @Override
        public int write(final ByteBuffer src, final int seqNum, final boolean replay) throws IOException
        {
            ByteBuffer writeBuffer = src;

            final List<ConnectionBackPressureEvent> events = this.events;
            if (events != null)
            {
                writeBuffer = checkBackpressureEvent(src, seqNum, replay, events);
            }

            final int remaining = writeBuffer.remaining();
            reproductionMessageHandler.onMessage(connectionId, writeBuffer);
            return remaining;
        }

        private ByteBuffer checkBackpressureEvent(
            final ByteBuffer src,
            final int seqNum,
            final boolean replay,
            final List<ConnectionBackPressureEvent> events)
        {
            final int nextEvent = this.nextEvent;

            if (nextEvent < events.size())
            {
                ConnectionBackPressureEvent event = events.get(nextEvent);
                if (event.replay() == replay)
                {
                    int eventSeqNum = event.seqNum();
                    // We've gone past this point somehow
                    if (seqNum > eventSeqNum)
                    {
                        int candidateEventNum = nextEvent;
                        while (candidateEventNum < events.size())
                        {
                            event = events.get(candidateEventNum);
                            eventSeqNum = event.seqNum();

                            if (replay == event.replay() && seqNum >= eventSeqNum)
                            {
                                break;
                            }
                            candidateEventNum++;
                        }
                        this.nextEvent = candidateEventNum;

                        if (IS_REPLAY_LOG_TAG_ENABLED)
                        {
                            missedEvent.clear().with(connectionId).with(nextEvent).with(candidateEventNum);
                            DebugLogger.log(LogTag.REPRODUCTION, missedEvent);
                        }
                    }

                    if (seqNum == eventSeqNum)
                    {
                        final int written = event.written();
                        final ByteBuffer writeBuffer = src.duplicate();
                        writeBuffer.limit(writeBuffer.position() + written);
                        this.nextEvent = nextEvent + 1;
                        return writeBuffer;
                    }
                }
            }

            return src;
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException
        {
            final int length = this.length;
            if (length > 0)
            {
                reproductionBuffer.getBytes(0, dst, length);
                this.length = 0;
                if (!isResendRequest)
                {
                    endOperation.run();
                }
                return length;
            }

            return 0;
        }

        @Override
        public void close()
        {
        }

        @Override
        public void onReplayComplete(final long correlationId)
        {
            endOperation.run();
        }

        public boolean enqueueMessage(
            final DirectBuffer buffer, final int initialOffset, final int messageOffset,
            final int length,
            final boolean isResendRequest)
        {
            if (this.length != 0)
            {
                return false;
            }

            reproductionBuffer.putBytes(0, buffer, initialOffset + messageOffset, length);
            this.isResendRequest = isResendRequest;
            this.length = length;
            return true;
        }
    }

    @Override
    public void open(final InetSocketAddress address, final InitiatedChannelHandler channelHandler) throws IOException
    {
    }

    @Override
    public void stopConnecting(final InetSocketAddress address) throws IOException
    {
    }

    @Override
    public int pollSelector(final long timeInMs, final NewChannelHandler handler) throws IOException
    {
        if (address != null)
        {
            final ReproductionTcpChannel channel = new ReproductionTcpChannel(
                connectionId, getEvents());
            connectionIdToChannel.put(connectionId, channel);
            handler.onNewChannel(timeInMs, channel);
            address = null;
        }

        return 0;
    }

    private List<ConnectionBackPressureEvent> getEvents()
    {
        if (reproductionLog == null)
        {
            return Collections.emptyList();
        }

        return reproductionLog.lookupEvents(connectionId);
    }

    @Override
    public void unbind() throws IOException
    {
    }

    @Override
    public void bind() throws IOException
    {
    }

    @Override
    public void close() throws Exception
    {
    }

    public void enqueueConnect(final ConnectDecoder connectDecoder)
    {
        connectionId = connectDecoder.connection();
        address = connectDecoder.address();
    }

    public boolean enqueueMessage(
        final long connectionId,
        final DirectBuffer buffer,
        final int initialOffset,
        final int messageOffset,
        final int length,
        final boolean isResendRequest)
    {
        final ReproductionTcpChannel channel = connectionIdToChannel.get(connectionId);
        if (channel != null)
        {
            return channel.enqueueMessage(buffer, initialOffset, messageOffset, length, isResendRequest);
        }

        return false;
    }
}
