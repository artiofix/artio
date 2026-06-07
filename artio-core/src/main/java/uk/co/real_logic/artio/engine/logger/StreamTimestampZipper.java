/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.FragmentAssembler;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;
import uk.co.real_logic.artio.messages.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.Math.min;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.*;

public class StreamTimestampZipper implements AutoCloseable
{
    private static final TimestampComparator TIMESTAMP_COMPARATOR = new TimestampComparator();
    private static final UnstableReverseTimestampComparator REVERSE_TIMESTAMP_COMPARATOR =
        new UnstableReverseTimestampComparator();
    private static final OffsetComparator OFFSET_COMPARATOR = new OffsetComparator();

    private final int maximumBufferSize;
    private final int compactionSize;
    private final StreamPoller[] pollers;
    private final FragmentAssembler fragmentAssembler;
    private final LogEntryHandler logEntryHandler;
    private final ExpandableArrayBuffer reorderBuffer;
    private final boolean lazilyCompact;

    private final ArrayList<BufferedPosition> positions = new ArrayList<>();

    private int reorderBufferOffset;

    public StreamTimestampZipper(
        final FixMessageConsumer fixMessageConsumer,
        final FixPMessageConsumer fixPMessageConsumer,
        final int compactionSize,
        final int maximumBufferSize,
        final boolean lazilyCompact,
        final Poller... pollers)
    {
        this.maximumBufferSize = maximumBufferSize;
        this.lazilyCompact = lazilyCompact;
        this.compactionSize = compactionSize;
        this.pollers = new StreamPoller[pollers.length];
        for (int i = 0; i < pollers.length; i++)
        {
            this.pollers[i] = new StreamPoller(pollers[i]);
        }

        reorderBuffer = new ExpandableArrayBuffer(compactionSize);
        logEntryHandler = new LogEntryHandler(fixMessageConsumer, fixPMessageConsumer);
        fragmentAssembler = new FragmentAssembler(logEntryHandler);
    }

    public int poll(final int fragmentLimit)
    {
        int read = 0;
        final StreamPoller[] pollers = this.pollers;
        final int size = pollers.length;
        for (int i = 0; i < size; i++)
        {
            read += pollers[i].poll(pollers, fragmentAssembler, fragmentLimit);
            if (read >= fragmentLimit)
            {
                break;
            }
        }

        // Lazily compact: only process reorder buffer and compact when you hit the compaction size
        // Can generate a significant speed in batch archive scanning at the expense of greater latency
        // on handing off messages to the handler
        if (read > 0 && (read <= fragmentLimit) && (!lazilyCompact || reorderBufferOffset > compactionSize))
        {
            read += processReorderBuffer(pollers, fragmentLimit - read);

            compact();
        }

        return read;
    }

    private int processReorderBuffer(final StreamPoller[] pollers, final int fragmentLimit)
    {
        final ArrayList<BufferedPosition> positions = this.positions;

        int read = 0;
        positions.sort(REVERSE_TIMESTAMP_COMPARATOR);

        final int lastIndex = positions.size() - 1;
        int i = lastIndex;
        for (; i >= 0; i--)
        {
            final BufferedPosition position = positions.get(i);
            final long timestamp = position.timestamp;
            final StreamPoller owner = position.owner;
            final long timestampLowWaterMark = findMinLowWaterMark(pollers, owner);

            if (timestamp <= timestampLowWaterMark)
            {
                owner.handledTimestamp(timestamp);
                owner.elementsInBuffer--;
                logEntryHandler.owner = owner;
                logEntryHandler.onBufferedMessage(position.offset, position.length);
                read++;

                updateOwnerTimestamp(positions, i, owner);

                if (read >= fragmentLimit)
                {
                    break;
                }
            }
            else
            {
                break;
            }
        }

        i++;

        for (int j = lastIndex; j >= i; j--)
        {
            positions.remove(j);
        }

        return read;
    }

