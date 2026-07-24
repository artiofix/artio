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

import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import org.agrona.CloseHelper;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;


import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.Reply.State;
import uk.co.real_logic.artio.builder.HeaderEncoder;
import uk.co.real_logic.artio.builder.ResendRequestEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.engine.logger.FixMessageConsumer;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.library.TestHelper;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.InternalSession;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.Constants.*;
import static uk.co.real_logic.artio.FixMatchers.*;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.logger.FixMessageConsumerValidator.validateFixMessageConsumer;
import static uk.co.real_logic.artio.messages.MessageStatus.CATCHUP_REPLAY;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class AbstractGatewayToGatewaySystemTest
{
    public static final long TEST_TIMEOUT_IN_MS = 20_000L;
    public static final long LONG_TEST_TIMEOUT_IN_MS = 50_000L;

    static final int META_DATA_VALUE = 123;
    static final int META_DATA_WRONG_VALUE = 124;
    static final long META_DATA_SESSION_ID = 1L;
    static final long META_DATA_WRONG_SESSION_ID = 2L;

    protected int port = unusedPort();
    protected int libraryAeronPort = unusedPort();
    protected ArchivingMediaDriver mediaDriver;
    protected TestSystem testSystem;

    final FakeResendRequestController fakeResendRequestController = new FakeResendRequestController();
    final EpochNanoClock nanoClock = new OffsetEpochNanoClock();

    FixEngine acceptingEngine;
    FixEngine initiatingEngine;
    FixLibrary acceptingLibrary;
    FixLibrary initiatingLibrary;
    Session initiatingSession;
    Session acceptingSession;

    FakeOtfAcceptor acceptingOtfAcceptor = new FakeOtfAcceptor();
    FakeHandler acceptingHandler = new FakeHandler(acceptingOtfAcceptor);

    FakeOtfAcceptor initiatingOtfAcceptor = new FakeOtfAcceptor();
    FakeHandler initiatingHandler = new FakeHandler(initiatingOtfAcceptor);

    TimeRange connectTimeRange;

    AgentRunner logger;

    CapturingAuthenticationStrategy auth;
    final MessageTimingCaptor messageTimingHandler = new MessageTimingCaptor();

    void launchGatewayToGateway()
    {
        final MediaDriver.Context context = mediaDriverContext(TERM_BUFFER_LENGTH, true);
        mediaDriver = launchMediaDriver(context);

//        logger = FixMessageLogger.start();

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .deleteLogFileDirOnStart(true);
        auth = new CapturingAuthenticationStrategy(acceptingConfig.messageValidationStrategy());
        acceptingConfig.authenticationStrategy(auth);
        acceptingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        acceptingConfig.messageTimingHandler(messageTimingHandler);
        acceptingEngine = FixEngine.launch(acceptingConfig);

        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, nanoClock);
        initiatingConfig.deleteLogFileDirOnStart(true);
        initiatingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        initiatingEngine = FixEngine.launch(initiatingConfig);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibrary = connect(acceptingLibraryConfig);

        final LibraryConfiguration initiatingLibraryConfig = initiatingLibraryConfig(
            libraryAeronPort, initiatingHandler, nanoClock);
        initiatingLibraryConfig.resendRequestController(fakeResendRequestController);
        initiatingLibrary = connect(initiatingLibraryConfig);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        connectSessions();
    }

    @AfterEach
    public void close()
    {
        TestHelper.clearPollStatus(initiatingLibrary);
        Exceptions.closeAll(
            this::closeInitiatingEngine,
            this::closeAcceptingEngine,
            initiatingLibrary,
            this::closeAcceptingLibrary,
            logger,
            () -> cleanupMediaDriver(mediaDriver));
    }

    void closeAcceptingLibrary()
    {
        TestHelper.clearPollStatus(acceptingLibrary);
        CloseHelper.close(acceptingLibrary);
        if (testSystem != null)
        {
            testSystem.remove(acceptingLibrary);
        }
    }

    void closeInitiatingEngine()
    {
        closeEngine(initiatingEngine);
    }

    void closeAcceptingEngine()
    {
        closeEngine(acceptingEngine);
    }

    private void closeEngine(final FixEngine engine)
    {
        if (testSystem != null)
        {
            testSystem.awaitLongBlocking(() -> CloseHelper.close(engine));
        }
        else
        {
            CloseHelper.close(engine);
        }
    }

    void assertOriginalLibraryDoesNotReceiveMessages(final int initiatorMessageCount)
    {
        initiatingLibrary.poll(LIBRARY_LIMIT);
        assertThat("Messages received by wrong initiator",
            initiatingOtfAcceptor.messages(), hasSize(initiatorMessageCount));
    }

    void assertSequenceFromInitToAcceptAt(final int expectedInitToAccSeqNum, final int expectedAccToInitSeqNum)
    {
        assertEquals(expectedInitToAccSeqNum, initiatingSession.lastSentMsgSeqNum());
        assertEquals(expectedInitToAccSeqNum, acceptingSession.lastReceivedMsgSeqNum());

        awaitMessage(expectedAccToInitSeqNum, initiatingSession);

        assertEquals(expectedAccToInitSeqNum, initiatingSession.lastReceivedMsgSeqNum());
        assertEquals(expectedAccToInitSeqNum, acceptingSession.lastSentMsgSeqNum());
    }

    void awaitMessage(final int sequenceNumber, final Session session)
    {
        testSystem.await(
            "Library Never reaches " + sequenceNumber,
            () -> session.lastReceivedMsgSeqNum() >= sequenceNumber);
    }

    void disconnectSessions()
    {
        logoutAcceptingSession();

        assertSessionsDisconnected();
    }

    long logoutAcceptingSession()
    {
        return logoutSession(testSystem, acceptingSession);
    }

    void logoutInitiatingSession()
    {
        logoutSession(testSystem, initiatingSession);
    }

    void assertSessionsDisconnected()
    {
        assertSessionDisconnected(initiatingSession);
        assertSessionDisconnected(acceptingSession);

        assertEventuallyTrue("libraries receive disconnect messages",
            () ->
            {
                testSystem.poll();
                assertNotSession(acceptingHandler, acceptingSession);
                assertNotSession(initiatingHandler, initiatingSession);
            });
    }

    void sessionNoLongerManaged(final FakeHandler handler, final Session session)
    {
        assertEventuallyTrue("libraries receive disconnect messages",
            () ->
            {
                testSystem.poll();
                assertNotSession(handler, session);
            });
    }

    protected void assertSessionDisconnected(final Session session)
    {
        SystemTestUtil.assertSessionDisconnected(testSystem, session);
    }

    void assertNotSession(final FakeHandler sessionHandler, final Session session)
    {
        assertThat(sessionHandler.sessions(), not(hasItem(session)));
    }

    void wireSessions()
    {
        connectSessions();
        acquireAcceptingSession();
    }

    void acquireAcceptingSession()
    {
        acquireAcceptingSession(INITIATOR_ID);
    }

    void acquireAcceptingSession(final String initiatorId)
    {
        final long sessionId = acceptingHandler.awaitSessionId(testSystem::poll);

        acceptingSession = acquireSession(acceptingHandler, acceptingLibrary, sessionId, testSystem);
        assertEquals(initiatorId, acceptingHandler.lastInitiatorCompId());
        assertEquals(ACCEPTOR_ID, acceptingHandler.lastAcceptorCompId());
        assertNotNull(acceptingSession, "unable to acquire accepting session");
    }

    void connectSessions()
    {
        connectSessions(null);
    }

    void connectSessions(final Class<? extends FixDictionary> fixDictionary)
    {
        connectTimeRange = new TimeRange(nanoClock);
        final Reply<Session> reply = initiate(
            initiatingLibrary, port, INITIATOR_ID, ACCEPTOR_ID, TEST_REPLY_TIMEOUT_IN_MS, true, fixDictionary);
        completeConnectInitiatingSession(reply);
        connectTimeRange.end();
    }

    void completeConnectInitiatingSession(final Reply<Session> reply)
    {
        initiatingSession = completeConnectSessions(reply);
    }

    Session completeConnectSessions(final Reply<Session> reply)
    {
        final Session session = testSystem.awaitCompletedReply(reply).resultIfPresent();
        assertConnected(session);
        return session;
    }

    void completeFailedSession(final Reply<Session> reply)
    {
        testSystem.awaitReply(reply);
        assertEquals(State.ERRORED, reply.state(), reply.toString());
    }

    FixMessage assertMessageResent(final int sequenceNumber, final String msgType, final boolean isGapFill)
    {
        assertThat(acceptingOtfAcceptor.messages(), hasSize(0));
        assertEventuallyTrue("Failed to receive the reply",
            () ->
            {
                testSystem.poll();

                final FixMessage message = acceptingOtfAcceptor.lastReceivedMessage();
                assertEquals(msgType, message.msgType());
                if (isGapFill)
                {
                    assertEquals("Y", message.get(GAP_FILL_FLAG));
                }
                else
                {
                    assertNotNull(message.get(ORIG_SENDING_TIME));
                }
                assertEquals("Y", message.possDup());
                assertEquals(String.valueOf(sequenceNumber), message.get(MSG_SEQ_NUM));
                assertEquals(INITIATOR_ID, message.get(Constants.SENDER_COMP_ID));
                assertNull(acceptingOtfAcceptor.lastError(), "Detected Error");
                assertTrue(acceptingOtfAcceptor.isCompleted(), "Failed to complete parsing");
            });

        return acceptingOtfAcceptor.lastReceivedMessage();
    }

    int acceptorSendsResendRequest()
    {
        final int seqNum = acceptingSession.lastReceivedMsgSeqNum();
        return acceptorSendsResendRequest(seqNum);
    }

    int acceptorSendsResendRequest(final int seqNum)
    {
        acceptorSendsResendRequest(seqNum, seqNum);

        return seqNum;
    }

    void acceptorSendsResendRequest(final int beginSeqNo, final int endSeqNo)
    {
        sendResendRequest(beginSeqNo, endSeqNo, acceptingOtfAcceptor, acceptingSession);
    }

    void sendResendRequest(
        final int beginSeqNo, final int endSeqNo, final FakeOtfAcceptor otfAcceptor, final Session session)
    {
        final ResendRequestEncoder resendRequest = new ResendRequestEncoder()
            .beginSeqNo(beginSeqNo)
            .endSeqNo(endSeqNo);

        otfAcceptor.messages().clear();

        while (session.trySend(resendRequest) < 0)
        {
            Thread.yield();
            testSystem.poll();
        }
    }

    void messagesCanBeExchanged()
    {
        messagesCanBeExchanged(initiatingSession);
    }

    void messagesCanBeExchanged(final Session session)
    {
        messagesCanBeExchanged(session, initiatingOtfAcceptor);
    }

    void acceptingMessagesCanBeExchanged()
    {
        messagesCanBeExchanged(acceptingSession, acceptingOtfAcceptor);
    }

    long messagesCanBeExchanged(final Session sendingSession, final FakeOtfAcceptor receivingAcceptor)
    {
        final String testReqID = testReqId();
        return messagesCanBeExchanged(sendingSession, receivingAcceptor, testReqID);
    }

    long messagesCanBeExchanged(
        final Session sendingSession, final FakeOtfAcceptor receivingAcceptor, final String testReqID)
    {
        final long position = sendTestRequest(testSystem, sendingSession, testReqID);

        assertReceivedSingleHeartbeat(testSystem, receivingAcceptor, testReqID);

        return position;
    }

    void clearMessages()
    {
        initiatingOtfAcceptor.messages().clear();
        acceptingOtfAcceptor.messages().clear();
    }

    void launchAcceptingEngine()
    {
        acceptingEngine = FixEngine.launch(
            acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock));
    }

    void assertSequenceIndicesAre(final int sequenceIndex)
    {
        assertAcceptingSessionHasSequenceIndex(sequenceIndex);
        assertInitiatingSequenceIndexIs(sequenceIndex);
        assertAllMessagesHaveSequenceIndex(sequenceIndex);
    }

    void assertAcceptingSessionHasSequenceIndex(final int sequenceIndex)
    {
        if (acceptingSession != null)
        {
            assertThat(acceptingSession, hasSequenceIndex(sequenceIndex));
        }

        assertEngineSequenceIndexIs(acceptingEngine, sequenceIndex);
    }

    void assertInitiatingSequenceIndexIs(final int sequenceIndex)
    {
        assertThat(initiatingSession, hasSequenceIndex(sequenceIndex));

        assertEngineSequenceIndexIs(initiatingEngine, sequenceIndex);
    }

    private void assertEngineSequenceIndexIs(final FixEngine engine, final int sequenceIndex)
    {
        for (final SessionInfo sessionInfo : engine.allSessions())
        {
            assertEquals(sequenceIndex, sessionInfo.sequenceIndex(), sessionInfo.toString());
        }
    }

    void assertAllMessagesHaveSequenceIndex(final int sequenceIndex)
    {
        acceptingOtfAcceptor.allMessagesHaveSequenceIndex(sequenceIndex);
        initiatingOtfAcceptor.allMessagesHaveSequenceIndex(sequenceIndex);
    }

    void sessionsCanReconnect()
    {
        acquireAcceptingSession();

        logoutSession(acceptingSession);
        assertSessionsDisconnected();

        assertAllMessagesHaveSequenceIndex(0);
        clearMessages();

        wireSessions();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(1);
    }

    void releaseSessionToEngine(final Session session, final FixLibrary library, final FixEngine engine)
    {
        final long connectionId = session.connectionId();
        final long sessionId = session.id();

        final SessionReplyStatus status = releaseToEngine(library, session, testSystem);

        assertEquals(OK, status);
        assertEquals(SessionState.DISABLED, session.state());
        assertThat(library.sessions(), hasSize(0));

        final List<ConnectedSessionInfo> sessions = gatewayLibraryInfo(engine).sessions();
        assertThat(sessions, contains(allOf(
            hasConnectionId(connectionId),
            hasSessionId(sessionId))));
    }

    void assertCountersClosed(final boolean expectedClosed, final Session session)
    {
        assertEquals(expectedClosed, ((InternalSession)session).areCountersClosed());
    }

    void engineShouldManageSession(
        final Session session,
        final FixLibrary library,
        final FakeOtfAcceptor otfAcceptor,
        final Session otherSession,
        final FakeOtfAcceptor otherAcceptor)
    {
        final int lastReceivedMsgSeqNum = engineShouldManageSession(session, library, otherSession, otherAcceptor, OK);

        // Callbacks for the missing messages whilst the gateway managed them
        final List<FixMessage> messages = otfAcceptor.messages();
        final String expectedSeqNum = String.valueOf(lastReceivedMsgSeqNum + 1);
        final long messageCount = messages
            .stream()
            .filter((m) -> m.msgType().equals(TEST_REQUEST_MESSAGE_AS_STR) &&
            m.get(MSG_SEQ_NUM).equals(expectedSeqNum))
            .count();

        assertEquals(1, messageCount, "Expected a single test request" + messages.toString());

        messagesCanBeExchanged(otherSession, otherAcceptor);
    }

    int engineShouldManageSession(
        final Session session,
        final FixLibrary library,
        final Session otherSession,
        final FakeOtfAcceptor otherAcceptor,
        final SessionReplyStatus expectedStatus)
    {
        final long sessionId = session.id();
        final int lastReceivedMsgSeqNum = session.lastReceivedMsgSeqNum();
        final int sequenceIndex = session.sequenceIndex();

        releaseToEngine(library, session, testSystem);

        messagesCanBeExchanged(otherSession, otherAcceptor);

        final SessionReplyStatus status = requestSession(
            library, sessionId, lastReceivedMsgSeqNum, sequenceIndex, testSystem);
        assertEquals(expectedStatus, status);

        final List<Session> sessions = library.sessions();
        assertThat(sessions, hasSize(1));

        return lastReceivedMsgSeqNum;
    }

    Reply<Session> connectPersistentSessions(
        final int initiatorInitialSentSequenceNumber,
        final int initiatorInitialReceivedSequenceNumber,
        final boolean resetSeqNum)
    {
        final SessionConfiguration config = SessionConfiguration.builder()
            .address("localhost", port)
            .credentials("bob", "Uv1aegoh")
            .senderCompId(INITIATOR_ID)
            .targetCompId(ACCEPTOR_ID)
            .sequenceNumbersPersistent(true)
            .initialReceivedSequenceNumber(initiatorInitialReceivedSequenceNumber)
            .initialSentSequenceNumber(initiatorInitialSentSequenceNumber)
            .resetSeqNum(resetSeqNum)
            .build();

        connectTimeRange = new TimeRange(nanoClock);
        final Reply<Session> reply = initiatingLibrary.initiate(config);
        testSystem.awaitReply(reply);
        connectTimeRange.end();
        return reply;
    }

    void deleteAcceptorLogs()
    {
        delete(ACCEPTOR_LOGS);
    }

    void deleteClientLogs()
    {
        delete(CLIENT_LOGS);
    }

    void deleteLogs()
    {
        deleteAcceptorLogs();
        deleteClientLogs();
    }

    void launchMediaDriverWithDirs()
    {
        mediaDriver = TestFixtures.launchMediaDriverWithDirs();
    }

    void assertLastLogonEquals(final int lastLogonReceivedSequenceNumber, final int lastLogonSequenceIndex)
    {
        assertEquals(lastLogonReceivedSequenceNumber, acceptingHandler.lastLogonReceivedSequenceNumber());
        assertEquals(lastLogonSequenceIndex, acceptingHandler.lastLogonSequenceIndex());
    }

    void assertSequenceResetTimeAtLatestLogon(final Session session)
    {
        final long lastLogonTime = session.lastLogonTimeInNs();
        final long lastSequenceResetTime = session.lastSequenceResetTimeInNs();
        connectTimeRange.assertWithinRange(lastLogonTime);
        assertEquals(lastLogonTime, lastSequenceResetTime, "lastSequenceResetTime was not the same as lastLogonTime");
    }

    List<String> getMessagesFromArchive(final EngineConfiguration configuration, final int queryStreamId)
    {
        final IntHashSet queryStreamIds = new IntHashSet();
        queryStreamIds.add(queryStreamId);
        return getMessagesFromArchive(configuration, queryStreamIds);
    }

    List<String> getMessagesFromArchive(final EngineConfiguration configuration, final IntHashSet queryStreamIds)
    {
        return getFromArchive(configuration, queryStreamIds)
            .stream().map(ArchiveEntry::body).collect(Collectors.toList());
    }

    static class ArchiveEntry
    {
        private final MessageStatus status;
        private final String body;

        ArchiveEntry(final MessageStatus status, final String body)
        {
            this.status = status;
            this.body = body;
        }

        public String body()
        {
            return body;
        }

        public MessageStatus status()
        {
            return status;
        }

        @Override
        public String toString()
        {
            return "ArchiveEntry{" +
                "status=" + status +
                ", body='" + body + '\'' +
                '}';
        }
    }

    List<ArchiveEntry> getFromArchive(
        final EngineConfiguration configuration,
        final IntHashSet queryStreamIds)
    {
        final List<ArchiveEntry> messages = new ArrayList<>();
        final FixMessageConsumer fixMessageConsumer =
            (message, buffer, offset, length, header) ->
            messages.add(new ArchiveEntry(
            message.status(),
            validateFixMessageConsumer(message, buffer, offset, length)));

        SystemTestUtil.getMessagesFromArchive(configuration, queryStreamIds, fixMessageConsumer, null, false);

        return messages;
    }

    void writeMetaData()
    {
        final UnsafeBuffer writeBuffer = new UnsafeBuffer(new byte[SIZE_OF_INT]);
        writeBuffer.putInt(0, META_DATA_VALUE);
        writeMetaData(writeBuffer);
    }

    void writeMetaData(final UnsafeBuffer writeBuffer)
    {
        final Reply<MetaDataStatus> reply = writeMetaData(writeBuffer, META_DATA_SESSION_ID);
        assertEquals(MetaDataStatus.OK, reply.resultIfPresent());
    }

    Reply<MetaDataStatus> writeMetaData(final UnsafeBuffer writeBuffer, final long sessionId)
    {
        return writeMetaData(writeBuffer, sessionId, 0);
    }

    Reply<MetaDataStatus> writeMetaData(
        final UnsafeBuffer writeBuffer, final long sessionId, final int metaDataOffset)
    {
        final Reply<MetaDataStatus> reply = acceptingLibrary.writeMetaData(
            sessionId, metaDataOffset, writeBuffer, 0, writeBuffer.capacity());

        testSystem.awaitCompletedReplies(reply);

        return reply;
    }

    UnsafeBuffer readSuccessfulMetaData(final UnsafeBuffer writeBuffer)
    {
        final FakeMetadataHandler handler = readMetaData(META_DATA_SESSION_ID);
        assertEquals(MetaDataStatus.OK, handler.status());

        final UnsafeBuffer readBuffer = handler.buffer();
        assertEquals(writeBuffer.capacity(), readBuffer.capacity());
        return readBuffer;
    }

    FakeMetadataHandler readMetaData(final long sessionId)
    {
        final FakeMetadataHandler handler = new FakeMetadataHandler();

        acceptingLibrary.readMetaData(sessionId, handler);

        Timing.assertEventuallyTrue("reading session meta data failed to terminate", () ->
        {
            testSystem.poll();

            return handler.callbackReceived();
        });

        return handler;
    }

    void assertOfflineSession(final long sessionId, final Session session)
    {
        assertEquals(sessionId, session.id());
        assertEquals("", session.connectedHost());
        assertEquals(Session.UNKNOWN, session.connectedPort());
        assertEquals(NO_CONNECTION_ID, session.connectionId());
        assertEquals(SessionState.DISCONNECTED, session.state());
    }

    Reply<?> resetSequenceNumber(final long sessionId)
    {
        return testSystem.awaitReply(testSystem.startResetSequenceNumber(acceptingEngine, sessionId));
    }

    void assertInitSeqNum(
        final int lastReceivedMsgSeqNum, final int lastSentMsgSeqNum, final int sequenceIndex)
    {
        assertSeqNum(lastReceivedMsgSeqNum, lastSentMsgSeqNum, sequenceIndex, initiatingSession);
    }

    void assertAccSeqNum(
        final int lastReceivedMsgSeqNum, final int lastSentMsgSeqNum, final int sequenceIndex)
    {
        assertSeqNum(lastReceivedMsgSeqNum, lastSentMsgSeqNum, sequenceIndex, acceptingSession);
    }

    void assertSeqNum(
        final int lastReceivedMsgSeqNum, final int lastSentMsgSeqNum, final int sequenceIndex, final Session session)
    {
        assertEquals(lastReceivedMsgSeqNum, session.lastReceivedMsgSeqNum(), "incorrect lastReceivedMsgSeqNum");
        assertEquals(lastSentMsgSeqNum, session.lastSentMsgSeqNum(), "incorrect lastSentMsgSeqNum");
        assertEquals(sequenceIndex, session.sequenceIndex(), "incorrect sequenceIndex");
    }

    void acceptingEngineHasSessionAndLibraryIsNotified()
    {
        final LibraryDriver driver = LibraryDriver.accepting(testSystem, nanoClock);
        engineHasSessionAndLibraryIsNotified(driver, acceptingEngine, acceptingSession);
    }

    void initiatingEngineHasSessionAndLibraryIsNotified()
    {
        engineHasSessionAndLibraryIsNotified(
            LibraryDriver.initiating(libraryAeronPort, testSystem, nanoClock), initiatingEngine, initiatingSession);
    }

    void engineHasSessionAndLibraryIsNotified(
        final LibraryDriver libraryDriver, final FixEngine engine, final Session session)
    {
        try (LibraryDriver library2 = libraryDriver)
        {
            library2.becomeOnlyLibraryConnectedTo(engine);

            final LibraryInfo engineLibraryInfo = engineLibrary(libraries(engine));

            assertEquals(ENGINE_LIBRARY_ID, engineLibraryInfo.libraryId());
            assertThat(engineLibraryInfo.sessions(), contains(hasConnectionId(session.connectionId())));

            final SessionExistsInfo sessionId = library2.awaitCompleteSessionId();
            assertSameSession(sessionId, session);
        }
    }

    void assertSameSession(final SessionExistsInfo sessionId, final Session session)
    {
        final CompositeKey compositeKey = session.compositeKey();

        assertEquals(sessionId.surrogateId(), session.id());
        assertEquals(compositeKey.localCompId(), sessionId.localCompId());
        assertEquals(compositeKey.remoteCompId(), sessionId.remoteCompId());
    }

    SessionWriter createFollowerSession(final long timeoutInMs)
    {
        return createFollowerSession(timeoutInMs, testSystem, acceptingLibrary, INITIATOR_ID, ACCEPTOR_ID);
    }

    static SessionWriter createFollowerSession(
        final long timeoutInMs, final TestSystem testSystem, final FixLibrary library, final String sender,
        final String target)
    {
        final HeaderEncoder headerEncoder = new HeaderEncoder()
            .senderCompID(sender)
            .targetCompID(target);

        final Reply<SessionWriter> followerSession = testSystem.awaitCompletedReply(
            library.followerSession(headerEncoder, timeoutInMs));
        return followerSession.resultIfPresent();
    }

    void assertAllSessionsOnlyContains(final FixEngine engine, final Session session)
    {
        final List<SessionInfo> allSessions = engine.allSessions();
        assertThat(allSessions, hasSize(1));

        final SessionInfo sessionInfo = allSessions.get(0);
        assertThat(sessionInfo.sessionId(), is(session.id()));
        assertThat(sessionInfo.sessionKey(), is(session.compositeKey()));
    }

    void assertReplayReceivedMessages()
    {
        final Reply<ReplayMessagesStatus> reply = acceptingSession.replayReceivedMessages(
            1, 0, 2, 0, 5_000L);
        testSystem.awaitCompletedReplies(reply);
        assertEquals(ReplayMessagesStatus.OK, reply.resultIfPresent());

        final FixMessage testRequest = acceptingOtfAcceptor
            .receivedMessage(TEST_REQUEST_MESSAGE_AS_STR)
            .findFirst()
            .get();
        assertEquals(CATCHUP_REPLAY, testRequest.status());
    }

    void sleep(final int timeInMs)
    {
        testSystem.awaitBlocking(() ->
        {
            try
            {
                Thread.sleep(timeInMs);
            }
            catch (final InterruptedException e)
            {
                e.printStackTrace();
            }
        });
    }

    void assertResendsCompleted(final int count, final Matcher<Iterable<Integer>> items)
    {
        testSystem.await("ResendRequestController not notified ",
            () -> fakeResendRequestController.completeCount() == count);
        assertThat(fakeResendRequestController.seenReplaysInFlight(), items);
    }

}
