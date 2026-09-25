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
package uk.co.real_logic.artio.library;

import io.aeron.Subscription;
import io.aeron.driver.DutyCycleTracker;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.framer.FakeEpochClock;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.messages.ControlNotificationDecoder.SessionsDecoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.InternalSession;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.timing.LibraryTimers;

import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.LivenessDetector.SEND_INTERVAL_FRACTION;
import static uk.co.real_logic.artio.library.SessionConfiguration.*;
import static uk.co.real_logic.artio.messages.ConnectionType.ACCEPTOR;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.ENGINE;
import static uk.co.real_logic.artio.messages.SessionState.ACTIVE;

public class LibraryPollerTest
{
    private static final long CONNECTION_ID = 2;
    private static final long SESSION_ID = 3;
    private static final long OTHER_CONNECTION_ID = 4;
    private static final long OTHER_SESSION_ID = 5;
    private static final long CONNECT_ATTEMPT_TIMEOUT = DEFAULT_REPLY_TIMEOUT_IN_MS / SEND_INTERVAL_FRACTION;

    private static final int LAST_SENT_SEQUENCE_NUMBER = 1;
    private static final int LAST_RECEIVED_SEQUENCE_NUMBER = 1;
    private static final int HEARTBEAT_INTERVAL_IN_S = 1;
    private static final int REPLY_TO_ID = 0;

    private static final String FIRST_CHANNEL = "1";
    private static final String LEADER_CHANNEL = "2";
    private static final List<String> CLUSTER_CHANNELS = asList(FIRST_CHANNEL, LEADER_CHANNEL, "3");
    private static final int SEQUENCE_INDEX = 0;

    private final ArgumentCaptor<Session> session = ArgumentCaptor.forClass(Session.class);
    private final LibraryConnectHandler connectHandler = mock(LibraryConnectHandler.class);
    private final SessionHandler sessionHandler = mock(SessionHandler.class);
    private final SessionAcquireHandler sessionAcquireHandler = mock(SessionAcquireHandler.class);
    private final GatewayPublication outboundPublication = mock(GatewayPublication.class);
    private final Subscription inboundSubscription = mock(Subscription.class);
    private final LibraryTransport transport = mock(LibraryTransport.class);
    private final FixCounters counters = mock(FixCounters.class);
    private final FixLibrary fixLibrary = mock(FixLibrary.class);
    private final String address = "localhost:1234";
    private final FakeEpochClock clock = new FakeEpochClock();

    private LibraryPoller library;

    @BeforeEach
    public void setUp()
    {
        when(transport.outboundPublication()).thenReturn(outboundPublication);
        when(transport.inboundSubscription()).thenReturn(inboundSubscription);

        when(counters.getLibraryDutyCycleTracker(anyLong())).thenReturn(mock(DutyCycleTracker.class));
        when(counters.receivedMsgSeqNo(anyLong(), anyLong())).thenReturn(mock(AtomicCounter.class));
        when(counters.sentMsgSeqNo(anyLong(), anyLong())).thenReturn(mock(AtomicCounter.class));

        when(sessionAcquireHandler.onSessionAcquired(session.capture(), any())).thenReturn(sessionHandler);
    }

    @Test
    public void shouldNotifyClientOfSessionTimeouts()
    {
        connectToSingleEngine();

        manageConnection(CONNECTION_ID, SESSION_ID);

        library.onControlNotification(libraryId(), ENGINE, noSessionIds());

        verify(sessionHandler).onTimeout(libraryId(), session.getValue());
    }

    @Test
    public void shouldNotifyClientsOfRelevantSessionTimeouts()
    {
        connectToSingleEngine();

        manageConnection(CONNECTION_ID, SESSION_ID);
        manageConnection(OTHER_CONNECTION_ID, OTHER_SESSION_ID);

        library.onControlNotification(libraryId(), ENGINE, hasOtherSessionId());

        final Session firstSession = session.getAllValues().get(0);
        verify(sessionHandler).onTimeout(libraryId(), firstSession);
    }

    @Test
    public void shouldDisconnectSingleEngineAfterTimeout()
    {
        connectToSingleEngine();

        disconnectDueToTimeout();
    }