    private void updateOwnerTimestamp(
        final ArrayList<BufferedPosition> positions, final int i, final StreamPoller owner)
    {
        // Find the next entry with the same owner and set the owner's timestamp to the next
        // timestamp
        for (int j = i - 1; j >= 0; j--)
        {
            final BufferedPosition remainingPosition = positions.get(j);
            if (remainingPosition.owner == owner)
            {
                // don't go through method here because this might increase the min buffered timestamp.
                owner.minBufferedTimestamp = remainingPosition.timestamp;
                return;
            }
        }

        owner.nothingBuffered();
    }

    private boolean compact()
    {
        if (reorderBufferOffset > compactionSize)
        {
            positions.sort(OFFSET_COMPARATOR);
            int reorderBufferOffset = 0;
            for (final BufferedPosition position : positions)
            {
                final int offset = position.offset;
                // No compaction possible
                if (offset == reorderBufferOffset)
                {
                    return false;
                }

                final int length = position.length;
                position.offset = reorderBufferOffset;
                reorderBuffer.putBytes(reorderBufferOffset, reorderBuffer, offset, length);

                final int newReorderBufferOffset = reorderBufferOffset + length;
                validateReorderBufferOffset(length, reorderBufferOffset, newReorderBufferOffset);
                reorderBufferOffset = newReorderBufferOffset;
            }
            this.reorderBufferOffset = reorderBufferOffset;
        }

        return true;
    }

    public int bufferPosition()
    {
        return reorderBufferOffset;
    }

    public int bufferCapacity()
    {
        return reorderBuffer.capacity();
    }

    private void dumpBuffer()
    {
        final StreamPoller originalOwner = this.logEntryHandler.owner;

        final LogEntryHandler logEntryHandler = this.logEntryHandler;
        final List<BufferedPosition> positions = this.positions;
        final int size = positions.size();

        positions.sort(TIMESTAMP_COMPARATOR);

        for (int i = 0; i < size; i++)
        {
            final BufferedPosition position = positions.get(i);
            logEntryHandler.owner = position.owner;
            logEntryHandler.onBufferedMessage(position.offset, position.length);
        }

        positions.clear();
        for (final StreamPoller poller : pollers)
        {
            poller.elementsInBuffer = 0;
            poller.nothingBuffered();
        }
        reorderBufferOffset = 0;

        this.logEntryHandler.owner = originalOwner;
    }

    public void close()
    {
        dumpBuffer();

        for (final StreamPoller poller : pollers)
        {
            poller.close();
        }
    }

    static class BufferedPosition
    {
        final StreamPoller owner;
        final long timestamp;
        int offset;
        final int length;

        BufferedPosition(final StreamPoller owner, final long timestamp, final int offset, final int length)
        {
            this.owner = owner;
            this.timestamp = timestamp;
            this.offset = offset;
            this.length = length;
        }

        public String toString()
        {
            return "BufferedPosition{" +
                "owner=" + owner.poller.streamId() +
                ", timestamp=" + timestamp +
                ", offset=" + offset +
                ", length=" + length +
                '}';
        }
    }

    static class TimestampComparator implements Comparator<BufferedPosition>
    {
        public int compare(final BufferedPosition o1, final BufferedPosition o2)
        {
            return Long.compare(o1.timestamp, o2.timestamp);
        }
    }

    static class UnstableReverseTimestampComparator implements Comparator<BufferedPosition>
    {
        public int compare(final BufferedPosition o1, final BufferedPosition o2)
        {
            final int timestampCompare = Long.compare(o2.timestamp, o1.timestamp);

            // When we have two messages with an equal timestamp we don't want to have a stable sort, we want it to
            // reverse the origin positions, so we then compare based upon the offset of the positions.
            if (timestampCompare == 0)
            {
                return Long.compare(o2.offset, o1.offset);
            }

            return timestampCompare;
        }
    }

    static class OffsetComparator implements Comparator<BufferedPosition>
    {
        public int compare(final BufferedPosition o1, final BufferedPosition o2)
        {
            return Long.compare(o1.offset, o2.offset);
        }
    }

    class StreamPoller
    {
        private static final long NOTHING_BUFFERED = -1;

