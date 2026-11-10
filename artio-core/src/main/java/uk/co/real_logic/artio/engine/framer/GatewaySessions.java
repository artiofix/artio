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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochClock;
import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.AbstractAuthenticationProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static uk.co.real_logic.artio.engine.ConnectedSessionInfo.UNK_SESSION;

/**
 * Keeps track of which sessions managed by the gateway
 */
abstract class GatewaySessions
{
    protected final Long2LongHashMap sessionIdToLastLibraryId = new Long2LongHashMap(UNK_SESSION);
    protected final LongHashSet disconnectedSessionIds = new LongHashSet();
    protected final CharFormatter acquiredConnection = new CharFormatter("Gateway Acquired Connection %s");
    protected final List<GatewaySession> sessions = new ArrayList<>();
    protected final EpochClock epochClock;
    protected final GatewayPublication inboundPublication;
    protected final GatewayPublication outboundPublication;
    protected final SequenceNumberIndexReader sentSequenceNumberIndex;
    protected final SequenceNumberIndexReader receivedSequenceNumberIndex;
    protected ErrorHandler errorHandler;

    GatewaySessions(
        final EpochClock epochClock,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication,
        final ErrorHandler errorHandler,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex)
    {
        this.epochClock = epochClock;
        this.inboundPublication = inboundPublication;
        this.outboundPublication = outboundPublication;
        this.errorHandler = errorHandler;
        this.sentSequenceNumberIndex = sentSequenceNumberIndex;
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
    }

    static GatewaySession removeSessionByConnectionId(final long connectionId, final List<GatewaySession> sessions)
    {
        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final GatewaySession session = sessions.get(i);
            if (session.connectionId() == connectionId)
            {
                sessions.remove(i);
                return session;
            }
        }

