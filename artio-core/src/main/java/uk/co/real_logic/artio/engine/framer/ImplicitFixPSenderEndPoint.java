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
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;
import uk.co.real_logic.artio.messages.DisconnectReason;

import java.io.IOException;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.fixp.AbstractFixPOffsets.templateId;
import static uk.co.real_logic.artio.fixp.AbstractFixPSequenceExtractor.FORCE_START_REPLAY_CORR_ID;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.readSofhMessageSize;
import static uk.co.real_logic.artio.messages.DisconnectReason.SLOW_CONSUMER;

/**
 * FIXP protocols with implicit sequence numbers. Don't currently support full interleaving and needs to send
 * an explicit sequence message when sending messages from the library
 */
class ImplicitFixPSenderEndPoint extends FixPSenderEndPoint
{
    private final int templateIdOffset;
    private final int retransmissionTemplateId;
    private final FixPSenderEndPoints fixPSenderEndPoints;
    private final ReattemptState normalBuffer = new ReattemptState();
    private final ReattemptState retransmitBuffer = new ReattemptState();

    private boolean retransmitting;
    private boolean requiresReattempting;

    protected ImplicitFixPSenderEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final ErrorHandler errorHandler,
        final ExclusivePublication inboundPublication,
        final ReproductionLogWriter reproductionLogWriter,
        final int libraryId,
        final int templateIdOffset,
        final int retransmissionTemplateId,
        final FixPSenderEndPoints fixPSenderEndPoints,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final Framer framer,
        final FixPReceiverEndPoint receiverEndPoint,
        final SenderSequenceNumber senderSequenceNumber)
    {
        super(connectionId, channel, errorHandler, inboundPublication, reproductionLogWriter, libraryId,
            bytesInBuffer, maxBytesInBuffer, framer, receiverEndPoint, senderSequenceNumber);
        this.templateIdOffset = templateIdOffset;
        this.retransmissionTemplateId = retransmissionTemplateId;
        this.fixPSenderEndPoints = fixPSenderEndPoints;
    }

    public Action onMessage(final DirectBuffer directBuffer, final int offset, final boolean retransmit)
    {
        try
        {
            final int headerOffset = offset + SOFH_LENGTH;
            final int templateId = templateId(directBuffer, headerOffset, templateIdOffset);

            final int messageSize = readSofhMessageSize(directBuffer, offset);

            if ((retransmitting && !retransmit) || (!retransmitting && retransmit) || reattemptBytesWritten > 0)
            {
                enqueue(directBuffer, offset, messageSize, retransmit);
                return CONTINUE;
            }

            final int totalWritten = writeBuffer(directBuffer, offset, messageSize);

            if (totalWritten < messageSize)
            {
                this.reattemptBytesWritten = totalWritten;
                enqueue(directBuffer, offset, messageSize, retransmit);
            }
            else
            {
                this.reattemptBytesWritten = NO_REATTEMPT;

                if (templateId == retransmissionTemplateId)
                {
                    retransmitting = true;

                    processReattemptBuffer(true);

                    // retransmission messages can have a sequence buffered up for resend.
                    final int secondOffset = offset + messageSize;
                    final int secondSize = readSofhMessageSize(directBuffer, secondOffset);
                    enqueue(directBuffer, secondOffset, secondSize, retransmit);
                }
            }
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);

            receiverEndPoint.disconnectEndpoint(DisconnectReason.EXCEPTION);
        }

        return CONTINUE;
    }

    public Action onReplayComplete(final long correlationId)
    {
        if (!retransmitting)
        {
            return ABORT;
        }

        if (!processReattemptBuffer(true))
        {
            return ABORT;
        }

        retransmitting = false;

        processReattemptBuffer(false);

        return super.onReplayComplete(correlationId);
    }

    void enqueue(final DirectBuffer srcBuffer, final int srcOffst, final int messageSize, final boolean retransmit)
    {
        // we only need re-attempting when we've got messages buffered for the current state
        final boolean currentStream = retransmit == retransmitting;
        if (!requiresReattempting && currentStream)
        {
            requiresReattempting = true;
            fixPSenderEndPoints.backPressured(this);
        }

        final ReattemptState reattemptState = reattemptState(retransmit);
        final int reattemptOffset = reattemptState.usage;
        final ExpandableDirectByteBuffer buffer = reattemptState.buffer();

        final int bufferUsage = reattemptOffset + messageSize;
        reattemptState.usage = bufferUsage;
        if (currentStream)
        {
            if (bufferUsage > maxBytesInBuffer)
            {
                removeEndpoint(SLOW_CONSUMER);
            }

            bytesInBuffer.setOrdered(bufferUsage);
        }
        buffer.putBytes(reattemptOffset, srcBuffer, srcOffst, messageSize);
    }

    private void removeEndpoint(final DisconnectReason reason)
    {
        framer.onDisconnect(libraryId, connectionId, reason);
    }

    private ReattemptState reattemptState(final boolean retransmit)
    {
        return retransmit ? retransmitBuffer : normalBuffer;
    }

    private boolean processReattemptBuffer(final boolean retransmit)
    {
        final ReattemptState reattemptState = reattemptState(retransmit);
        final ExpandableDirectByteBuffer buffer = reattemptState.buffer;
        final int reattemptBufferUsage = reattemptState.usage;

        int offset = 0;
        while (offset < reattemptBufferUsage)
        {
            try
            {
                final int messageSize = readSofhMessageSize(buffer, offset);
                final int totalWritten = writeBuffer(buffer, offset, messageSize);
                if (totalWritten < messageSize)
                {
                    this.reattemptBytesWritten = totalWritten;
                    break;
                }
                else
                {
                    offset += totalWritten;
                    this.reattemptBytesWritten = NO_REATTEMPT;
                }
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
                // falls through to buffer shuffle below
                break;
            }
        }

        final int usage = reattemptState.shuffleWritten(offset);
        bytesInBuffer.setOrdered(usage);
        return usage == 0;
    }

    public boolean reattempt()
    {
        final boolean caughtUp = processReattemptBuffer(retransmitting);
        if (caughtUp)
        {
            requiresReattempting = false;
        }
        return caughtUp;
    }

    public void onStartReplay(final long correlationId)
    {
        // We do a Fake replay of messages to support the next session version id feature
        // This doesn't send a retransmit FIXP message so we internally trigger that state
        if (correlationId == FORCE_START_REPLAY_CORR_ID)
        {
            retransmitting = true;
        }
    }

    static class ReattemptState
    {
        ExpandableDirectByteBuffer buffer;
        int usage;

        ExpandableDirectByteBuffer buffer()
        {
            ExpandableDirectByteBuffer buffer = this.buffer;
            if (buffer == null)
            {
                buffer = this.buffer = new ExpandableDirectByteBuffer();
            }

            buffer.checkLimit(usage);

            return buffer;
        }

        int shuffleWritten(final int written)
        {
            int usage = this.usage;
            if (written > 0)
            {
                usage -= written;
                buffer.putBytes(0, buffer, written, usage);
                this.usage = usage;
            }
            return usage;
        }
    }

}