        private final ArtioLogHeader header;
        private final Poller poller;
        private long minBufferedTimestamp = NOTHING_BUFFERED;
        private long maxHandledTimestamp;
        private boolean isDrained = false;
        private int elementsInBuffer = 0;

        StreamPoller(final Poller poller)
        {
            this.poller = poller;
            header = new ArtioLogHeader(poller.streamId());
        }

        public int poll(
            final StreamPoller[] pollers, final FragmentAssembler fragmentAssembler, final int fragmentLimit)
        {
            final long minOtherTimestamp = findMinLowWaterMark(pollers, this);
            logEntryHandler.reset(minOtherTimestamp, this);
            return poller.poll(fragmentAssembler, fragmentLimit);
        }

        // This is the position at which it is safe for other streams to emit below.
        long timestampLowWaterMark()
        {
            return minBufferedTimestamp == NOTHING_BUFFERED ? maxHandledTimestamp : minBufferedTimestamp;
        }

        void handledTimestamp(final long timestamp)
        {
            maxHandledTimestamp = Math.max(maxHandledTimestamp, timestamp);
        }

        void bufferedTimestamp(final long timestamp)
        {
            if (minBufferedTimestamp == NOTHING_BUFFERED)
            {
                minBufferedTimestamp = timestamp;
            }
            else
            {
                minBufferedTimestamp = min(minBufferedTimestamp, timestamp);
            }
        }

        public void nothingBuffered()
        {
            minBufferedTimestamp = NOTHING_BUFFERED;
        }

        public String toString()
        {
            return "StreamPoller{" +
                "header=" + header +
                ", isDrained=" + isDrained +
                ", poller=" + poller +
                '}';
        }

        public void close()
        {
            poller.close();
        }

        boolean isDrained()
        {
            if (isDrained)
            {
                return true;
            }

            if (!poller.isComplete())
            {
                return false;
            }

            if (elementsInBuffer > 0)
            {
                return false;
            }

            isDrained = true;
            return true;
        }
    }

    private long findMinLowWaterMark(final StreamPoller[] pollers, final StreamPoller owner)
    {
        long timestampLowWaterMark = Long.MAX_VALUE;
        for (int i = 0; i < pollers.length; i++)
        {
            final StreamPoller poller = pollers[i];
            // If the poller has already complete, then there's definitely no more messages from it, so we can
            // just ignore its timestamp
            if (poller != owner && !poller.isDrained())
            {
                timestampLowWaterMark = min(timestampLowWaterMark, poller.timestampLowWaterMark());
            }
        }
        return timestampLowWaterMark;
    }

    class LogEntryHandler implements FragmentHandler
    {
        private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
        private final FixMessageDecoder fixMessage = new FixMessageDecoder();
        private final FixPMessageDecoder fixpMessage = new FixPMessageDecoder();
        private final ReplayerTimestampDecoder replayerTimestamp = new ReplayerTimestampDecoder();
        private final ApplicationHeartbeatDecoder applicationHeartbeat = new ApplicationHeartbeatDecoder();
        private final ConnectDecoder connect = new ConnectDecoder();

        private final FixMessageConsumer fixHandler;
        private final ReproductionFixProtocolConsumer reproductionFixProtocolHandler;
        private final FixPMessageConsumer fixPHandler;

        StreamPoller owner;
        long maxTimestampToHandle;

        LogEntryHandler(final FixMessageConsumer fixHandler, final FixPMessageConsumer fixPHandler)
        {
            this.fixHandler = fixHandler;
            reproductionFixProtocolHandler = fixHandler instanceof ReproductionFixProtocolConsumer ?
                (ReproductionFixProtocolConsumer)fixHandler : null;
            this.fixPHandler = fixPHandler;
        }

