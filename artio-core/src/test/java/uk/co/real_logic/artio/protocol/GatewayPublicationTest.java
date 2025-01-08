package uk.co.real_logic.artio.protocol;

import io.aeron.*;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.DirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MessageStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.TestFixtures.mediaDriverContext;
import static uk.co.real_logic.artio.protocol.GatewayPublication.FRAMED_MESSAGE_SIZE;

class GatewayPublicationTest
{
    private static final int MAX_UNFRAGMENTED_BODY_LENGTH = 1305;

    static IntStream bodyLengthRange()
    {
        return IntStream.rangeClosed(
            MAX_UNFRAGMENTED_BODY_LENGTH,
            MAX_UNFRAGMENTED_BODY_LENGTH + DataHeaderFlyweight.HEADER_LENGTH
        );
    }

    @Timeout(2)
    @ParameterizedTest
    @MethodSource("bodyLengthRange")
    void testSavingMessagesOverTermBoundary(final int bodyLength)
    {
        final int termBufferLength = 64 * 1024;
        try (
            MediaDriver driver = MediaDriver.launch(mediaDriverContext(termBufferLength, true));
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final String channel = CommonContext.IPC_CHANNEL;
            final int streamId = 1000;

            final Subscription subscription = aeron.addSubscription(channel, streamId);
            final ExclusivePublication publication = aeron.addExclusivePublication(channel, streamId);
            final Counter fails = aeron.addCounter(1001, "fails");

            // first one won't be fragmented, but subsequent ones will
            assertEquals(MAX_UNFRAGMENTED_BODY_LENGTH, publication.maxPayloadLength() - FRAMED_MESSAGE_SIZE);

            final GatewayPublication gatewayPublication = new GatewayPublication(
                publication,
                fails,
                NoOpIdleStrategy.INSTANCE,
                new SystemEpochNanoClock(),
                5
            );

            // leave enough space in the term for just over one full frame
            final int startingPosition = (termBufferLength - driver.context().ipcMtuLength() - 1) &
                -DataHeaderFlyweight.HEADER_LENGTH; // align down to min frame length
            advanceToPosition(startingPosition, publication, subscription);

            final byte[] body = new byte[bodyLength];
            ThreadLocalRandom.current().nextBytes(body);
            final DirectBuffer srcBuffer = new UnsafeBuffer(body);

            while (true)
            {
                final long result = gatewayPublication.saveMessage(
                    srcBuffer,
                    0,
                    body.length,
                    5000,
                    68,
                    1,
                    0,
                    1234,
                    MessageStatus.OK,
                    42
                );
                if (result > 0)
                {
                    break;
                }
                if (Thread.currentThread().isInterrupted())
                {
                    fail("failed to save message: " + result);
                }
            }

            final MessageCapturingProtocolHandler protocolHandler = new MessageCapturingProtocolHandler();
            final ProtocolSubscription protocolSubscription = ProtocolSubscription.of(protocolHandler);
            final ControlledFragmentHandler fragmentHandler = new ControlledFragmentAssembler(protocolSubscription);

            subscription.controlledPoll(fragmentHandler, 5);
            subscription.controlledPoll(fragmentHandler, 5);

            final CapturedMessage capturedMessage = protocolHandler.capturedMessages.get(0);
            assertArrayEquals(body, capturedMessage.body());
            assertEquals(68, capturedMessage.messageType());
            assertEquals(42, capturedMessage.sequenceNumber());
        }
    }

    private void advanceToPosition(
        final long position,
        final ExclusivePublication publication,
        final Subscription subscription)
    {
        if (position % FRAME_ALIGNMENT != 0)
        {
            fail("position is not frame aligned: " + position);
        }

        long lastPubPos = 0;
        final MutableLong lastSubPos = new MutableLong();
        final FragmentHandler fragmentHandler = (buffer1, offset, length, header) -> lastSubPos.set(header.position());
        final DirectBuffer buffer = new UnsafeBuffer();

        while (lastPubPos < position || lastSubPos.get() < position)
        {
            if (lastPubPos < position)
            {
                final long result = publication.offer(buffer);
                if (result > 0)
                {
                    lastPubPos = result;
                }
            }

            subscription.poll(fragmentHandler, 5);
        }
    }

    private record CapturedMessage(byte[] body, long messageType, int sequenceNumber)
    {
    }

    private static final class MessageCapturingProtocolHandler implements ProtocolHandler
    {
        private final List<CapturedMessage> capturedMessages = new ArrayList<>();

        public ControlledFragmentHandler.Action onMessage(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int libraryId,
            final long connectionId,
            final long sessionId,
            final int sequenceIndex,
            final long messageType,
            final long timestamp,
            final MessageStatus status,
            final int sequenceNumber,
            final Header header,
            final int metaDataLength)
        {
            final byte[] body = new byte[length];
            buffer.getBytes(offset, body);

            capturedMessages.add(new CapturedMessage(
                body,
                messageType,
                sequenceNumber
            ));

            return ControlledFragmentHandler.Action.CONTINUE;
        }

        public ControlledFragmentHandler.Action onDisconnect(
            final int libraryId,
            final long connectionId,
            final DisconnectReason reason)
        {
            throw new IllegalStateException();
        }

        public ControlledFragmentHandler.Action onFixPMessage(
            final long connectionId,
            final DirectBuffer buffer,
            final int offset)
        {
            throw new IllegalStateException();
        }
    }
}
