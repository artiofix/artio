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
package uk.co.real_logic.artio.system_tests;

import org.agrona.collections.IntArrayList;
import org.agrona.concurrent.status.ReadablePosition;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.DynamicLibraryScheduler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.Constants.*;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.MonitoringAgentFactory.consumeDistinctErrors;
import static uk.co.real_logic.artio.Reply.State.ERRORED;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.mediaDriverContext;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.system_tests.FixMessage.hasMessageSequenceNumber;
import static uk.co.real_logic.artio.system_tests.FixMessage.hasSequenceIndex;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

public class PersistentSequenceNumberGatewayToGatewaySystemTest extends AbstractGatewayToGatewaySystemTest
{
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss[.nnn]");

    private static final int HIGH_INITIAL_SEQUENCE_NUMBER = 1000;
    private static final int DOES_NOT_MATTER = -1;
    private static final int DEFAULT_SEQ_NUM_AFTER = 4;

    private File backupLocation = null;

    private Runnable onAcquireSession = () ->
    {
        final long sessionId = getAcceptingSessionId();

        acquireSession(sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
    };

    private Consumer<Reply<Session>> onInitiateReply = reply ->
    {
        assertEquals("Reply failed: " + reply, Reply.State.COMPLETED, reply.state());
        initiatingSession = reply.resultIfPresent();
        assertConnected(initiatingSession);
    };

    private final ErrorCounter errorCounter = new ErrorCounter();
    private Runnable duringRestart = () -> dirsDeleteOnStart = false;
    private Runnable beforeReconnect = this::nothing;
    private boolean printErrorMessages = true;
    private boolean resetSequenceNumbersOnLogon = false;
    private boolean dirsDeleteOnStart = true;

    private TimeRange firstConnectTimeRange;

    private final IntArrayList resendMsgSeqNums = new IntArrayList();

    @Before
    public void setUp() throws IOException
    {
        deleteAcceptorLogs();
        delete(CLIENT_LOGS);
        backupLocation = Files.createTempFile("backup", "tmp").toFile();
    }

    @After
    public void cleanupBackup()
    {
        if (null != backupLocation)
        {
            assertTrue("Failed to delete: " + backupLocation, backupLocation.delete());
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sequenceNumbersCanPersistOverRestarts()
    {
        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, DEFAULT_SEQ_NUM_AFTER);

        assertSequenceIndicesAre(0);
        assertLastLogonEquals(4, 0);

        assertSequenceResetBeforeLastLogon(initiatingSession);
        assertSequenceResetBeforeLastLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void previousMessagesAreReplayed()
    {
        onAcquireSession = this::requestReplayWhenReacquiringSession;

        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, DEFAULT_SEQ_NUM_AFTER);

        assertSequenceIndicesAre(0);
        assertLastLogonEquals(4, 0);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldCopeWithCatchupReplayOfMissingMessages()
    {
        deleteAcceptorLogsDuringRestart();

        onAcquireSession = () -> assertReplyStatusWhenReplayRequested(OK);

        resetSequenceNumbersOnLogon = true;

        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, DOES_NOT_MATTER);

        assertOnlyAcceptorSequenceReset();
        assertLastLogonEquals(1, 0);
    }

    private void deleteAcceptorLogsDuringRestart()
    {
        duringRestart = () ->
        {
            dirsDeleteOnStart = false;
            deleteAcceptorLogs();
        };
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldCopeWithResendRequestOfMissingMessages()
    {
        printErrorMessages = false;

        // Trigger a situation where a resend request is sent to the acceptor for messages
        // That aren't in its archive to hit a "missing messages" scenario

        beforeReconnect = () ->
        {
            final long acceptingId = acceptingSession.id();

            acceptingSession = SystemTestUtil.acquireSession(
                acceptingHandler, acceptingLibrary, acceptingId, testSystem);
            assertOfflineSession(acceptingId, acceptingSession);

            // reset the sequence number to a higher number
            final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);
            final ReportFactory factory = new ReportFactory();
            factory.sendReport(testSystem, acceptingSession, Side.BUY); // 4
            acceptingSession.lastSentMsgSeqNum(7);
            final long position = factory.sendReport(testSystem, acceptingSession, Side.BUY); // 8

            testSystem.awaitPosition(positionCounter, position);

            onAcquireSession = this::nothing;
        };

        exchangeMessagesAroundARestart(4, 9, 4, 9);

        sendResendRequestFromInit(4, 6);
        receivesGapfill(initiatingOtfAcceptor, equalTo(7));
        assertResendsCompleted(1, hasItems(0));
    }

    private void receivesGapfill(final FakeOtfAcceptor initiatingOtfAcceptor, final Matcher<Integer> newSeqNoMatcher)
    {
        final FixMessage gapFillMessage =
            testSystem.awaitMessageOf(initiatingOtfAcceptor, SEQUENCE_RESET_MESSAGE_AS_STR);
        final int newSeqNo = Integer.parseInt(gapFillMessage.get(Constants.NEW_SEQ_NO));
        final String gapFillFlag = gapFillMessage.get(Constants.GAP_FILL_FLAG);

        assertEquals("Y", gapFillFlag);
        assertThat(newSeqNo, newSeqNoMatcher);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldCopeWithResendRequestOfMissingMessagesWithHighInitialSequenceNumberSet()
    {
        exchangeMessagesAroundARestart(
            HIGH_INITIAL_SEQUENCE_NUMBER,
            4,
            HIGH_INITIAL_SEQUENCE_NUMBER,
            5,
            greaterThan(HIGH_INITIAL_SEQUENCE_NUMBER));

        // Test that we don't accidentally send another resend request
        // Reproduction of reported bug
        Timing.assertEventuallyTrue("", () -> testSystem.poll(), 100);
        assertEquals(1, initiatingOtfAcceptor.receivedMessage(RESEND_REQUEST_MESSAGE_AS_STR).count());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void messagesCanBeReplayedOverRestart()
    {
        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, DEFAULT_SEQ_NUM_AFTER);

        sendResendRequestFromInit(1, 1);

        final FixMessage message = testSystem.awaitMessageOf(initiatingOtfAcceptor, SEQUENCE_RESET_MESSAGE_AS_STR);

        assertEquals(message.get(Constants.MSG_SEQ_NUM), "1");
        assertEquals(message.get(Constants.SENDER_COMP_ID), ACCEPTOR_ID);
        assertEquals(message.get(Constants.TARGET_COMP_ID), INITIATOR_ID);

        assertSequenceIndicesAre(0);
        assertLastLogonEquals(4, 0);
    }

    private void sendResendRequestFromInit(final int beginSeqNo, final int endSeqNo)
    {
        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();
        resendRequest.beginSeqNo(beginSeqNo).endSeqNo(endSeqNo);

        initiatingOtfAcceptor.messages().clear();

        testSystem.send(initiatingSession, resendRequest);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void customInitialSequenceNumbersCanBeSet()
    {
        exchangeMessagesAroundARestart(4, DEFAULT_SEQ_NUM_AFTER);

        assertSequenceIndicesAre(0);
        assertLastLogonEquals(4, 0);

        assertSequenceResetBeforeLastLogon(initiatingSession);
        assertSequenceResetBeforeLastLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sessionsCanBeReset()
    {
        beforeReconnect = this::resetSessions;

        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, 1);

        // Different sessions themselves, so we start again at 0
        assertSequenceIndicesAre(0);
        assertLastLogonEquals(1, 0);

        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sequenceNumbersCanBeResetWhileSessionDisconnected()
    {
        beforeReconnect = this::resetSequenceNumbers;

        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, 1);

        assertSequenceIndicesAre(1);
        assertLastLogonEquals(1, 1);

        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sequenceNumbersCanBeResetOnLogon()
    {
        resetSequenceNumbersOnLogon = true;

        exchangeMessagesAroundARestart(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, 1);

        acceptingOtfAcceptor.logonMessagesHaveSequenceNumbers(1);
        initiatingOtfAcceptor.logonMessagesHaveSequenceNumbers(1);
        assertSequenceIndicesAre(1);
        assertLastLogonEquals(1, 1);

        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void sequenceNumbersCanBeResetOnLogonWithoutARestart()
    {
        launch(this::nothing);
        connectPersistingSessions();

        assertSequenceIndicesAre(0);

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);
        assertSequenceFromInitToAcceptAt(2, 2);

        final long initiatedSessionId = initiatingSession.id();
        final long acceptingSessionId = acceptingSession.id();

        logoutInitiatingSession();
        assertSessionsDisconnected();
        assertInitiatingSequenceIndexIs(0);
        assertAcceptingSessionHasSequenceIndex(0);

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        connectPersistingSessions(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, true);

        assertEquals(initiatedSessionId, initiatingSession.id());
        assertEquals(acceptingSessionId, acceptingSession.id());

        acceptingOtfAcceptor.logonMessagesHaveSequenceNumbers(1);
        initiatingOtfAcceptor.logonMessagesHaveSequenceNumbers(1);

        // Sequence numbers reset, so the sequence index should increment
        assertSequenceIndicesAre(1);
        assertLastLogonEquals(1, 1);

        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReceiveRelevantLogoutErrorTextDuringConnect()
    {
        onInitiateReply = reply ->
        {
            initiatingSession = reply.resultIfPresent();
            assertTrue("reply did not end in an error", reply.hasErrored());
        };

        onAcquireSession = this::nothing;

        // connect but fail to logon because the initial sequence number is invalid.
        launch(this::nothing);
        connectPersistingSessions(0, false);

        // No session established due to error during logon
        assertNull(initiatingSession);

        // In this case we just get immediately disconnected.
        final FixMessage lastMessage = testSystem.awaitMessageOf(initiatingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);

        assertEquals(LOGOUT_MESSAGE_AS_STR, lastMessage.msgType());
        assertEquals(1, lastMessage.messageSequenceNumber());
        assertEquals("MsgSeqNum too low, expecting 1 but received 0", lastMessage.get(Constants.TEXT));
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldPersistSequenceNumbersWithoutARestart()
    {
        launch(this::nothing);
        connectPersistingSessions();

        assertSequenceIndicesAre(0);

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);
        assertSequenceFromInitToAcceptAt(2, 2);

        final long initiatedSessionId = initiatingSession.id();
        final long acceptingSessionId = acceptingSession.id();

        logoutInitiatingSession();
        assertSessionsDisconnected();
        assertInitiatingSequenceIndexIs(0);
        assertAcceptingSessionHasSequenceIndex(0);

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        connectPersistingSessions();

        assertEquals(initiatedSessionId, initiatingSession.id());
        assertEquals(acceptingSessionId, acceptingSession.id());

        // Sequence numbers not reset, so the same sequence index
        assertInitiatingSequenceIndexIs(0);
        assertAcceptingSessionHasSequenceIndex(0);
        messagesCanBeExchanged();

        // 5: logon, test-req/heartbeat, logout. logon 2, test-req/heartbeat 2
        assertSequenceFromInitToAcceptAt(5, 5);
        assertLastLogonEquals(4, 0);
    }

    @Test(timeout = LONG_TEST_TIMEOUT_IN_MS)
    public void shouldNotAllowResendRequestSpamming()
    {
        errorCounter.containsString("Ignore resend request for sessionId");

        printErrorMessages = false;

        launch(this::nothing);

        final ReplayCountChecker replayCountChecker =
            ReplayCountChecker.start(acceptingEngine, testSystem, 1);

        connectPersistingSessions();
        // Send execution report with seqNum=1
        final ReportFactory reportFactory = new ReportFactory();
        final int messageCount = 100;
        for (int i = 0; i < messageCount; i++)
        {
            reportFactory.sendReport(testSystem, acceptingSession, Side.BUY);
        }

        final int lastSeqNum = messageCount + 1;
        assertSequenceFromInitToAcceptAt(1, lastSeqNum);

        logoutInitiatingSession();
        assertSessionsDisconnected();

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        connectPersistingSessions();

        final int replayRequests = 1000;
        for (int i = 0; i < replayRequests; i++)
        {
            sendResendRequest(2, 0, initiatingOtfAcceptor, initiatingSession);
        }

        testSystem.await("Failed to suppress resend requests", () -> errorCounter.lastObservationCount() > 0);

        testSystem.awaitSend("Failed to requestDisconnect", () -> initiatingSession.requestDisconnect());
        assertSessionDisconnected(acceptingSession);

        testSystem.removeOperation(replayCountChecker);
        replayCountChecker.assertBelowThreshold();
    }

    @Test(timeout = 50_000)
    public void shouldDetectDisconnectDuringReplay()
    {
        printErrorMessages = false;

        launch(this::nothing);

        connectPersistingSessions();
        final ReportFactory reportFactory = new ReportFactory();
        final int messageCount = 2_000;
        for (int i = 0; i < messageCount; i++)
        {
            reportFactory.sendReport(testSystem, acceptingSession, Side.BUY);
        }
        testSystem.await("Failed to receive execution reports",
            () -> initiatingOtfAcceptor.receivedMessage(EXECUTION_REPORT_MESSAGE_AS_STR).count() == messageCount);

        final ReadablePosition initReadablePosition = testSystem.libraryPosition(initiatingEngine, initiatingLibrary);

        for (int i = 0; i < 3; i++)
        {
            final long position = testSystem.awaitSend(initiatingSession::requestDisconnect);
            assertSessionsDisconnected();
            clearMessages();
            initiatingSession = null;
            acceptingSession = null;
            acceptingHandler.clearSessionExistsInfos();
            testSystem.awaitPosition(initReadablePosition, position);

            connectPersistingSessions();

            assertFalse(acceptingSession.isReplaying());
            sendResendRequest(1, 0, initiatingOtfAcceptor, initiatingSession);
            testSystem.await("failed to start replaying", acceptingSession::isReplaying);

            // Pause for a little bit to test out race with replaying
            testSystem.awaitBlocking(() ->
            {
                try
                {
                    Thread.sleep(100);
                }
                catch (final InterruptedException e)
                {
                    e.printStackTrace();
                }
            });
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldRejectIncorrectInitiatorSequenceNumber()
    {
        printErrorMessages = false;

        launch(this::nothing);
        connectPersistingSessions();

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);
        assertSequenceFromInitToAcceptAt(2, 2);

        logoutInitiatingSession();
        assertSessionsDisconnected();

        final int initiatorSequenceNumber = initiatingSession.lastSentMsgSeqNum() + 1;
        int acceptorSequenceNumber = acceptingSession.lastSentMsgSeqNum() + 1;

        assertTrue(acceptingOtfAcceptor.messages().get(0).isValid());

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        // Replication of bug #295
        cannotConnectWithSequence(acceptorSequenceNumber, 1);
        cannotConnectWithSequence(acceptorSequenceNumber, 2);
        acceptorSequenceNumber += 2;

        final Reply<Session> okReply = connectPersistentSessions(
            initiatorSequenceNumber, acceptorSequenceNumber, false);
        assertEquals(Reply.State.COMPLETED, okReply.state());
        initiatingSession = okReply.resultIfPresent();
        acceptorSequenceNumber++;

        // An old message with a possDupFlag=Y set shouldn't break sequence number indices
        final int oldSequenceNumber = initiatingSession.lastSentMsgSeqNum() - 2;
        initiatingSession.lastSentMsgSeqNum(oldSequenceNumber);
        final TestRequestEncoder testRequest = new TestRequestEncoder().testReqID(testReqId());
        testRequest.header().possDupFlag(true);
        testSystem.send(initiatingSession, testRequest);

        initiatingSession.requestDisconnect();
        assertSessionDisconnected(initiatingSession);
        sessionNoLongerManaged(initiatingHandler, initiatingSession);

        cannotConnectWithSequence(acceptorSequenceNumber, oldSequenceNumber + 1);

        connectPersistingSessions();
    }

    private void cannotConnectWithSequence(
        final int acceptorSequenceNumber, final int initiatorInitialSentSequenceNumber)
    {
        final Reply<Session> reply = connectPersistentSessions(
            initiatorInitialSentSequenceNumber, acceptorSequenceNumber, false);
        assertEquals(ERRORED, reply.state());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReadOldMetaDataOverPersistentConnectionReconnect()
    {
        launch(this::nothing);
        connectPersistingSessions();

        writeMetaData();

        logoutInitiatingSession();
        assertSessionsDisconnected();

        connectPersistingSessions();

        readMetaData(acceptingSession.id());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentWhilstOffline()
    {
        launch(this::nothing);
        connectPersistingSessions();

        disconnectSessions();

        final long sessionId = acceptingSession.id();

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        acquireAcceptingSession();
        receiveReplayFromOfflineSession(sessionId);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentWhilstInSessionStateTransition()
    {
        final ReportFactory reportFactory = new ReportFactory();

        launch(this::nothing);
        connectPersistingSessions();

        logoutSession(testSystem, acceptingSession);
        reportFactory.sendReport(testSystem, acceptingSession, Side.BUY);

        assertSessionsDisconnected();
        assertEquals("Received Execution Report sent after logout",
            0, initiatingOtfAcceptor.receivedMessage(EXECUTION_REPORT_MESSAGE_AS_STR).count());

        final long sessionId = acceptingSession.id();

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        acquireAcceptingSession();
        receiveReplayFromOfflineSession(sessionId);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentWithNewOfflineSession()
    {
        launch(this::nothing);

        final SessionWriter sessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);
        final long sessionId = sessionWriter.id();

        acceptingSession = SystemTestUtil.acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        receiveReplayFromOfflineSession(sessionId);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldReleaseNewOfflineSession()
    {
        launch(this::nothing);

        final SessionWriter sessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);
        final long sessionId = sessionWriter.id();

        acceptingSession = SystemTestUtil.acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        assertEquals(0, acceptingSession.sequenceIndex());

        final Reply<SessionReplyStatus> reply = acceptingLibrary.releaseToGateway(
            acceptingSession, TEST_TIMEOUT_IN_MS);
        testSystem.awaitCompletedReply(reply);

        // check that after release it can be re-acquired and logon.
        acceptingSession = SystemTestUtil.acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        assertEquals(0, acceptingSession.sequenceIndex());

        connectPersistingSessionsWithoutAcquiring();
        assertConnected(acceptingSession);
        assertEquals(0, acceptingSession.sequenceIndex());
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentWhilstOfflineWithFollowerSession()
    {
        printErrorMessages = false;

        launch(this::nothing);

        final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);

        final SessionWriter sessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);

        sendReportsOnFollowerSession(testSystem, sessionWriter, positionCounter);

        receivedReplayFromReconnectedSession();

        logoutInitiatingSession();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentOfflineWithFollowerSessionAndSequenceReset()
    {
        shouldStoreAndForwardMessagesSentOfflineWithFollowerSessionAndSequenceReset(2, 1);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesSentOfflineWithFollowerSessionAndSequenceResetHighInitiator()
    {
        shouldStoreAndForwardMessagesSentOfflineWithFollowerSessionAndSequenceReset(10, 10);
    }

    // Reproduction of a client bug
    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldStoreAndForwardMessagesWithSequenceReset2()
    {
        final int firstReportSeqNum = 2;
        final int secondReportSeqNum = 101;

        launch(this::nothing);
        final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);

        // Client connects for the first time.
        connectPersistingSessions(1, false);

        // Artio sends an Execution Report.
        final ReportFactory factory = new ReportFactory();
        factory.sendReport(testSystem, acceptingSession, Side.BUY);
        assertEquals(firstReportSeqNum, acceptingSession.lastSentMsgSeqNum());

        // Client TCP disconnects.
        testSystem.awaitRequestDisconnect(initiatingSession);
        assertSessionsDisconnected();
        initiatingSession = null;
        acceptingSession = null;
        acquireAcceptingSession();
        assertOfflineSession(acceptingSession.id(), acceptingSession);

        // Outbound sequence is set to 100.
        testSystem.awaitSend(() -> acceptingSession.trySendSequenceReset(secondReportSeqNum));

        // Artio sends another Execution Report.
        final long position = factory.sendReport(testSystem, acceptingSession, Side.BUY);
        testSystem.awaitPosition(positionCounter, position);
        assertEquals(secondReportSeqNum, acceptingSession.lastSentMsgSeqNum());

        // Client connects again.
        clearMessages();
        onAcquireSession = this::nothing;
        connectPersistingSessions(2, 1, false);

        // Client asks for a resend from 1 to 0.
        assertReceivedReplayedReport(firstReportSeqNum);
        assertReceivedReplaySequenceReset(firstReportSeqNum, secondReportSeqNum);
        assertReceivedReplayedReport(secondReportSeqNum);
    }

    private void assertReceivedReplaySequenceReset(final int firstReportSeqNum, final int secondReportSeqNum)
    {
        final Predicate<FixMessage> p = msg -> msg.messageSequenceNumber() == firstReportSeqNum + 1;
        final FixMessage gapFillMessage =
            testSystem.awaitMessageOf(initiatingOtfAcceptor, SEQUENCE_RESET_MESSAGE_AS_STR, p);
        final String msg = gapFillMessage.toString();

        assertEquals(msg, "Y", gapFillMessage.possDup());

        final int newSeqNo = Integer.parseInt(gapFillMessage.get(Constants.NEW_SEQ_NO));
        assertThat(msg, newSeqNo, equalTo(secondReportSeqNum));
    }

    private void shouldStoreAndForwardMessagesSentOfflineWithFollowerSessionAndSequenceReset(
        final int resetSequenceNumber,
        final int initiatorInitialReceivedSequenceNumber)
    {
        launch(this::nothing);

        final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);

        final SessionWriter sessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);

        final LogonEncoder logonEncoder = new LogonEncoder();
        logonEncoder.encryptMethod(EncryptMethod.NONE_OTHER);
        logonEncoder.heartBtInt(30);
        setupAndSend(sessionWriter, logonEncoder, 1);

        final HeartbeatEncoder heartbeatEncoder = new HeartbeatEncoder();
        heartbeatEncoder.testReqID("test");
        setupAndSend(sessionWriter, heartbeatEncoder, 2);

        final LogoutEncoder logoutEncoder = new LogoutEncoder();
        setupAndSend(sessionWriter, logoutEncoder, 3);

        final SequenceResetEncoder sequenceResetEncoder = new SequenceResetEncoder();
        sequenceResetEncoder.newSeqNo(resetSequenceNumber);
        final int sequenceIndex = sessionWriter.sequenceIndex() + 1;
        sessionWriter.sequenceIndex(sequenceIndex);
        setupAndSend(sessionWriter, sequenceResetEncoder, 3);

        final long position = sendReportOnFollowerSession(
            testSystem, sessionWriter, resetSequenceNumber, PossDupOption.MISSING_FIELD);

        testSystem.awaitPosition(positionCounter, position);

        // connectPersistingSessionsWithoutAcquiring
        onAcquireSession = this::nothing;
        connectPersistingSessions(1, initiatorInitialReceivedSequenceNumber, false);

        assertReceivedReplayedReport(resetSequenceNumber);

        logoutInitiatingSession();
        assertSessionDisconnected(initiatingSession);
    }

    private void setupAndSend(final SessionWriter sessionWriter, final Encoder encoder, final int seqNum)
    {
        setupHeader(seqNum, encoder.header());
        testSystem.awaitSend("failed to send", () -> sessionWriter.send(encoder, seqNum));
    }

    class AttemptSend implements Runnable
    {
        final ReportFactory factory = new ReportFactory();

        boolean firstConnect = true;
        int lastSentMsgSeqNum = 0;
        int lastSentMsgSeqNumAfterLogon = 0;

        public void run()
        {
            if (acceptingSession.state() == SessionState.DISCONNECTED)
            {
                if (factory.trySendReport(acceptingSession, Side.BUY) > 0)
                {
                    lastSentMsgSeqNum = acceptingSession.lastSentMsgSeqNum();
                }
            }
            else if (firstConnect)
            {
                lastSentMsgSeqNumAfterLogon = acceptingSession.lastSentMsgSeqNum();
                firstConnect = false;
            }
        }

        public void validate()
        {
            assertThat(lastSentMsgSeqNumAfterLogon, greaterThan(lastSentMsgSeqNum));
        }
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldNotRaceOfflineMessagesWithLogon()
    {
        launch(this::nothing);
        connectPersistingSessions();

        disconnectSessions();

        clearMessages();
        initiatingSession = null;
        acceptingSession = null;

        acquireAcceptingSession();

        final AttemptSend attemptSend = new AttemptSend();
        testSystem.addOperation(attemptSend);
        connectPersistingSessionsWithoutAcquiring();
        testSystem.removeOperation(attemptSend);
        attemptSend.validate();
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldSynchroniseOfflineSequenceNumbersWithFollowerSession()
    {
        launch(this::nothing);

        final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);

        final SessionWriter sessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);
        final long sessionId = sessionWriter.id();

        // we rely on this API handing out the same object for the same session id
        final SessionWriter sameSessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);
        assertSame(sessionWriter, sameSessionWriter);

        int sequenceNumber = 2;
        final long position = sendReportOnFollowerSession(testSystem, sessionWriter, sequenceNumber, PossDupOption.NO);
        testSystem.awaitPosition(positionCounter, position);

        // acquired sessions should know the sequence number from the follower session.
        acceptingSession = SystemTestUtil.acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        assertEquals(sequenceNumber, acceptingSession.lastSentMsgSeqNum());

        // acquired sessions should be informed of sequence number changes.
        sequenceNumber++;
        sendReportOnFollowerSession(testSystem, sessionWriter, sequenceNumber, PossDupOption.NO);
        assertEquals(sequenceNumber, acceptingSession.lastSentMsgSeqNum());
        connectPersistingSessionsWithoutAcquiring();

        logoutAcceptingSession();
        assertSessionDisconnected(initiatingSession);
        assertSessionDisconnected(acceptingSession);
        sequenceNumber = acceptingSession.lastSentMsgSeqNum();

        // Cover the other ordering of session first acquired before session writer.
        closeAcceptingLibrary();

        // Check that the old session writer can't be used.
        assertClosed(() -> sessionWriter.send(new NewOrderSingleEncoder(), 1));
        assertClosed(() -> sessionWriter.sequenceIndex(5));
        assertClosed(() -> sessionWriter.send(null, 1, 1, 1, 1));
        assertClosed(() -> sessionWriter.requestDisconnect(DisconnectReason.EXCEPTION));

        awaitLibraryDisconnect(acceptingEngine, testSystem);

        acceptingLibrary = testSystem.connect(acceptingLibraryConfig(acceptingHandler, nanoClock));

        acceptingSession = SystemTestUtil.acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        assertEquals(sequenceNumber, acceptingSession.lastSentMsgSeqNum());

        final SessionWriter newSessionWriter = createFollowerSession(TEST_TIMEOUT_IN_MS);
        assertEquals(sessionId, newSessionWriter.id());
        assertNotSame(sessionWriter, newSessionWriter);

        sequenceNumber++;
        sendReportOnFollowerSession(testSystem, newSessionWriter, sequenceNumber, PossDupOption.NO);
        assertEquals(sequenceNumber, acceptingSession.lastSentMsgSeqNum());
    }

    private void assertClosed(final ThrowingRunnable throwingRunnable)
    {
        assertThrows(IllegalStateException.class, throwingRunnable);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldResetSequenceNumbersOfOfflineSessions()
    {
        printErrorMessages = false;

        resetSomeSequenceNumbersOfOfflineSessions(
            () -> acceptingSession.trySendSequenceReset(1, 1),
            1,
            1,
            true);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldResetSequenceNumbersOfOfflineSessionsWithResetSequenceNumbers()
    {
        resetSomeSequenceNumbersOfOfflineSessions(
            () -> acceptingSession.tryResetSequenceNumbers(),
            1,
            1,
            true);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void shouldResetReceivedSequenceNumbersOfOfflineSessions()
    {
        resetSomeSequenceNumbersOfOfflineSessions(
            () -> acceptingSession.tryUpdateLastReceivedSequenceNumber(0),
            4,
            4,
            false);
    }

    private void resetSomeSequenceNumbersOfOfflineSessions(
        final LongSupplier resetSeqNums,
        final int logonSequenceNumber,
        final int initiatorInitialReceivedSequenceNumber,
        final boolean retry)
    {
        launch(this::nothing);
        connectPersistingSessions();
        assertEquals(0, acceptingSession.sequenceIndex());
        disconnectSessions();
        clearMessages();

        acquireAcceptingSession();
        assertOfflineSession(acceptingSession.id(), acceptingSession);
        assertEquals(0, acceptingSession.sequenceIndex());

        final int acceptorSentSequenceNumber = acceptingSession.lastSentMsgSeqNum() + 1;
        cannotConnectWithSequence(acceptorSentSequenceNumber, 1);

        final ReadablePosition positionCounter = testSystem.libraryPosition(acceptingEngine, acceptingLibrary);

        assertThat(acceptingSession, FixMatchers.hasSequenceIndex(0));
        long position = testSystem.awaitSend(resetSeqNums);
        assertThat(acceptingSession, FixMatchers.hasSequenceIndex(1));
        assertEquals(0, acceptingSession.lastReceivedMsgSeqNum());

        if (retry)
        {
            // Retry operation to ensure that it doesn't stall the sequence index increment:
            // If this fails then the prune operation can later fail.
            position = testSystem.awaitSend(resetSeqNums);
            assertThat(acceptingSession, FixMatchers.hasSequenceIndex(2));
        }

        initiatingOtfAcceptor.messages().clear();

        onAcquireSession = this::nothing;
        testSystem.awaitPosition(positionCounter, position);
        connectPersistingSessions(1, initiatorInitialReceivedSequenceNumber, false);

        final FixMessage logon = initiatingOtfAcceptor.receivedMessage(LOGON_MESSAGE_AS_STR).findFirst().get();
        assertEquals(logonSequenceNumber, logon.messageSequenceNumber());

        assertAcceptingSessionHasSequenceIndex(retry ? 2 : 1);
    }

    @Test(timeout = TEST_TIMEOUT_IN_MS)
    public void followerSessionShouldReturnCurrentConnectionId()
    {
        launch(this::nothing);

        final SessionWriter followerSession = createFollowerSession(TEST_REPLY_TIMEOUT_IN_MS);
        assertEquals(NO_CONNECTION_ID, followerSession.connectionId());

        for (int i = 0; i < 2; i++)
        {
            connectPersistingSessions();

            assertNotEquals(NO_CONNECTION_ID, followerSession.connectionId());
            assertEquals(acceptingSession.connectionId(), followerSession.connectionId());

            disconnectSessions();

            assertEquals(NO_CONNECTION_ID, followerSession.connectionId());
        }
    }

    private void connectPersistingSessions()
    {
        connectPersistingSessions(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, false);
    }

    private void resetSequenceNumbers()
    {
        testSystem.resetSequenceNumber(initiatingEngine, initiatingSession.id());
        testSystem.resetSequenceNumber(acceptingEngine, acceptingSession.id());
    }

    @SuppressWarnings("deprecation")
    private void resetSessions()
    {
        testSystem.awaitCompletedReplies(
            acceptingEngine.resetSessionIds(backupLocation),
            initiatingEngine.resetSessionIds(backupLocation));
    }

    private void launch(final Runnable beforeConnect)
    {
        mediaDriver = launchMediaDriver(mediaDriverContext(
            TestFixtures.TERM_BUFFER_LENGTH,
            dirsDeleteOnStart));

        final EngineConfiguration config = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        config.sessionPersistenceStrategy(alwaysPersistent());
        config.resendRequestController(fakeResendRequestController);
        if (!printErrorMessages)
        {
            config.monitoringAgentFactory(consumeDistinctErrors(errorCounter));
        }
        acceptingEngine = FixEngine.launch(config);
        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, nanoClock);
        if (!printErrorMessages)
        {
            config.monitoringAgentFactory(consumeDistinctErrors(errorCounter));
        }
        initiatingEngine = FixEngine.launch(initiatingConfig);

        // Use so that the SharedLibraryScheduler is integration tested
        final DynamicLibraryScheduler libraryScheduler = new DynamicLibraryScheduler();

        acceptingLibrary = connect(
            acceptingLibraryConfig(acceptingHandler, nanoClock)
                .scheduler(libraryScheduler)
                .resendRequestController(fakeResendRequestController));

        initiatingLibrary = connect(initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock)
            .scheduler(libraryScheduler));

        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        beforeConnect.run();
    }

    private void connectPersistingSessions(final int initialSequenceNumber, final boolean resetSeqNum)
    {
        this.connectPersistingSessions(initialSequenceNumber, initialSequenceNumber, resetSeqNum);
    }

    private void connectPersistingSessions(
        final int initiatorInitialSentSequenceNumber,
        final int initiatorInitialReceivedSequenceNumber,
        final boolean resetSeqNum)
    {
        final Reply<Session> reply = connectPersistentSessions(
            initiatorInitialSentSequenceNumber, initiatorInitialReceivedSequenceNumber, resetSeqNum);

        onInitiateReply.accept(reply);

        onAcquireSession.run();
    }

    private void exchangeMessagesAroundARestart(final int initialSequenceNumber, final int seqNumAfter)
    {
        exchangeMessagesAroundARestart(initialSequenceNumber, initialSequenceNumber, seqNumAfter, seqNumAfter);
    }

    private void exchangeMessagesAroundARestart(
        final int initialSentSequenceNumber,
        final int initialReceivedSequenceNumber,
        final int expectedInitToAccSeqNum,
        final int expectedAccToInitSeqNum)
    {
        exchangeMessagesAroundARestart(initialSentSequenceNumber,
            initialReceivedSequenceNumber,
            expectedInitToAccSeqNum,
            expectedAccToInitSeqNum,
            null);
    }

    private void exchangeMessagesAroundARestart(
        final int initialSentSequenceNumber,
        final int initialReceivedSequenceNumber,
        final int expectedInitToAccSeqNum,
        final int expectedAccToInitSeqNum,
        final Matcher<Integer> gapFillMatcher)
    {
        launch(this::nothing);
        connectPersistingSessions(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, resetSequenceNumbersOnLogon);
        assertLastLogonEquals(1, 0);
        assertSequenceResetTimeAtLatestLogon(initiatingSession);
        assertSequenceResetTimeAtLatestLogon(acceptingSession);

        assertSequenceIndicesAre(0);

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);
        assertSequenceFromInitToAcceptAt(2, 2);

        final long initiatedSessionId = initiatingSession.id();
        final long acceptingSessionId = acceptingSession.id();

        logoutInitiatingSession();
        assertSessionsDisconnected();

        assertInitiatingSequenceIndexIs(0);
        clearMessages();
        acceptingHandler.clearSessionExistsInfos();
        close();

        duringRestart.run();
        firstConnectTimeRange = connectTimeRange;

        launch(this.beforeReconnect);

        connectPersistingSessions(
            initialSentSequenceNumber,
            initialReceivedSequenceNumber,
            resetSequenceNumbersOnLogon);

        assertEquals("initiatedSessionId not stable over restarts", initiatedSessionId, initiatingSession.id());
        assertEquals("acceptingSessionId not stable over restarts", acceptingSessionId, acceptingSession.id());
        if (expectedInitToAccSeqNum != DOES_NOT_MATTER)
        {
            assertSequenceFromInitToAcceptAt(expectedInitToAccSeqNum, expectedAccToInitSeqNum);
        }

        // this means a resend request will be sent by the acceptor
        if (initialReceivedSequenceNumber != AUTOMATIC_INITIAL_SEQUENCE_NUMBER &&
            initialReceivedSequenceNumber < expectedAccToInitSeqNum)
        {
            assertReceivedResendRequest(testSystem, initiatingOtfAcceptor, expectedAccToInitSeqNum);
        }
        if (gapFillMatcher != null)
        {
            receivesGapfill(acceptingOtfAcceptor, gapFillMatcher);
        }

        assertTestRequestSentAndReceived(initiatingSession, testSystem, acceptingOtfAcceptor);
    }

    private void nothing()
    {
    }

    private long getAcceptingSessionId()
    {
        return acceptingHandler.awaitSessionId(testSystem::poll);
    }

    private void acquireSession(final long sessionId, final int lastReceivedMsgSeqNum, final int sequenceIndex)
    {
        acceptingSession = SystemTestUtil.acquireSession(
            acceptingHandler, acceptingLibrary, sessionId, testSystem, lastReceivedMsgSeqNum, sequenceIndex);
    }

    private void requestReplayWhenReacquiringSession()
    {
        final long sessionId = getAcceptingSessionId();

        if (acceptingSession != null)
        {
            final int lastReceivedMsgSeqNum = acceptingSession.lastReceivedMsgSeqNum();
            final int sequenceIndex = acceptingSession.sequenceIndex();
            acquireSession(sessionId, lastReceivedMsgSeqNum, sequenceIndex);

            final FixMessage firstReplayedMessage = acceptingOtfAcceptor.messages().get(0);
            assertThat(firstReplayedMessage, hasMessageSequenceNumber(lastReceivedMsgSeqNum));
            assertThat(firstReplayedMessage, hasSequenceIndex(sequenceIndex));
        }
        else
        {
            acquireSession(sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
        }
    }

    private void assertOnlyAcceptorSequenceReset()
    {
        // Only delected the acceptor logs so they have different sequence indices.

        assertAcceptingSessionHasSequenceIndex(0);
        acceptingOtfAcceptor.allMessagesHaveSequenceIndex(0);
        assertInitiatingSequenceIndexIs(1);
        initiatingOtfAcceptor.allMessagesHaveSequenceIndex(1);
    }

    private void assertReplyStatusWhenReplayRequested(final SessionReplyStatus replyStatus)
    {
        final long sessionId = getAcceptingSessionId();

        if (acceptingSession != null)
        {
            // Require replay of at least one message that has been sent
            final int lastReceivedMsgSeqNum = acceptingSession.lastReceivedMsgSeqNum() - 2;
            final int sequenceIndex = acceptingSession.sequenceIndex();
            final SessionReplyStatus reply = requestSession(
                acceptingLibrary, sessionId, lastReceivedMsgSeqNum, sequenceIndex, testSystem);
            assertEquals(replyStatus, reply);

            acceptingSession = acceptingHandler.lastSession();
            acceptingHandler.resetSession();
        }
        else
        {
            acquireSession(sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
        }
    }

    private void assertSequenceResetBeforeLastLogon(final Session session)
    {
        firstConnectTimeRange.assertWithinRange(session.lastSequenceResetTimeInNs());
        connectTimeRange.assertWithinRange(session.lastLogonTimeInNs());
        assertNotEquals(session.lastLogonTimeInNs(), session.lastSequenceResetTimeInNs());
    }

    private void receiveReplayFromOfflineSession(final long sessionId)
    {
        assertOfflineSession(sessionId, acceptingSession);

        final ReportFactory factory = new ReportFactory();
        factory.sendReport(testSystem, acceptingSession, Side.BUY);
        resendMsgSeqNums.add(factory.lastMsgSeqNum());

        factory.possDupFlag(PossDupOption.YES);
        factory.sendReport(testSystem, acceptingSession, Side.BUY);
        resendMsgSeqNums.add(factory.lastMsgSeqNum());

        factory.possDupFlag(PossDupOption.NO);
        factory.sendReport(testSystem, acceptingSession, Side.BUY);
        resendMsgSeqNums.add(factory.lastMsgSeqNum());

        receivedReplayFromReconnectedSession();
    }

    private void assertReceivedReplayedReport(final int msgSeqNum)
    {
        final FixMessage executionReport = testSystem.awaitMessageOf(
            initiatingOtfAcceptor, EXECUTION_REPORT_MESSAGE_AS_STR, msg -> msg.messageSequenceNumber() == msgSeqNum);
        assertEquals(ReportFactory.MSFT, executionReport.get(SYMBOL));
        assertEquals("Y", executionReport.possDup());
        assertEquals(executionReport + " has incorrect status", MessageStatus.OK, executionReport.status());
        assertTrue(executionReport + " was not valid", executionReport.isValid());

        final LocalDateTime sendingTime = LocalDateTime.parse(executionReport.get(SENDING_TIME), FORMATTER);
        final LocalDateTime origSendingTime = LocalDateTime.parse(executionReport.get(ORIG_SENDING_TIME), FORMATTER);
        assertThat(origSendingTime, Matchers.lessThan(sendingTime));
    }

    private void receivedReplayFromReconnectedSession()
    {
        connectPersistingSessionsWithoutAcquiring();

        for (final int resendMsgSeqNum : resendMsgSeqNums)
        {
            assertReceivedReplayedReport(resendMsgSeqNum);
        }
    }

    private void connectPersistingSessionsWithoutAcquiring()
    {
        onAcquireSession = this::nothing;
        connectPersistingSessions();
    }

    void sendReportsOnFollowerSession(
        final TestSystem testSystem,
        final SessionWriter sessionWriter,
        final ReadablePosition positionCounter)
    {
        sendReportOnFollowerSession(testSystem, sessionWriter, 1, PossDupOption.MISSING_FIELD);
        sendReportOnFollowerSession(testSystem, sessionWriter, 2, PossDupOption.YES);
        sendReportOnFollowerSession(testSystem, sessionWriter, 3, PossDupOption.NO);
        sendReportOnFollowerSession(
            testSystem, sessionWriter, 4, PossDupOption.NO_WITHOUT_ORIG_SENDING_TIME);
        final long position = sendReportOnFollowerSession(
            testSystem, sessionWriter, 5, PossDupOption.YES_WITHOUT_ORIG_SENDING_TIME);

        resendMsgSeqNums.addAll(Arrays.asList(1, 2, 3, 4, 5));

        testSystem.awaitPosition(positionCounter, position);
    }

    private long sendReportOnFollowerSession(
        final TestSystem testSystem, final SessionWriter sessionWriter, final int msgSeqNum,
        final PossDupOption possDupFlag)
    {
        final ReportFactory reportFactory = new ReportFactory();
        reportFactory.possDupFlag(possDupFlag);
        final ExecutionReportEncoder report = reportFactory.setupReport(Side.BUY, msgSeqNum);

        final HeaderEncoder header = report.header();
        setupHeader(msgSeqNum, header);

        return testSystem.awaitSend("failed to send", () -> sessionWriter.send(report, msgSeqNum));
    }

    private void setupHeader(final int msgSeqNum, final SessionHeaderEncoder header)
    {
        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        final long timeInThePast = System.currentTimeMillis() - 100;
        header.senderCompID(ACCEPTOR_ID).targetCompID(INITIATOR_ID)
            .sendingTime(timestampEncoder.buffer(), timestampEncoder.encode(timeInThePast))
            .msgSeqNum(msgSeqNum);
    }
}
