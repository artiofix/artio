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

import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.session.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.Constants.LOGOUT_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

public class EnableLastMsgSeqNumProcessedTest extends AbstractGatewayToGatewaySystemTest
{
    @BeforeEach
    public void launch()
    {
        mediaDriver = launchMediaDriver();

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        acceptingConfig.deleteLogFileDirOnStart(true);
        acceptingConfig.acceptedEnableLastMsgSeqNumProcessed(true);
        acceptingConfig.sessionPersistenceStrategy(alwaysPersistent());

        acceptingEngine = FixEngine.launch(acceptingConfig);
        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);

        final Reply<Session> reply = connectSession();
        completeConnectInitiatingSession(reply);
    }

    private Reply<Session> connectSession()
    {
        final SessionConfiguration config = SessionConfiguration.builder()
            .address("localhost", port)
            .credentials(USERNAME, PASSWORD)
            .senderCompId(INITIATOR_ID)
            .targetCompId(ACCEPTOR_ID)
            .sequenceNumbersPersistent(true)
            .initialSentSequenceNumber(1)
            .enableLastMsgSeqNumProcessed(true)
            .build();

        return initiatingLibrary.initiate(config);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void lastMsgSeqNumProcessedUpdatedByEngine()
    {
        messagesCanBeExchanged();

        messagesCanBeExchanged();

        assertLastInitiatorReceivedMsgSeqNumProcessed(2);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void lastMsgSeqNumProcessedUpdatedByLibrary()
    {
        acquireAcceptingSession();

        messagesCanBeExchanged();

        messagesCanBeExchanged();

        assertLastInitiatorReceivedMsgSeqNumProcessed(2);
        assertEquals(2, acceptingOtfAcceptor.lastReceivedMsgSeqNumProcessed());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void lastMsgSeqNumProcessedCorrectInLowSequenceNumberLogout()
    {
        messagesCanBeExchanged();
        messagesCanBeExchanged();

        assertLastInitiatorReceivedMsgSeqNumProcessed(2);

        acquireAcceptingSession();
        disconnectSessions();

        clearMessages();

        final Reply<Session> reply = connectSession();
        testSystem.awaitReply(reply);
        testSystem.awaitMessageOf(initiatingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);
        assertLastInitiatorReceivedMsgSeqNumProcessed(4);
    }

    private void assertLastInitiatorReceivedMsgSeqNumProcessed(final int expected)
    {
        assertEquals(expected, initiatingOtfAcceptor.lastReceivedMsgSeqNumProcessed());
    }

}
