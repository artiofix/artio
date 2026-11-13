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

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.messages.ConnectionType;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_RESEND_REQUEST_CONTROLLER;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_SESSION_BUFFER_SIZE;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS;
import static uk.co.real_logic.artio.messages.SessionState.*;
import static uk.co.real_logic.artio.session.DirectSessionProxy.NO_LAST_MSG_SEQ_NUM_PROCESSED;
import static uk.co.real_logic.artio.session.Session.UNKNOWN_TIME;

public class InitiatorSessionTest extends AbstractSessionTest
{

    @Override
    protected Session newSession()
    {
        final InternalSession session = new InternalSession(HEARTBEAT_INTERVAL_IN_S,
            CONNECTION_ID,
            nanoClock,
            CONNECTED,
            false,
            sessionProxy,
            mock(GatewayPublication.class),
            mockPublication,
            idStrategy,
            SENDING_TIME_WINDOW,
            mockReceivedMsgSeqNo,
            mockSentMsgSeqNo,
            LIBRARY_ID,
            1,
            SEQUENCE_INDEX,
            DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS,
            new MutableAsciiBuffer(new byte[DEFAULT_SESSION_BUFFER_SIZE]),
            false,
            SessionCustomisationStrategy.none(),
            messageInfo,
            fakeEpochFractionClock,
            ConnectionType.INITIATOR,
            DEFAULT_RESEND_REQUEST_CONTROLLER,
            forcedHeartbeatIntervalInS,
            disableHeartbeatRepliesToTestRequests,
            true,
            new InternalSession.Formatters());
        session.fixDictionary(makeDictionary());
        session.sessionProcessHandler(fixSessionOwner);
        return session;
    }

    @Test
    public void shouldInitiallyBeConnected()
    {
        assertEquals(CONNECTED, session().state());
    }

    @Test
    public void shouldActivateUponLogonResponse()
    {
        readyForLogon();

        assertEquals(CONTINUE, onLogon(1));

        assertState(ACTIVE);
        verify(sessionProxy).seqNumResetRequested();
        verifyNoFurtherMessages();
        verifyNotifiesLoginListener();
        assertHasLogonTime();
    }

    @Test
    public void shouldAttemptLogonWhenConnected()
    {
        session().id(SESSION_ID);
        session().poll(0);

        verifyLogon();

        assertEquals(1, session().lastSentMsgSeqNum());
    }

    @Test
    public void shouldAttemptLogonOnlyOnce()
    {
        session().id(SESSION_ID);
        session().poll(0);

        session().poll(10_000_000);

        session().poll(20_000_000);

        verifyLogon();
    }

    @Test
    public void shouldNotifyGatewayWhenLoggedIn()
    {
        session().state(SENT_LOGON);

        assertEquals(CONTINUE, onLogon(1));

        verifyNotifiesLoginListener();
        assertHasLogonTime();
    }

    @Test
    public void shouldNotifyGatewayWhenLoggedInOnce()
    {
        session().state(SENT_LOGON);

        assertEquals(CONTINUE, onLogon(1));

        assertEquals(CONTINUE, onLogon(2));

        verifyNotifiesLoginListener();
        assertHasLogonTime();
    }

    @Test
    public void shouldStartAcceptLogonBasedSequenceNumberResetWhenSequenceNumberIsOne()
    {
        shouldStartAcceptLogonBasedSequenceNumberResetWhenSequenceNumberIsOne(SEQUENCE_INDEX);
    }

    @Test
    public void shouldTakeForcedHeartbeatConfigurationIntoAccountWhenSendingInitialLogon()
    {
        forcedHeartbeatIntervalInS = 5;
        session().id(SESSION_ID);

        session().poll(0);

        verifyLogon();

        onLogon(1);
        assertForcedHeartbeatInterval();
    }

    @Test
    public void shouldDisconnectIfFirstMessageNotALogon()
    {
        session().id(SESSION_ID);
        session().poll(0);

        verifyLogon();

        onMessage(1);

        verifyDisconnect(times(1));
        verifyNoFurtherMessages();
    }

    private void verifyLogon()
    {
        verify(sessionProxy, times(1)).sendLogon(
            1, HEARTBEAT_INTERVAL_IN_S, null, null, false, SEQUENCE_INDEX, NO_LAST_MSG_SEQ_NUM_PROCESSED,
            null, MISSING_INT);
    }

    @Override
    protected void readyForLogon()
    {
        session().state(SENT_LOGON);
    }

    private void assertHasLogonTime()
    {
        assertTrue(session().lastLogonTimeInNs() != UNKNOWN_TIME);
    }
}
