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
import org.agrona.CloseHelper;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.junit.jupiter.api.*;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.ReplayMessagesStatus;
import uk.co.real_logic.artio.session.Session;

import java.io.IOException;

import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.unusedPort;
import static uk.co.real_logic.artio.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;
import static uk.co.real_logic.artio.system_tests.AbstractGatewayToGatewaySystemTest.TEST_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class InterferingReplayStreamTest
{
    private static final int TERM_BUFFER_LENGTH = 64 * 1024;

    ArchivingMediaDriver mediaDriver;
    FixEngine acceptingEngine;
    FixLibrary acceptingLibrary;
    TestSystem testSystem;

    private final EpochNanoClock nanoClock = new OffsetEpochNanoClock();
    private final int port = unusedPort();
    private final FakeOtfAcceptor acceptingOtfAcceptor = new FakeOtfAcceptor();
    private final FakeHandler acceptingHandler = new FakeHandler(acceptingOtfAcceptor);

    @BeforeEach
    void setup()
    {
        mediaDriver = launchMediaDriver(TERM_BUFFER_LENGTH);

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .deleteLogFileDirOnStart(true)
            .initialAcceptedSessionOwner(SOLE_LIBRARY)
            .replayFragmentLimit(100);
        acceptingEngine = FixEngine.launch(acceptingConfig);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibrary = FixLibrary.connect(acceptingLibraryConfig);
        assertEventuallyTrue(
            () -> "Unable to connect to engine",
            () ->
            {
                acceptingLibrary.poll(1);
                return acceptingLibrary.isConnected();
            },
            DEFAULT_TIMEOUT_IN_MS,
            acceptingLibrary::close);
        testSystem = new TestSystem(acceptingLibrary);
    }

    @AfterEach
    void tearDown()
    {
        CloseHelper.closeAll(acceptingLibrary, acceptingEngine);
        cleanupMediaDriver(mediaDriver);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    void shouldReplaySubscriptionsNotInterfere() throws IOException
    {
        try (FixConnection fixConnection = FixConnection.initiate(port))
        {
            fixConnection.logon(true);
            testSystem.awaitBlocking(() -> fixConnection.readLogon());

            final int messagesForHalfTerm = 320;
            for (int i = 0; i < messagesForHalfTerm; ++i)
            {
                OrderFactory.sendOrder(fixConnection);
                testSystem.poll();
            }

            final Session session = acceptingHandler.lastSession();
            for (int i = 0; i < messagesForHalfTerm; ++i)
            {
                ReportFactory.sendOneReport(testSystem, session, Side.SELL);
                testSystem.awaitBlocking(() -> fixConnection.readExecutionReport());
            }

            final Reply<ReplayMessagesStatus> sessionReplayReply = session.replayReceivedMessages(
                0, session.sequenceIndex(), session.lastReceivedMsgSeqNum(), session.sequenceIndex(), 10_000L);
            testSystem.awaitCompletedReply(sessionReplayReply);
            Assertions.assertEquals(ReplayMessagesStatus.OK, sessionReplayReply.resultIfPresent());

            fixConnection.sendResendRequest(1, 0);
            testSystem.awaitBlocking(() -> fixConnection.readSequenceResetGapFill(2));
            for (int i = 0; i < messagesForHalfTerm; ++i)
            {
                testSystem.awaitBlocking(() -> fixConnection.readExecutionReport());
            }
        }
    }
}