        return null;
    }

    GatewaySession releaseBySessionId(final long sessionId)
    {
        final int index = indexBySessionId(sessionId);
        if (index < 0)
        {
            return null;
        }

        return sessions.remove(index);
    }

    GatewaySession sessionById(final long sessionId)
    {
        final int index = indexBySessionId(sessionId);
        if (index < 0)
        {
            return null;
        }

        return sessions.get(index);
    }

    private int indexBySessionId(final long sessionId)
    {
        final List<GatewaySession> sessions = this.sessions;

        return indexBySessionId(sessionId, sessions);
    }

    static int indexBySessionId(final long sessionId, final List<GatewaySession> sessions)
    {
        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final GatewaySession session = sessions.get(i);
            if (session.sessionId() == sessionId)
            {
                return i;
            }
        }

        return UNK_SESSION;
    }

    void releaseByConnectionId(final long connectionId)
    {
        final GatewaySession session = removeSessionByConnectionId(connectionId, sessions);
        if (session != null)
        {
            session.onDisconnectReleasedByOwner();
            session.close();

            final long sessionId = session.sessionId();
            if (sessionId != UNK_SESSION)
            {
                sessionIdToLastLibraryId.put(sessionId, session.lastLibraryId);
            }
        }
    }

    int pollSessions(final long timeInMs, final long timeInNs)
    {
        final List<GatewaySession> sessions = this.sessions;

        int eventsProcessed = 0;
        for (int i = 0, size = sessions.size(); i < size;)
        {
            final GatewaySession session = sessions.get(i);
            eventsProcessed += session.poll(timeInMs, timeInNs);
            if (session.hasDisconnected())
            {
                size--;
            }
            else
            {
                i++;
            }
        }
        return eventsProcessed;
    }

    List<GatewaySession> sessions()
    {
        return sessions;
    }

    private boolean lookupSequenceNumbers(final GatewaySession gatewaySession, final long requiredPosition)
    {
        final int aeronSessionId = outboundPublication.sessionId();
        final long initialPosition = outboundPublication.initialPosition();
        // At requiredPosition=initialPosition there won't be anything indexed, so indexedPosition will be -1
        if (requiredPosition > initialPosition)
        {
            final long indexedPosition = sentSequenceNumberIndex.indexedPosition(aeronSessionId);
            if (indexedPosition < requiredPosition)
            {
                return false;
            }
        }

        final long sessionId = gatewaySession.sessionId();
        final int lastSentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
        final int lastReceivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
        gatewaySession.acceptorSequenceNumbers(lastSentSequenceNumber, lastReceivedSequenceNumber);
        if (lastReceivedSequenceNumber != UNK_SESSION)
        {
            setLastSequenceResetTime(gatewaySession);
        }
        return true;
    }

    protected abstract void setLastSequenceResetTime(GatewaySession gatewaySession);

    // We put the gateway session in our list of sessions to poll in order to check engine level timeouts,
    // But we aren't actually acquiring the session.
    void track(final GatewaySession gatewaySession)
    {
        sessions.add(gatewaySession);
    }

    public LongHashSet findDisconnectedSessions(final int libraryId)
    {
        disconnectedSessionIds.clear();
        final Long2LongHashMap.EntryIterator it = sessionIdToLastLibraryId.entrySet().iterator();
        while (it.hasNext())
        {
            it.next();
            if (it.getLongValue() == libraryId)
            {
                disconnectedSessionIds.add(it.getLongKey());
            }
        }
        return disconnectedSessionIds;
    }

    public void removeDisconnectedSessions(final LongHashSet disconnectedSessions)
    {
        final LongHashSet.LongIterator it = disconnectedSessions.iterator();
        while (it.hasNext())
        {
            sessionIdToLastLibraryId.remove(it.nextValue());
        }
    }

    enum AuthenticationState
    {
        /** Initial State -> AUTHENTICATED, SAVING_REJECTED_LOGON_WITH_REPLY */
        PENDING,

        /** -> INDEXER_CATCHUP, ACCEPTED */
        AUTHENTICATED,
        /** -> ACCEPTED */
        INDEXER_CATCHUP,
        /** Terminal State */
        ACCEPTED,

        /** -> ENCODING_REJECT_MESSAGE */
        SAVING_REJECTED_LOGON_WITH_REPLY,
        /** -> SENDING_REJECT_MESSAGE */
        ENCODING_REJECT_MESSAGE,
        /** -> LINGERING_REJECT_MESSAGE */
        SENDING_REJECT_MESSAGE,
        /** -> REJECTED */
        LINGERING_REJECT_MESSAGE,

        /** -> REJECTED */
        SAVING_REJECTED_LOGON_NO_REPLY,
        /** Terminal State */
        REJECTED
    }

    enum SendRejectResult
    {
        INFLIGHT,
        BACK_PRESSURED,
        DISCONNECTED
    }

    protected abstract class PendingAcceptorLogon implements AbstractAuthenticationProxy, AcceptorLogonResult
    {
        private static final long NO_REQUIRED_POSITION = -1;

        protected final long connectionId;
        protected final TcpChannel channel;
        protected final Framer framer;
        protected final ReceiverEndPoint receiverEndPoint;

        protected volatile AuthenticationState state = AuthenticationState.PENDING;

        protected GatewaySession session;
        protected DisconnectReason reason;
        protected long requiredPosition = NO_REQUIRED_POSITION;
        protected long lingerTimeoutInMs;
        protected long lingerExpiryTimeInMs;
        protected ByteBuffer rejectEncodeBuffer;
        protected MutableAsciiBuffer rejectAsciiBuffer;

        PendingAcceptorLogon(
            final GatewaySession gatewaySession,
            final long connectionId,
            final TcpChannel channel,
            final Framer framer,
            final ReceiverEndPoint receiverEndPoint)
        {
            this.session = gatewaySession;
            this.connectionId = connectionId;
            this.channel = channel;
            this.framer = framer;
            this.receiverEndPoint = receiverEndPoint;
        }

        protected void onStrategyError(
            final String strategyName,
            final Throwable throwable,
            final long connectionId,
            final String theDefault,
            final String messageForError)
        {
            final String message = String.format(
                "Exception thrown by %s strategy for connectionId=%d, processing [%s], defaulted to %s",
                strategyName,
                connectionId,
                messageForError,
                theDefault);
            onError(new FixGatewayException(message, throwable));
        }

        protected void onError(final Throwable throwable)
        {
            // Library code should throw the exception to make users aware of it
            // Engine code should log it through the normal error handling process.
            if (errorHandler == null)
            {
                LangUtil.rethrowUnchecked(throwable);
            }
            else
            {
                errorHandler.onError(throwable);
            }
        }

        @Override
        public DisconnectReason reason()
        {
            return reason;
        }

        @Override
        public void accept()
        {
            validateState();

            setState(AuthenticationState.AUTHENTICATED);
        }

        protected void validateState()
        {
            // NB: simple best efforts state check to catch programming errors.
            // Technically can race if two different threads call accept and reject at the exact same moment.
            final AuthenticationState state = this.state;

            if (!(state == AuthenticationState.PENDING || state == AuthenticationState.AUTHENTICATED))
            {
                throw new IllegalStateException(String.format(
                    "Cannot reject and accept a pending operation at the same time (state=%s)", state));
            }
        }

        @Override
        public boolean poll()
        {
            switch (state)
            {
                case AUTHENTICATED:
                    session.onAuthenticationResult();

                    onAuthenticated();
                    return false;

                case SAVING_REJECTED_LOGON_NO_REPLY:
                    checkedOnAuthenticationResult();
                    final boolean sentRejectedPendingLogon = receiverEndPoint.sendRejectedPendingLogon();
                    if (sentRejectedPendingLogon)
                    {
                        setState(AuthenticationState.REJECTED);
                    }
                    return sentRejectedPendingLogon;

                case SAVING_REJECTED_LOGON_WITH_REPLY:
                    if (receiverEndPoint.sendRejectedPendingLogon())
                    {
                        setState(AuthenticationState.ENCODING_REJECT_MESSAGE);
                    }
                    return false;

                case ENCODING_REJECT_MESSAGE:
                    checkedOnAuthenticationResult();
                    onEncodingRejectMessage();
                    return false;

                case SENDING_REJECT_MESSAGE:
                    return onSendingRejectMessage();

                case INDEXER_CATCHUP:
                    onIndexerCatchup();
                    return false;

                case ACCEPTED:
                case REJECTED:
                    return true;

                case PENDING:
                case LINGERING_REJECT_MESSAGE:
                default:
                    return false;
            }
        }

        protected abstract void onAuthenticated();

        private void checkedOnAuthenticationResult()
        {
            if (session != null)
            {
                session.onAuthenticationResult();
                session = null;
            }
        }

        private void onEncodingRejectMessage()
        {
            try
            {
                encodeRejectMessage();
                setState(AuthenticationState.SENDING_REJECT_MESSAGE);
            }
            catch (final Exception e)
            {
                errorHandler.onError(e);
                setState(AuthenticationState.REJECTED);
            }
        }

        private boolean onSendingRejectMessage()
        {
            switch (sendReject())
            {
                case INFLIGHT:
                    final long timeInMs = epochClock.time();
                    lingerExpiryTimeInMs = timeInMs + lingerTimeoutInMs;
                    framer.startLingering(this, lingerExpiryTimeInMs);
                    setState(AuthenticationState.LINGERING_REJECT_MESSAGE);
                    return false;

                case BACK_PRESSURED:
                    return false;

                case DISCONNECTED:
                default:
                    // The TCP Connection has disconnected, therefore we consider this complete.
                    setState(AuthenticationState.SAVING_REJECTED_LOGON_NO_REPLY);
                    return true;
            }
        }

        protected abstract void encodeRejectMessage();

        protected abstract SendRejectResult sendReject();

        private void onIndexerCatchup()
        {
            if (lookupSequenceNumbers(session, requiredPosition))
            {
                setState(AuthenticationState.ACCEPTED);
            }
        }

        @Override
        public abstract void reject();

        protected void reject(final DisconnectReason reason)
        {
            validateState();

            this.reason = reason;
            this.setState(AuthenticationState.SAVING_REJECTED_LOGON_NO_REPLY);
        }

        @Override
        public boolean isAccepted()
        {
            return AuthenticationState.ACCEPTED == state;
        }

        @Override
        public long connectionId()
        {
            return connectionId;
        }

        public void setState(final AuthenticationState state)
        {
            this.state = state;
        }

        public boolean onLingerTimeout()
        {
            setState(AuthenticationState.REJECTED);
            final ReceiverEndPoint receiverEndPoint = this.receiverEndPoint;
            receiverEndPoint.poll();

            // retry if we're not disconnected at this point.
            return receiverEndPoint.hasDisconnected();
        }
    }

}
