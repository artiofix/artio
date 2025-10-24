/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.decoder.*;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;
import uk.co.real_logic.artio.util.MessageTypeEncoding;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.agrona.CloseHelper.close;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Constants.EXECUTION_REPORT_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.Constants.RESEND_REQUEST_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.SessionRejectReason.COMPID_PROBLEM;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_RECEIVER_BUFFER_SIZE;
import static uk.co.real_logic.artio.engine.logger.Replayer.MOST_RECENT_MESSAGE;
import static uk.co.real_logic.artio.library.FixLibrary.CURRENT_SEQUENCE;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.ENGINE;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;
import static uk.co.real_logic.artio.messages.ThrottleConfigurationStatus.OK;
import static uk.co.real_logic.artio.system_tests.AbstractGatewayToGatewaySystemTest.*;
import static uk.co.real_logic.artio.system_tests.FixConnection.BUFFER_SIZE;
import static uk.co.real_logic.artio.system_tests.MessageBasedInitiatorSystemTest.assertConnectionDisconnects;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class MessageBasedAcceptorSystemTest extends AbstractMessageBasedAcceptorSystemTest
{
    private static final DateTimeFormatter UTC_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("uuuuMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final int timeoutDisconnectHeartBtIntInS = 1;
    private final long timeoutDisconnectHeartBtIntInMs = TimeUnit.SECONDS.toMillis(timeoutDisconnectHeartBtIntInS);

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldComplyWithLogonBasedSequenceNumberResetOn()
        throws IOException
    {
        shouldComplyWithLogonBasedSequenceNumberReset(true);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldComplyWithLogonBasedSequenceNumberResetOff()
        throws IOException
    {
        shouldComplyWithLogonBasedSequenceNumberReset(false);
    }

    private void shouldComplyWithLogonBasedSequenceNumberReset(final boolean sequenceNumberReset)
        throws IOException
    {
        setup(sequenceNumberReset, true);

        logonThenLogout();

        logonThenLogout();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotNotifyLibraryOfSessionUntilLoggedOn() throws IOException
    {
        setup(true, true);

        final FakeOtfAcceptor fakeOtfAcceptor = new FakeOtfAcceptor();
        final FakeHandler fakeHandler = new FakeHandler(fakeOtfAcceptor);
        try (FixLibrary library = newAcceptingLibrary(fakeHandler, nanoClock))
        {
            try (FixConnection connection = FixConnection.initiate(port))
            {
                library.poll(10);

                assertFalse(fakeHandler.hasSeenSession());

                logon(connection);

                fakeHandler.awaitSessionIdFor(INITIATOR_ID, ACCEPTOR_ID, () -> library.poll(2), 1000);
            }
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectExceptionalLogonMessageAndLogout() throws IOException
    {
        setup(true, true);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            sendInvalidLogon(connection);

            final RejectDecoder reject = connection.readMessage(new RejectDecoder());
            assertEquals(1, reject.refSeqNum());
            assertEquals(Constants.SENDING_TIME, reject.refTagID());
            assertEquals(LogonDecoder.MESSAGE_TYPE_AS_STRING, reject.refMsgTypeAsString());

            connection.readLogout();
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectExceptionalSessionMessage() throws IOException
    {
        setup(true, true);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            sendInvalidTestRequestMessage(connection);

            final RejectDecoder reject = connection.readReject();
            assertEquals(2, reject.refSeqNum());
            assertEquals(Constants.SENDING_TIME, reject.refTagID());

            connection.logoutAndAwaitReply();
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldShutdownWithNotLoggedInSessionsOpen() throws IOException
    {
        setup(true, true);

        try (FixConnection ignore = FixConnection.initiate(port))
        {
            close(engine);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectConnectionWithNoLogonEngine() throws IOException
    {
        shouldDisconnectConnectionWithNoLogon(ENGINE);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectConnectionWithNoLogonSoleLibrary() throws IOException
    {
        shouldDisconnectConnectionWithNoLogon(SOLE_LIBRARY);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectConnectionWithNoLogoutReply() throws IOException
    {
        setup(true, true);

        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(true, 1);

            final LogonDecoder logon = connection.readLogon();
            assertTrue(logon.resetSeqNumFlag());

            final Session session = acquireSession();
            logoutSession(session);
            assertSessionDisconnected(testSystem, session);

            assertConnectionDisconnects(testSystem, connection);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportRapidLogonAndLogoutOperations() throws IOException
    {
        setup(false, true, true);

        setupLibrary();

        final Session session;

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(false);

            handler.awaitSessionId(testSystem::poll);

            session = acquireSession();

            connection.readLogon();

            connection.logout();
        }

        try (FixConnection connection = FixConnection.initiate(port))
        {
            // The previous disconnection hasn't yet been detected as it's still active.
            assertTrue(session.isActive());

            connection.logon(true);

            // During this loop the logout message for the disconnected connection is sent,
            // But not received by the new connection.
            // Send a test request here to increase likelihood of triggering the race
            SystemTestUtil.sendTestRequest(testSystem, session, "badTestRequest");
            Timing.assertEventuallyTrue("Library has disconnected old session", () ->
            {
                testSystem.poll();

                return !handler.sessions().contains(session);
            });

            connection.readLogon();
            // check that it really is a logon and not the logout
            assertThat(
                connection.lastTotalBytesRead(),
                containsString("\00135=A\001"));

            connection.logoutAndAwaitReply();
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectMessageWithInvalidSenderAndTargetCompIds() throws IOException
    {
        setup(true, true);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            final Session session = acquireSession();

            final TestRequestEncoder testRequestEncoder = new TestRequestEncoder();
            connection.setupHeader(testRequestEncoder.header(), connection.acquireMsgSeqNum(), false);
            testRequestEncoder.header().senderCompID("Wrong").targetCompID("Values");
            testRequestEncoder.testReqID("ABC");
            connection.send(testRequestEncoder);

            Timing.assertEventuallyTrue("", () ->
            {
                testSystem.poll();

                return session.lastSentMsgSeqNum() >= 2;
            });

            final RejectDecoder rejectDecoder = connection.readReject();
            assertEquals(2, rejectDecoder.refSeqNum());
            assertEquals(TEST_REQUEST_MESSAGE_TYPE_STR, rejectDecoder.refMsgTypeAsString());
            assertEquals(COMPID_PROBLEM, rejectDecoder.sessionRejectReasonAsEnum());

            assertThat(rejectDecoder.refTagID(), either(is(SENDER_COMP_ID)).or(is(TARGET_COMP_ID)));
            assertFalse(otfAcceptor.lastReceivedMessage().isValid());
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidResendRequestsWrongCompId() throws IOException
    {
        setup(true, true);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            final String testReqId = "ABC";
            final int headerSeqNum = connection.exchangeTestRequestHeartbeat(testReqId).header().msgSeqNum();

            // Send an invalid resend request
            final ResendRequestEncoder resendRequest = new ResendRequestEncoder();
            resendRequest.beginSeqNo(headerSeqNum).endSeqNo(headerSeqNum);
            connection.setupHeader(resendRequest.header(), 1, false);
            resendRequest.header().targetCompID(" ");
            connection.send(resendRequest);

            connection.readReject();
            connection.readLogout();

            sleep(200);

            connection.logout();
            assertFalse(connection.isConnected(), "Read a resent FIX message instead of a disconnect");
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidResendRequestsHighBeginSeqNoUsingReplayer() throws IOException
    {
        shouldRejectInvalidResendRequests(true, (connection, reportSeqNum) ->
        {
            final int invalidSeqNum = reportSeqNum + 1;
            return connection.sendResendRequest(invalidSeqNum, invalidSeqNum);
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidResendRequestsHighBeginSeqNoUsingGapfiller() throws IOException
    {
        shouldRejectInvalidResendRequests(false, (connection, reportSeqNum) ->
        {
            final int invalidSeqNum = reportSeqNum + 1;
            return connection.sendResendRequest(invalidSeqNum, invalidSeqNum);
        });
    }


    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidResendRequestsEndSeqNoBelowBeginSeqNoUsingReplayer() throws IOException
    {
        shouldRejectInvalidResendRequests(true, (connection, reportSeqNum) ->
        {
            return connection.sendResendRequest(reportSeqNum, reportSeqNum - 1);
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidResendRequestsEndSeqNoBelowBeginSeqNoUsingGapfiller() throws IOException
    {
        shouldRejectInvalidResendRequests(false, (connection, reportSeqNum) ->
        {
            return connection.sendResendRequest(reportSeqNum, reportSeqNum - 1);
        });
    }

    private void shouldRejectInvalidResendRequests(
        final boolean logging,
        final BiFunction<FixConnection, Integer, ResendRequestEncoder> resendRequester)
        throws IOException
    {
        setupWithLogging(true, true, logging);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            final String testReqId = "ABC";
            connection.exchangeTestRequestHeartbeat(testReqId).header().msgSeqNum();

            session = acquireSession();
            ReportFactory.sendOneReport(testSystem, session, Side.SELL);

            testSystem.awaitBlocking(() ->
            {
                final int reportSeqNum = connection.readExecutionReport().header().msgSeqNum();

                // Send an invalid resend request
                final ResendRequestEncoder resendRequest = resendRequester.apply(connection, reportSeqNum);

                final RejectDecoder reject = connection.readReject();
                assertEquals(RESEND_REQUEST_MESSAGE_AS_STR, reject.refMsgTypeAsString());
                assertEquals(resendRequest.header().msgSeqNum(), reject.refSeqNum());

                connection.logout();
                connection.readLogout();
                assertFalse(connection.isConnected(), "Read a resent FIX message instead of a disconnect");
            });
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReplyWithOnlyValidMessageSequenceWithHighEndSeqNo() throws IOException
    {
        connectionWithOnlyValidMessageSequenceWithHighEndSeqNo(true, (connection, reportSeqNum) ->
        {
            final SequenceResetDecoder sequenceResetDecoder =
                connection.readMessage(new SequenceResetDecoder());
            assertTrue(sequenceResetDecoder.header().possDupFlag());
            assertEquals(reportSeqNum, sequenceResetDecoder.newSeqNo(), connection.lastMessageAsString());
            final ExecutionReportDecoder secondExecutionReport = connection.readExecutionReport();
            assertTrue(secondExecutionReport.header().possDupFlag());
            assertEquals(reportSeqNum, secondExecutionReport.header().msgSeqNum());
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldGapfillWithOnlyValidMessageSequenceWithHighEndSeqNo() throws IOException
    {
        connectionWithOnlyValidMessageSequenceWithHighEndSeqNo(false, (connection, reportSeqNum) ->
        {
            final SequenceResetDecoder sequenceResetDecoder =
                connection.readMessage(new SequenceResetDecoder());
            assertTrue(sequenceResetDecoder.header().possDupFlag());
            assertEquals(reportSeqNum + 1, sequenceResetDecoder.newSeqNo(), connection.lastMessageAsString());
        });
    }

    private void connectionWithOnlyValidMessageSequenceWithHighEndSeqNo(
        final boolean logging,
        final BiConsumer<FixConnection, Integer> connectionConsumer) throws IOException
    {
        setupWithLogging(true, true, logging);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            final String testReqId = "ABC";
            final int headerSeqNum = connection.exchangeTestRequestHeartbeat(testReqId).header().msgSeqNum();

            session = acquireSession();
            ReportFactory.sendOneReport(testSystem, session, Side.SELL);

            testSystem.awaitBlocking(() ->
            {
                final int reportSeqNum = connection.readExecutionReport().header().msgSeqNum();

                // Send an invalid resend request
                final int invalidSeqNum = reportSeqNum + 100;
                connection.sendResendRequest(headerSeqNum, invalidSeqNum);

                connectionConsumer.accept(connection, reportSeqNum);

                final HeartbeatDecoder heartbeat = connection.exchangeTestRequestHeartbeat("ABC2");
                assertFalse(heartbeat.header().hasPossDupFlag());
                assertEquals(reportSeqNum + 1, heartbeat.header().msgSeqNum());

                connection.logout();
                connection.readLogout();
                assertFalse(connection.isConnected(), "Read a resent FIX message instead of a disconnect");
            });
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectInvalidLogonWithMissingTargetCompId()
    {
        setup(true, true, true, SOLE_LIBRARY);

        setupLibrary();

        testSystem.awaitBlocking(() ->
        {
            //  Create a logon message that will fail session level validation, but nothing else.
            try (FixConnection connection = new FixConnection(
                SocketChannel.open(new InetSocketAddress("localhost", port)),
                INITIATOR_ID,
                "\000"))
            {
                final LogonEncoder logon = new LogonEncoder();
                connection.setupHeader(logon.header(), connection.acquireMsgSeqNum(), false);

                logon
                    .encryptMethod(0)
                    .heartBtInt(20)
                    .username("AAAAAAAAA")
                    .password("asd");

                final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(new byte[BUFFER_SIZE]);
                final long result = logon.encode(asciiBuffer, 0);
                final int offset = Encoder.offset(result);
                final int length = Encoder.length(result);

                // Remove the acceptor id whilst keeping the checksum the same
                final byte[] badLogon = asciiBuffer.getAscii(offset, length)
                    .replace("\000", "")
                    .replace(INITIATOR_ID, INITIATOR_ID + "\000")
                    .getBytes(US_ASCII);

                connection.sendBytes(badLogon);
                assertFalse(connection.isConnected());
            }
            catch (final IOException e)
            {
                e.printStackTrace();
            }
        });

        final List<SessionInfo> sessions = engine.allSessions();
        assertThat("sessions = " + sessions, sessions, hasSize(0));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRejectMessagesOverThrottle() throws IOException
    {
        setup(true, true, true, ENGINE, true);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);
            testSystem.poll();

            assertMessagesRejectedAboveThrottleRate(connection, THROTTLE_MSG_LIMIT, 2, 4, 1);

            testSystem.awaitBlocking(MessageBasedAcceptorSystemTest::sleepThrottleWindow);

            final HeartbeatDecoder abc = connection.exchangeTestRequestHeartbeat("ABC");
            assertEquals(10, abc.header().msgSeqNum());

            // Test that resend requests work with throttle rejection
            connection.sendResendRequest(4, 5);
            testSystem.awaitBlocking(() ->
            {
                assertReadsBusinessReject(connection, 4, 6, true, THROTTLE_MSG_LIMIT);
                assertReadsBusinessReject(connection, 5, 7, true, THROTTLE_MSG_LIMIT);
            });

            final HeartbeatDecoder def = connection.exchangeTestRequestHeartbeat("DEF");
            assertEquals(11, def.header().msgSeqNum());
            testSystem.poll();

            // Reset the throttle rate
            session = acquireSession();
            final Reply<ThrottleConfigurationStatus> reply = testSystem.awaitCompletedReply(session.throttleMessagesAt(
                TEST_THROTTLE_WINDOW_IN_MS, RESET_THROTTLE_MSG_LIMIT));
            assertEquals(OK, reply.resultIfPresent(), reply.toString());

            testSystem.awaitBlocking(() ->
            {
                sleepThrottleWindow();

                assertMessagesRejectedAboveThrottleRate(connection, RESET_THROTTLE_MSG_LIMIT, 12, 20, 0);

                sleepThrottleWindow();

                connection.logoutAndAwaitReply();
            });
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldAnswerResendRequestWithHighSeqNumUsingReplayer() throws IOException
    {
        shouldAnswerResendRequestWithHighSeqNum(true);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldAnswerResendRequestWithHighSeqNumUsingGapfiller() throws IOException
    {
        shouldAnswerResendRequestWithHighSeqNum(false);
    }

    private void shouldAnswerResendRequestWithHighSeqNum(final boolean logging) throws IOException
    {
        setupWithLogging(true, true, logging);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);
            testSystem.poll();

            final HeartbeatDecoder abc = connection.exchangeTestRequestHeartbeat("ABC");
            assertEquals(2, abc.header().msgSeqNum());

            // shift msg seq num
            connection.msgSeqNum(connection.msgSeqNum() + 3);
            connection.sendResendRequest(1, 2);
            // answers with resend
            connection.readResendRequest(3, 0);
            // because original resend request is not resent, but rather gap filled
            connection.sendGapFill(3, connection.msgSeqNum() + 1);
            // answers the resend as well
            final SequenceResetDecoder sequenceResetDecoder = connection.readMessage(new SequenceResetDecoder());
            assertEquals(sequenceResetDecoder.header().msgSeqNum(), 1);
            assertEquals(sequenceResetDecoder.newSeqNo(), 3);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportLogonBasedSequenceNumberResetWithImmediateMessageSend() throws IOException
    {
        shouldSupportLogonBasedSequenceNumberReset(true, ENGINE, (connection, reportFactory) ->
        {
            connection.msgSeqNum(1).logon(true);

            testSystem.awaitReceivedSequenceNumber(session, 1);
            reportFactory.sendReport(testSystem, session, Side.SELL);
            assertEquals(3, session.lastSentMsgSeqNum());
            connection.readExecutionReport(3);
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportLogonBasedSequenceNumberResetWithImmediateMessageSendAndDisconnect() throws IOException
    {
        shouldSupportLogonBasedSequenceNumberReset(false, SOLE_LIBRARY, (connection, reportFactory) ->
        {
            // before we can reply with a Logon, a disconnect happens
        });

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.msgSeqNum(1).logon(false);
            testSystem.awaitBlocking(() -> connection.readLogon(3));

            connection.sendTestRequest("test1");
            testSystem.awaitBlocking(() -> connection.readHeartbeat("test1"));
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportLogonBasedSequenceNumberResetWithLowSequenceNumberReconnect() throws IOException
    {
        shouldSupportLogonBasedSequenceNumberReset(true, SOLE_LIBRARY, (connection, reportFactory) ->
        {
            // Replicate a buggy client triggering a disconnect whilst the session is performing a
            // sequence number reset that results in a logon not being replied to
            connection.sendExecutionReport(1, false);
            assertSessionDisconnected(testSystem, session);
            assertEquals("MsgSeqNum too low, expecting 3 but received 1",
                connection.readLogout().textAsString());
            assertFalse(connection.isConnected());
        });

        try (FixConnection connection = FixConnection.initiate(port))
        {
            awaitedLogon(connection);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportLogonBasedSequenceNumberResetWithMessagesSentBeforeLogonResponse() throws IOException
    {
        shouldSupportLogonBasedSequenceNumberReset(false, SOLE_LIBRARY, (connection, reportFactory) ->
        {
            final int seqNum1 = connection.acquireMsgSeqNum();
            assertEquals(3, seqNum1);
            connection.sendExecutionReport(seqNum1, false);
            testSystem.awaitMessageOf(otfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR,
                msg -> msg.messageSequenceNumber() == seqNum1 && msg.sequenceIndex() == 0);

            connection.msgSeqNum(1);
            connection.logon(true);

            final int seqNum2 = connection.acquireMsgSeqNum();
            assertEquals(2, seqNum2);
            connection.sendExecutionReport(seqNum2, false);
            testSystem.awaitMessageOf(otfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR,
                msg -> msg.messageSequenceNumber() == seqNum2 && msg.sequenceIndex() == 1);

            connection.sendResendRequest(2, 2);
            testSystem.awaitBlocking(
                () -> assertEquals(Side.SELL, connection.readResentExecutionReport(2).sideAsEnum()));
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldHandleOnlineResetFollowedByDisconnectAndRestart() throws IOException
    {
        final int erSeqNum;

        setup(false, true, true, SOLE_LIBRARY, false, false, 0, 0, true, true);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(false);
            testSystem.awaitBlocking(() -> connection.readLogon(1));

            session = handler.lastSession();

            connection.sendTestRequest("one");
            testSystem.awaitBlocking(() -> connection.readHeartbeat("one"));

            testSystem.awaitSend(session::tryResetSequenceNumbers);
            final LogonDecoder resettingLogon = connection.readLogon(1);
            assertTrue(resettingLogon.hasResetSeqNumFlag() && resettingLogon.resetSeqNumFlag());
        }

        assertSessionDisconnected(testSystem, session);
        ReportFactory.sendOneReport(testSystem, session, Side.SELL);
        erSeqNum = session.lastSentMsgSeqNum();

        teardownArtio();
        cleanupMediaDriver(mediaDriver);

        setup(false, true, true, SOLE_LIBRARY, false, false, 0, 0, false, true);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(false);
            testSystem.awaitBlocking(() -> connection.readLogon());

            connection.sendTestRequest("two");
            final HeartbeatDecoder hb = testSystem.awaitBlocking(() -> connection.readHeartbeat("two"));

            connection.sendResendRequest(1, 0);
            testSystem.awaitBlocking(() ->
            {
                connection.readSequenceResetGapFill(erSeqNum);
                connection.readResentExecutionReport(erSeqNum);
                connection.readSequenceResetGapFill(hb.header().msgSeqNum() + 1);
            });
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportReplayingReceivedMessagesWithDifferentFromAndToSequenceIndices() throws IOException
    {
        shouldSupportLogonBasedSequenceNumberReset(true, ENGINE, (connection, reportFactory) ->
        {
            connection.msgSeqNum(1).logon(true);

            testSystem.awaitReceivedSequenceNumber(session, 1);

            otfAcceptor.messages().clear();
            testSystem.awaitCompletedReply(session.replayReceivedMessages(
                1, 0,
                MOST_RECENT_MESSAGE, 1,
                5000));
            assertThat(otfAcceptor.messages(), hasSize(3));
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldFilterCatchupReplayMessagesWhenRequestingSession() throws IOException
    {
        setup(false, true);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);

            final Session session = acquireSession(0, CURRENT_SEQUENCE);
            assertThat(otfAcceptor.messages(), hasSize(1));

            testSystem.awaitReply(library.releaseToGateway(session, DEFAULT_REPLY_TIMEOUT_IN_MS));
            otfAcceptor.messages().clear();

            connection.exchangeTestRequestHeartbeat("ABC");

            testSystem.awaitReply(
                library.requestSession(session.id(), 0, CURRENT_SEQUENCE, DEFAULT_REPLY_TIMEOUT_IN_MS));
            assertThat(otfAcceptor.messages(), hasSize(2));
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldGracefullyHandleExceptionsInOnSessionStart() throws IOException
    {
        setup(true, true, true, SOLE_LIBRARY);

        setupLibrary();

        handler.shouldThrowInOnSessionStart(true);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            testSystem.awaitBlocking(() ->
            {
                connection.logon(false);

                final LogonDecoder logonReply = connection.readLogon();
                assertEquals(1, logonReply.header().msgSeqNum());

                connection.readLogout();
            });
        }

        assertTrue(handler.onSessionStartCalled());
        verify(errorHandler).onError(ArgumentMatchers.any(RuntimeException.class));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSendLogoutOnTimeoutDisconnect() throws IOException
    {
        reasonableTransmissionTimeInMs = 1;

        setup(true, true, true, ENGINE);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(true, timeoutDisconnectHeartBtIntInS);
            final LogonDecoder logon = connection.readLogon();
            assertTrue(logon.resetSeqNumFlag());

            processTimeoutDisconnect(connection);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSendLogoutOnTimeoutDisconnectSoleLibrary() throws IOException
    {
        reasonableTransmissionTimeInMs = 1;

        setup(true, true, true, SOLE_LIBRARY);

        setupLibrary();
        testSystem.awaitTimeoutInMs(LONG_TEST_TIMEOUT_IN_MS);

        final FakeDisconnectHandler onDisconnect = new FakeDisconnectHandler();
        handler.onDisconnectCallback(onDisconnect);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(true, timeoutDisconnectHeartBtIntInS);
            //noinspection Convert2MethodRef
            final LogonDecoder logon = testSystem.awaitBlocking(() -> connection.readLogon());
            assertTrue(logon.resetSeqNumFlag());

            session = handler.lastSession();
            assertNotNull(session);

            final long logoutTimeInMs = testSystem.awaitBlocking(() -> processTimeoutDisconnect(connection));
            SystemTestUtil.assertSessionDisconnected(testSystem, session);

            assertPromptDisconnect(logoutTimeInMs, onDisconnect.timeInMs());
            assertEquals(DisconnectReason.FIX_HEARTBEAT_TIMEOUT, onDisconnect.reason());
        }
    }

    private long processTimeoutDisconnect(final FixConnection connection)
    {
        connection.readHeartbeat();
        final TestRequestDecoder testRequest = connection.readTestRequest();
        assertEquals("TEST", testRequest.testReqIDAsString());

        connection.readHeartbeat();
        connection.readLogout();
        final long logoutTimeInMs = System.currentTimeMillis();

        assertFalse(connection.isConnected());
        final long disconnectTimeInMs = System.currentTimeMillis();

        assertPromptDisconnect(logoutTimeInMs, disconnectTimeInMs);

        return logoutTimeInMs;
    }

    private void assertPromptDisconnect(final long logoutTimeInMs, final long disconnectTimeInMs)
    {
        assertThat("Disconnect not prompt enough", (disconnectTimeInMs - logoutTimeInMs),
            Matchers.lessThan(timeoutDisconnectHeartBtIntInMs));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotAllowRequestingOfUnknownSession() throws IOException
    {
        setup(true, true);

        setupLibrary();

        // Create internal session object with a session id of -1
        try (FixConnection connection = FixConnection.initiate(port))
        {
            final SessionReplyStatus sessionReplyStatus = requestSession(library, Session.UNKNOWN, testSystem);
            assertEquals(SessionReplyStatus.UNKNOWN_SESSION, sessionReplyStatus);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportResendRequestsAfterSequenceReset() throws IOException
    {
        setup(true, true);

        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            // Given setup session with 1 sent execution report
            logon(connection);

            session = acquireSession();
            ReportFactory.sendOneReport(testSystem, session, Side.SELL);

            final ExecutionReportDecoder executionReport = connection.readExecutionReport();
            assertSell(executionReport);
            final int msgSeqNum = executionReport.header().msgSeqNum();

            // When you perform the sequence reset
            // NB: this is a high sequence number, so a normal FIX session would reject this message but it can be
            // useful to send it on an offline session
            final int highSeqNum = 100;
            assertThat(highSeqNum, greaterThan(msgSeqNum));
            testSystem.awaitSend(() -> session.trySendSequenceReset(highSeqNum, highSeqNum));

            testSystem.awaitBlocking(() ->
            {
                final SequenceResetDecoder sequenceReset = connection.readSequenceReset();
                assertFalse(sequenceReset.hasGapFillFlag(), sequenceReset.toString());
                assertEquals(highSeqNum, sequenceReset.newSeqNo());
            });

            testSystem.awaitBlocking(() ->
            {
                // Then you get the resend
                connection.msgSeqNum(highSeqNum).sendResendRequest(1, 0);
                connection.readSequenceResetGapFill(msgSeqNum);
                final ExecutionReportDecoder executionReportResent = connection.readExecutionReport(msgSeqNum);
                assertSell(executionReportResent);
                connection.readSequenceResetGapFill(highSeqNum);
            });
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportResendRequestsAfterOfflineSequenceReset() throws Exception
    {
        printErrors = true;

        setup(false, true, true, SOLE_LIBRARY);

        setupLibrary();

        int highestPrevSeqNum = 0;
        final ReportFactory reportFactory = new ReportFactory();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(false);
            testSystem.awaitBlocking(() -> connection.readLogon(1));

            session = handler.lastSession();
            assertNotNull(session);

            // make the acceptor send some messages to increase the outgoing sequence number
            final int erCount = 10;
            for (int i = 0; i < erCount; i++)
            {
                reportFactory.sendReport(testSystem, session, Side.BUY);
            }
            for (int i = 0; i < erCount; i++)
            {
                highestPrevSeqNum = connection.readExecutionReport().header().msgSeqNum();
            }
        }

        // reset sequence numbers while the session is disconnected to trigger the sequence reset flow
        assertSessionDisconnected(testSystem, session);
        testSystem.awaitSend(() -> session.tryResetSequenceNumbers());

        // we need to await on the received sequence number index, because that's what the framer will query on accept
        awaitIndexerCaughtUp(
            testSystem,
            mediaDriver.mediaDriver().aeronDirectoryName(),
            engine,
            library);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            // reconnect with sequence numbers reset
            connection.logon(false);
            testSystem.awaitBlocking(() -> connection.readLogon(1));

            // make the acceptor send an execution report, so that we can request a resend of something
            reportFactory.sendReport(testSystem, session, Side.SELL);
            final ExecutionReportDecoder er = connection.readExecutionReport();
            assertSell(er);

            // now request a resend of the execution report, which should get serviced, while making sure the sequence
            // number is lower than the highest in the previous sequence index
            final int erSeqNum = er.header().msgSeqNum();
            assertThat(erSeqNum, lessThan(highestPrevSeqNum));
            connection.sendResendRequest(erSeqNum, 0);
            testSystem.awaitBlocking(() -> connection.readResentExecutionReport(erSeqNum));
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectConnectionTryingToSendOversizedMessage() throws IOException
    {
        setup(true, true);

        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);
            final Session session = acquireSession();

            connection.sendBytesLarge(TestFixtures.largeMessage(DEFAULT_RECEIVER_BUFFER_SIZE + 5));

            assertSessionDisconnected(testSystem, session);
            assertEquals(1, session.lastReceivedMsgSeqNum());

            assertConnectionDisconnects(testSystem, connection);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldInvalidateMessageWithInvalidMsgType() throws IOException
    {
        setup(true, true);

        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);
            acquireSession();

            connection.sendBytes(messageWithMsgType(null));
            connection.sendBytes(messageWithMsgType(""));
            connection.sendBytes(messageWithMsgType("ABCDEFGHI"));

            final String longestAllowedMsgType = "ABCDEFGH";
            connection.sendBytes(messageWithMsgType(longestAllowedMsgType));

            final FixMessage message = testSystem.awaitMessageOf(otfAcceptor, longestAllowedMsgType);
            assertEquals(MessageTypeEncoding.packMessageType(longestAllowedMsgType), message.messageType());

            if (otfAcceptor.messages().size() > 1)
            {
                fail("received more messages than expected: " + otfAcceptor.messages());
            }
        }
    }

    private static byte[] messageWithMsgType(final String msgType)
    {
        final byte[] buffer = new byte[128];
        final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(buffer);

        final int bodyStart = 16;
        int index = bodyStart;

        if (null != msgType)
        {
            index += asciiBuffer.putIntAscii(index, 35);
            asciiBuffer.putByte(index++, (byte)'=');
            index += asciiBuffer.putAscii(index, msgType);
            asciiBuffer.putByte(index++, START_OF_HEADER);
        }

        index += asciiBuffer.putAscii(index, "49=initiator\u000156=acceptor\u000134=2\u000152=");
        index += asciiBuffer.putAscii(index, UTC_TIMESTAMP_FORMATTER.format(Instant.now()));
        asciiBuffer.putByte(index++, START_OF_HEADER);

        asciiBuffer.putByte(bodyStart - 1, START_OF_HEADER);
        final int length = index - bodyStart;
        int startIndex = asciiBuffer.putNaturalIntAsciiFromEnd(length, bodyStart - 1);
        final String prefix = "8=FIX.4.4\u00019=";
        final int prefixLength = prefix.length();
        startIndex -= asciiBuffer.putAscii(startIndex - prefixLength, prefix);

        final int checksum = asciiBuffer.computeChecksum(startIndex, index);
        index += asciiBuffer.putAscii(index, "10=");
        asciiBuffer.putNaturalPaddedIntAscii(index, 3, checksum);
        index += 3;
        asciiBuffer.putByte(index++, START_OF_HEADER);

        return Arrays.copyOfRange(buffer, startIndex, index);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldSupportFollowerSessionLogonWithoutSequenceResetOnDisconnectBeforeLibraryLogonResponse()
        throws IOException
    {
        setup(false, true);
        setupLibrary();

        final List<SessionInfo> noSessionContext = engine.allSessions();
        assertEquals(0, noSessionContext.size());

        final SessionWriter sessionWriter = createFollowerSession(
            TEST_TIMEOUT_IN_MS, testSystem, library, INITIATOR_ID, ACCEPTOR_ID);
        final SessionReplyStatus requestSessionReply = requestSession(library, sessionWriter.id(), testSystem);
        assertEquals(SessionReplyStatus.OK, requestSessionReply);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(false);
            Timing.assertEventuallyTrue("Library did not transition session to connected",
                () ->
                {
                    library.poll(1);
                    final List<Session> sessions = library.sessions();
                    return sessions.size() == 1 && sessions.get(0).state() == SessionState.CONNECTED;
                }
            );
        }

        Timing.assertEventuallyTrue("Fix connection was not disconnected",
            () ->
            {
                final Reply<List<LibraryInfo>> libraryReply = engine.libraries();
                while (!libraryReply.hasCompleted())
                {
                    sleep(100);
                }

                final List<LibraryInfo> allLibraryInfo = libraryReply.resultIfPresent();
                for (final LibraryInfo libraryInfo : allLibraryInfo)
                {
                    if (libraryInfo.libraryId() == library.libraryId())
                    {
                        return libraryInfo.sessions().isEmpty();
                    }
                }
                return false;
            }
        );

        Timing.assertEventuallyTrue("Library did not transition session to active",
            () ->
            {
                library.poll(1);
                final List<Session> sessions = library.sessions();
                return sessions.size() == 1 && sessions.get(0).state() == SessionState.ACTIVE;
            }
        );

        assertEngineSubscriptionCaughtUpToLibraryPublication(
            testSystem, mediaDriver.mediaDriver().aeronDirectoryName(), engine, library);

        final List<SessionInfo> sessionContextAfterLogonNoSenderEndpoint = engine.allSessions();
        assertEquals(1, sessionContextAfterLogonNoSenderEndpoint.size());
        assertEquals(0, sessionContextAfterLogonNoSenderEndpoint.get(0).sequenceIndex());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldResendAllOutboundMessagesInFlight() throws IOException
    {
        final int outboundMessageCount = 100;

        createOutboundMessagesInFlight(outboundMessageCount, true, (connection ->
        {
            connection.readSequenceResetGapFill(2);

            for (int i = 0; i < outboundMessageCount; ++i)
            {
                final ExecutionReportDecoder decoder = connection.readExecutionReport();
                assertSell(decoder);
                assertTrue(decoder.header().hasPossDupFlag() && decoder.header().possDupFlag(),
                    decoder.toString());
            }
        }));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldGapfillAllOutboundMessagesInFlight() throws IOException
    {
        final int outboundMessageCount = 100;

        createOutboundMessagesInFlight(outboundMessageCount, false, connection ->
        {
            connection.readSequenceResetGapFill(outboundMessageCount + 2);
        });
    }

    private void createOutboundMessagesInFlight(
        final int outboundMessageCount,
        final boolean logging,
        final Consumer<FixConnection> fixConnectionConsumer) throws IOException
    {
        setupWithLogging(true, true, logging);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.logon(true, 45);
            testSystem.awaitBlocking(() -> connection.readLogon());

            session = acquireSession();
            for (int i = 0; i < outboundMessageCount; ++i)
            {
                ReportFactory.sendOneReport(testSystem, session, Side.SELL);
            }

            connection.sendResendRequest(1, 0);
            testSystem.awaitIsReplaying(session);

            testSystem.awaitBlocking(() ->
            {
                for (int i = 0; i < outboundMessageCount; ++i)
                {
                    final ExecutionReportDecoder decoder = connection.readExecutionReport();
                    assertSell(decoder);
                    assertFalse(decoder.header().hasPossDupFlag(), decoder.toString());
                }

                fixConnectionConsumer.accept(connection);
            });
        }
    }

    private void assertSell(final ExecutionReportDecoder executionReport)
    {
        assertEquals(Side.SELL, executionReport.sideAsEnum(), executionReport.toString());
    }

    private void shouldSupportLogonBasedSequenceNumberReset(
        final boolean sequenceNumberReset,
        final InitialAcceptedSessionOwner owner,
        final BiConsumer<FixConnection, ReportFactory> onNext)
        throws IOException
    {
        // Replicates a bug reported where if you send a message on a FIX session after a tryResetSequenceNumbers
        // and before the counter-party replies with their logon then it can result in an infinite logon loop.

        setup(sequenceNumberReset, true, true, owner);
        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            awaitedLogon(connection);

            final ReportFactory reportFactory = new ReportFactory();

            if (owner == ENGINE)
            {
                session = acquireSession();
            }
            else
            {
                session = handler.lastSession();
                assertNotNull(session);
            }
            reportFactory.sendReport(testSystem, session, Side.BUY);
            connection.readExecutionReport(2);
            connection.sendExecutionReport(connection.acquireMsgSeqNum(), false);
            testSystem.awaitReceivedSequenceNumber(session, 2);

            testSystem.awaitSend(session::tryResetSequenceNumbers);
            reportFactory.sendReport(testSystem, session, Side.SELL);
            assertEquals(2, session.lastSentMsgSeqNum());
            assertEquals(2, session.lastReceivedMsgSeqNum());

            assertTrue(connection.readLogon(1).resetSeqNumFlag());
            connection.readExecutionReport(2);

            // Last sequence numbers Artio->Client: 2, Client->Artio: 2, client needs to send logon,seqnum=1 after
            onNext.accept(connection, reportFactory);
        }
    }

    public static void sleepThrottleWindow()
    {
        sleep(TEST_THROTTLE_WINDOW_IN_MS);
    }

    private void assertMessagesRejectedAboveThrottleRate(
        final FixConnection connection,
        final int limitOfMessages,
        final int seqNumOffset,
        final int refSeqNumOffset,
        final int messagesWithinThrottleWindow)
    {
        final int reportCount = 10;
        for (int i = 0; i < reportCount; i++)
        {
            connection.sendExecutionReport(connection.acquireMsgSeqNum(), false);

            sleep(1);
        }

        // messagesWithinThrottleWindow - could be login message at the start
        for (int i = 0; i < (reportCount - limitOfMessages) + messagesWithinThrottleWindow; i++)
        {
            assertReadsBusinessReject(
                connection, i + seqNumOffset, i + refSeqNumOffset, false, limitOfMessages);
        }
    }

    private void assertReadsBusinessReject(
        final FixConnection connection,
        final int seqNum,
        final int refSeqNum,
        final boolean possDup,
        final int limitOfMessages)
    {
        final BusinessMessageRejectDecoder reject = connection.readBusinessReject();
        final HeaderDecoder header = reject.header();
        assertEquals(seqNum, header.msgSeqNum());
        assertEquals(ACCEPTOR_ID, header.senderCompIDAsString());
        assertEquals(INITIATOR_ID, header.targetCompIDAsString());
        assertEquals(possDup, header.hasPossDupFlag() && header.possDupFlag());

        assertEquals(99, reject.businessRejectReason(), "wrong businessRejectReason");
        assertEquals("8", reject.refMsgTypeAsString());
        assertEquals(refSeqNum, reject.refSeqNum(), "wrong refSeqNum");
        assertEquals("Throttle limit exceeded (" + limitOfMessages + " in 300ms)",
            reject.textAsString());
    }

    private static void sleep(final int timeInMs)
    {
        try
        {
            Thread.sleep(timeInMs);
        }
        catch (final InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    private void shouldDisconnectConnectionWithNoLogon(final InitialAcceptedSessionOwner initialAcceptedSessionOwner)
        throws IOException
    {
        setup(true, true, true, initialAcceptedSessionOwner);

        final FakeOtfAcceptor fakeOtfAcceptor = new FakeOtfAcceptor();
        final FakeHandler fakeHandler = new FakeHandler(fakeOtfAcceptor);
        try (FixLibrary library = newAcceptingLibrary(fakeHandler, nanoClock))
        {
            try (FixConnection connection = FixConnection.initiate(port))
            {
            }
        }
    }

    private void sendInvalidLogon(final FixConnection connection)
    {
        final LogonEncoder logon = new LogonEncoder()
            .resetSeqNumFlag(true)
            .encryptMethod(0)
            .heartBtInt(30)
            .maxMessageSize(9999);

        sendInvalidMessage(connection, logon);
    }

    private void sendInvalidTestRequestMessage(final FixConnection connection)
    {
        final TestRequestEncoder testRequest = new TestRequestEncoder();
        testRequest.testReqID("A");

        sendInvalidMessage(connection, testRequest);
    }

    private void sendInvalidMessage(final FixConnection connection, final Encoder encoder)
    {
        final SessionHeaderEncoder header = encoder.header();

        connection.setupHeader(header, connection.acquireMsgSeqNum(), false);

        header.sendingTime("nonsense".getBytes(US_ASCII));

        connection.send(encoder);
    }

    private void logonThenLogout() throws IOException
    {
        try (FixConnection connection = FixConnection.initiate(port))
        {
            logon(connection);
            connection.logoutAndAwaitReply();
        }
    }
}
