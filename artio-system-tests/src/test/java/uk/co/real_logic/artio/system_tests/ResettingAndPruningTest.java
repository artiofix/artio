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

import org.agrona.concurrent.status.ReadablePosition;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.OrdType;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.DynamicLibraryScheduler;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.session.Session;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

@Disabled
public class ResettingAndPruningTest extends AbstractGatewayToGatewaySystemTest
{
    private long resetPosition = 0;

    @Test
    @Timeout(50_000)
    public void shouldLogOnWithBothSeqNumsReset()
    {
        launch();

        final ReadablePosition initPosition = testSystem.libraryPosition(initiatingEngine, initiatingLibrary);

        initiatingHandler.onDisconnectCallback((session, reason) ->
        {
            resetPosition = session.trySendSequenceReset(1, 1);
            assertTrue(resetPosition > 0);
        });

        final NewOrderSingleEncoder nos = new NewOrderSingleEncoder();
        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();

        for (int i = 0; i < 20; i++)
        {
            initiateSession();
            acceptSession();

            messagesCanBeExchanged();

            for (int j = 0; j < 100; j++)
            {
                nos.reset();
                nos.clOrdID("order-" + i);
                nos.instrument().symbol("ABC");
                nos.side(Side.BUY);
                nos.transactTime(timestampEncoder.buffer(), timestampEncoder.encode(System.currentTimeMillis()));
                nos.ordType(OrdType.LIMIT);
                nos.price(1, 0);
                testSystem.awaitSend(() -> acceptingSession.trySend(nos));
            }

            logoutInitiatingSession();
            assertSessionsDisconnected();
            resetAcceptingSession();
            testSystem.awaitPosition(initPosition, resetPosition);

            sleep(100, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldPurgeSegmentsBeforeLastReset()
    {
        // Test a reproduction of a spotted bug, performing a pruneArchive() twice caused the first index positions
        // to be cached even after a reset of the sequence indices.

        launch();
        initiateSession();
        acceptSession();
        final Set<String> segments1 = getArchiveSegments();

        pruneArchive();

        // generate enough data to move at least to the second segment
        for (int i = 0; i < 1000; i++)
        {
            messagesCanBeExchanged();
        }

        // verify we now have more segments
        final Set<String> segments2 = getArchiveSegments();
        assertThat(segments1 + " < " + segments2, segments1.size(), lessThan(segments2.size()));

        logoutInitiatingSession();
        assertSessionsDisconnected();
        resetInitiatingSession();

        // the only session has been reset, so pruning should be able to delete some segments
        pruneArchive();
        final Set<String> segments3 = getArchiveSegments();
        assertThat(segments3 + " < " + segments2, segments3.size(), lessThan(segments2.size()));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldPurgeSegmentsBeforeLastResetWhilstSessionConnected()
    {
        // Test a reproduction of a spotted bug, performing a pruneArchive() twice caused the first index positions
        // to be cached even after a reset of the sequence indices.

        launch();
        initiateSession();
        acceptSession();
        final Set<String> segments1 = getArchiveSegments();

        pruneArchive();

        // generate enough data to move at least to the second segment
        for (int i = 0; i < 1000; i++)
        {
            messagesCanBeExchanged();
        }

        // verify we now have more segments
        final Set<String> segments2 = getArchiveSegments();
        assertThat(segments1 + " < " + segments2, segments1.size(), lessThan(segments2.size()));

        logoutInitiatingSession();
        assertSessionsDisconnected();
        resetInitiatingSession();
        resetAcceptingSession();

        initiateSession();
        acceptSession();

        // the only session has been reset, so pruning should be able to delete some segments
        pruneArchive();
        final Set<String> segments3 = getArchiveSegments();
        assertThat(segments3 + " < " + segments2, segments3.size(), lessThan(segments2.size()));
    }

    private void launch()
    {
        deleteLogs();
        mediaDriver = launchMediaDriver(64 * 1024);
        final EngineConfiguration config = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        config.sessionPersistenceStrategy(alwaysPersistent());
        acceptingEngine = FixEngine.launch(config);
        final EngineConfiguration initiatingConfig = initiatingConfig(libraryAeronPort, nanoClock);
        initiatingConfig.initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY);
        initiatingEngine = FixEngine.launch(initiatingConfig);
        final DynamicLibraryScheduler libraryScheduler = new DynamicLibraryScheduler();
        acceptingLibrary = connect(acceptingLibraryConfig(acceptingHandler, nanoClock).scheduler(libraryScheduler));
        initiatingLibrary = connect(initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock)
            .scheduler(libraryScheduler));
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    private void initiateSession()
    {
        final Reply<Session> reply =
            connectPersistentSessions(AUTOMATIC_INITIAL_SEQUENCE_NUMBER, AUTOMATIC_INITIAL_SEQUENCE_NUMBER, false);
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

    private void resetInitiatingSession()
    {
        testSystem.resetSequenceNumber(initiatingEngine, initiatingSession.id());
    }

    private void pruneArchive()
    {
        testSystem.awaitReply(initiatingEngine.pruneArchive(null));
    }

    private void sleep(final long duration, final TimeUnit unit)
    {
        final long deadline = System.nanoTime() + unit.toNanos(duration);
        while (System.nanoTime() - deadline < 0)
        {
            testSystem.poll();
        }
    }

    private Set<String> getArchiveSegments()
    {
        final Set<String> result = new TreeSet<>();
        final String[] files = mediaDriver.archive().context().archiveDir().list();
        if (files != null)
        {
            for (final String file : files)
            {
                if (file.endsWith(".rec"))
                {
                    result.add(file);
                }
            }
        }
        return result;
    }
}
