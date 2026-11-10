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

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionAcquiredInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.*;

import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.Constants.LOGOUT_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.Reply.State.COMPLETED;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Timing.withTimeout;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ExternallyControlledSystemTest extends AbstractGatewayToGatewaySystemTest
{
    private boolean awaitsNewOrderSingle = false;
    private final FakeSessionProxy fakeSessionProxy = new FakeSessionProxy();
    private SessionWriter acceptingSessionWriter = null;
    private final FakeHandler acceptingHandler = new FakeHandler(acceptingOtfAcceptor)
    {
        @Override
        public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
        {
            acceptingSessionWriter = acceptingLibrary.sessionWriter(
                session.id(),
                session.connectionId(),
                session.sequenceIndex());

            return super.onSessionAcquired(session, acquiredInfo);
        }
    };

    private int sessionProxyRequests = 0;

    @BeforeEach
    public void launch()
    {
        mediaDriver = launchMediaDriver();

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        acceptingConfig.deleteLogFileDirOnStart(true);
        acceptingConfig.initialAcceptedSessionOwner(SOLE_LIBRARY);

        acceptingEngine = FixEngine.launch(acceptingConfig);
        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock)
            .sessionProxyFactory(this::sessionProxyFactory);

        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldRoundTripMessagesViaExternalSystem()
    {
        connectSessions();

        awaitForwardingOfAcceptingSession();

        assertNotNull(acceptingSessionWriter);

        if (awaitsNewOrderSingle)
        {
            testSystem.awaitMessageOf(initiatingOtfAcceptor, "D");
        }

        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(1, fakeSessionProxy.sentHeartbeats);
        assertEquals(1, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReconnectConnections()
    {
        shouldRoundTripMessagesViaExternalSystem();

        disconnectSessions();
        acceptingSession = null;
        initiatingSession = null;

        connectSessions();

        awaitForwardingOfAcceptingSession();
        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(2, fakeSessionProxy.sentHeartbeats);
        assertEquals(2, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToContinueProcessingAFollowersSession()
    {
        final SessionWriter sessionWriter = writeMessageWithFollowerSessionWriter();
        final long writerSessionId = sessionWriter.id();

        fakeSessionProxy.sequenceNumberAdjustment = 1;

        awaitsNewOrderSingle = true;
        shouldRoundTripMessagesViaExternalSystem();

        assertEquals(acceptingSession.id(), writerSessionId);

        final FixMessage resentNewOrderSingle = awaitMessageFromSessionWriter(3, 1);
        assertEquals("Y", resentNewOrderSingle.possDup());

        // Check we can continue to use the session writer after session reconnected.
        assertEquals(acceptingSession.connectionId(), sessionWriter.connectionId());
        initiatingOtfAcceptor.messages().clear();
        final int secondNOSSeqNum = acceptingSession.lastSentMsgSeqNum() + 1;
        writeMessageWith(sessionWriter, secondNOSSeqNum);
        awaitMessageFromSessionWriter(secondNOSSeqNum, secondNOSSeqNum);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToAdjustSequenceNumbersFromTheControlSystem()
    {
        connectSessions();
        awaitForwardingOfAcceptingSession();

        // Write a message from the control system mid-stream
        writeMessageWith(acceptingSessionWriter, 2);
        fakeSessionProxy.sequenceNumberAdjustment = 1;

        awaitMessageFromSessionWriter(2, 2);

        messagesCanBeExchanged();

        assertEquals(1, sessionProxyRequests);
        assertEquals(1, fakeSessionProxy.sentHeartbeats);
        assertEquals(1, fakeSessionProxy.sentLogons);
        assertEquals(0, fakeSessionProxy.sentResendRequests);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveLogoutBeforeDisconnectInClusteredCaseInitiatorSentLogout()
    {
        connectSessions();

        final SessionWriter followerSessionWriter = followerSession();

        // fake delaying a logout to simulate it round-tripping a cluster
        fakeSessionProxy.send(false);
        testSystem.awaitSend(initiatingSession::startLogout);
        testSystem.awaitMessageOf(acceptingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);
        assertEquals(1, fakeSessionProxy.sentLogouts);

        testSystem.awaitSend(() -> fakeSessionProxy.sendLogoutMessage(followerSessionWriter));

        assertSessionDisconnected(initiatingSession);
        final FixMessage lastInitRecvMsg = initiatingOtfAcceptor.lastReceivedMessage();
        assertEquals(LOGOUT_MESSAGE_AS_STR, lastInitRecvMsg.msgType());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveLogoutBeforeDisconnectInClusteredCaseAcceptorSentLogout()
    {
        connectSessions();
        awaitForwardingOfAcceptingSession();
        final SessionWriter followerSession = followerSession();

        testSystem.awaitSend(() -> fakeSessionProxy.setupAndSendLogoutMessage(2, followerSession));

        testSystem.awaitMessageOf(initiatingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);
        assertSessionDisconnected(initiatingSession);

        final FixMessage fixMessage = testSystem.awaitMessageOf(acceptingOtfAcceptor, LOGOUT_MESSAGE_AS_STR);
        assertTrue(fixMessage.isValid());
    }

    private FixMessage awaitMessageFromSessionWriter(final int lastReceivedMsgSeqNum, final int newOrderSingleSeqNum)
    {
        final FixMessage receivedNewOrderSingle = withTimeout("Unable to find NOS", () ->
        {
            testSystem.poll();
            return initiatingOtfAcceptor.receivedMessage("D").findFirst();
        }, DEFAULT_TIMEOUT_IN_MS);
        assertEquals(newOrderSingleSeqNum, receivedNewOrderSingle.messageSequenceNumber());
        assertEquals(lastReceivedMsgSeqNum, initiatingSession.lastReceivedMsgSeqNum());
        return receivedNewOrderSingle;
    }

    private SessionWriter writeMessageWithFollowerSessionWriter()
    {
        final SessionWriter sessionWriter = followerSession();

        writeMessageWith(sessionWriter, 1);

        return sessionWriter;
    }

    private SessionWriter followerSession()
    {
        final HeaderEncoder headerEncoder = new HeaderEncoder()
            .senderCompID(INITIATOR_ID)
            .targetCompID(ACCEPTOR_ID);

        final Reply<SessionWriter> reply = acceptingLibrary.followerSession(headerEncoder, DEFAULT_TIMEOUT_IN_MS);
        testSystem.awaitCompletedReplies(reply);
        assertEquals(COMPLETED, reply.state());

        final SessionWriter sessionWriter = reply.resultIfPresent();
        return sessionWriter;
    }

    private void writeMessageWith(final SessionWriter sessionWriter, final int msgSeqNum)
    {
        final NewOrderSingleEncoder newOrderSingle = new NewOrderSingleEncoder();
        final DecimalFloat price = new DecimalFloat(100);
        final DecimalFloat orderQty = new DecimalFloat(2);
        final UtcTimestampEncoder time = new UtcTimestampEncoder();

        final int timeLength = time.encode(System.currentTimeMillis());

        newOrderSingle
            .clOrdID("A")
            .side(Side.BUY)
            .transactTime(time.buffer(), timeLength)
            .ordType(OrdType.MARKET)
            .price(price);

        newOrderSingle.instrument().symbol("MSFT");
        newOrderSingle.orderQtyData().orderQty(orderQty);

        newOrderSingle
            .header()
            .senderCompID(ACCEPTOR_ID)
            .targetCompID(INITIATOR_ID)
            .sendingTime(time.buffer(), timeLength)
            .msgSeqNum(msgSeqNum);

        assertTrue(acceptingLibrary.isConnected());
        assertThat(sessionWriter.send(newOrderSingle, msgSeqNum), greaterThan(0L));
    }

    private void awaitForwardingOfAcceptingSession()
    {
        Timing.assertEventuallyTrue(
            "Couldn't acquire session",
            () ->
            {
                testSystem.poll();
                acceptingSession = acceptingHandler.lastSession();

                return acceptingSession != null;
            });

        acceptingHandler.resetSession();
        final CompositeKey compositeKey = acceptingSession.compositeKey();
        assertEquals(INITIATOR_ID, compositeKey.remoteCompId());
        assertEquals(ACCEPTOR_ID, compositeKey.localCompId());
        assertNotNull(acceptingSession, "unable to acquire accepting session");
    }

    private SessionProxy sessionProxyFactory(
        final int sessionBufferSize,
        final GatewayPublication gatewayPublication,
        final SessionIdStrategy sessionIdStrategy,
        final SessionCustomisationStrategy customisationStrategy,
        final EpochNanoClock clock,
        final long connectionId,
        final int libraryId,
        final ErrorHandler errorHandler,
        final EpochFractionFormat epochFractionPrecision)
    {
        sessionProxyRequests++;
        fakeSessionProxy.setup(gatewayPublication, libraryId);
        return fakeSessionProxy;
    }

    class FakeSessionProxy implements SessionProxy
    {
        private final SessionIdStrategy sessionIdStrategy = SessionIdStrategy.senderAndTarget();
        private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        private final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
        private final LogonEncoder logon = new LogonEncoder();
        private final LogoutEncoder logout = new LogoutEncoder();
        private final List<HeaderEncoder> headers = asList(logon.header(), heartbeat.header(), logout.header());
        private GatewayPublication gatewayPublication;
        private int libraryId;

        FakeSessionProxy()
        {

        }

        private int sentLogons = 0;
        private int sentLogouts = 0;
        private int sentHeartbeats = 0;
        private int sentResendRequests = 0;

        private int sequenceNumberAdjustment = 0;

        private boolean seqNumResetRequested = false;

        private boolean send = true;

        public void send(final boolean send)
        {
            this.send = send;
        }

        @Override
        public void fixDictionary(final FixDictionary dictionary)
        {
        }

        @Override
        public void setupSession(final long sessionId, final CompositeKey sessionKey)
        {
            requireNonNull(sessionKey, "sessionKey");

            for (final HeaderEncoder header : headers)
            {
                sessionIdStrategy.setupSession(sessionKey, header);
            }
        }

        @Override
        public void connectionId(final long connectionId)
        {
        }

        @Override
        public long sendResendRequest(
            final int msgSeqNo,
            final int beginSeqNo,
            final int endSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            sentResendRequests++;
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendResendRequest");
            return 0;
        }

        @Override
        public long sendRequestDisconnect(final long connectionId, final DisconnectReason reason)
        {
            return gatewayPublication.saveRequestDisconnect(libraryId, connectionId, reason);
        }

        @Override
        public long sendLogon(
            final int msgSeqNo,
            final int heartbeatIntervalInS,
            final String username,
            final String password,
            final boolean resetSeqNumFlag,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed,
            final CancelOnDisconnectOption cancelOnDisconnectOption,
            final int cancelOnDisconnectTimeoutWindowInMs)
        {
            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            sentLogons++;

            final HeaderEncoder header = logon.header();
            setupHeader(header, adjustedMsgSeqNo);

            logon
                .heartBtInt(heartbeatIntervalInS)
                .resetSeqNumFlag(resetSeqNumFlag)
                .encryptMethod(0);

            if (notNullOrEmpty(username))
            {
                logon.username(username);
            }
            if (notNullOrEmpty(password))
            {
                logon.password(password);
            }
            seqNumResetRequested = logon.resetSeqNumFlag();

            return acceptingSessionWriter.send(logon, adjustedMsgSeqNo);
        }

        @Override
        public long sendLogout(
            final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed, final byte[] text)
        {
            sentLogouts++;

            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            final HeaderEncoder header = logout.header();
            setupHeader(header, adjustedMsgSeqNo);
            if (text != null)
            {
                logout.text(text);
            }

            if (send)
            {
                return sendLogoutMessage(acceptingSessionWriter);
            }
            else
            {
                return 1;
            }
        }

        long setupAndSendLogoutMessage(final int msgSeqNo, final SessionWriter sessionWriter)
        {
            final HeaderEncoder header = logout.header();
            setupHeader(header, msgSeqNo);
            return sendLogoutMessage(sessionWriter);
        }

        long sendLogoutMessage(final SessionWriter sessionWriter)
        {
            return sessionWriter.send(logout, logout.header().msgSeqNum());
        }

        @Override
        public long sendLogout(
            final int msgSeqNo,
            final int sequenceIndex,
            final int rejectReason,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.logout");
            return 0;
        }

        @Override
        public long sendLowSequenceNumberLogout(
            final int msgSeqNo,
            final int expectedSeqNo,
            final int receivedSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendLowSequenceNumberLogout");
            return 0;
        }

        @Override
        public long sendIncorrectBeginStringLogout(
            final int msgSeqNo,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendIncorrectBeginStringLogout");
            return 0;
        }

        @Override
        public long sendNegativeHeartbeatLogout(
            final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendNegativeHeartbeatLogout");
            return 0;
        }

        @Override
        public long sendReceivedMessageWithoutSequenceNumber(
            final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendReceivedMessageWithoutSequenceNumber");
            return 0;
        }

        @Override
        public long sendRejectWhilstNotLoggedOn(
            final int msgSeqNo, final RejectReason reason, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendRejectWhilstNotLoggedOn");
            return 0;
        }

        @Override
        public long sendHeartbeat(final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            return sendHeartbeat(msgSeqNo, null, 0, sequenceIndex, lastMsgSeqNumProcessed);
        }

        @Override
        public long sendHeartbeat(
            final int msgSeqNo,
            final char[] testReqId,
            final int testReqIdLength,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            final int adjustedMsgSeqNo = msgSeqNo + sequenceNumberAdjustment;
            sentHeartbeats++;

            final HeaderEncoder header = heartbeat.header();
            setupHeader(header, adjustedMsgSeqNo);

            if (testReqId != null)
            {
                heartbeat.testReqID(testReqId, testReqIdLength);
            }
            else
            {
                heartbeat.resetTestReqID();
            }

            return acceptingSessionWriter.send(heartbeat, adjustedMsgSeqNo);
        }

        @Override
        public long sendReject(
            final int msgSeqNo,
            final int refSeqNum,
            final int refTagId,
            final char[] refMsgType,
            final int refMsgTypeLength,
            final int rejectReason,
            final int sequenceIndex,
            final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendReject");
            return 0;
        }

        @Override
        public long sendTestRequest(
            final int msgSeqNo, final CharSequence testReqID, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendTestRequest");
            return 0;
        }

        @Override
        public long sendSequenceReset(
            final int msgSeqNo, final int newSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendSequenceReset");
            return 0;
        }

        @Override
        public void libraryConnected(final boolean libraryConnected)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.libraryConnected");
        }

        @Override
        public boolean seqNumResetRequested()
        {
            return seqNumResetRequested;
        }

        @Override
        public long sendCancelOnDisconnectTrigger(final long id, final long timeInNs)
        {
            DebugLogger.log(LogTag.FIX_TEST, "FakeSessionProxy.sendCancelOnDisconnectTrigger");
            return 0;
        }

        private void setupHeader(final HeaderEncoder header, final int msgSeqNo)
        {
            final UtcTimestampEncoder timestampEncoder = this.timestampEncoder;
            header.sendingTime(timestampEncoder.buffer(), timestampEncoder.update(System.currentTimeMillis()));
            header.msgSeqNum(msgSeqNo);
        }

        private boolean notNullOrEmpty(final String string)
        {
            return string != null && string.length() > 0;
        }

        void setup(final GatewayPublication gatewayPublication, final int libraryId)
        {
            this.gatewayPublication = gatewayPublication;
            this.libraryId = libraryId;
        }

        @Override
        public boolean isAsync()
        {
            return true;
        }
    }
}
