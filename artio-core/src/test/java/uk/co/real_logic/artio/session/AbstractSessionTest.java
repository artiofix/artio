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
package uk.co.real_logic.artio.session;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.decoder.ExampleMessageDecoder;
import uk.co.real_logic.artio.decoder.SequenceResetDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.engine.framer.FakeEpochClock;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.EpochFractionClock;
import uk.co.real_logic.artio.util.EpochFractionClocks;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S;
import static uk.co.real_logic.artio.CommonConfiguration.NO_FORCED_HEARTBEAT_INTERVAL;
import static uk.co.real_logic.artio.Constants.NEW_SEQ_NO;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;
import static uk.co.real_logic.artio.fields.RejectReason.*;
import static uk.co.real_logic.artio.messages.CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT;
import static uk.co.real_logic.artio.messages.DisconnectReason.*;
import static uk.co.real_logic.artio.messages.SessionState.*;
import static uk.co.real_logic.artio.session.DirectSessionProxy.NO_LAST_MSG_SEQ_NUM_PROCESSED;
import static uk.co.real_logic.artio.session.Session.TEST_REQ_ID;
import static uk.co.real_logic.artio.session.Session.UNKNOWN;

public abstract class AbstractSessionTest
{
    public static final long SESSION_ID = 2L;

    static final long TWO_MINUTES = MINUTES.toMillis(2);
    static final long SENDING_TIME_WINDOW = 2000;
    static final long CONNECTION_ID = 3L;
    static final int HEARTBEAT_INTERVAL_IN_S = 2;
    static final int SESSION_TIMEOUT = HEARTBEAT_INTERVAL_IN_S + DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S;
    static final int LIBRARY_ID = 4;
    static final int SEQUENCE_INDEX = 0;

    private static final char[] MSG_TYPE_CHARS = "D".toCharArray();

    final char[] testReqId = "ABC".toCharArray();

    static final long POSITION = 1024;

