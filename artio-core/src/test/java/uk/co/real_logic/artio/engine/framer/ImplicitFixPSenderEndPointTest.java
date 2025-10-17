/*
 * Copyright 2021 Monotonic Ltd.
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
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.engine.MessageTimingHandler;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.fixp.AbstractFixPParser.STANDARD_TEMPLATE_ID_OFFSET;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.writeBinaryEntryPointSofh;
import static uk.co.real_logic.artio.messages.DisconnectReason.SLOW_CONSUMER;

public class ImplicitFixPSenderEndPointTest
{
    private static final int CONNECTION_ID = 1;
    private static final int CORRELATION_ID = 3;
    private static final int LIBRARY_ID = 2;
    private static final int OFFSET = 1;
    private static final int CAPACITY = 256;
    private static final int MSG_SIZE = 64;
    private static final int HEADER_LENGTH = 8;

    private static final int TEMPLATE_INDEX = SOFH_LENGTH + STANDARD_TEMPLATE_ID_OFFSET;
    private static final int START_INDEX = SOFH_LENGTH + HEADER_LENGTH;

    public static final int RETRANSMISSION_ID = 13;
    public static final int SEQUENCE_ID = 8;

    private final TcpChannel channel = mock(TcpChannel.class);
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private final ExclusivePublication inboundPublication = mock(ExclusivePublication.class);
    private final ReproductionLogWriter reproductionPublication = mock(ReproductionLogWriter.class);
    private final MessageTimingHandler timingHandler = mock(MessageTimingHandler.class);
    private final FixPSenderEndPoints fixPSenderEndPoints = mock(FixPSenderEndPoints.class);
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(CAPACITY));
    private final AtomicCounter bytesInBuffer = new AtomicCounter(new UnsafeBuffer(new byte[128]), 0);
    private final Framer framer = mock(Framer.class);

    private final FixPSenderEndPoint endPoint = FixPSenderEndPoint.of(
        CONNECTION_ID,
        channel,
        errorHandler,
        inboundPublication,
        reproductionPublication,
        LIBRARY_ID,
        timingHandler,
        false,
        STANDARD_TEMPLATE_ID_OFFSET,
        RETRANSMISSION_ID,
        fixPSenderEndPoints,
        bytesInBuffer,
        CAPACITY,
        framer,
        mock(FixPReceiverEndPoint.class),
        mock(SenderSequenceNumber.class));

    @Before
    public void setup()
    {
        when(inboundPublication.tryClaim(anyInt(), any())).then(invocation ->
        {
            final BufferClaim claim = invocation.getArgument(1);
            final int length = invocation.getArgument(0);
            final int totalLength = length + DataHeaderFlyweight.HEADER_LENGTH;
            claim.wrap(new UnsafeBuffer(new byte[totalLength]), 0, totalLength);
            return 1L;
        });
    }

    @Test
    public void shouldWriteNormalMessage() throws IOException
    {
        onSentMessage(1, false);
    }

    @Test
    public void shouldReattemptBackPressuredWrite() throws IOException
    {
        onBackpressuredMessage(1, false);

        verifyRegistered();
        assertBytesInBuffer(MSG_SIZE);

        onWrite().then(inv -> checkBackpressuredResent(1, inv));
        reattempt();
        verifyWritten(1);

        onSentMessage(2, false);
    }

    @Test
    public void shouldReattemptBackpressuredWriteRepeatedly() throws IOException
    {
        onBackpressuredMessage(1, false);

        verifyRegistered();

        onWrite().thenReturn(0);
        incompleteReattempt();
        verifyWritten(1);

        assertBytesInBuffer(MSG_SIZE);

        onWrite().then(inv -> checkBackpressuredResent(1, inv));
        reattempt();
        verifyWritten(1);

        onSentMessage(2, false);
    }

    @Test
    public void shouldReattemptBackpressuredWrites() throws IOException
    {
        onBackpressuredMessage(1, false);
        verifyRegistered();

        onNotSentMessage(2, false);

        assertBytesInBuffer(2 * MSG_SIZE);

        onWrite()
            .then(inv -> checkBackpressuredResent(1, inv))
            .then(inv -> checkMessageFullySent(2, inv));
        reattempt();
        verifyWritten(2);

        onSentMessage(3, false);
    }

    @Test
    public void shouldDisconnectStreamWhenOverMaxBytesInBuffer() throws IOException
    {
        onBackpressuredMessage(1, false);
        verifyRegistered();

        onNotSentMessage(2, false);
        onNotSentMessage(3, false);
        onNotSentMessage(4, false);
        verifySlowDisconnect(never());

        onNotSentMessage(5, false);

        verifySlowDisconnect(times(1));
        bytesInBuffer.set(0);
    }

    private void verifySlowDisconnect(final VerificationMode mode)
    {
        verify(framer, mode).onDisconnect(LIBRARY_ID, CONNECTION_ID, SLOW_CONSUMER);
    }

    @Test
    public void shouldReattemptBackpressuredWritesRepeatedly() throws IOException
    {
        onBackpressuredMessage(1, false);
        verifyRegistered();
        onNotSentMessage(2, false);

        assertBytesInBuffer(2 * MSG_SIZE);

        onWrite()
            .then(inv -> checkBackpressuredResent(1, inv))
            .thenReturn(0);
        incompleteReattempt();
        verifyWritten(2);

        assertBytesInBuffer(MSG_SIZE);

        onWrite().then(inv -> checkMessageFullySent(2, inv));
        reattempt();
        verifyWritten(1);

        onSentMessage(3, false);
    }

    @Test
    public void shouldRetransmit() throws IOException
    {
        // receives retransmit and buffers sequence for after retransmit
        onMessage(3, RETRANSMISSION_ID, CONTINUE, MSG_SIZE, MSG_SIZE, false);

        // retransmit messages
        onSentMessage(1, true);
        onSentMessage(2, true);

        // verify sequence sent
        onWrite().then(inv -> checkMessageFullySent(4, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(1);

        onSentMessage(5, false);
    }

    @Test
    public void shouldReattemptBackPressuredRetransmit() throws IOException
    {
        onMessage(3, RETRANSMISSION_ID, CONTINUE, MSG_SIZE, MSG_SIZE, false);

        onBackpressuredMessage(1, true);
        verifyRegistered();

        onNotSentMessage(2, true);

        assertBytesInBuffer(2 * MSG_SIZE);

        onWrite()
            .then(inv -> checkBackpressuredResent(1, inv))
            .then(inv -> checkMessageFullySent(2, inv))
            .then(inv -> checkMessageFullySent(4, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(3);

        onSentMessage(5, false);
    }

    @Test
    public void shouldBufferLateNormalMessagesDuringRetransmit() throws IOException
    {
        onMessage(3, RETRANSMISSION_ID, CONTINUE, MSG_SIZE, MSG_SIZE, false);

        onSentMessage(1, true);
        // late arriving normal message
        onNotSentMessage(5, false);
        onSentMessage(2, true);

        // verify sequence sent
        onWrite()
            .then(inv -> checkMessageFullySent(4, inv))
            .then(inv -> checkMessageFullySent(5, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(2);

        onSentMessage(6, false);
    }

    @Test
    public void shouldBufferEarlyRetransmitMessages() throws IOException
    {
        // receive retransmit message earlier than the retransmission (the streams aren't sync'd)
        onNotSentMessage(1, true);

        // because we're not reading from that stream they don't count as bytes in buffer
        assertBytesInBuffer(0);

        onWrite()
            .then(inv -> checkMessageSent(3, RETRANSMISSION_ID, MSG_SIZE, MSG_SIZE, inv))
            .then(inv -> checkMessageFullySent(1, inv));
        endpointOnMessage(3, RETRANSMISSION_ID, CONTINUE, false);
        verifyWritten(2);

        // retransmit messages
        onSentMessage(2, true);

        // verify sequence sent
        onWrite().then(inv -> checkMessageFullySent(4, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(1);

        onSentMessage(5, false);
    }

    @Test
    public void shouldBufferThenReattemptEarlyRetransmit() throws IOException
    {
        onNotSentMessage(1, true);
        onNotSentMessage(2, true);
        assertEquals(ABORT, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(0);
        assertBytesInBuffer(0);

        onWrite()
            .then(inv -> checkMessageSent(3, RETRANSMISSION_ID, MSG_SIZE, MSG_SIZE, inv))
            .then(inv -> checkMessageFullySent(1, inv))
            .then(inv -> checkMessageFullySent(2, inv));
        endpointOnMessage(3, RETRANSMISSION_ID, CONTINUE, false);
        verifyWritten(3);

        // verify sequence sent
        onWrite().then(inv -> checkMessageFullySent(4, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(1);

        onSentMessage(5, false);
    }

    @Test
    public void shouldBufferThenReattemptEarlyRetransmitWhenBackPressured() throws IOException
    {
        onNotSentMessage(1, true);
        onNotSentMessage(2, true);
        assertEquals(ABORT, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(0);

        onBackpressuredMessage(3, false);
        verifyRegistered();

        onWrite()
            .then(inv -> checkBackpressuredResent(3, inv));
        reattempt();
        verifyWritten(1);

        onWrite()
            .then(inv -> checkMessageSent(4, RETRANSMISSION_ID, MSG_SIZE, MSG_SIZE, inv))
            .then(inv -> checkMessageFullySent(1, inv))
            .then(inv -> checkMessageFullySent(2, inv));
        endpointOnMessage(4, RETRANSMISSION_ID, CONTINUE, false);
        verifyWritten(3);

        // verify sequence sent
        onWrite().then(inv -> checkMessageFullySent(5, inv));
        assertEquals(CONTINUE, endPoint.onReplayComplete(CORRELATION_ID));
        verifyWritten(1);

        onSentMessage(6, false);
    }

    private void verifyRegistered()
    {
        verify(fixPSenderEndPoints).backPressured(endPoint);
        reset(fixPSenderEndPoints);
    }

    @After
    public void safeAtEnd()
    {
        verify(fixPSenderEndPoints, never()).backPressured(endPoint);
        assertBytesInBuffer(0);
    }

    private void assertBytesInBuffer(final int expected)
    {
        assertEquals(expected, bytesInBuffer.get());
    }

    private void reattempt()
    {
        assertTrue("reattempt() false", endPoint.reattempt());
    }

    private void incompleteReattempt()
    {
        assertFalse("reattempt() true", endPoint.reattempt());
    }

    private int checkBackpressuredResent(final int num, final InvocationOnMock inv)
    {
        return checkMessageSent(num, SEQUENCE_ID, MSG_SIZE / 2, MSG_SIZE / 2, inv);
    }

    private void onBackpressuredMessage(final int num, final boolean retransmit) throws IOException
    {
        onMessage(num, CONTINUE, MSG_SIZE / 2, MSG_SIZE, retransmit);
    }

    private int checkMessageFullySent(final int num, final InvocationOnMock inv)
    {
        return checkMessageSent(num, SEQUENCE_ID, MSG_SIZE, MSG_SIZE, inv);
    }

    private void onNotSentMessage(final int num, final boolean retransmit) throws IOException
    {
        onMessage(num, CONTINUE, 0, 0, retransmit);
    }

    private void verifyWritten(final int wantedNumInvocations) throws IOException
    {
        verify(channel, times(wantedNumInvocations)).write(any(), anyInt(), anyBoolean());
        reset(channel);
    }

    private void onSentMessage(final int num, final boolean retransmit) throws IOException
    {
        onMessage(num, CONTINUE, MSG_SIZE, MSG_SIZE, retransmit);
    }

    private void onMessage(
        final int num,
        final Action expectedAction,
        final int written,
        final int expectedWriteLimit,
        final boolean retransmit) throws IOException
    {
        onMessage(num, SEQUENCE_ID, expectedAction, written, expectedWriteLimit, retransmit);
    }

    private void onMessage(
        final int num,
        final int templateId,
        final Action expectedAction,
        final int written,
        final int expectedWriteLimit,
        final boolean retransmit) throws IOException
    {
        if (written > 0)
        {
            onWrite().then(inv -> checkMessageSent(num, templateId, written, expectedWriteLimit, inv));
        }

        endpointOnMessage(num, templateId, expectedAction, retransmit);
        verifyWritten(written > 0 ? 1 : 0);
    }

    private void endpointOnMessage(
        final int num, final int templateId, final Action expectedAction, final boolean retransmit)
    {
        writeFakeMessage(num, templateId, OFFSET);

        if (templateId == RETRANSMISSION_ID)
        {
            writeFakeMessage(num + 1, SEQUENCE_ID, MSG_SIZE + OFFSET);
        }

        final Action action = endPoint.onMessage(buffer, OFFSET, retransmit);
        assertEquals(expectedAction, action);
    }

    private int checkMessageSent(
        final int num,
        final int templateId,
        final int written, final int expectedWriteLimit, final InvocationOnMock invocation)
    {
        final ByteBuffer byteBuffer = invocation.getArgument(0);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

        assertEquals("wrong expectedWriteLimit", expectedWriteLimit, byteBuffer.remaining());
        final int position = byteBuffer.position();
        assertEquals("wrong num", num, byteBuffer.getInt(position + START_INDEX));
        if (expectedWriteLimit == MSG_SIZE)
        {
            // only check the template is correct for the first part of a back-pressured message
            assertEquals((short)templateId, byteBuffer.getShort(position + TEMPLATE_INDEX));
        }
        return written;
    }

    private OngoingStubbing<Integer> onWrite() throws IOException
    {
        return when(channel.write(any(), anyInt(), anyBoolean()));
    }

    private void writeFakeMessage(final int num, final int templateId, final int offset)
    {
        writeBinaryEntryPointSofh(buffer, offset, MSG_SIZE);
        buffer.putInt(offset + START_INDEX, num, ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(offset + (MSG_SIZE / 2) + START_INDEX, num, ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(offset + TEMPLATE_INDEX, (short)templateId, ByteOrder.LITTLE_ENDIAN);
    }

}
