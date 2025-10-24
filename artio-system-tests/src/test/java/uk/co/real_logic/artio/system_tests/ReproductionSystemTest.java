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
package uk.co.real_logic.artio.system_tests;

import org.agrona.collections.IntArrayList;
import org.junit.Ignore;
import org.junit.Test;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.decoder.LogonDecoder;
import uk.co.real_logic.artio.engine.ReproductionMessageHandler;
import uk.co.real_logic.artio.engine.framer.ReproductionProtocolHandler;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.Session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static uk.co.real_logic.artio.Constants.*;
import static uk.co.real_logic.artio.LogTag.REPRODUCTION_TEST;
import static uk.co.real_logic.artio.TestFixtures.closeMediaDriver;
import static uk.co.real_logic.artio.library.FixLibrary.CURRENT_SEQUENCE;
import static uk.co.real_logic.artio.system_tests.DebugTcpChannelSupplier.NULL_WRITE_BYTES;
import static uk.co.real_logic.artio.system_tests.DebugTcpChannelSupplier.WRITE_MAX;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

@Ignore // Flaky on CI due to low CPU
public class ReproductionSystemTest extends AbstractMessageBasedAcceptorSystemTest
{
    public static final int MESSAGES_SENT = 3;

    public static final String TEST_REQ_ID = "ABC";

    private static final int[] MAX_BYTES_TO_WRITE_ARR = { WRITE_MAX, 50, 50, 16 };
    private static final IntArrayList MAX_BYTES_TO_WRITE = new IntArrayList(
        MAX_BYTES_TO_WRITE_ARR, MAX_BYTES_TO_WRITE_ARR.length, NULL_WRITE_BYTES);

    static class StashingMessageHandler implements ReproductionMessageHandler
    {
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();

        private int i;

        public void onMessage(final long connectionId, final ByteBuffer bytes)
        {
            try
            {
                final byte[] stashed = new byte[bytes.remaining()];
                bytes.get(stashed);
                final String message = new String(stashed, StandardCharsets.US_ASCII);
                // System.out.println("message = " + message);

                if (i < MAX_BYTES_TO_WRITE.size() && i > 0 && MAX_BYTES_TO_WRITE.get(i - 1) != WRITE_MAX)
                {
                    final int lastIndex = messages.size() - 1;
                    final String combinedMessage = messages.get(lastIndex) + message;
                    // System.out.println("combinedMessage = " + combinedMessage);
                    messages.set(lastIndex, combinedMessage);
                }
                else
                {
                    messages.add(message);
                }
                i++;
            }
            catch (final Throwable e)
            {
                e.printStackTrace();
            }
        }

        public List<String> messages()
        {
            return Collections.unmodifiableList(messages);
        }
    }

    static class Counter implements IntConsumer
    {
        private volatile boolean failed = false;

        public void accept(final int count)
        {
            System.err.println("Invalid Count: " + count);
            failed = true;
        }

        void verify()
        {
            assertFalse("Failed STZ count check: see stderr for details", failed);
        }
    }