        public void onFragment(
            final DirectBuffer buffer, final int start, final int length, final Header header)
        {
            int offset = start;
            messageHeader.wrap(buffer, offset);
            final int templateId = messageHeader.templateId();
            final int blockLength = messageHeader.blockLength();
            final int version = messageHeader.version();

            if (templateId == FixMessageDecoder.TEMPLATE_ID)
            {
                onFixMessage(buffer, start, length, offset, blockLength, version);
            }
            else if (templateId == ReplayerTimestampDecoder.TEMPLATE_ID)
            {
                onReplayTimestamp(buffer, offset, blockLength, version);
            }
            else if (templateId == ApplicationHeartbeatDecoder.TEMPLATE_ID)
            {
                offset += MessageHeaderDecoder.ENCODED_LENGTH;

                applicationHeartbeat.wrap(buffer, offset, blockLength, version);
                final long timestampInNs = applicationHeartbeat.timestampInNs();
                final ReproductionFixProtocolConsumer reproductionHandler = this.reproductionFixProtocolHandler;
                if (reproductionHandler != null)
                {
                    if (timestampInNs <= maxTimestampToHandle)
                    {
                        owner.handledTimestamp(timestampInNs);
                        reproductionHandler.onApplicationHeartbeat(applicationHeartbeat, buffer, offset, length);
                    }
                    else
                    {
                        putBufferedMessage(buffer, start, length, timestampInNs);
                    }
                }
                else
                {
                    owner.handledTimestamp(timestampInNs);
                }
            }
            else if (templateId == FixPMessageDecoder.TEMPLATE_ID)
            {
                offset += MessageHeaderDecoder.ENCODED_LENGTH;

                fixpMessage.wrap(buffer, offset, blockLength, version);

                offset += FixPMessageDecoder.BLOCK_LENGTH;

                final long timestamp = fixpMessage.enqueueTime();

                if (timestamp <= maxTimestampToHandle)
                {
                    owner.handledTimestamp(timestamp);
                    fixPHandler.onMessage(fixpMessage, buffer, offset, owner.header);
                }
                else
                {
                    putBufferedMessage(buffer, start, length, timestamp);
                }
            }
            else if (templateId == ConnectDecoder.TEMPLATE_ID)
            {
                final ReproductionFixProtocolConsumer reproductionHandler = this.reproductionFixProtocolHandler;
                if (reproductionHandler != null)
                {
                    offset += MessageHeaderDecoder.ENCODED_LENGTH;

                    connect.wrap(buffer, offset, blockLength, version);

                    final long timestamp = connect.timestamp();

                    if (timestamp <= maxTimestampToHandle)
                    {
                        owner.handledTimestamp(timestamp);
                        reproductionHandler.onConnect(connect, buffer, start, length);
                    }
                    else
                    {
                        putBufferedMessage(buffer, start, length, timestamp);
                    }
                }
            }
        }

        private void onReplayTimestamp(
            final DirectBuffer buffer, final int start, final int blockLength, final int version)
        {
            final int offset = start + MessageHeaderDecoder.ENCODED_LENGTH;

            replayerTimestamp.wrap(buffer, offset, blockLength, version);
            final long timestampInNs = replayerTimestamp.timestamp();
            owner.handledTimestamp(timestampInNs);
        }

        private void onFixMessage(
            final DirectBuffer buffer,
            final int start, final int length, final int prevOffset, final int blockLength, final int version)
        {
            int offset = prevOffset + MessageHeaderDecoder.ENCODED_LENGTH;

            final FixMessageDecoder fixMessage = this.fixMessage;
            fixMessage.wrap(buffer, offset, blockLength, version);

            if (version >= metaDataSinceVersion())
            {
                offset += metaDataHeaderLength() + fixMessage.metaDataLength();
                fixMessage.skipMetaData();
            }

            final long timestamp = fixMessage.timestamp();

            // hand off the first message you see, otherwise buffer it.
            if (timestamp <= maxTimestampToHandle)
            {
                owner.handledTimestamp(timestamp);
                onFixMessage(offset, buffer, fixMessage);
            }
            else
            {
                putBufferedMessage(buffer, start, length, timestamp);
            }
        }