    @Test
    public void shouldReconnectToSingleEngineAfterTimeoutOnHeartbeat()
    {
        shouldDisconnectSingleEngineAfterTimeout();

        reconnectAfterTimeout();
    }

    @Test
    public void shouldRepeatedlyReconnectToSingleEngineAfterTimeoutOnHeartbeat()
    {
        shouldReconnectToSingleEngineAfterTimeoutOnHeartbeat();

        disconnectDueToTimeout();

        reconnectAfterTimeout();

        disconnectDueToTimeout();

        reconnectAfterTimeout();

        disconnectDueToTimeout();

        reconnectAfterTimeout();
    }

    @Test
    public void shouldResendConnectToSameEngineWhenConnectionTimesOut()
    {
        setupAndConnectToFirstChannel();

        pollTwice();

        clock.advanceMilliSeconds(CONNECT_ATTEMPT_TIMEOUT + 1);

        poll();

        sendsLibraryConnect(times(1));

        doesNotAttemptConnectTo(LEADER_CHANNEL);
    }

    @Test
    public void shouldStopResendingConnectToSameEngineAfterHeartbeat()
    {
        shouldResendConnectToSameEngineWhenConnectionTimesOut();

        reset(outboundPublication);

        reconnectAfterTimeout();

        clock.advanceMilliSeconds(CONNECT_ATTEMPT_TIMEOUT + 1);

        pollTwice();

        sendsLibraryConnect(never());
    }

    @Test
    public void shouldAttemptNextEngineWhenEngineTimesOut()
    {
        setupAndConnectToFirstChannel();

        pollTwice();

        clock.advanceMilliSeconds(DEFAULT_REPLY_TIMEOUT_IN_MS + 1);

        poll();

        attemptToConnectTo(LEADER_CHANNEL);
    }

    @Test
    public void shouldNotAttemptNextEngineUntilEngineTimesOut()
    {
        setupAndConnectToFirstChannel();

        pollTwice();

        clock.advanceMilliSeconds(DEFAULT_REPLY_TIMEOUT_IN_MS - 1);

        pollTwice();

        doesNotAttemptConnectTo(LEADER_CHANNEL);
    }

    private void sendsLibraryConnect(final VerificationMode times)
    {
        verify(outboundPublication, times)
            .saveLibraryConnect(eq(libraryId()), anyString(), anyLong());
    }

    private void pollTwice()
    {
        poll();

        poll();
    }

    private void poll()
    {
        library.poll(1);
    }

    private void doesNotAttemptConnectTo(final String channel)
    {
        verify(transport, never()).initStreams(channel);
    }

    private void setupAndConnectToFirstChannel()
    {
        newLibraryPoller(CLUSTER_CHANNELS);

        library.startConnecting();

        poll();

        attemptToConnectTo(FIRST_CHANNEL);
    }

    private void disconnectDueToTimeout()
    {
        advanceBeyondReplyTimeout();

        pollTwice();

        assertFalse(library.isConnected(), "Library failed to timeout");
    }

    private void reconnectAfterTimeout()
    {
        receiveOneApplicationHeartbeat();

        pollTwice();

        assertTrue(library.isConnected(), "Library still timed out");
    }

    private void advanceBeyondReplyTimeout()
    {
        clock.advanceMilliSeconds(DEFAULT_REPLY_TIMEOUT_IN_MS + 1);
    }

    private int libraryId()
    {
        return library.libraryId();
    }

    private void attemptToConnectTo(final String... channels)
    {
        final InOrder inOrder = inOrder(transport, outboundPublication);
        for (final String channel : channels)
        {
            inOrder.verify(transport).initStreams(channel);
            inOrder.verify(transport).inboundSubscription();
            inOrder.verify(transport).inboundPublication();
            inOrder.verify(transport).outboundPublication();
            inOrder.verify(outboundPublication)
                   .saveLibraryConnect(eq(libraryId()), anyString(), anyLong());
        }
        verifyNoMoreInteractions(transport);
        reset(outboundPublication);
    }

    private void connectToSingleEngine()
    {
        receiveOneApplicationHeartbeat();

        newLibraryPoller(singletonList(IPC_CHANNEL));

        library.startConnecting();

        pollTwice();

        assertTrue(library.isConnected(), "Failed to connect");
    }

