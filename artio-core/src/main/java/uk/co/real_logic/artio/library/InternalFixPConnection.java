/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import io.aeron.exceptions.TimeoutException;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.util.concurrent.TimeUnit;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.fixp.FixPConnection.State.*;
import static uk.co.real_logic.artio.messages.DisconnectReason.LOGOUT;

public abstract class InternalFixPConnection implements FixPConnection
{
    protected final GatewayPublication outboundPublication;
    protected final GatewayPublication inboundPublication;
    protected final int libraryId;
    protected final EpochNanoClock clock;
    protected final FixPSessionOwner owner;
    protected final AbstractFixPProxy proxy;
    protected final FixPMessageDissector dissector;
    protected final FixPMessageHeader messageHeader;

    protected State state;
    protected FixPConnectionHandler handler;
    protected LibraryReply<InternalFixPConnection> initiateReply;
    protected long connectionId;

    protected long counterpartyKeepAliveIntervalInMs;
    protected long ourKeepAliveIntervalInMs;
    protected long nextSentSeqNo;
    protected long nextRecvSeqNo;

    protected long retransmitFillTimeoutInMs = NOT_AWAITING_RETRANSMIT;
    protected long nextReceiveMessageTimeInMs;
    protected long nextSendMessageTimeInMs;

    protected final long sendingTimeWindowInNs;

