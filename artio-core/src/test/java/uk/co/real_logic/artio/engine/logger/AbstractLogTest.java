/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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

import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.BitUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.decoder.ExampleMessageDecoder;
import uk.co.real_logic.artio.decoder.TestRequestDecoder;
import uk.co.real_logic.artio.fields.UtcTimestampDecoder;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.FixMessageEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.TestFixtures.MESSAGE_BUFFER_SIZE_IN_BYTES;
import static uk.co.real_logic.artio.engine.logger.Replayer.SIZE_OF_LENGTH_FIELD;
import static uk.co.real_logic.artio.engine.logger.Replayer.START_REPLAY_LENGTH;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataHeaderLength;

public class AbstractLogTest
{
    protected static final String ORIGINAL_SENDING_TIME = "19700101-00:00:00";
    protected static final long ORIGINAL_SENDING_EPOCH_MS =
        new UtcTimestampDecoder(true).decode(ORIGINAL_SENDING_TIME.getBytes(US_ASCII));

    protected static final long SESSION_ID = 1;
    protected static final long SESSION_ID_2 = 2;

    protected static final long CONNECTION_ID = 1;
    protected static final long CONNECTION_ID_2 = 2;

    protected static final int STREAM_ID = DEFAULT_OUTBOUND_LIBRARY_STREAM;
    protected static final int START = FRAME_ALIGNMENT;
    protected static final int SEQUENCE_NUMBER = 2;
    protected static final int LIBRARY_ID = 7;
    protected static final int BEGIN_SEQ_NO = 2;
    protected static final int END_SEQ_NO = 2;
    protected static final int SEQUENCE_INDEX = 1;
    private static final int ENCODE_OFFSET = 1;
    static final String BUFFER_SENDER = "sender";
    static final String BUFFER_TARGET = "target";
    private static final String RESEND_SENDER = "target";
    private static final String RESEND_TARGET = "sender";
    static final String RESEND_TARGET_2 = "sender2";
    public static final int PREFIX_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FixMessageEncoder.BLOCK_LENGTH + SIZE_OF_LENGTH_FIELD +
        metaDataHeaderLength();
    public static final int BIG_BUFFER_LENGTH = MESSAGE_BUFFER_SIZE_IN_BYTES + 500;

    protected MessageHeaderEncoder header = new MessageHeaderEncoder();
    protected FixMessageEncoder messageFrame = new FixMessageEncoder();

    protected ExclusivePublication publication = mock(ExclusivePublication.class);
    protected BufferClaim claim = mock(BufferClaim.class);
    protected UnsafeBuffer resultBuffer;
    protected MutableAsciiBuffer resultAsciiBuffer = new MutableAsciiBuffer();

    protected UnsafeBuffer buffer = new UnsafeBuffer(new byte[BIG_BUFFER_LENGTH]);

    protected int logEntryLength;
    protected int offset;

    protected void bufferContainsExampleMessage(final boolean hasPossDupFlag)
    {
        bufferContainsExampleMessage(hasPossDupFlag, SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX);
    }

    protected void bufferContainsExampleMessage(
        final boolean hasPossDupFlag, final long sessionId, final int sequenceNumber, final int sequenceIndex)
    {
        bufferContainsExampleMessage(hasPossDupFlag, sessionId, sequenceNumber, sequenceIndex, "abc");
    }

    protected void bufferContainsExampleMessage(
        final boolean hasPossDupFlag,
        final long sessionId,
        final int sequenceNumber,
        final int sequenceIndex,
        final String testReqId)
    {
        final ExampleMessageEncoder exampleMessage = new ExampleMessageEncoder();
        final HeaderEncoder header = exampleMessage.header();
        exampleMessage.testReqID(testReqId);

        if (hasPossDupFlag)
        {
            // NB: set to false to check that it gets flipped upon resend
            header.possDupFlag(false);
            header.origSendingTime(ORIGINAL_SENDING_TIME.getBytes(US_ASCII));
        }

        bufferContainsMessage(
            sessionId, sequenceNumber, sequenceIndex, exampleMessage, header, ExampleMessageDecoder.MESSAGE_TYPE);
    }

    protected void bufferContainsTestRequest(final int sequenceNumber)
    {
        final TestRequestEncoder testRequestEncoder = new TestRequestEncoder();
        final HeaderEncoder header = testRequestEncoder.header();
        testRequestEncoder.testReqID("abc");
        header.possDupFlag(false);

        bufferContainsMessage(
            SESSION_ID, sequenceNumber, SEQUENCE_INDEX, testRequestEncoder, header, TestRequestDecoder.MESSAGE_TYPE);
    }