    private void reproduceScenario(
        final ReportFactory reportFactory,
        final List<FixMessage> originalReceivedMessages,
        final long[] sentPositions,
        final List<String> sentMessages,
        final long startInNs, final long endInNs)
    {
        DebugLogger.log(REPRODUCTION_TEST, "Start of scenario reproduction");

        final StashingMessageHandler messageStash = new StashingMessageHandler();
        reproductionMessageHandler = messageStash;

        setup(false, true, true, InitialAcceptedSessionOwner.ENGINE, false,
            true, startInNs, endInNs, false, true);
        setupLibrary();

        // Reply to messages
        final long[] reproPositions = new long[MESSAGES_SENT];
        handler.onMessageCallback((sess, fixMessage) ->
        {
            final int seqNum = fixMessage.messageSequenceNumber();
            if (seqNum >= 2 && seqNum <= 4)
            {
                reproPositions[seqNum - 2] = reportFactory.trySendReport(sess, Side.SELL);
            }
        });

        final Reply<?> startReply = engine.startReproduction();

        final Session session = acquireSession(0, CURRENT_SEQUENCE);
        assertSessionId(session);
        final CompositeKey compositeKey = session.compositeKey();
        assertEquals(INITIATOR_ID, compositeKey.remoteCompId());
        assertEquals(ACCEPTOR_ID, compositeKey.localCompId());

        awaitDisconnect(session);

        DebugLogger.log(REPRODUCTION_TEST, "End of first session logon");

        final Session reconnectedSession = acquireSession(6, CURRENT_SEQUENCE);
        DebugLogger.log(REPRODUCTION_TEST, "Session reconnected");
        assertSessionId(reconnectedSession);
        awaitDisconnect(reconnectedSession);
        DebugLogger.log(REPRODUCTION_TEST, "End of reconnected session");

        final List<FixMessage> messages = otfAcceptor.messages();
        messages.removeIf(msg -> !messageToCheck(msg));
        assertEquals(toString(originalReceivedMessages) + " vs " + toString(messages),
            originalReceivedMessages, messages);

        final int firstBatch = 1 + MESSAGES_SENT + 2;
        final int resentBatch = 2 + MESSAGES_SENT + 2;
        final List<String> reproSentMessages = awaitStashedMessages(messageStash, firstBatch + resentBatch);

        assertEquals(stripTimesAndChecksums(sentMessages), stripTimesAndChecksums(reproSentMessages));
        // Do we actually need this?
        // assertArrayEquals(sentPositions, reproPositions);

        testSystem.awaitCompletedReply(startReply);

        DebugLogger.log(REPRODUCTION_TEST, "End of scenario reproduction");
    }

    private List<String> awaitStashedMessages(final StashingMessageHandler messageStash, final int total)
    {
        final List<String> reproSentMessages = messageStash.messages();
        testSystem.await("Failed to receive messages", () ->
            reproSentMessages.size() >= total);
        return reproSentMessages;
    }

    @Test
    public void shouldReproduceMessageExchange() throws IOException
    {
        final Counter counter = new Counter();
        ReproductionProtocolHandler.countHandler = counter;
        printErrors = true;

        final ReportFactory reportFactory = new ReportFactory();
        final List<FixMessage> originalReceivedMessages = new ArrayList<>();
        final long[] sentPositions = new long[MESSAGES_SENT];
        final List<String> sentMessages = new ArrayList<>();

        final long startInNs = nanoClock.nanoTime();
        createScenario(reportFactory, originalReceivedMessages, sentPositions, sentMessages);
        final long endInNs = nanoClock.nanoTime();

        reproduceScenario(reportFactory, originalReceivedMessages, sentPositions, sentMessages, startInNs, endInNs);

        counter.verify();
    }

    private String toString(final List<?> values)
    {
        return values.stream().map(Object::toString).collect(Collectors.joining(", \n", "[", "]"));
    }

    private void assertSessionId(final Session reconnectedSession)
    {
        assertEquals(1, reconnectedSession.id());
    }

    private void awaitDisconnect(final Session session)
    {
        testSystem.await("Failed to disconnect", () -> session.state() == SessionState.DISCONNECTED);
    }

    private boolean messageToCheck(final FixMessage msg)
    {
        final String msgType = msg.msgType();
        return NEW_ORDER_SINGLE_MESSAGE_AS_STR.equals(msgType) ||
            TEST_REQUEST_MESSAGE_AS_STR.equals(msgType) ||
            RESEND_REQUEST_MESSAGE_AS_STR.equals(msgType);
    }

    private List<String> stripTimesAndChecksums(final List<String> messages)
    {
        return messages.stream()
            .map(msg -> msg.replaceAll("\001(52|10)=[^\001]+", ""))
            .collect(Collectors.toList());
    }

