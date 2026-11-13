/*
 * Copyright 2020-2021 Monotonic Ltd.
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

import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.fixp.FixPRejectRefIdExtractor;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.FixPMessageEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE_TCP;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.readSofh;
import static uk.co.real_logic.artio.messages.DisconnectReason.INVALID_FIXP_MESSAGE;
import static uk.co.real_logic.artio.messages.GatewayError.EXCEPTION;

abstract class FixPReceiverEndPoint extends ReceiverEndPoint
{
    public static final int ARTIO_HEADER_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FixPMessageEncoder.BLOCK_LENGTH;

    private static final int TEMPLATE_ID_OFFSET = SOFH_LENGTH + 2;

    private final FixPMessageEncoder fixPMessage = new FixPMessageEncoder();
    private final UnsafeBuffer headerBuffer = new UnsafeBuffer(new byte[ARTIO_HEADER_LENGTH]);
    private final ExclusivePublication inboundPublication;
    private final EpochNanoClock epochNanoClock;
    private final long correlationId;
    private final short encodingType;
    private final FixPRejectRefIdExtractor fixPRejectRefIdExtractor;

    protected FixPGatewaySession fixPGatewaySession;
    private long sessionId;

    FixPReceiverEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final int bufferSize,
        final ErrorHandler errorHandler,
        final Framer framer,
        final GatewayPublication publication,
        final int libraryId,
        final EpochNanoClock epochNanoClock,
        final long correlationId,
        final short encodingType,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages,
        final FixPRejectRefIdExtractor fixPRejectRefIdExtractor)
    {
        super(publication, channel, connectionId, bufferSize, errorHandler, framer, libraryId,
            throttleWindowInMs, throttleLimitOfMessages);
        inboundPublication = publication.dataPublication();
        this.epochNanoClock = epochNanoClock;
        this.correlationId = correlationId;
        this.encodingType = encodingType;
        this.fixPRejectRefIdExtractor = fixPRejectRefIdExtractor;

        makeHeader();
    }

    private void makeHeader()
    {
        final MessageHeaderEncoder header = new MessageHeaderEncoder();

        fixPMessage
            .wrapAndApplyHeader(headerBuffer, 0, header)
            .connection(connectionId);
    }

    public void sessionId(final long sessionId)
    {
        this.sessionId = sessionId;
        fixPMessage.sessionId(sessionId);
    }

    @Override
    void removeEndpointFromFramer()
    {
        trackDisconnect();
        framer.onDisconnect(libraryId, connectionId, null);
    }

    @Override
    void cleanupDisconnectState(final DisconnectReason reason)
    {
        // Not needed in iLink implementation
    }

    @Override
    boolean retryFrameMessages()
    {
        return frameMessages();
    }

    private int readData() throws IOException
    {
        final int dataRead = channel.read(byteBuffer);
        if (dataRead != SOCKET_DISCONNECTED)
        {
            if (dataRead > 0)
            {
                DebugLogger.logBytes(FIX_MESSAGE_TCP, "Read     ", byteBuffer, usedBufferData, dataRead);
            }
            usedBufferData += dataRead;
        }
        else
        {
            onDisconnectDetected();
        }

        return dataRead;
    }

    @Override
    int poll()
    {
        if (pendingAcceptorLogon != null)
        {
            return pollPendingAcceptorLogon();
        }

        try
        {
            final int bytesRead = readData();
            if (bytesRead > 0)
            {
                return frameMessages() ? bytesRead : -bytesRead;
            }
            else if (usedBufferData > 0)
            {
                return frameMessages() ? 1 : -1;
            }

            return bytesRead;
        }
        catch (final ClosedChannelException ex)
        {
            onDisconnectDetected();
        }
        catch (final IllegalArgumentException ex)
        {
            errorHandler.onError(ex);
            saveError(ex);
            completeDisconnect(INVALID_FIXP_MESSAGE);
        }
        catch (final Exception ex)
        {
            // Regular disconnects aren't errors
            if (!Exceptions.isJustDisconnect(ex))
            {
                errorHandler.onError(ex);
            }

            saveError(ex);
            onDisconnectDetected();
        }

        return 1;
    }

    private int pollPendingAcceptorLogon()
    {
        if (pendingAcceptorLogon.poll())
        {
            if (!pendingAcceptorLogon.isAccepted())
            {
                completeDisconnect(pendingAcceptorLogon.reason());
            }

            pendingAcceptorLogon = null;
        }

        return 1;
    }

    private void saveError(final Exception ex)
    {
        framer.saveError(EXCEPTION, libraryId, correlationId, ex.getMessage());
    }

    // false iff back pressured
    private boolean frameMessages()
    {
        final MutableAsciiBuffer buffer = this.buffer;

        int offset = 0;
        while (usedBufferData > SOFH_LENGTH)
        {
            final int messageSize = readSofh(buffer, offset, encodingType);
            if (messageSize > usedBufferData)
            {
                moveRemainingDataToBufferStart(offset);
                return true;
            }

            checkMessage(buffer, offset, messageSize);
            final long nanoTime = epochNanoClock.nanoTime();
            if (shouldThrottle(nanoTime))
            {
                if (!throttleMessage(buffer, offset))
                {
                    moveRemainingDataToBufferStart(offset);
                    return false;
                }
            }
            else
            {
                fixPMessage.enqueueTime(nanoTime);

                final long position = inboundPublication.offer(
                    headerBuffer,
                    0,
                    ARTIO_HEADER_LENGTH,
                    buffer,
                    offset,
                    messageSize);

                if (position < 0)
                {
                    moveRemainingDataToBufferStart(offset);
                    return false;
                }
            }

            usedBufferData -= messageSize;
            offset += messageSize;
        }

        moveRemainingDataToBufferStart(offset);
        return true;
    }

    private boolean throttleMessage(
        final DirectBuffer buffer, final int offset)
    {
        final FixPRejectRefIdExtractor fixPRejectRefIdExtractor = this.fixPRejectRefIdExtractor;
        fixPRejectRefIdExtractor.search(buffer, offset);

        final long refMsgType = fixPRejectRefIdExtractor.messageType();
        final int refIdOffset = fixPRejectRefIdExtractor.offset();
        final int refIdLength = fixPRejectRefIdExtractor.length();

        final long position = publication.saveThrottleNotification(
            libraryId,
            connectionId,
            refMsgType,
            Session.UNKNOWN,
            sessionId,
            Session.UNKNOWN,
            buffer, refIdOffset, refIdLength);

        return position > 0;
    }

    abstract void checkMessage(MutableAsciiBuffer buffer, int offset, int messageSize);

    static int readTemplateId(final MutableAsciiBuffer buffer, final int offset)
    {
        return (buffer.getShort(offset + TEMPLATE_ID_OFFSET, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
    }

    private void moveRemainingDataToBufferStart(final int offset)
    {
        if (usedBufferData > 0)
        {
            buffer.putBytes(0, buffer, offset, usedBufferData);
        }
        // position set to ensure that back pressure is applied to TCP when read(byteBuffer) called.
        ByteBufferUtil.position(byteBuffer, usedBufferData);
    }

    @Override
    void closeResources()
    {
        trackDisconnect();

        try
        {
            channel.close();
        }
        catch (final Exception ex)
        {
            errorHandler.onError(ex);
        }
    }

    public void gatewaySession(final FixPGatewaySession fixPGatewaySession)
    {
        this.fixPGatewaySession = fixPGatewaySession;
    }

    @Override
    boolean sendRejectedPendingLogon()
    {
        return true;
    }

    abstract void trackDisconnect();
}