        private void putBufferedMessage(
            final DirectBuffer buffer, final int start, final int length, final long timestamp)
        {
            if (reorderBufferOffset + length > maximumBufferSize)
            {
                dumpBuffer();
            }

            final int reorderBufferOffset = StreamTimestampZipper.this.reorderBufferOffset;
            owner.bufferedTimestamp(timestamp);
            owner.elementsInBuffer++;
            reorderBuffer.putBytes(reorderBufferOffset, buffer, start, length);
            positions.add(new BufferedPosition(owner, timestamp, reorderBufferOffset, length));

            final int newReorderBufferOffset = reorderBufferOffset + length;
            StreamTimestampZipper.this.reorderBufferOffset = newReorderBufferOffset;
            validateReorderBufferOffset(length, reorderBufferOffset, newReorderBufferOffset);
        }

        void reset(final long minOtherTimestamp, final StreamPoller owner)
        {
            maxTimestampToHandle = minOtherTimestamp;
            this.owner = owner;
        }

        public void onBufferedMessage(final int start, final int length)
        {
            int offset = start;

            final ExpandableArrayBuffer buffer = StreamTimestampZipper.this.reorderBuffer;
            final MessageHeaderDecoder messageHeader = this.messageHeader;
            messageHeader.wrap(buffer, offset);
            final int templateId = messageHeader.templateId();
            final int blockLength = messageHeader.blockLength();
            final int version = messageHeader.version();

            offset += MessageHeaderDecoder.ENCODED_LENGTH;

            if (templateId == FixMessageDecoder.TEMPLATE_ID)
            {
                final FixMessageDecoder fixMessage = this.fixMessage;
                fixMessage.wrap(buffer, offset, blockLength, version);

                if (version >= metaDataSinceVersion())
                {
                    offset += metaDataHeaderLength() + fixMessage.metaDataLength();
                    fixMessage.skipMetaData();
                }

                onFixMessage(offset, buffer, fixMessage);
            }
            else if (templateId == FixPMessageDecoder.TEMPLATE_ID)
            {
                fixpMessage.wrap(buffer, offset, blockLength, version);

                offset += FixPMessageDecoder.BLOCK_LENGTH;

                fixPHandler.onMessage(fixpMessage, buffer, offset, owner.header);
            }
            else if (templateId == ConnectDecoder.TEMPLATE_ID)
            {
                final ReproductionFixProtocolConsumer reproductionHandler = this.reproductionFixProtocolHandler;
                if (reproductionHandler != null)
                {
                    final ConnectDecoder connect = this.connect;
                    connect.wrap(buffer, offset, blockLength, version);
                    reproductionHandler.onConnect(connect, buffer, start, length);
                }
            }
            else if (templateId == ApplicationHeartbeatDecoder.TEMPLATE_ID)
            {
                final ReproductionFixProtocolConsumer reproductionHandler = this.reproductionFixProtocolHandler;
                if (reproductionHandler != null)
                {
                    final ApplicationHeartbeatDecoder applicationHeartbeat = this.applicationHeartbeat;
                    applicationHeartbeat.wrap(buffer, offset, blockLength, version);
                    reproductionHandler.onApplicationHeartbeat(applicationHeartbeat, buffer, offset, length);
                }
            }
        }

        private void onFixMessage(final int offset, final DirectBuffer buffer, final FixMessageDecoder fixMessage)
        {
            final int messageLength = fixMessage.bodyLength();
            fixHandler.onMessage(fixMessage, buffer,
                offset + FixMessageDecoder.BLOCK_LENGTH + bodyHeaderLength(), messageLength, owner.header);
        }
    }

    private static void validateReorderBufferOffset(
        final int length, final int reorderBufferOffset, final int newReorderBufferOffset)
    {
        if (newReorderBufferOffset < 0)
        {
            throw new IllegalStateException("Detected negative newReorderBufferOffset: " +
                newReorderBufferOffset + ", reorderBufferOffset=" + reorderBufferOffset + ", length=" + length);
        }
    }

    public interface Poller
    {
        int poll(FragmentAssembler fragmentAssembler, int fragmentLimit);

        int streamId();

        void close();

        boolean isComplete();
    }
}
