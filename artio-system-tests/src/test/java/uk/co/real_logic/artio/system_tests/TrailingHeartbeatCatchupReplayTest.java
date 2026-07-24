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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import uk.co.real_logic.artio.MonitoringAgentFactory;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.messages.ReplayMessagesStatus;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.session.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

/**
 * Verifies that catchup replay succeeds when the replay range ends with
 * trailing heartbeat messages (35=0) and no subsequent non-heartbeat message.
 */
public class TrailingHeartbeatCatchupReplayTest extends AbstractGatewayToGatewaySystemTest
{
    private static final int TERM_BUFFER_LENGTH = 64 * 1024;

    @Test
    @Timeout(30)
    public void shouldNotReportMissingMessagesForTrailingHeartbeats()
    {
        final int lastBusinessMsgSeqNum = prepareTrailingHeartbeatRange();
        final long acceptingSessionId = acceptingSession.id();

        // Disconnect without sending Logout to ensure the last inbound
        // messages on the accepting side are heartbeats (not a Logout).
        initiatingSession.requestDisconnect();
        assertSessionsDisconnected();
        closeEnginesAndLibraries();

        // Relaunch engines
        launchEngines();

        // Request session with replay starting from the last business message.
        // The replay range will include the business message + trailing heartbeats.
        // replayFromSequenceNumber = lastBusinessMsgSeqNum (the last business message before heartbeats)
        // replayToSequenceNumber = lastReceivedMsgSeqNum (includes trailing heartbeats, from persisted index)
        final Reply<SessionReplyStatus> reply = testSystem.awaitReply(acceptingLibrary.requestSession(
            acceptingSessionId, lastBusinessMsgSeqNum, FixLibrary.CURRENT_SEQUENCE, 10_000));

        assertEquals(OK, reply.resultIfPresent(),
            "Replay of trailing heartbeats should succeed");

        acceptingSession = acceptingHandler.lastSession();
        assertNotNull(acceptingSession, "Should have acquired the accepting session");

        closeEnginesAndLibraries();
    }

    @Test
    @Timeout(30)
    public void shouldReportMissingMessagesAfterTrailingHeartbeatRange()
    {
        final int lastBusinessMsgSeqNum = prepareTrailingHeartbeatRange();
        final int sequenceIndex = acceptingSession.sequenceIndex();
        final int lastHeartbeatSequenceNumber = acceptingSession.lastReceivedMsgSeqNum();
        final int requestedReplayToSequenceNumber = lastHeartbeatSequenceNumber + 5;

        final Reply<ReplayMessagesStatus> reply = testSystem.awaitReply(acceptingSession.replayReceivedMessages(
            lastBusinessMsgSeqNum,
            sequenceIndex,
            requestedReplayToSequenceNumber,
            sequenceIndex,
            10_000));

        assertEquals(ReplayMessagesStatus.MISSING_MESSAGES, reply.resultIfPresent(),
            "Messages after the trailing heartbeat range should be reported as missing");

        closeEnginesAndLibraries();
    }

    private int prepareTrailingHeartbeatRange()
    {
        launch();

        initiateSession();
        acceptSession();

        // Exchange a business message (TestRequest from initiator → acceptor).
        // This gives us a non-heartbeat inbound message on the accepting side (seqNum=2,
        // after the Logon at seqNum=1).
        messagesCanBeExchanged();

        final int lastBusinessMsgSeqNum = acceptingSession.lastReceivedMsgSeqNum();

        // Generate trailing heartbeats as inbound messages on the accepting side:
        // Send TestRequests (35=1) FROM acceptor TO initiator. Per FIX protocol, the
        // initiator responds with Heartbeats (35=0) echoing the TestReqID. These heartbeats
        // are inbound messages for the accepting engine, recorded in its replay-index.
        final int expectedLastSeqNum = lastBusinessMsgSeqNum + 3;
        for (int i = 0; i < 3; i++)
        {
            final String testReqID = testReqId();
            sendTestRequest(testSystem, acceptingSession, testReqID);
        }

        assertEventuallyTrue("Heartbeat responses not received",
            () ->
            {
                testSystem.poll();
                return acceptingSession.lastReceivedMsgSeqNum() >= expectedLastSeqNum;
            });

        assertEquals(expectedLastSeqNum, acceptingSession.lastReceivedMsgSeqNum(),
            "Expected trailing heartbeats to be recorded");

        return lastBusinessMsgSeqNum;
    }

    private void launch()
    {
        deleteLogs();
        mediaDriver = launchMediaDriver(TERM_BUFFER_LENGTH);
        testSystem = new TestSystem();
        launchEngines();
    }

    private void launchEngines()
    {
        launchAcceptingEngineAndLibrary();
        launchInitiatingEngineAndLibrary();
    }

    private void launchInitiatingEngineAndLibrary()
    {
        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, nanoClock);
        initiatingConfig.initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY);
        initiatingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        initiatingEngine = FixEngine.launch(initiatingConfig);
        initiatingLibrary = testSystem.connect(initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock));
    }

    private void launchAcceptingEngineAndLibrary()
    {
        final EngineConfiguration config = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        config.sessionPersistenceStrategy(alwaysPersistent());
        config.monitoringAgentFactory(MonitoringAgentFactory.none());
        acceptingEngine = FixEngine.launch(config);
        acceptingLibrary = testSystem.connect(acceptingLibraryConfig(acceptingHandler, nanoClock));
    }

    private void initiateSession()
    {
        final Reply<Session> reply = connectPersistentSessions(1, 1, false);
        assertEquals(Reply.State.COMPLETED, reply.state(), "Reply failed: " + reply);
        initiatingSession = reply.resultIfPresent();
        assertConnected(initiatingSession);
    }

    private void acceptSession()
    {
        final long sessionId = acceptingHandler.awaitSessionId(testSystem::poll);
        acceptingSession = SystemTestUtil.acquireSession(
            acceptingHandler, acceptingLibrary, sessionId, testSystem, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
    }

    private void closeEnginesAndLibraries()
    {
        Exceptions.closeAll(
            this::closeInitiatingEngine,
            this::closeAcceptingEngine,
            initiatingLibrary,
            this::closeAcceptingLibrary);
        testSystem.remove(initiatingLibrary);
    }
}
