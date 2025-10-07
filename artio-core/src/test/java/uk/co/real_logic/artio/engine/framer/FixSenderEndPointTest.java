/*
 * Copyright 2015-2025 Real Logic Limited.
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
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.engine.MessageTimingHandler;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.engine.framer.FixSenderEndPoint.*;
import static uk.co.real_logic.artio.engine.logger.ArchiveDescriptor.alignTerm;
import static uk.co.real_logic.artio.messages.DisconnectReason.SLOW_CONSUMER;
import static uk.co.real_logic.artio.protocol.GatewayPublication.FRAME_SIZE;

public class FixSenderEndPointTest
{
    private static final long CONNECTION_ID = 1;
    private static final int LIBRARY_ID = 2;
    private static final int HEADER_LENGTH = MessageHeaderDecoder.ENCODED_LENGTH;

    private static final long POSITION = 8 * 1024;
    private static final int BODY_LENGTH = 84;
    private static final int FRAGMENT_LENGTH = alignTerm(HEADER_LENGTH + FRAME_SIZE + BODY_LENGTH);
    private static final int MAX_BYTES_IN_BUFFER = 3 * BODY_LENGTH;
    public static final int INBOUND_BUFFER_LEN = 128;
    public static final int REPLAY_CORRELATION_ID = 2;
    public static final int REPLAY_CORRELATION_ID_2 = 3;
    public static final int MSG_OFFSET = 200;

    private final TcpChannel tcpChannel = mock(TcpChannel.class);
    private final AtomicCounter bytesInBuffer = fakeCounter();
    private final AtomicCounter invalidLibraryAttempts = mock(AtomicCounter.class);
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private final Framer framer = mock(Framer.class);
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
    private final UnsafeBuffer buffer = new UnsafeBuffer(byteBuffer);
    private final SenderSequenceNumber senderSequenceNumber = mock(SenderSequenceNumber.class);
    private final MessageTimingHandler messageTimingHandler = mock(MessageTimingHandler.class);
    private final ExclusivePublication inboundPublication = mock(ExclusivePublication.class);
    private final ReproductionLogWriter reproductionLogWriter = mock(ReproductionLogWriter.class);
    private final UnsafeBuffer inboundBuffer = new UnsafeBuffer(new byte[INBOUND_BUFFER_LEN]);
    private final FixReceiverEndPoint receiverEndPoint = mock(FixReceiverEndPoint.class);
    private final FixSenderEndPoint endPoint = new FixSenderEndPoint(
        CONNECTION_ID,
        LIBRARY_ID,
        inboundPublication,
        reproductionLogWriter,
        tcpChannel,
        bytesInBuffer,
        invalidLibraryAttempts,
        errorHandler,
        framer,
        MAX_BYTES_IN_BUFFER,
        DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS,
        0,
        senderSequenceNumber,
        messageTimingHandler,
        receiverEndPoint,
        new FixSenderEndPoint.Formatters());

    @BeforeEach
    public void setup()
    {
        when(inboundPublication.tryClaim(anyInt(), any())).then(invocation ->
        {
            final BufferClaim claim = invocation.getArgument(1);
            final int length = invocation.getArgument(0);
            claim.wrap(inboundBuffer, 0, length + DataHeaderFlyweight.HEADER_LENGTH);
            return 1L;
        });
    }

    @Test
    public void shouldBeAbleToFragmentSlowConsumerRetries()
    {
        becomeSlowConsumer();

        final int firstWrites = 41;
        final int remaining = BODY_LENGTH - firstWrites;

        channelWillWrite(firstWrites);
        poll();
        byteBufferWritten();
        assertReattemptBytesWritten(firstWrites);
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);

        channelWillWrite(remaining);
        poll();
        byteBufferWritten();
        assertBytesInBuffer(0);
        verifyNoMoreErrors();
    }

    private void assertReattemptBytesWritten(final int firstWrites)
    {
        assertEquals(firstWrites, endPoint.reattemptBytesWritten());
    }

    @Test
    public void shouldDisconnectSlowConsumerAfterTimeout()
    {
        long timeInMs = 100;
        long position = POSITION;
        channelWillWrite(BODY_LENGTH);
        onOutboundMessage(timeInMs);

        timeInMs += 100;
        position += FRAGMENT_LENGTH;

        channelWillWrite(0);
        onOutboundMessage(timeInMs);

        timeInMs += DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS + 1;

        endPoint.poll(timeInMs);

        verifySlowConsumerDisconnect(times(1));
    }

    @Test
    public void shouldNotDisconnectSlowConsumerBeforeTimeout()
    {
        long timeInMs = 100;
        channelWillWrite(BODY_LENGTH);
        onOutboundMessage(timeInMs);

        timeInMs += (DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS - 1);

        endPoint.poll(timeInMs);

        verifySlowConsumerDisconnect(never());
        verifyNoMoreErrors();
    }

    @Test
    public void shouldNotDisconnectAtStartDueToTimeout()
    {
        final long timeInMs = DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS - 1;

        endPoint.poll(timeInMs);

        verifySlowConsumerDisconnect(never());
        verifyNoMoreErrors();
    }

    @Test
    public void shouldDisconnectSlowConsumerAfterTimeoutAfterFragment()
    {
        becomeSlowConsumer();

        final int firstWrites = 41;

        channelWillWrite(firstWrites);
        poll();

        channelWillWrite(0);
        poll();

        endPoint.poll(DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS + 101);

        verifySlowConsumerDisconnect(times(1));
    }

    private void startValidReplay()
    {
        endPoint.onValidResendRequest(REPLAY_CORRELATION_ID);
        endPoint.onStartReplay(REPLAY_CORRELATION_ID);
    }

    @Test
    public void shouldDisconnectReplaySlowConsumer()
    {
        startValidReplay();

        long position = 0;
        channelWillWrite(0);
        onReplayMessage(0);

        position += FRAGMENT_LENGTH;
        onReplayMessage(0);

        position += FRAGMENT_LENGTH;
        onReplayMessage(0);

        verifySlowConsumerDisconnect(times(1));
    }

    @Test
    public void shouldNotSendReplayMessageUntilSlowConsumerComplete()
    {
        final int firstWrites = 41;
        final int remaining = BODY_LENGTH - firstWrites;

        // 1. we're slow
        becomeSlowConsumer();

        // 2. enqueue a replay because we need to process slow messages first.
        startValidReplay();
        assertNotReplaying();

        // 3. write part of the normal message
        channelWillWrite(firstWrites);
        poll();
        byteBufferWritten();
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);

        // 4. enqueue the replay message and complete
        channelWillWrite(0);
        onReplayMessage(0);
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);
        onReplayComplete();

        // 5. we poll the enqueued normal message and process it.
        poll();
        byteBufferWrittenTwice();
        assertReattemptBytesWritten(firstWrites);
        assertRequiresReattempting();
        assertNotReplaying();

        // 6. write the normal message and flip
        channelWillWrite(remaining);
        poll();
        byteBufferWritten();
        assertBytesInBuffer(ENQ_START_REPLAY_LEN + (BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN) + ENQ_REPLAY_COMPLETE_LEN);
        assertReplaying();
        assertRequiresReattempting();

        // 7. write the replay
        channelWillWrite(BODY_LENGTH);
        poll();
        byteBufferWritten();
        assertBytesInBuffer(0);
        assertNotReplaying();

        verifyNoMoreErrors();
    }

    private void byteBufferWrittenTwice()
    {
        byteBufferWritten(times(2));
    }

    @Test
    public void shouldNotSendSlowMessagesUntilReplayComplete()
    {
        final int firstWrites = 41;
        final int remaining = BODY_LENGTH - firstWrites;

        // 1. start a replay
        startValidReplay();
        channelWillWrite(firstWrites);
        onReplayMessage(0);
        byteBufferWritten();
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);
        assertReplaying();
        assertRequiresReattempting();

        // 2. enqueue a normal message
        onOutboundMessage(0);
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);
        assertReplaying();
        assertRequiresReattempting();

        // 3. complete the replay
        onReplayComplete();
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN + ENQ_REPLAY_COMPLETE_LEN);
        assertReplaying();
        assertRequiresReattempting();

        channelWillWrite(remaining);
        poll();
        assertNotReplaying();
        assertRequiresReattempting();
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);

        // 4. poll sends normal message
        channelWillWrite(BODY_LENGTH);
        poll();
        assertNotReplaying();
        assertDoesNotRequireReattempting();
        assertBytesInBuffer(0);

        verifyNoMoreErrors();
    }

    private void assertReplaying()
    {
        assertTrue(endPoint.isReplaying(), "not isReplaying");
    }

    private void assertNotReplaying()
    {
        assertFalse(endPoint.isReplaying(), "isReplaying");
    }

    @Test
    public void shouldCopeWithMultipleResendRequests()
    {
        endPoint.onValidResendRequest(REPLAY_CORRELATION_ID);
        endPoint.onValidResendRequest(REPLAY_CORRELATION_ID_2);

        // First replay
        endPoint.onStartReplay(REPLAY_CORRELATION_ID);

        channelWillWrite(BODY_LENGTH);
        onReplayMessage(1);
        assertBytesInBuffer(0);

        onReplayComplete();

        // second replay
        endPoint.onStartReplay(REPLAY_CORRELATION_ID_2);

        channelWillWrite(BODY_LENGTH);
        onReplayMessage(1);
        assertBytesInBuffer(0);

        endPoint.onReplayComplete(REPLAY_CORRELATION_ID_2);

        verifyNoMoreErrors();
    }

    private void byteBufferNotWritten()
    {
        byteBufferWritten(never());
    }

    private void errorLogged()
    {
        verify(errorHandler).onError(any(IllegalStateException.class));
    }

    private void verifyNoMoreErrors()
    {
        verifyNoMoreInteractions(errorHandler);
    }

    private void onOutboundMessage(final long timeInMs)
    {
        endPoint.onOutboundMessage(LIBRARY_ID, buffer, MSG_OFFSET, BODY_LENGTH, 0, 0, 0L, timeInMs, 0);
    }

    private void onReplayMessage(final long timeInMs)
    {
        assertEquals(CONTINUE, endPoint.onReplayMessage(buffer, MSG_OFFSET, BODY_LENGTH, timeInMs, 0));
    }

    private void verifySlowConsumerDisconnect(final VerificationMode times)
    {
        verify(receiverEndPoint, times).completeDisconnect(SLOW_CONSUMER);
    }

    private void byteBufferWritten()
    {
        byteBufferWritten(times(1));
    }

    private void byteBufferWritten(final VerificationMode times)
    {
        try
        {
            final ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
            verify(tcpChannel, times).write(bufferCaptor.capture(), anyInt(), anyBoolean());
            reset(tcpChannel);
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    private void assertBytesInBuffer(final int bytes)
    {
        if (bytes > 0)
        {
            assertRequiresReattempting();
        }
        assertEquals(bytes, bytesInBuffer.get());
    }

    private void assertRequiresReattempting()
    {
        assertTrue(endPoint.requiresRetry(), "not requiresReattempting");
    }

    private void assertDoesNotRequireReattempting()
    {
        assertFalse(endPoint.requiresRetry(), "requiresReattempting");
    }

    private void becomeSlowConsumer()
    {
        channelWillWrite(0);
        onOutboundMessage(0);
        byteBufferWritten();
        assertBytesInBuffer(BODY_LENGTH + ENQ_MESSAGE_BLOCK_LEN);
    }

    private void channelWillWrite(final int bodyLength)
    {
        try
        {
            when(tcpChannel.write(any(), anyInt(), anyBoolean())).thenReturn(bodyLength);
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    private AtomicCounter fakeCounter()
    {
        final AtomicLong value = new AtomicLong();
        final AtomicCounter atomicCounter = mock(AtomicCounter.class);
        final Answer<Long> get = inv -> value.get();
        final Answer<?> set = inv ->
        {
            value.set(inv.getArgument(0));
            return null;
        };

        final Answer<?> add = (inv) -> value.getAndAdd(inv.getArgument(0));

        when(atomicCounter.get()).then(get);
        when(atomicCounter.getWeak()).then(get);

        doAnswer(set).when(atomicCounter).set(anyLong());
        doAnswer(set).when(atomicCounter).setOrdered(anyLong());
        doAnswer(set).when(atomicCounter).setWeak(anyLong());

        when(atomicCounter.getAndAdd(anyLong())).then(add);
        when(atomicCounter.getAndAddOrdered(anyLong())).then(add);

        return atomicCounter;
    }

    private void onReplayComplete()
    {
        endPoint.onReplayComplete(REPLAY_CORRELATION_ID);
    }

    private void poll()
    {
        endPoint.poll(0);
    }
}