    private void receiveOneApplicationHeartbeat()
    {
        whenPolled()
            .then(replyWithApplicationHeartbeat())
            .then(noReply());
    }

    private Answer<Integer> replyWithApplicationHeartbeat()
    {
        return (inv) ->
        {
            library.onApplicationHeartbeat(libraryId(), ApplicationHeartbeatDecoder.TEMPLATE_ID, 0);
            return 1;
        };
    }

    private Answer<Integer> noReply()
    {
        return inv -> 0;
    }

    private void newLibraryPoller(final List<String> libraryAeronChannels)
    {
        library = new LibraryPoller(
            new LibraryConfiguration()
                .libraryAeronChannels(libraryAeronChannels)
                .sessionAcquireHandler(sessionAcquireHandler)
                .libraryConnectHandler(connectHandler)
                .conclude(),
            new LibraryTimers(clock::time, mock(AtomicCounter.class)),
            counters,
            transport,
            fixLibrary,
            clock,
            LangUtil::rethrowUnchecked);
    }

    private OngoingStubbing<Integer> whenPolled()
    {
        return when(inboundSubscription.controlledPoll(any(), anyInt()));
    }

    private void manageConnection(final long connectionId, final long sessionId)
    {
        library.onManageSession(libraryId(),
            connectionId,
            sessionId,
            LAST_SENT_SEQUENCE_NUMBER,
            LAST_RECEIVED_SEQUENCE_NUMBER,
            SessionStatus.SESSION_HANDOVER,
            SlowStatus.NOT_SLOW,
            ACCEPTOR,
            ACTIVE,
            HEARTBEAT_INTERVAL_IN_S,
            DEFAULT_CLOSED_RESEND_INTERVAL,
            NO_RESEND_REQUEST_CHUNK_SIZE,
            DEFAULT_SEND_REDUNDANT_RESEND_REQUESTS,
            DEFAULT_ENABLE_LAST_MSG_SEQ_NUM_PROCESSED,
            REPLY_TO_ID,
            SEQUENCE_INDEX,
            InternalSession.INITIAL_AWAITING_HEARTBEAT,
            InternalSession.INITIAL_LAST_RESENT_MSG_SEQ_NO,
            InternalSession.INITIAL_LAST_RESEND_CHUNK_MSG_SEQ_NUM,
            InternalSession.INITIAL_END_OF_RESEND_REQUEST_RANGE,
            InternalSession.INITIAL_AWAITING_HEARTBEAT,
            LAST_RECEIVED_SEQUENCE_NUMBER, SEQUENCE_INDEX,
            Session.UNKNOWN_TIME, Session.UNKNOWN_TIME,
            "ABC",
            "",
            "",
            "DEF",
            "",
            "", address,
            "",
            "",
            FixDictionary.findDefault(),
            MetaDataStatus.NO_META_DATA,
            new UnsafeBuffer(new byte[0]),
            0,
            0,
            CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT,
            0);
    }

    private ControlNotificationDecoder hasOtherSessionId()
    {
        final SessionsDecoder sessionsDecoder = mock(SessionsDecoder.class);
        when(sessionsDecoder.hasNext()).thenReturn(true, false);
        when(sessionsDecoder.sessionId()).thenReturn(OTHER_SESSION_ID);
        return controlNotification(sessionsDecoder);
    }

    private ControlNotificationDecoder noSessionIds()
    {
        final SessionsDecoder sessionsDecoder = mock(SessionsDecoder.class);
        when(sessionsDecoder.hasNext()).thenReturn(false);
        return controlNotification(sessionsDecoder);
    }

    private ControlNotificationDecoder controlNotification(final SessionsDecoder sessionsDecoder)
    {
        final ControlNotificationDecoder controlNotificationDecoder = mock(ControlNotificationDecoder.class);
        when(controlNotificationDecoder.sessions()).thenReturn(sessionsDecoder);
        final ControlNotificationDecoder.DisconnectedSessionsDecoder disconnectedSessions =
            mock(ControlNotificationDecoder.DisconnectedSessionsDecoder.class);
        when(controlNotificationDecoder.disconnectedSessions()).thenReturn(disconnectedSessions);
        return controlNotificationDecoder;
    }
}
