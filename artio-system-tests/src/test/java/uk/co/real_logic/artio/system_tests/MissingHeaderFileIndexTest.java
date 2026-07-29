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
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.session.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

public class MissingHeaderFileIndexTest extends AbstractGatewayToGatewaySystemTest
{
    public static final int TERM_BUFFER_LENGTH = 64 * 1024;

    // Test reproduces an issue where a missing header file can cause the archiver thread to go into a loop.
    // Caused by a request session being driven by

    @Test
    @Timeout(50_000)
    public void shouldRestartAfterResetAndSomeSessionsNotReconnected() throws InterruptedException
    {
        launch();

        initiateSession();
        acceptSession();

        messagesCanBeExchanged();

        // Complete a second connection
        final Reply<Session> successfulReply = initiate(initiatingLibrary, port, INITIATOR_ID2, ACCEPTOR_ID);
        final Session initiatingSession2 = completeConnectSessions(successfulReply);

        messagesCanBeExchanged(initiatingSession2);

        final int seqNum1 = initiatingSession.lastReceivedMsgSeqNum();
        final long id1 = initiatingSession.id();

        logoutInitiatingSession();
        logoutSession(testSystem, initiatingSession2);
        assertSessionDisconnected(initiatingSession2);
        assertSessionsDisconnected();

        closeEnginesAndLibraries();

        Thread.sleep(1000);

        launchEngines();

        resetAcceptingSession();

        final Reply<SessionReplyStatus> reply = testSystem.awaitReply(acceptingLibrary.requestSession(
            id1, seqNum1 + 1, FixLibrary.CURRENT_SEQUENCE, 10_000));
        assertEquals(SessionReplyStatus.MISSING_MESSAGES, reply.resultIfPresent());

        initiateSession();
        messagesCanBeExchanged(initiatingSession);

        closeEnginesAndLibraries();
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

    private void closeEnginesAndLibraries()
    {
        Exceptions.closeAll(
            this::closeInitiatingEngine,
            this::closeAcceptingEngine,
            initiatingLibrary,
            this::closeAcceptingLibrary);
        testSystem.remove(initiatingLibrary);
    }

    private void initiateSession()
    {
        final Reply<Session> reply =
            connectPersistentSessions(1, 1, false);
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

    private void resetAcceptingSession()
    {
        testSystem.resetSequenceNumber(acceptingEngine, acceptingSession.id());
    }
}
