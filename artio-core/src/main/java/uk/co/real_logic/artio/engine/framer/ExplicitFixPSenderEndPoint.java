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
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.engine.MessageTimingHandler;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;

import java.io.IOException;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.fixp.AbstractFixPOffsets.MISSING_OFFSET;
import static uk.co.real_logic.artio.fixp.AbstractFixPOffsets.clientSeqNum;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.readSofhMessageSize;

/**
 * FixP protocol versions with explicit sequence numbers, we support
 * interleaving for them.
 */
class ExplicitFixPSenderEndPoint extends FixPSenderEndPoint
{
    private final MessageTimingHandler messageTimingHandler;

    ExplicitFixPSenderEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final ErrorHandler errorHandler,
        final ExclusivePublication inboundPublication,
        final ReproductionLogWriter reproductionLogWriter,
        final int libraryId,
        final MessageTimingHandler messageTimingHandler,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final Framer framer,
        final FixPReceiverEndPoint receiverEndPoint,
        final SenderSequenceNumber senderSequenceNumber)
    {
        super(connectionId, channel, errorHandler, inboundPublication, reproductionLogWriter,
            libraryId, bytesInBuffer, maxBytesInBuffer,
            framer, receiverEndPoint, senderSequenceNumber);
        this.messageTimingHandler = messageTimingHandler;
    }

    public Action onMessage(final DirectBuffer directBuffer, final int offset, final boolean retransmit)
    {
        try
        {
            final int messageSize = readSofhMessageSize(directBuffer, offset);
            final int totalWritten = writeBuffer(directBuffer, offset, messageSize);
            if (totalWritten < messageSize)
            {
                this.reattemptBytesWritten = totalWritten;

                return ABORT;
            }
            else
            {
                final MessageTimingHandler messageTimingHandler = this.messageTimingHandler;
                if (messageTimingHandler != null)
                {
                    final int sbeHeaderOffset = offset + SOFH_LENGTH;
                    final long sequenceNumber = clientSeqNum(directBuffer, sbeHeaderOffset);
                    if (sequenceNumber != MISSING_OFFSET)
                    {
                        messageTimingHandler.onMessage(
                            sequenceNumber, connectionId, directBuffer, 0, 0);
                    }
                }

                this.reattemptBytesWritten = NO_REATTEMPT;
            }
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
        }

        return CONTINUE;
    }

    boolean reattempt()
    {
        return true;
    }

    public void onStartReplay(final long correlationId)
    {
    }
}