    OnMessageInfo messageInfo = mock(OnMessageInfo.class);
    DirectSessionProxy sessionProxy = mock(DirectSessionProxy.class);
    GatewayPublication mockPublication = mock(GatewayPublication.class);
    FakeEpochClock fakeClock = new FakeEpochClock();
    EpochNanoClock nanoClock = fakeClock.nanoClockView();
    EpochFractionClock fakeEpochFractionClock = EpochFractionClocks.millisClock(nanoClock);
    AtomicCounter mockReceivedMsgSeqNo = mock(AtomicCounter.class);
    AtomicCounter mockSentMsgSeqNo = mock(AtomicCounter.class);
    SessionIdStrategy idStrategy = mock(SessionIdStrategy.class);
    ArgumentCaptor<DirectBuffer> bufferCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
    ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);
    TestRequestEncoder testRequest = new TestRequestEncoder();
    FixSessionOwner fixSessionOwner = mock(FixSessionOwner.class);
    int forcedHeartbeatIntervalInS = NO_FORCED_HEARTBEAT_INTERVAL;
    boolean disableHeartbeatRepliesToTestRequests = false;
    Session session;

    AbstractSessionTest()
    {
        doAnswer(
            (inv) ->
            {
                final HeaderEncoder encoder = (HeaderEncoder)inv.getArguments()[1];
                encoder.senderCompID("senderCompID").targetCompID("targetCompID");
                return null;
            }).when(idStrategy).setupSession(any(), any());

        when(mockPublication.saveMessage(
            bufferCaptor.capture(),
            offsetCaptor.capture(),
            lengthCaptor.capture(),
            anyInt(),
            anyLong(),
            anyLong(),
            anyInt(),
            anyLong(),
            any(),
            anyInt(),
            eq(null),
            eq(0))).thenReturn(POSITION);
        when(mockPublication.saveMessage(
            bufferCaptor.capture(),
            offsetCaptor.capture(),
            lengthCaptor.capture(),
            anyInt(),
            anyLong(),
            anyLong(),
            anyInt(),
            anyLong(),
            any(),
            anyInt(),
            anyLong(),
            eq(null),
            eq(0))).thenReturn(POSITION);

        when(sessionProxy.sendResendRequest(anyInt(), anyInt(), anyInt(), eq(SEQUENCE_INDEX), anyInt()))
            .thenReturn(POSITION);
    }

    FixDictionary makeDictionary()
    {
        return FixDictionary.of(FixDictionary.findDefault());
    }

    @Test
    public void shouldLogoutOnLowSequenceNumber()
    {
        givenActive();
        session().lastReceivedMsgSeqNum(2);

        onMessage(1);
        verify(sessionProxy).sendLowSequenceNumberLogout(
            1, 3, 1, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldDisconnectIfMissingSequenceNumber()
    {
        readyForLogon();
        onLogon(1);

        final int nextMsgSeqNum = nextMsgSeqNum();

        assertEquals(CONTINUE, onMessage(MISSING_INT));

        receivedMessageWithoutSequenceNumber(nextMsgSeqNum, 1);
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldDisconnectIfMissingSequenceNumberWhenBackPressured()
    {
        readyForLogon();
        onLogon(1);

        final int nextMsgSeqNum = nextMsgSeqNum();

        when(sessionProxy.sendReceivedMessageWithoutSequenceNumber(
            nextMsgSeqNum, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED))
            .thenReturn(BACK_PRESSURED, POSITION);

        assertEquals(ABORT, onMessage(MISSING_INT));

        assertEquals(CONTINUE, onMessage(MISSING_INT));

        receivedMessageWithoutSequenceNumber(nextMsgSeqNum, 2);
        verifyDisconnect(times(1));
    }

    private int nextMsgSeqNum()
    {
        return session().lastSentMsgSeqNum() + 1;
    }

    private void receivedMessageWithoutSequenceNumber(final int sentMsgSeqNum, final int times)
    {
        verify(sessionProxy, times(times))
            .sendReceivedMessageWithoutSequenceNumber(sentMsgSeqNum, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    @Test
    public void shouldLogoutIfNegativeHeartbeatInterval()
    {
        readyForLogon();

        final int heartbeatInterval = -1;

        onLogon(heartbeatInterval, 1, false);

        verify(sessionProxy).sendNegativeHeartbeatLogout(1, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    @Test
    public void shouldValidateOriginalSendingTimeBeforeSendingTime()
    {
        final long sendingTime = sendingTime();
        final long origSendingTime = sendingTime + 10;

        readyForLogon();
        onLogon(1);

        onMessage(2);

        // Ensure sequence numbers are consistent
        session().lastSentMsgSeqNum(1);
        session().onMessage(
            2, MSG_TYPE_CHARS, sendingTime, origSendingTime, true, true, POSITION);

        verifySendingTimeProblem();
        assertSequenceIndexIs(SEQUENCE_INDEX);
    }

    @Test
    public void shouldValidateOriginalSendingTimeExistsIfPossDupFlagIsSet()
    {
        readyForLogon();
        onLogon(1);

        onMessage(2);

        // Ensure sequence numbers are consistent
        session().lastSentMsgSeqNum(1);
        onMessage(2, true, UNKNOWN);

        verify(sessionProxy).sendReject(
            2,
            2,
            SessionConstants.ORIG_SENDING_TIME,
            MSG_TYPE_CHARS,
            MSG_TYPE_CHARS.length,
            REQUIRED_TAG_MISSING.representation(),
            SEQUENCE_INDEX,
            NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    @Test
    public void shouldNotifyClientUponSequenceReset()
    {
        final int newSentSeqNo = 10;
        final int newReceivedSeqNo = 10;

        onLogon(1);

        assertThat(session().lastSentMsgSeqNum(), lessThanOrEqualTo(1));

        session().trySendSequenceReset(newSentSeqNo, newReceivedSeqNo);

        verify(sessionProxy).sendSequenceReset(anyInt(), eq(newSentSeqNo), eq(SEQUENCE_INDEX), anyInt());
        assertEquals(newSentSeqNo - 1, session().lastSentMsgSeqNum());
        assertSequenceIndexIs(SEQUENCE_INDEX);
    }

    @Test
    public void shouldResetReceivingSequenceNumbers()
    {
        final int newSentSeqNo = 10;
        final int newReceivedSeqNo = 10;

        givenActive();

        session().trySendSequenceReset(newSentSeqNo, newReceivedSeqNo);

        final String testReqId = "hello";

        session().onTestRequest(
            newReceivedSeqNo,
            testReqId.toCharArray(),
            testReqId.length(),
            sendingTime(),
            MISSING_LONG,
            false, false, POSITION);

        verifyConnected();
        assertState(ACTIVE);
    }

    @Test
    public void shouldSendHeartbeatAfterLogonSpecifiedInterval()
    {
        readyForLogon();

        final int heartbeatInterval = 1;
        onLogon(heartbeatInterval, 1, false);

        heartbeatSentAfterInterval(heartbeatInterval, 2, false);
    }

    @Test
    public void shouldSendHeartbeatAfterInterval()
    {
        shouldSendHeartbeatAfterInterval(false);
    }

    @Test
    public void shouldSendHeartbeatAfterIntervalWhenBackPressured()
    {
        shouldSendHeartbeatAfterInterval(true);
    }

    private void shouldSendHeartbeatAfterInterval(final boolean backPressured)
    {
        readyForLogon();

        onLogon(1);

        heartbeatSentAfterInterval(1, 2, backPressured);
    }

    @Test
    public void shouldSendHeartbeatsAfterIntervalRepeatedly()
    {
        shouldSendHeartbeatAfterInterval();

        heartbeatSentAfterInterval(2, 3, false);

        heartbeatSentAfterInterval(3, 4, false);
    }

    @Test
    public void shouldSendHeartbeatsAfterIntervalRepeatedlyWhenBackPressured()
    {
        shouldSendHeartbeatAfterInterval(true);

        heartbeatSentAfterInterval(2, 3, true);

        heartbeatSentAfterInterval(3, 4, true);
    }

    @Test
    public void shouldReplyToValidLogout()
    {
        givenActive();

        onLogout();

        verifyLogout(1, times(1));
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldReplyToValidLogoutWhenBackPressured()
    {
        givenActive();

        backPressureLogout();

        onLogout();

        poll();

        poll();

        poll();

        verifyLogout(1, times(2));
        verifyDisconnect(times(2));
    }

    private void backPressureLogout()
    {
        when(sessionProxy.sendLogout(anyInt(),
          eq(SEQUENCE_INDEX),
          anyInt(),
          any())).thenReturn(BACK_PRESSURED, POSITION);

        backPressureDisconnect();
    }

    @Test
    public void shouldDisconnectUponLogoutAcknowledgement()
    {
        session().state(AWAITING_LOGOUT);

        onLogout();

        verifyDisconnect(times(1));
    }

    @Test
    public void shouldReplyToTestRequestsWithAHeartbeat()
    {
        receiveTestRequest();

        verify(sessionProxy).sendHeartbeat(
            1, testReqId, testReqId.length, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    @Test
    public void shouldReplyToTestRequestsWithAHeartbeatOnlyWhenConfigured()
    {
        disableHeartbeatRepliesToTestRequests = true;

        receiveTestRequest();

        verifyNoFurtherMessages();
    }

    private void receiveTestRequest()
    {
        final Session session = session();

        session.id(SESSION_ID);
        session.state(ACTIVE);
        session.onTestRequest(1, testReqId, testReqId.length, sendingTime(), UNKNOWN, false, false, POSITION);
    }

    @Test
    public void shouldResendRequestForUnexpectedGapFill()
    {
        session().id(SESSION_ID);

        onSequenceReset();

        verify(sessionProxy).sendResendRequest(1, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    @Test
    public void shouldResendRequestForUnexpectedGapFillWhenBackPressured()
    {
        backPressureResendRequest();

        session().id(SESSION_ID);

        session().lastSentMsgSeqNum(70);
        assertEquals(ABORT, onSequenceReset());
        assertEquals(CONTINUE, onSequenceReset());

        assertEquals(71, session().lastSentMsgSeqNum());
        assertEquals(3, session().lastReceivedMsgSeqNum());

        verify(sessionProxy, times(2))
            .sendResendRequest(71, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    private Action onSequenceReset()
    {
        return session().onSequenceReset(3, 4, true, false, POSITION);
    }

    private void backPressureResendRequest()
    {
        when(sessionProxy.sendResendRequest(anyInt(), anyInt(), anyInt(), eq(SEQUENCE_INDEX), anyInt()))
            .thenReturn(BACK_PRESSURED, POSITION);
    }

    @Test
    public void shouldIgnoreDuplicateGapFill()
    {
        session().lastReceivedMsgSeqNum(2);

        session().onSequenceReset(1, 4, false, true, POSITION);

        verifyNoFurtherMessages();
    }

    @Test
    public void shouldLogoutOnInvalidGapFill()
    {
        session().lastReceivedMsgSeqNum(2);

        onGapFill(1, 4);

        verify(sessionProxy).sendLowSequenceNumberLogout(anyInt(), eq(3), eq(1), eq(SEQUENCE_INDEX), anyInt());
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldUpdateSequenceNumberOnValidGapFill()
    {
        givenActive();

        onGapFill(1, 4);

        assertEquals(4, session().expectedReceivedSeqNum());
        verifyNoFurtherMessages();
        verifyConnected();
        assertState(ACTIVE);

        verifyCanRoundtripTestMessage();
    }

    @Test
    public void shouldIgnoreMsgSeqNumWithoutGapFillFlag()
    {
        givenActive();

        session().onSequenceReset(0, 4, false, false, POSITION);

        assertEquals(4, session().expectedReceivedSeqNum());
        verifyNoFurtherMessages();
        verifyConnected();

        verifyCanRoundtripTestMessage();
    }

    @Test
    public void shouldUpdateSequenceNumberOnSequenceReset()
    {
        session().onSequenceReset(4, 4, false, false, POSITION);

        assertEquals(4, session().expectedReceivedSeqNum());
        verifyNoFurtherMessages();
        assertSequenceIndexIs(SEQUENCE_INDEX);
    }

    @Test
    public void shouldAcceptUnnecessarySequenceReset()
    {
        session().lastReceivedMsgSeqNum(3);

        session().onSequenceReset(4, 4, false, false, POSITION);

        assertEquals(4, session().expectedReceivedSeqNum());
        verifyNoFurtherMessages();
        assertSequenceIndexIs(SEQUENCE_INDEX);
    }

    @Test
    public void shouldRejectLowSequenceReset()
    {
        session().lastReceivedMsgSeqNum(3);

        session().onSequenceReset(2, 1, false, false, POSITION);

        assertEquals(4, session().expectedReceivedSeqNum());
        verify(sessionProxy).sendReject(
            1,
            2,
            NEW_SEQ_NO,
            SequenceResetDecoder.MESSAGE_TYPE_CHARS,
            SequenceResetDecoder.MESSAGE_TYPE_CHARS.length,
            VALUE_IS_INCORRECT.representation(),
            SEQUENCE_INDEX,
            NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertSequenceIndexIs(SEQUENCE_INDEX);
    }

    @Test
    public void shouldSendTestRequestUponTimeout()
    {
        shouldSendTestRequestUponTimeout(false);
    }

    private void shouldSendTestRequestUponTimeout(final boolean backPressured)
    {
        givenActive();
        session().lastSentMsgSeqNum(5);
        session().lastReceivedMsgSeqNum(9);

        onMessage(10);

        twoHeartBeatIntervalsPass();

        if (backPressured)
        {
            when(sessionProxy.sendTestRequest(7, TEST_REQ_ID, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED))
                .thenReturn(BACK_PRESSURED, POSITION);
        }

        poll();

        if (backPressured)
        {
            poll();
        }

        verify(sessionProxy, retry(backPressured))
            .sendTestRequest(7, TEST_REQ_ID, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingHeartbeat();
    }

    @Test
    public void shouldSendTestRequestUponTimeoutWhenBackPressured()
    {
        shouldSendTestRequestUponTimeout(true);
    }

    @Test
    public void shouldLogoutAndDisconnectUponTimeout()
    {
        shouldSendTestRequestUponTimeout();

        twoHeartBeatIntervalsPass();

        poll();

        verifyLogout(9, times(1));
    }

    @Test
    public void shouldLogoutAndDisconnectUponTimeoutOnlyOnce()
    {
        shouldSendTestRequestUponTimeout();

        twoHeartBeatIntervalsPass();

        poll();

        assertState(DISCONNECTED);

        poll();

        verifyLogoutOnlyOnce();
    }

    @Test
    public void shouldLogoutAndDisconnectUponTimeoutOnlyOnceWithCluster()
    {
        when(sessionProxy.isAsync()).thenReturn(true);

        shouldSendTestRequestUponTimeout();

        twoHeartBeatIntervalsPass();

        poll();

        // Initially we wait for the logout to be roundtripped via a cluster
        assertState(AWAITING_ASYNC_PROXY_LOGOUT);

        poll();

        verifyLogoutOnlyOnce();

        // Roundtrip completed, so we disconnect the session
        session.onSessionWriterLogout();
        verify(sessionProxy).sendRequestDisconnect(CONNECTION_ID, FIX_HEARTBEAT_TIMEOUT);
        assertState(DISCONNECTED);
    }

    @Test
    public void shouldLogoutOnLogonWithLowSequenceNumberAndWithAsyncProxy()
    {
        when(sessionProxy.isAsync()).thenReturn(true);

        session().lastSentMsgSeqNum(10);
        session().lastReceivedMsgSeqNum(5);

        readyForLogon();
        onLogon(2);

        verify(sessionProxy).sendLowSequenceNumberLogout(
            11, 6, 2, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);

        // Initially we wait for the logout to be roundtripped via a cluster
        assertState(AWAITING_ASYNC_PROXY_LOGOUT);

        // Roundtrip completed, so we disconnect the session
        session.onSessionWriterLogout();
        verify(sessionProxy).sendRequestDisconnect(CONNECTION_ID, MSG_SEQ_NO_TOO_LOW);
        assertState(DISCONNECTED);
    }

    private void verifyLogoutOnlyOnce()
    {
        verify(sessionProxy, times(1)).sendLogout(
            anyInt(), eq(SEQUENCE_INDEX), eq(NO_LAST_MSG_SEQ_NUM_PROCESSED), any());
    }

    @Test
    public void shouldLogoutAndDisconnectUponTimeoutWhenBackPressured()
    {
        shouldSendTestRequestUponTimeout();

        twoHeartBeatIntervalsPass();

        backPressureLogout();

        poll();

        assertState(LOGGING_OUT_AND_DISCONNECTING);

        poll();

        verifyLogout(9, times(2));
    }

    @Test
    public void shouldKeepCustomLogoutTextWhenBackPressured()
    {
        shouldSendTestRequestUponTimeout();

        fakeClock.advanceSeconds(1);

        backPressureLogout();

        final byte[] logoutTextBytes = "customText".getBytes();
        session().logoutAndDisconnect(logoutTextBytes);

        assertState(LOGGING_OUT_AND_DISCONNECTING);

        poll();

        assertState(DISCONNECTING);

        verifyLogout(8, times(2), logoutTextBytes);
    }

    @Test
    public void shouldSuppressTimeoutWhenMessageReceived()
    {
        givenActive();
        session().lastReceivedMsgSeqNum(9);

        onMessage(10);

        fakeClock.advanceSeconds(1);

        poll();
        onMessage(11);

        fakeClock.advanceSeconds(1);

        poll();

        verifyConnected();
    }

    @Test
    public void shouldRequestResendIfHighSeqNo()
    {
        givenActive();

        // when high sequence number message
        onMessage(3);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        // Receives gapfill
        session().onSequenceReset(1, 4, true, true, POSITION);
        assertState(ACTIVE);
        assertNotAwaitingResend();
    }

    @Test
    public void shouldRequestResendIfHighSeqNoClosedResendInterval()
    {
        session().closedResendInterval(true);

        givenActive();

        // when high sequence number message
        onMessage(3);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 3, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
    }

    @Test
    public void shouldSendWhenAwaitingResend()
    {
        givenActive();

        // when high sequence number message
        onMessage(3);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        sendTestRequest(100);
    }

    @Test
    public void shouldResendRequestShorterThanResendRequestChunkSizeWhenClosedResendInterval()
    {
        givenActive();
        session().closedResendInterval(true);
        session().resendRequestChunkSize(5);

        // when high sequence number message
        onMessage(3);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 3, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
    }

    @Test
    public void shouldResendRequestInfinityWithThanResendRequestChunkSizeWhenIntervalShorter()
    {
        givenActive();
        session().resendRequestChunkSize(5);

        // when high sequence number message
        onMessage(3);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
    }

    @Test
    public void shouldNotResendRequestLongerThanResendRequestChunkSize()
    {
        givenActive();
        fakeClock.advanceMilliSeconds(10);
        session().resendRequestChunkSize(2);

        // when high sequence number message
        onMessage(4);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 2, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
        reset(sessionProxy);

        onPossDupMessage(1);
        onPossDupMessage(2);

        verify(sessionProxy).sendResendRequest(2, 3, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        onPossDupMessage(3);
        onPossDupMessage(4);
        assertState(ACTIVE);
        assertNotAwaitingResend();
    }

    @Test
    public void shouldNotResendRequestLongerThanResendRequestChunkSizeWhenClosedResendInterval()
    {
        givenActive();
        fakeClock.advanceMilliSeconds(10);
        session().closedResendInterval(true);
        session().resendRequestChunkSize(2);

        // when high sequence number message
        onMessage(4);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 2, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
        reset(sessionProxy);

        onPossDupMessage(1);
        onPossDupMessage(2);

        verify(sessionProxy).sendResendRequest(2, 3, 4, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        onPossDupMessage(3);
        onPossDupMessage(4);
        assertState(ACTIVE);
        assertNotAwaitingResend();
    }

    @Test
    public void shouldNotResendRequestLongerThanResendRequestChunkSizeWithGapfillReplies()
    {
        givenActive();
        fakeClock.advanceMilliSeconds(10);
        session().resendRequestChunkSize(2);

        // when high sequence number message
        onMessage(4);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 2, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
        reset(sessionProxy);

        onGapFill(1, 3);

        verify(sessionProxy).sendResendRequest(2, 3, 0, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        onGapFill(3, 5);
        assertState(ACTIVE);
        assertNotAwaitingResend();
    }

    @Test
    public void shouldNotResendRequestLongerThanResendRequestChunkSizeWhenClosedResendIntervalWithGapfillReplies()
    {
        givenActive();
        fakeClock.advanceMilliSeconds(10);
        session().closedResendInterval(true);
        session().resendRequestChunkSize(2);

        // when high sequence number message
        onMessage(4);

        // then sends a resend request
        verify(sessionProxy).sendResendRequest(1, 1, 2, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();
        reset(sessionProxy);

        onGapFill(1, 3);

        verify(sessionProxy).sendResendRequest(2, 3, 4, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        assertState(ACTIVE);
        assertAwaitingResend();

        onGapFill(3, 5);
        assertState(ACTIVE);
        assertNotAwaitingResend();
    }

    @Test
    public void shouldDisconnectIfBeginStringIsInvalidAtLogon()
    {
        onBeginString(true);
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldDisconnectIfBeginStringIsInvalid()
    {
        onBeginString(false);
        incorrectBeginStringLogout(1);
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldDisconnectIfBeginStringIsInvalidWhenBackPressured()
    {
        when(sessionProxy.sendIncorrectBeginStringLogout(1, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED))
            .thenReturn(BACK_PRESSURED, POSITION);
        backPressureDisconnect();

        onBeginString(false);

        assertState(DISCONNECTING);

        poll();
        poll();

        incorrectBeginStringLogout(2);
        verifyDisconnect(times(2));
    }

    @Test
    public void shouldStartLogonBasedSequenceNumberReset()
    {
        sequenceNumbersAreThreeAndActive();

        session().tryResetSequenceNumbers();

        verifySetsSentSequenceNumbersToTwo(SEQUENCE_INDEX + 1);
    }

    public void shouldStartAcceptLogonBasedSequenceNumberResetWhenSequenceNumberIsOne(final int sequenceIndex)
    {
        onLogon(HEARTBEAT_INTERVAL_IN_S, 1, true);

        verifySetsSequenceNumbersToTwo(sequenceIndex);
    }

    @Test
    public void shouldComplyWithLogonBasedSequenceNumberReset()
    {
        int sequenceIndex = SEQUENCE_INDEX;
        for (final SessionState state : SessionState.values())
        {
            Mockito.reset(sessionProxy);

            session().state(state);
            sequenceNumbersAreThree();

            onLogon(HEARTBEAT_INTERVAL_IN_S, 1, true);

            session().poll(100_000_000);

            sequenceIndex++;
            verifySetsSequenceNumbersToTwo(sequenceIndex);
            assertSequenceIndexIs(sequenceIndex);
        }
    }

    @Test
    public void shouldTerminateLogonBasedSequenceNumberReset()
    {
        shouldStartLogonBasedSequenceNumberReset();

        onLogon(HEARTBEAT_INTERVAL_IN_S, 1, true);

        verifyNoFurtherMessages();
        assertSequenceIndexIs(SEQUENCE_INDEX + 1);
    }

    @Test
    public void shouldCorrectEncodeMessageTimestamps()
    {
        givenActive();

        final String message = sendTestRequest(0);

        assertThat(message, containsString(":00.000\001"));
    }

    @Test
    public void shouldCorrectEncodeMessageTimestampsRepeatedly()
    {
        givenActive();

        final long nonSecondDurationInMs = 111;
        final long remainderInMs = SECONDS.toMillis(1) - nonSecondDurationInMs;

        final String firstMessage = sendTestRequest(nonSecondDurationInMs);
        final String secondMessage = sendTestRequest(remainderInMs);

        assertThat(firstMessage, containsString(":00.111\001"));
        assertThat(secondMessage, containsString(":01.000\001"));
    }

    // See http://www.fixtradingcommunity.org/pg/discussions/topicpost/164720/fix-4x-sessionlevel-protocol-tests
    // 1d_InvalidLogonBadSendingTime.def
    @Test
    public void shouldDisconnectIfInvalidSendingTimeAtLogon()
    {
        logonWithInvalidSendingTime(CONTINUE);

        verifySendingTimeAccuracyProblem(1);

        verifyDoesNotNotifyLoginListener();
    }

    @Test
    public void shouldDisconnectIfInvalidSendingTimeAtLogonWhenBackPressured()
    {
        when(sessionProxy.sendRejectWhilstNotLoggedOn(anyInt(), any(), eq(SEQUENCE_INDEX), anyInt()))
            .thenReturn(BACK_PRESSURED, POSITION);

        logonWithInvalidSendingTime(ABORT);

        logonWithInvalidSendingTime(CONTINUE);

        verifySendingTimeAccuracyProblem(2);

        verifyDoesNotNotifyLoginListener();
    }

    @Test
    public void shouldValidateSendingTimeNotTooLate()
    {
        givenActive();
        session().lastSentMsgSeqNum(1);

        messageWithWeirdTime(sendingTime() + TWO_MINUTES);

        verifySendingTimeProblem();
        verifySendingTimeAccuracyLogout();
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldValidateSendingTimeNotTooEarly()
    {
        givenActive();
        session().lastSentMsgSeqNum(1);

        messageWithWeirdTime(sendingTime() - TWO_MINUTES);

        verifySendingTimeProblem();
        verifySendingTimeAccuracyLogout();
        verifyDisconnect(times(1));
    }

    @Test
    public void shouldEncodeAsciiBufferHeaderExternally()
    {
        final char[] testReqId = "MyTestReqId".toCharArray();
        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(new byte[512]);
        final ExampleMessageDecoder testDecoder = new ExampleMessageDecoder();
        final ExampleMessageEncoder testEncoder = new ExampleMessageEncoder();
        final Session session = session();

        // set our encoder field
        testEncoder.testReqID(testReqId);

        // setup our session for the first encoding
        long time = fakeClock.time();
        session.lastSentMsgSeqNum(0);

        // encode the header from the encoder
        final int encodedSentSeqNum1 = session.prepare(testEncoder.header());
        assertEquals(1, encodedSentSeqNum1); // expect to be 1 more than last sent seq num

        // write our encoder to our buffer (header is not encoded from session)
        final long result = testEncoder.encode(asciiBuffer, 0);
        final int offset = Encoder.offset(result);
        final int length = Encoder.length(result);

        // decode the ascii buffer and make sure all the fields are correctly set
        testDecoder.decode(asciiBuffer, offset, length);
        assertArrayEquals(testReqId, testDecoder.testReqID());
        assertEquals(1, testDecoder.header().msgSeqNum());
        final String timeAsString1 = new String(timestampEncoder.buffer(), 0, timestampEncoder.encode(time));
        assertEquals(timeAsString1, testDecoder.header().sendingTimeAsString());

        // update the session internal state
        session.lastSentMsgSeqNum(1); // increase last seen sent seq number
        fakeClock.advanceSeconds(1);
        time = fakeClock.time();

        // encode the header of the encoder again with the new session state
        final SessionHeaderEncoder headerEncoder = testEncoder.header();
        final int encodedSentSeqNum2 = session.prepare(headerEncoder);
        assertEquals(2, encodedSentSeqNum2);

        // encode the header of the buffer with the new session state
        headerEncoder.startMessage(asciiBuffer, 0);

        // decode it to make sure all the fields are correctly set
        testDecoder.decode(asciiBuffer, offset, length);
        assertArrayEquals(testReqId, testDecoder.testReqID());
        assertEquals(2, testDecoder.header().msgSeqNum());
        final String timeAsString2 = new String(timestampEncoder.buffer(), 0, timestampEncoder.encode(time));
        assertEquals(timeAsString2, testDecoder.header().sendingTimeAsString());
        assertNotEquals(timeAsString1, timeAsString2); // make sure time has moved forward
    }

    @Test
    public void shouldTakeForcedHeartbeatConfigurationIntoAccount()
    {
        forcedHeartbeatIntervalInS = 5;
        assertForcedHeartbeatInterval();

        onLogon(1);
        assertForcedHeartbeatInterval();

        session().heartbeatIntervalInS(3);
        assertForcedHeartbeatInterval();
    }

    @Test
    public void shouldTakeForcedHeartbeatConfigurationIntoAccountForResetBasedLogons()
    {
        forcedHeartbeatIntervalInS = 5;
        givenActive();

        session().tryResetSequenceNumbers();
        verifySendLogon(1, true);

        onLogon(HEARTBEAT_INTERVAL_IN_S, 1, true);
        assertForcedHeartbeatInterval();
    }

    void assertForcedHeartbeatInterval()
    {
        assertEquals(5_000, session().heartbeatIntervalInMs());
    }

    private void verifySendingTimeAccuracyLogout()
    {
        verify(sessionProxy, times(1)).sendLogout(3, SEQUENCE_INDEX,
            SENDINGTIME_ACCURACY_PROBLEM.representation(), NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    private void verifySendingTimeAccuracyProblem(final int times)
    {
        verify(sessionProxy, times(times)).sendRejectWhilstNotLoggedOn(
            1, SENDINGTIME_ACCURACY_PROBLEM, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    private void logonWithInvalidSendingTime(final Action expectedAction)
    {
        fakeClock.advanceMilliSeconds(2 * SENDING_TIME_WINDOW);

        final Action action = session().onLogon(
            HEARTBEAT_INTERVAL_IN_S,
            1,
            1,
            UNKNOWN, null, null, false, false, false,
            POSITION,
            DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT,
            MISSING_INT);

        assertEquals(expectedAction, action);
    }

    private String sendTestRequest(final long nonSecondDurationInMs)
    {
        testRequest.reset();
        testRequest.testReqID("testReqID");
        fakeClock.advanceMilliSeconds(nonSecondDurationInMs);
        session().trySend(testRequest);
        return getSentMessage();
    }

    private String getSentMessage()
    {
        final MutableAsciiBuffer buffer = (MutableAsciiBuffer)this.bufferCaptor.getValue();
        return buffer.getAscii(offsetCaptor.getValue(), lengthCaptor.getValue());
    }

    private void verifySetsSequenceNumbersToTwo(final int sequenceIndex)
    {
        verifySetsSentSequenceNumbersToTwo(sequenceIndex);
        assertEquals(1, session().lastReceivedMsgSeqNum());
    }

    private void verifySetsSentSequenceNumbersToTwo(final int sequenceIndex)
    {
        verifySendLogon(sequenceIndex, true);
        assertEquals(1, session().lastSentMsgSeqNum());
        verifyNoFurtherMessages();
    }

    void verifySendLogon(final int sequenceIndex, final boolean resetSeqNumFlag)
    {
        verify(sessionProxy).sendLogon(
            eq(1), eq(HEARTBEAT_INTERVAL_IN_S), any(), any(), eq(resetSeqNumFlag), eq(sequenceIndex), anyInt(),
            any(), anyInt());
    }

    private void sequenceNumbersAreThreeAndActive()
    {
        givenActive();
        sequenceNumbersAreThree();
    }

    private void sequenceNumbersAreThree()
    {
        session().lastReceivedMsgSeqNum(3).lastSentMsgSeqNum(3);
    }

    private void incorrectBeginStringLogout(final int times)
    {
        verify(sessionProxy, times(times)).sendIncorrectBeginStringLogout(
            1, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    private void heartbeatSentAfterInterval(
        final int heartbeatInterval,
        final int recvMsgSeqNo,
        final boolean backPressured)
    {
        if (backPressured)
        {
            when(sessionProxy.sendHeartbeat(anyInt(), eq(SEQUENCE_INDEX), anyInt()))
                .thenReturn(BACK_PRESSURED, POSITION);
        }

        final int sentMsgSeqNo = nextMsgSeqNum();

        fakeClock.advanceSeconds(heartbeatInterval + DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S);

        onMessage(recvMsgSeqNo);

        fakeClock.advanceSeconds(1);

        poll();

        if (backPressured)
        {
            poll();
        }

        verify(sessionProxy, retry(backPressured))
            .sendHeartbeat(sentMsgSeqNo, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED);
        reset(sessionProxy);
    }

    private VerificationMode retry(final boolean backPressured)
    {
        return times(backPressured ? 2 : 1);
    }

    public void verifyDisconnect(final VerificationMode times)
    {
        verify(sessionProxy, times).sendRequestDisconnect(eq(CONNECTION_ID), any());
        assertState(DISCONNECTED);
    }

    private void backPressureDisconnect()
    {
        when(sessionProxy.sendRequestDisconnect(eq(CONNECTION_ID), any())).thenReturn(BACK_PRESSURED, POSITION);
    }

    protected void givenActive()
    {
        session().state(ACTIVE);
    }

    public void verifyLogout(final int msgSeqNo, final VerificationMode times)
    {
        verifyLogout(msgSeqNo, times, null);
    }

    public void verifyLogout(final int msgSeqNo, final VerificationMode times, final byte[] text)
    {
        verify(sessionProxy, times).sendLogout(msgSeqNo, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED, text);
    }

    public void assertState(final SessionState state)
    {
        assertEquals(state, session().state());
    }

    public Action onLogon(final int msgSeqNo)
    {
        return onLogon(HEARTBEAT_INTERVAL_IN_S, msgSeqNo, false);
    }

    private Action onLogon(final int heartbeatIntervalInS, final int msgSeqNo, final boolean resetSeqNumFlag)
    {
        final String username = null;
        final String password = null;
        return session().onLogon(
            heartbeatIntervalInS,
            msgSeqNo,
            fakeClock.time(),
            UNKNOWN,
            username,
            password,
            false,
            resetSeqNumFlag,
            false,
            POSITION,
            DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT,
            MISSING_INT);
    }

    protected Action onMessage(final int msgSeqNo)
    {
        return onMessage(msgSeqNo, false, UNKNOWN);
    }

    protected Action onPossDupMessage(final int msgSeqNo)
    {
        return onMessage(msgSeqNo, true, sendingTime());
    }

    private Action onMessage(final int msgSeqNo, final boolean isPossDupOrResend, final long origSendingTime)
    {
        return session().onMessage(
            msgSeqNo, MSG_TYPE_CHARS, sendingTime(), origSendingTime, isPossDupOrResend, isPossDupOrResend, POSITION);
    }

    protected long sendingTime()
    {
        return fakeClock.time() - 1;
    }

    protected void onLogout()
    {
        assertEquals(CONTINUE, session().onLogout(1, sendingTime(), UNKNOWN, false, POSITION));
    }

    protected void verifySendingTimeProblem()
    {
        verify(sessionProxy).sendReject(
            2,
            2,
            52,
            MSG_TYPE_CHARS,
            MSG_TYPE_CHARS.length,
            SENDINGTIME_ACCURACY_PROBLEM.representation(),
            SEQUENCE_INDEX,
            NO_LAST_MSG_SEQ_NUM_PROCESSED);
    }

    protected void messageWithWeirdTime(final long sendingTime)
    {
        session().onMessage(2, MSG_TYPE_CHARS, sendingTime, UNKNOWN, false, false, POSITION);
    }

    protected void onBeginString(final boolean isLogon)
    {
        final char[] beginString = "FIX.3.9 ".toCharArray();
        final boolean valid = session().onBeginString(beginString, beginString.length - 1, isLogon);
        assertFalse(valid);
    }

    protected void twoHeartBeatIntervalsPass()
    {
        fakeClock.advanceSeconds(SESSION_TIMEOUT * 2);
    }

    protected void verifyConnected()
    {
        verify(sessionProxy, never()).sendRequestDisconnect(CONNECTION_ID, APPLICATION_DISCONNECT);
    }

    protected void verifyCanRoundtripTestMessage()
    {
        final char[] testReqId = "Hello".toCharArray();
        final int testReqIdLength = 5;

        session().onTestRequest(
            4, testReqId, testReqIdLength, sendingTime(), UNKNOWN, false, false, POSITION);
        verify(sessionProxy).sendHeartbeat(anyInt(), eq(testReqId), eq(testReqIdLength), eq(SEQUENCE_INDEX), anyInt());
        verifyConnected();
    }

    protected void poll()
    {
        session().poll(nanoClock.nanoTime());
    }

    protected abstract Session newSession();

    protected Session session()
    {
        if (session == null)
        {
            session = newSession();

            verify(sessionProxy).fixDictionary(any());
            verify(sessionProxy).connectionId(CONNECTION_ID);
        }

        return session;
    }

    protected void verifyNotifiesLoginListener()
    {
        verifyNotifiesLoginListener(times(1));
    }

    protected void verifyDoesNotNotifyLoginListener()
    {
        verifyNotifiesLoginListener(never());
    }

    protected abstract void readyForLogon();

    void verifyNoFurtherMessages()
    {
        verifyNoMoreInteractions(sessionProxy);
    }

    private void assertSequenceIndexIs(final int sequenceIndex)
    {
        assertEquals(sequenceIndex, session().sequenceIndex());
    }

    private void assertAwaitingResend()
    {
        assertTrue("Session is not awaiting resend", session().awaitingResend());
    }

    private void assertAwaitingHeartbeat()
    {
        assertTrue("Session is not awaiting heartbeat", session().awaitingHeartbeat());
    }

    private void assertNotAwaitingResend()
    {
        assertFalse("Session is awaiting resend", session().awaitingResend());
    }

    private void onGapFill(final int msgSeqNo, final int newSeqNo)
    {
        session().onSequenceReset(msgSeqNo, newSeqNo, true, false, POSITION);
    }

    private void verifyNotifiesLoginListener(final VerificationMode verificationMode)
    {
        verify(fixSessionOwner, verificationMode).onLogon(any());
    }
}