    protected void bufferContainsMessage(
        final long sessionId,
        final int sequenceNumber,
        final int sequenceIndex,
        final Encoder exampleMessage,
        final HeaderEncoder header,
        final long messageType)
    {
        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        final int timestampLength = timestampEncoder.encode(ORIGINAL_SENDING_EPOCH_MS);
        MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(new byte[BIG_BUFFER_LENGTH]);

        header
            .sendingTime(timestampEncoder.buffer(), timestampLength)
            .senderCompID(BUFFER_SENDER)
            .targetCompID(BUFFER_TARGET)
            .msgSeqNum(sequenceNumber);

        final long result = exampleMessage.encode(asciiBuffer, 0);
        logEntryLength = Encoder.length(result);
        final int encodedOffset = Encoder.offset(result);
        asciiBuffer = new MutableAsciiBuffer(asciiBuffer, encodedOffset, logEntryLength);

        bufferContainsMessage(sessionId, sequenceIndex, asciiBuffer, messageType);
    }

    protected void bufferContainsMessage(
        final long sessionId,
        final int sequenceIndex,
        final MutableAsciiBuffer asciiBuffer,
        final long messageType)
    {
        offset = START;

        messageFrame
            .wrapAndApplyHeader(buffer, offset, header)
            .session(sessionId)
            .connection(CONNECTION_ID)
            .status(MessageStatus.OK)
            .sequenceIndex(sequenceIndex)
            .libraryId(LIBRARY_ID)
            .messageType(messageType)
            .putMetaData(new byte[0], 0, 0)
            .putBody(asciiBuffer, 0, logEntryLength);

        offset += PREFIX_LENGTH;
    }

    protected int fragmentLength()
    {
        return endPosition() - START;
    }

    protected int endPosition()
    {
        return offset + logEntryLength;
    }

    protected int alignedEndPosition()
    {
        return BitUtil.align(endPosition(), FRAME_ALIGNMENT);
    }

    protected long bufferHasResendRequest(final int endSeqNo)
    {
        return bufferHasResendRequest(endSeqNo, RESEND_TARGET);
    }

    protected long bufferHasResendRequest(final int endSeqNo, final String targetCompId)
    {
        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        timestampEncoder.encode(System.currentTimeMillis());

        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();

        resendRequest
            .header()
            .sendingTime(timestampEncoder.buffer())
            .msgSeqNum(1)
            .senderCompID(RESEND_SENDER)
            .targetCompID(targetCompId);

        return resendRequest
            .beginSeqNo(BEGIN_SEQ_NO)
            .endSeqNo(endSeqNo)
            .encode(new MutableAsciiBuffer(buffer), ENCODE_OFFSET);
    }

    protected void setupPublication(final int srcLength)
    {
        when(publication.tryClaim(srcLength, claim)).thenReturn((long)srcLength);
    }

    protected void backpressureTryClaim()
    {
        when(publication.tryClaim(anyInt(), any())).thenReturn(Publication.BACK_PRESSURED);
        when(publication.tryClaim(eq(START_REPLAY_LENGTH), any())).thenReturn(1L);
    }

    protected void setupClaim(final int srcLength)
    {
        final int offset = offset();
        resultBuffer = new UnsafeBuffer(new byte[offset + srcLength]);
        resultAsciiBuffer.wrap(resultBuffer);
        when(claim.buffer()).thenReturn(resultBuffer);
        when(claim.offset()).thenReturn(offset);
        when(claim.length()).thenReturn(srcLength);
    }

    protected int claimedLength = -1;

    protected int setupCapturingClaim()
    {
        final int offset = offset();
        when(publication.tryClaim(anyInt(), eq(claim))).then(inv ->
        {
            claimedLength = (int)inv.getArguments()[0];
            resultBuffer = new UnsafeBuffer(new byte[offset + claimedLength]);
            resultAsciiBuffer.wrap(resultBuffer);
            when(claim.buffer()).thenReturn(resultBuffer);
            when(claim.offset()).thenReturn(offset);
            when(claim.length()).thenReturn(claimedLength);

            return (long)offset;
        });
        return offset;
    }

    protected int offset()
    {
        return START + 1;
    }

    protected void verifyCommit(final VerificationMode times)
    {
        verify(claim, times).commit();
    }
}
