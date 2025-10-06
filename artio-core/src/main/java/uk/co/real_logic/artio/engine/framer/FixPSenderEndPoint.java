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
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.MessageTimingHandler;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;

import java.io.IOException;
import java.nio.ByteBuffer;

import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE_TCP;

abstract class FixPSenderEndPoint extends SenderEndPoint
{
    protected static final int NO_REATTEMPT = 0;

    protected final FixPReceiverEndPoint receiverEndPoint;
    protected int reattemptBytesWritten = NO_REATTEMPT;

    static FixPSenderEndPoint of(
        final long connectionId,
        final TcpChannel channel,
        final ErrorHandler errorHandler,
        final ExclusivePublication inboundPublication,
        final ReproductionLogWriter reproductionPublication,
        final int libraryId,
        final MessageTimingHandler messageTimingHandler,
        final boolean explicitSequenceNumbers,
        final int templateIdOffset,
        final int retransmissionTemplateId,
        final FixPSenderEndPoints fixPSenderEndPoints,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final Framer framer,
        final FixPReceiverEndPoint receiverEndPoint,
        final SenderSequenceNumber senderSequenceNumber)
    {
        if (explicitSequenceNumbers)
        {
            return new ExplicitFixPSenderEndPoint(
                connectionId, channel, errorHandler, inboundPublication, reproductionPublication, libraryId,
                messageTimingHandler,
                bytesInBuffer, maxBytesInBuffer, framer, receiverEndPoint, senderSequenceNumber);
        }
        else
        {
            return new ImplicitFixPSenderEndPoint(
                connectionId, channel, errorHandler, inboundPublication, reproductionPublication, libraryId,
                templateIdOffset, retransmissionTemplateId, fixPSenderEndPoints,
                bytesInBuffer, maxBytesInBuffer, framer, receiverEndPoint, senderSequenceNumber);
        }
    }

    FixPSenderEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final ErrorHandler errorHandler,
        final ExclusivePublication inboundPublication,
        final ReproductionLogWriter reproductionLogWriter,
        final int libraryId,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final Framer framer,
        final FixPReceiverEndPoint receiverEndPoint,
        final SenderSequenceNumber senderSequenceNumber)
    {
        super(connectionId, inboundPublication, reproductionLogWriter, libraryId, channel, bytesInBuffer,
            maxBytesInBuffer, errorHandler,
            framer, senderSequenceNumber);
        this.receiverEndPoint = receiverEndPoint;
    }

    public abstract Action onMessage(DirectBuffer directBuffer, int offset, boolean retransmit);

    protected int writeBuffer(
        final DirectBuffer directBuffer, final int offset, final int messageSize) throws IOException
    {
        final int reattemptBytesWritten = this.reattemptBytesWritten;

        final ByteBuffer buffer = directBuffer.byteBuffer();
        final int bufferOffset = directBuffer.wrapAdjustment() + offset;
        final int startLimit = buffer.limit();
        final int startPosition = buffer.position();

        ByteBufferUtil.limit(buffer, bufferOffset + messageSize);
        ByteBufferUtil.position(buffer, reattemptBytesWritten + bufferOffset);

        final int written = channel.write(buffer, 0, false);
        ByteBufferUtil.position(buffer, bufferOffset);
        DebugLogger.logBytes(FIX_MESSAGE_TCP, "Written  ", buffer, startPosition, written);

        buffer.limit(startLimit).position(startPosition);

        return reattemptBytesWritten + written;
    }

    abstract boolean reattempt();
}
