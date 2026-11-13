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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.session.Session;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class NoLoggingGatewayToGatewaySystemTest extends AbstractGatewayToGatewaySystemTest
{

    @BeforeEach
    public void launch()
    {
        mediaDriver = launchMediaDriver();

        // logger = FixMessageLogger.start();

        acceptingEngine = FixEngine.launch(acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .logInboundMessages(false)
            .logOutboundMessages(false)
            .deleteLogFileDirOnStart(true)
            .resendRequestController(fakeResendRequestController));

        initiatingEngine = FixEngine.launch(initiatingConfig(libraryAeronPort, nanoClock)
            .logInboundMessages(false)
            .logOutboundMessages(false)
            .deleteLogFileDirOnStart(true));

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibraryConfig.resendRequestController(fakeResendRequestController);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        connectSessions();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromInitiatorToAcceptor()
    {
        messagesCanBeExchanged();

        assertInitiatingSequenceIndexIs(0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromInitiatorToAcceptingLibrary()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();

        assertSequenceIndicesAre(0);
    }

    @Override
    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void sessionsCanReconnect()
    {
        super.sessionsCanReconnect();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToReleaseInitiatedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngine(initiatingSession, initiatingLibrary, initiatingEngine);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void librariesShouldBeAbleToReleaseAcceptedSessionToEngine()
    {
        acquireAcceptingSession();

        releaseSessionToEngine(acceptingSession, acceptingLibrary, acceptingEngine);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void enginesShouldManageAcceptingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(acceptingSession, acceptingLibrary, initiatingSession, initiatingOtfAcceptor);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void enginesShouldManageInitiatingSession()
    {
        acquireAcceptingSession();

        engineShouldManageSession(initiatingSession, initiatingLibrary, acceptingSession, acceptingOtfAcceptor);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireTimedOutAcceptingSessions()
    {
        acquireAcceptingSession();

        testSystem.remove(acceptingLibrary);

        acceptingEngineHasSessionAndLibraryIsNotified();

        messagesCanBeExchanged();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void engineShouldAcquireTimedOutInitiatingSessions()
    {
        acquireAcceptingSession();

        testSystem.remove(initiatingLibrary);

        initiatingEngineHasSessionAndLibraryIsNotified();

        // Ensure that messages can be exchanged after the acquisition.
        acceptingMessagesCanBeExchanged();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void resendRequestsShouldGetGapFilled()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();

        sendResendRequest(1, 0, initiatingOtfAcceptor, initiatingSession);

        assertEventuallyTrue(() -> "Failed to receive gap fill: " + initiatingOtfAcceptor.messages(),
            () ->
            {
                testSystem.poll();

                assertEquals(1, initiatingOtfAcceptor
                    .receivedReplayGapFill(1, 3)
                    .count());
            }, 5000);

        assertResendsCompleted(1, hasItems(0));
        messagesCanBeExchanged();
    }

    private void engineShouldManageSession(
        final Session session,
        final FixLibrary library,
        final Session otherSession,
        final FakeOtfAcceptor otherAcceptor)
    {
        engineShouldManageSession(
            session,
            library,
            otherSession,
            otherAcceptor,
            INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES);

        messagesCanBeExchanged(otherSession, otherAcceptor);
    }

}
