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

import io.aeron.Aeron;
import io.aeron.driver.status.SubscriberPos;
import org.agrona.CloseHelper;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.session.Session;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.ACCEPTOR_ID;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.INITIATOR_ID;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.acceptingConfig;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.acceptingLibraryConfig;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.acquireSession;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.initiate;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.initiatingConfig;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.initiatingLibraryConfig;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.releaseToEngine;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.requestSession;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

/**
 * This test was submitted in <a href="https://github.com/artiofix/artio/issues/568">issue #568</a> as an attempt to
 * report a bug in resource management during artio catchup replays.
 * It turns out the original test had two problems:
 *
 * <ul>
 *   <li>it was using two FixEngines with the same media driver and the same archive replay stream ID. This means when
 *   a startReplay is invoked, two images are created and the one assigned to the initiator is never drained, thus
 *   causing a permanent increment to the sub-pos counter. For the sake of testing, we assigned the initiator to a
 *   different stream id.
 *   </li>
 *   <li>resource cleanup is an async operation, which means the assert on the sub-pos counter should be done within a
 *   {@code testSystem.await}.
 *   </li>
 * </ul>
 */
@Timeout(20)
public class RequestSessionReplaySubscriberLeakTest extends AbstractGatewayToGatewaySystemTest
{
    @BeforeEach
    public void setUp()
    {
        deleteLogs();
        mediaDriver = TestFixtures.launchMediaDriver();

        final EngineConfiguration acceptingConfig =
            acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .sessionPersistenceStrategy(alwaysPersistent())
            .deleteLogFileDirOnStart(true);
        acceptingEngine = FixEngine.launch(acceptingConfig);

        final EngineConfiguration initiatingConfig =
            initiatingConfig(libraryAeronPort, nanoClock);
        initiatingConfig.deleteLogFileDirOnStart(true).archiveReplayStream(11);
        initiatingEngine = FixEngine.launch(initiatingConfig);

        testSystem = new TestSystem();
        acceptingLibrary = testSystem.connect(
            acceptingLibraryConfig(acceptingHandler, nanoClock));
        initiatingLibrary = testSystem.connect(
            initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock));

        // Establish the session normally (no replay)
        final Reply<Session> reply = testSystem.awaitCompletedReply(
            initiate(initiatingLibrary, port, INITIATOR_ID, ACCEPTOR_ID));
        initiatingSession = reply.resultIfPresent();

        final long sessionId = acceptingHandler.awaitSessionId(testSystem::poll);
        acceptingSession = acquireSession(
            acceptingHandler, acceptingLibrary, sessionId, testSystem);
    }

    @AfterEach
    public void tearDown()
    {
        CloseHelper.close(initiatingLibrary);
        CloseHelper.close(acceptingLibrary);
        CloseHelper.close(initiatingEngine);
        CloseHelper.close(acceptingEngine);
        cleanupMediaDriver(mediaDriver);
    }

    @Test
    public void subscriberPositionsShouldNotLeakAfterCatchupReplayAndResendRequest()
    {
        // Exchange some messages so there is data in the archive to replay
        messagesCanBeExchanged();
        messagesCanBeExchanged();

        final int lastReceivedSeqNum = acceptingSession.lastReceivedMsgSeqNum();
        final int sequenceIndex = acceptingSession.sequenceIndex();

        assertEquals(OK, releaseToEngine(acceptingLibrary, acceptingSession, testSystem));

        final int subPosBefore = countSubscriberPositions();

        // Re-acquire with catchup replay (non-NO_MESSAGE_REPLAY value triggers CatchupReplayer)
        final SessionReplyStatus status = requestSession(
            acceptingLibrary,
            acceptingSession.id(),
            lastReceivedSeqNum,
            sequenceIndex,
            testSystem);
        assertEquals(OK, status);
        acceptingSession = acceptingHandler.lastSession();
        acceptingHandler.resetSession();

        // Now send a FIX ResendRequest from the accepting side (triggers Replayer's outbound replay)
        sendResendRequest(1, 0, initiatingOtfAcceptor, acceptingSession);

        // Allow time for replays to complete and resources to be cleaned up
        testSystem.await("resend request processed",
            () -> initiatingOtfAcceptor.receivedMessage("4").findAny().isPresent() ||
            !initiatingOtfAcceptor.messages().isEmpty());
        for (int i = 0; i < 100; i++)
        {
            testSystem.poll();
        }

        testSystem.await("subscriber positions cleaned up",
            () -> countSubscriberPositions() == subPosBefore);
    }

    @Test
    public void subscriberPositionsShouldNotGrowOverRepeatedCycles()
    {
        final int initPosCount = countSubscriberPositions();

        final int cycles = 10;

        for (int i = 0; i < cycles; i++)
        {
            messagesCanBeExchanged();

            final int lastReceivedSeqNum = acceptingSession.lastReceivedMsgSeqNum();
            final int sequenceIndex = acceptingSession.sequenceIndex();

            assertEquals(OK, releaseToEngine(acceptingLibrary, acceptingSession, testSystem));

            // Re-acquire with catchup replay
            final SessionReplyStatus status = requestSession(
                acceptingLibrary,
                acceptingSession.id(),
                lastReceivedSeqNum,
                sequenceIndex,
                testSystem);
            assertEquals(OK, status);
            acceptingSession = acceptingHandler.lastSession();
            acceptingHandler.resetSession();
        }

        // Allow cleanup
        for (int j = 0; j < 200; j++)
        {
            testSystem.poll();
        }

        testSystem.await("subscriber positions cleaned up after repeated cycles",
            () -> countSubscriberPositions() == initPosCount);
    }

    private int countSubscriberPositions()
    {
        final String aeronDir = mediaDriver.mediaDriver().aeronDirectoryName();
        try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir)))
        {
            final CountersReader counters = aeron.countersReader();
            final AtomicInteger count = new AtomicInteger(0);
            counters.forEach((counterId, typeId, keyBuffer, label) ->
            {
                if (typeId == SubscriberPos.SUBSCRIBER_POSITION_TYPE_ID)
                {
                    count.incrementAndGet();
                }
            });
            return count.get();
        }
    }
}