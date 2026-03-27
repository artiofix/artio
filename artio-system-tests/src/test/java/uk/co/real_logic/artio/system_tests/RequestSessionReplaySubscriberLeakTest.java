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
        initiatingConfig.deleteLogFileDirOnStart(true);
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

        final int subPosAfter = countSubscriberPositions();
        assertEquals(subPosBefore, subPosAfter,
            "Subscriber positions leaked: before=" + subPosBefore +
                " after=" + subPosAfter +
                ". Each catchup replay + resend request cycle leaves orphaned " +
                "subscriber positions on the archive replay stream (IPC:4) because " +
                "both the Framer's inbound ReplayQuery and the Replayer's outbound " +
                "ReplayQuery subscribe to the same stream, and images from one " +
                "replay appear on both subscriptions but are only polled by one.");
    }

    @Test
    public void subscriberPositionsShouldNotGrowOverRepeatedCycles()
    {
        final int cycles = 5;

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

        final int subPosCount = countSubscriberPositions();

        // With the leak, each cycle adds stale subscriber positions.  A healthy system should have a bounded, small number.
        // Typically, 2-4 subscriber positions are expected (engine + library streams).
        // With the leak we see subPosCount grow linearly with cycles.
        if (subPosCount > 10)
        {
            throw new AssertionError(
                "Subscriber position count (" + subPosCount + ") grew beyond expected bounds " +
                    "after " + cycles + " release/acquire-with-replay cycles. " +
                    "This indicates IpcPublication subscriber positions are leaking because " +
                    "ReplayOperation.stopReplay() is never called after successful catchup replay.");
        }
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