    private void createScenario(
        final ReportFactory reportFactory,
        final List<FixMessage> originalReceivedMessages,
        final long[] sentPositions,
        final List<String> sentMessages) throws IOException
    {
        try
        {
            optionalTcpChannelSupplierFactory = ec -> new DebugTcpChannelSupplier(ec, MAX_BYTES_TO_WRITE);
            writeReproductionLog = true;
            setup(false, true);
            writeReproductionLog = false;
            optionalTcpChannelSupplierFactory = null;
            setupLibrary();

            DebugLogger.log(REPRODUCTION_TEST, "Start of scenario creation");

            try (FixConnection connection = FixConnection.initiate(port))
            {
                logon(connection);
                sentMessages.add(connection.lastMessageAsString());

                final Session session = acquireSession();

                for (int i = 0; i < MESSAGES_SENT; i++)
                {
                    OrderFactory.sendOrder(connection);
                    originalReceivedMessages.add(
                        testSystem.awaitMessageOf(otfAcceptor, NEW_ORDER_SINGLE_MESSAGE_AS_STR));
                    sentPositions[i] = reportFactory.sendReport(testSystem, session, Side.SELL);
                    otfAcceptor.messages().clear();
                }

                connection.readExecutionReport(2);
                sentMessages.add(connection.lastMessageAsString());
                connection.readExecutionReport(3);
                sentMessages.add(connection.lastMessageAsString());
                connection.readExecutionReport(4);
                sentMessages.add(connection.lastMessageAsString());

                connection.sendTestRequest(TEST_REQ_ID);
                testSystem.await("Failed to send Heartbeat", () -> session.lastSentMsgSeqNum() >= 5);
                originalReceivedMessages.add(
                    testSystem.awaitMessageOf(otfAcceptor, TEST_REQUEST_MESSAGE_AS_STR));
                connection.readHeartbeat(TEST_REQ_ID);
                sentMessages.add(connection.lastMessageAsString());

                logoutAndDisconnect(sentMessages, connection, session);
            }

            DebugLogger.log(REPRODUCTION_TEST, "Reconnecting for Replay");
            otfAcceptor.messages().clear();

            try (FixConnection connection = FixConnection.initiate(port))
            {
                connection.msgSeqNum(7).logon(false);

                final LogonDecoder logon = connection.readLogon();
                sentMessages.add(connection.lastMessageAsString());

                assertFalse(logon.resetSeqNumFlag());
                assertEquals(7, logon.header().msgSeqNum());

                final Session session = acquireSession();

                // Perform resend request
                testSystem.awaitBlocking(() -> connection.sendResendRequest(1, 0));
                originalReceivedMessages.add(testSystem.awaitMessageOf(otfAcceptor, RESEND_REQUEST_MESSAGE_AS_STR));
                testSystem.awaitBlocking(() ->
                {
                    connection.readSequenceResetGapFill(2);
                    sentMessages.add(connection.lastMessageAsString());

                    connection.readResentExecutionReport(2);
                    sentMessages.add(connection.lastMessageAsString());

                    connection.readResentExecutionReport(3);
                    sentMessages.add(connection.lastMessageAsString());

                    connection.readResentExecutionReport(4);
                    sentMessages.add(connection.lastMessageAsString());

                    connection.readSequenceResetGapFill(8); // Heartbeat, Logoff, Logon
                    sentMessages.add(connection.lastMessageAsString());
                });

                logoutAndDisconnect(sentMessages, connection, session);
            }

            DebugLogger.log(REPRODUCTION_TEST, "End of scenario creation");

            libraryId = library.libraryId();
        }
        finally
        {
            teardownArtio();
            closeMediaDriver(mediaDriver);
        }
    }

    private void logoutAndDisconnect(
        final List<String> sentMessages, final FixConnection connection, final Session session)
    {
        testSystem.awaitSend(session::startLogout);
        connection.readLogout();
        sentMessages.add(connection.lastMessageAsString());
        connection.logout();
        assertSessionDisconnected(testSystem, session);
    }

    // Write lengths in un-backpressured test:
    // 1661269610028:main[REPRODUCTION_TEST]Start of scenario creation
    //src.remaining() = 116
    //written = 116
    //src.remaining() = 116
    //written = 116
    //src.remaining() = 116
    //written = 116
    //src.remaining() = 116
    //written = 116
    //src.remaining() = 90
    //written = 90
    //src.remaining() = 82
    //written = 82
    //1661269610106:main[REPRODUCTION_TEST]Reconnecting for Replay
    //src.remaining() = 116
    //written = 116
    //src.remaining() = 98
    //written = 98
    //src.remaining() = 148
    //written = 148
    //src.remaining() = 148
    //written = 148
    //src.remaining() = 148
    //written = 148
    //src.remaining() = 98
    //written = 98
    //src.remaining() = 82
    //written = 82
}
