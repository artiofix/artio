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

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.messages.ReplayMessagesStatus;
import uk.co.real_logic.artio.messages.ThrottleConfigurationStatus;
import uk.co.real_logic.artio.session.FixSessionOwner;
import uk.co.real_logic.artio.session.InternalSession;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionParser;
import uk.co.real_logic.artio.timing.Timer;

import java.util.function.BooleanSupplier;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.NO_REQUIRED_POSITION;
import static uk.co.real_logic.artio.messages.GatewayError.UNABLE_TO_LOGON;

class SessionSubscriber implements AutoCloseable, FixSessionOwner
{
    private final OnMessageInfo info;
    private final SessionParser parser;
    private final InternalSession session;
    private final Timer receiveTimer;
    private final Timer sessionTimer;
    private final LibraryPoller libraryPoller;
    private final long replyTimeoutInMs;
    private final ErrorHandler errorHandler;

    private SessionHandler handler;
    private InitiateSessionReply initiateSessionReply;
    private boolean userAbortedLastMessage = false;
    private long lastReceivedPosition = NO_REQUIRED_POSITION;

    SessionSubscriber(
        final OnMessageInfo info,
        final SessionParser parser,
        final InternalSession session,
        final Timer receiveTimer,
        final Timer sessionTimer,
        final LibraryPoller libraryPoller,
        final long replyTimeoutInMs,
        final ErrorHandler errorHandler)
    {
        this.info = info;
        this.parser = parser;
        this.session = session;
        this.receiveTimer = receiveTimer;
        this.sessionTimer = sessionTimer;
        this.libraryPoller = libraryPoller;
        this.replyTimeoutInMs = replyTimeoutInMs;
        this.errorHandler = errorHandler;
        this.session.sessionProcessHandler(this);
    }

    Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final int sequenceIndex,
        final long messageType,
        final long timestamp,
        final MessageStatus status,
        final long position)
    {
        final long now = receiveTimer.recordSince(timestamp);

        final OnMessageInfo info = this.info;
        info.status(status);
        // this gets set to false by the Session when a problem is detected.
        info.isValid(true);

        try
        {
            switch (status)
            {
                case OK:
                    final boolean userAbortedLastMessage = this.userAbortedLastMessage;
                    if (userAbortedLastMessage)
                    {
                        // Don't re-run the parser / session handling logic if you're on the retry path
                        final Action handlerAction = handler.onMessage(
                            buffer,
                            offset,
                            length,
                            libraryId,
                            session,
                            sequenceIndex,
                            messageType,
                            timestamp,
                            position,
                            info);

                        if (handlerAction != ABORT)
                        {
                            session.updateLastMessageProcessed();
                            this.userAbortedLastMessage = false;
                        }

                        return handlerAction;
                    }
                    else
                    {
                        final Action action = parser.onMessage(
                            buffer, offset, length, messageType, position);
                        if (action == ABORT)
                        {
                            return ABORT;
                        }

                        lastReceivedPosition = position;

                        final Action handlerAction = handler.onMessage(
                            buffer,
                            offset,
                            length,
                            libraryId,
                            session,
                            sequenceIndex,
                            messageType,
                            timestamp,
                            position,
                            info);

                        if (handlerAction == ABORT)
                        {
                            this.userAbortedLastMessage = true;
                        }
                        else
                        {
                            session.updateLastMessageProcessed();
                        }

                        return handlerAction;
                    }

                case CATCHUP_REPLAY:
                    return handler.onMessage(
                        buffer,
                        offset,
                        length,
                        libraryId,
                        session,
                        sequenceIndex,
                        messageType,
                        timestamp,
                        position,
                        info);

                default:
                    return CONTINUE;
            }
        }
        finally
        {
            sessionTimer.recordSince(now);
        }
    }

    Action onDisconnect(final int libraryId, final DisconnectReason reason)
    {
        final Action action = handler.onDisconnect(libraryId, session, reason);
        if (action != ABORT)
        {
            session.onDisconnect();
            // We've been disconnected before an initiator session has finished logging on, eg: wrong msgSeqNum in logon
            if (initiateSessionReply != null)
            {
                initiateSessionReply.onError(UNABLE_TO_LOGON, "Disconnected before session active");
                initiateSessionReply = null;
            }
        }
        return action;
    }

    @Override
    public void onLogon(final Session session)
    {
        try
        {
            handler.onSessionStart(session);

            if (initiateSessionReply != null)
            {
                initiateSessionReply.onComplete(session);
                // Don't want to hold a reference to the reply object for the
                // lifetime of the Session
                initiateSessionReply = null;
            }
        }
        catch (final Throwable t)
        {
            if (initiateSessionReply != null)
            {
                initiateSessionReply.onError(t);
                // Don't want to hold a reference to the reply object for the
                // lifetime of the Session
                initiateSessionReply = null;
            }
            else
            {
                errorHandler.onError(t);
            }

            ((InternalSession)session).logoutAndDisconnect(DisconnectReason.CALLBACK_EXCEPTION);
        }
    }

    @Override
    public Reply<ReplayMessagesStatus> replayReceivedMessages(
        final long sessionId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long timeoutInMs)
    {
        return new ReplayMessagesReply(
            libraryPoller,
            libraryPoller.timeInMs() + timeoutInMs,
            sessionId,
            replayFromSequenceNumber,
            replayFromSequenceIndex,
            replayToSequenceNumber,
            replayToSequenceIndex);
    }

    @Override
    public Reply<ThrottleConfigurationStatus> messageThrottle(
        final long sessionId, final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        return new ThrottleConfigurationReply(
            libraryPoller,
            libraryPoller.timeInMs() + replyTimeoutInMs,
            sessionId,
            throttleWindowInMs,
            throttleLimitOfMessages);
    }

    @Override
    public long inboundMessagePosition()
    {
        return lastReceivedPosition;
    }

    @Override
    public void enqueueTask(final BooleanSupplier task)
    {
        libraryPoller.enqueueTask(task);
    }

    void onTimeout(final int libraryId)
    {
        handler.onTimeout(libraryId, session);
    }

    void onSlowStatusNotification(final int libraryId, final boolean hasBecomeSlow)
    {
        session.isSlowConsumer(hasBecomeSlow);
        handler.onSlowStatus(libraryId, session, hasBecomeSlow);
    }

    @Override
    public void close()
    {
        session.requestDisconnect();
    }

    InternalSession session()
    {
        return session;
    }

    void handler(final SessionHandler handler)
    {
        this.handler = handler;
    }

    void reply(final InitiateSessionReply reply)
    {
        this.initiateSessionReply = reply;
    }

    public boolean onThrottleNotification(
        final long refMsgType,
        final int refSeqNum,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        return session.onThrottleNotification(
            refMsgType, refSeqNum, businessRejectRefIDBuffer, businessRejectRefIDOffset, businessRejectRefIDLength
        );
    }

    void onReplayComplete(final long correlationId)
    {
        session.onReplayComplete(correlationId);
    }
}