    protected InternalFixPConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final EpochNanoClock clock,
        final FixPSessionOwner owner,
        final AbstractFixPProxy proxy,
        final FixPMessageDissector dissector,
        final long sendingTimeWindowInMs)
    {
        this.connectionId = connectionId;
        this.outboundPublication = outboundPublication;
        this.inboundPublication = inboundPublication;
        this.libraryId = libraryId;
        this.clock = clock;
        this.owner = owner;
        this.proxy = proxy;
        this.dissector = dissector;
        this.sendingTimeWindowInNs = TimeUnit.MILLISECONDS.toNanos(sendingTimeWindowInMs);
        this.messageHeader = new FixPMessageHeader();
    }

    // -----------------------------------------------
    // Public API
    // -----------------------------------------------

    @Override
    public long connectionId()
    {
        return connectionId;
    }

    @Override
    public long nextSentSeqNo()
    {
        return nextSentSeqNo;
    }

    @Override
    public void nextSentSeqNo(final long nextSentSeqNo)
    {
        this.nextSentSeqNo = nextSentSeqNo;
    }

    @Override
    public long nextRecvSeqNo()
    {
        return nextRecvSeqNo;
    }

    @Override
    public void nextRecvSeqNo(final long nextRecvSeqNo)
    {
        this.nextRecvSeqNo = nextRecvSeqNo;
    }

    @Override
    public long requestDisconnect(final DisconnectReason reason)
    {
        return outboundPublication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

    @Override
    public boolean canSendMessage()
    {
        final State state = this.state;
        return state == ESTABLISHED || state == AWAITING_KEEPALIVE || state == RECV_FINISHED_SENDING ||
            state == UNBOUND;
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public long tryClaim(
        final MessageEncoderFlyweight message)
    {
        return tryClaim(message, 0);
    }

    @Override
    public long tryClaim(
        final MessageEncoderFlyweight message, final int variableLength)
    {
        validateCanSend();

        final long timestamp = requestTimestampInNs();

        final long position = proxy.claimMessage(
            message.sbeBlockLength() + variableLength, message, timestamp);

        if (position > 0)
        {
            nextSentSeqNo++;
        }

        return position;
    }

    @Override
    public void commit()
    {
        proxy.commit();

        onAttemptedToSendMessage();
    }

    @Override
    public void abort()
    {
        proxy.abort();

        nextSentSeqNo--;
    }

    @Override
    public boolean isConnected()
    {
        final State state = this.state;
        switch (state)
        {
            case CONNECTED:
            case SENT_NEGOTIATE:
            case RETRY_NEGOTIATE:
            case NEGOTIATE_REJECTED:
            case NEGOTIATED:
            case SENT_ESTABLISH:
            case RETRY_ESTABLISH:
            case ESTABLISH_REJECTED:
            case ACCEPTED:
            case SENT_NEGOTIATE_RESPONSE:
            case RETRY_NEGOTIATE_RESPONSE:
            case SENT_NEGOTIATE_REJECT:
            case RETRY_NEGOTIATE_REJECT:
            case RETRY_ESTABLISH_ACK:
            case SENT_ESTABLISH_REJECT:
            case RETRY_ESTABLISH_REJECT:
            case NEGOTIATED_REESTABLISH:
            case ESTABLISHED:
            case RETRANSMITTING:
            case SENT_FINISHED_SENDING:
            case RETRY_FINISHED_SENDING:
            case RECV_FINISHED_RECEIVING_ONLY:
            case RECV_FINISHED_SENDING:
            case REPLIED_FINISHED_SENDING:
            case RETRY_REPLY_FINISHED_SENDING:
            case AWAITING_KEEPALIVE:
            case SENT_TERMINATE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public long counterpartyKeepAliveIntervalInMs()
    {
        return counterpartyKeepAliveIntervalInMs;
    }

    @Override
    public long ourKeepAliveIntervalInMs()
    {
        return ourKeepAliveIntervalInMs;
    }

    // --------------------------------------------------
    // Internal Methods below, not part of the public API
    // --------------------------------------------------

    protected void onReceivedMessage()
    {
        if (state == AWAITING_KEEPALIVE)
        {
            state(ESTABLISHED);
        }

        nextReceiveMessageTimeInMs = nextRecvTimeoutInMs();
    }

    protected long nextRecvTimeoutInMs()
    {
        return System.currentTimeMillis() + counterpartyKeepAliveIntervalInMs;
    }

    protected long nextSendTimeoutInMs()
    {
        return System.currentTimeMillis() + ourKeepAliveIntervalInMs;
    }

    // This just suppresses sending sequence heartbeating messages
    protected void onAttemptedToSendMessage()
    {
        nextSendMessageTimeInMs = nextSendTimeoutInMs();
    }

    protected long requestTimestampInNs()
    {
        return clock.nanoTime();
    }

    protected void validateCanSend()
    {
        if (!canSendMessage())
        {
            throw new IllegalStateException(
                "State should be ESTABLISHED or AWAITING_KEEPALIVE or RECV_FINISHED_SENDING in order to send but is " +
                state);
        }
    }

    @SuppressWarnings("unchecked")
    protected void initiateReply(final LibraryReply<?> initiateReply)
    {
        this.initiateReply = (LibraryReply<InternalFixPConnection>)initiateReply;
    }

    protected void onNegotiateFailure()
    {
        onReplyError(new TimeoutException("Timed out: no reply for Negotiate"));
    }

    protected void onEstablishFailure()
    {
        onReplyError(new TimeoutException("Timed out: no reply for Establish"));
    }

    protected void onReplyError(final Exception error)
    {
        initiateReply.onError(error);
        initiateReply = null;
    }

    protected int commonPoll(final State state, final long timeInMs)
    {
        switch (state)
        {
            case ESTABLISHED:
                return pollEstablished(timeInMs);

            case AWAITING_KEEPALIVE:
                return pollAwaitingKeepAlive(timeInMs);

            case UNBINDING:
                return pollUnbinding(timeInMs);

            default:
                return 0;
        }
    }

    protected int pollAwaitingKeepAlive(final long timeInMs)
    {
        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            keepAliveExpiredTerminate();
            return 1;
        }

        return 0;
    }

    // Should switch into unbinding or resend terminate state after this.
    protected abstract void keepAliveExpiredTerminate();

    protected int pollUnbinding(final long timeInMs)
    {
        if (timeInMs > nextSendMessageTimeInMs)
        {
            fullyUnbind();
        }
        return 0;
    }

    protected int pollEstablished(final long timeInMs)
    {
        int events = pollExtraEstablished(timeInMs);

        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            sendSequence(true);

            onReceivedMessage();

            state(AWAITING_KEEPALIVE);
            events++;
        }
        else if (timeInMs > nextSendMessageTimeInMs)
        {
            sendSequence(false);
            events++;
        }

        return events;
    }

    protected abstract int pollExtraEstablished(long timeInMs);

    protected abstract long sendSequence(boolean lapsed);

    protected Action fullyUnbind()
    {
        return fullyUnbind(LOGOUT);
    }

    protected Action fullyUnbindInclRetry(final DisconnectReason reason)
    {
        // switching to an unbinding state means that we'll retry disconnects when we the poll
        // if this message gets backpressured, so it's safe to return CONTINUE here.
        this.state = UNBINDING;
        fullyUnbind(reason);
        return CONTINUE;
    }

    protected Action fullyUnbind(final DisconnectReason reason)
    {
        if (requestDisconnect(reason) < 0)
        {
            return ABORT;
        }

        final Action action = unbindState(reason);
        if (action != ABORT)
        {
            owner.remove(this);
        }

        return action;
    }

    protected Action unbindState(final DisconnectReason reason)
    {
        final State oldState = this.state;
        state(UNBOUND);
        final Action action = handler.onDisconnect(this, reason);
        if (action == ABORT)
        {
            state(oldState);
            return ABORT;
        }

        // Complete the reply if we're in the process of trying to establish a connection and we haven't provided
        // a more specific reason for a disconnect to happen.
        if (initiateReply != null)
        {
            onReplyError(new Exception("Unbound due to: " + reason));
        }

        return action;
    }

    protected void state(final State state)
    {
        this.state = state;
    }

    public void handler(final FixPConnectionHandler handler)
    {
        this.handler = handler;
    }

    protected abstract int poll(long timeInMs);

    protected abstract void onReplayComplete();

    protected abstract void onOfflineReconnect(long connectionId, FixPContext context);

    protected boolean onThrottleNotification(
        final long refMsgType,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        throw new UnsupportedOperationException("throttling isn't supported for this Protocol type");
    }

    /**
     * Start the end of day operation for a given connection. Invoked when the engine closes.
     *
     * @return position of sent message in order to enable back-pressure handling
     */
    public abstract long startEndOfDay();
}
