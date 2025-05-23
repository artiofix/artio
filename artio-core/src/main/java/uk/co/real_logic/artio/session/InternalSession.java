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
package uk.co.real_logic.artio.session;

import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.ConnectionType;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.EpochFractionClock;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.lang.ref.WeakReference;

/**
 * Exposes Session methods to internal APIs that we don't want to expose to the outside world
 */
public class InternalSession extends Session implements AutoCloseable
{
    // Default initialised values used by both the Session and also the manage session handover.
    public static final boolean INITIAL_AWAITING_RESEND = false;
    public static final int INITIAL_LAST_RESENT_MSG_SEQ_NO = 0;
    public static final int INITIAL_LAST_RESEND_CHUNK_MSG_SEQ_NUM = 0;
    public static final int INITIAL_END_OF_RESEND_REQUEST_RANGE = 0;
    public static final boolean INITIAL_AWAITING_HEARTBEAT = false;

    public static class Formatters
    {
        final CharFormatter replayComplete = new CharFormatter(
            "Sess.replayComplete: replaysInFlight=%s,conn=%s,corr=%s");
    }

    public InternalSession(
        final int heartbeatIntervalInS,
        final long connectionId,
        final EpochNanoClock clock,
        final SessionState state,
        final boolean initiatorResetSeqNum,
        final SessionProxy proxy,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication,
        final SessionIdStrategy sessionIdStrategy,
        final long sendingTimeWindowInMs,
        final AtomicCounter receivedMsgSeqNo,
        final AtomicCounter sentMsgSeqNo,
        final int libraryId,
        final int initialSentSequenceNumber,
        final int sequenceIndex,
        final long reasonableTransmissionTimeInMs,
        final MutableAsciiBuffer asciiBuffer,
        final boolean enableLastMsgSeqNumProcessed,
        final SessionCustomisationStrategy customisationStrategy,
        final OnMessageInfo messageInfo,
        final EpochFractionClock epochFractionClock,
        final ConnectionType connectionType,
        final ResendRequestController resendRequestController,
        final int forcedHeartbeatIntervalInS,
        final boolean disableHeartbeatRepliesToTestRequests,
        final boolean disconnectOnFirstMessageNotLogon,
        final Formatters formatters)
    {
        super(
            heartbeatIntervalInS,
            connectionId,
            clock,
            state,
            initiatorResetSeqNum,
            proxy,
            inboundPublication,
            outboundPublication,
            sessionIdStrategy,
            sendingTimeWindowInMs,
            receivedMsgSeqNo,
            sentMsgSeqNo,
            libraryId,
            initialSentSequenceNumber,
            sequenceIndex,
            reasonableTransmissionTimeInMs,
            asciiBuffer,
            enableLastMsgSeqNumProcessed,
            customisationStrategy,
            messageInfo,
            epochFractionClock,
            connectionType,
            resendRequestController,
            forcedHeartbeatIntervalInS,
            disableHeartbeatRepliesToTestRequests,
            disconnectOnFirstMessageNotLogon,
            formatters);
    }

    public int poll(final long timeInNs)
    {
        return super.poll(timeInNs);
    }

    public void disable()
    {
        super.disable();
    }

    public void libraryConnected(final boolean libraryConnected)
    {
        super.libraryConnected(libraryConnected);
    }

    public void sessionProcessHandler(final FixSessionOwner fixSessionOwner)
    {
        super.sessionProcessHandler(fixSessionOwner);
    }

    public void address(final String address)
    {
        final ParsedAddress parsed = ParsedAddress.parse(address);
        address(parsed.host(), parsed.port());
    }

    public void address(final String host, final int port)
    {
        super.address(host, port);
    }

    public void username(final String username)
    {
        super.username(username);
    }

    public void password(final String password)
    {
        super.password(password);
    }

    public void lastLogonTimeInNs(final long logonTimeInNs)
    {
        super.lastLogonTimeInNs(logonTimeInNs);
    }

    public void awaitingResend(final boolean awaitingResend)
    {
        super.awaitingResend(awaitingResend);
    }

    public void closedResendInterval(final boolean closedResendInterval)
    {
        super.closedResendInterval(closedResendInterval);
    }

    public void resendRequestChunkSize(final int resendRequestChunkSize)
    {
        super.resendRequestChunkSize(resendRequestChunkSize);
    }

    public void sendRedundantResendRequests(final boolean sendRedundantResendRequests)
    {
        super.sendRedundantResendRequests(sendRedundantResendRequests);
    }

    public void updateLastMessageProcessed()
    {
        super.updateLastMessageProcessed();
    }

    public void initialLastReceivedMsgSeqNum(final int lastReceivedMsgSeqNum)
    {
        super.initialLastReceivedMsgSeqNum(lastReceivedMsgSeqNum);
    }

    public ControlledFragmentHandler.Action onInvalidMessage(
        final int refSeqNum,
        final int refTagId,
        final char[] refMsgType,
        final int refMsgTypeLength,
        final int rejectReason, final long position)
    {
        return super.onInvalidMessage(refSeqNum, refTagId, refMsgType, refMsgTypeLength, rejectReason, position);
    }

    public void lastResentMsgSeqNo(final int lastResentMsgSeqNo)
    {
        super.lastResentMsgSeqNo(lastResentMsgSeqNo);
    }

    public void lastResendChunkMsgSeqNum(final int lastResendChunkMsgSeqNum)
    {
        super.lastResendChunkMsgSeqNum(lastResendChunkMsgSeqNum);
    }

    public void lastSequenceResetTimeInNs(final long lastSequenceResetTimeInNs)
    {
        super.lastSequenceResetTimeInNs(lastSequenceResetTimeInNs);
    }

    public void endOfResendRequestRange(final int endOfResendRequestRange)
    {
        super.endOfResendRequestRange(endOfResendRequestRange);
    }

    public void awaitingHeartbeat(final boolean awaitingHeartbeat)
    {
        super.awaitingHeartbeat(awaitingHeartbeat);
    }

    public int lastResendChunkMsgSeqNum()
    {
        return super.lastResendChunkMsgSeqNum();
    }

    public int endOfResendRequestRange()
    {
        return super.endOfResendRequestRange();
    }

    public int lastResentMsgSeqNo()
    {
        return super.lastResentMsgSeqNo();
    }

    public void fixDictionary(final FixDictionary fixDictionary)
    {
        super.fixDictionary(fixDictionary);
    }

    public void setupSession(
        final long sessionId,
        final CompositeKey sessionKey,
        final WeakReference<SessionWriter> sessionWriterRef)
    {
        super.setupSession(sessionId, sessionKey, sessionWriterRef);
    }

    public void linkTo(final SessionWriter sessionWriter)
    {
        sessionWriter.linkTo(this);
    }

    public static void disconnectWriter(final SessionWriter writer)
    {
        writer.onDisconnect();
    }

    public static void closeWriter(final SessionWriter writer)
    {
        writer.close();
    }

    public void close()
    {
        super.close();
    }

    public void onReconnect(
        final long connectionId,
        final SessionState sessionState,
        final int heartbeatIntervalInS,
        final int sequenceIndex,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary,
        final String address,
        final FixCounters counters,
        final ConnectionType connectionType)
    {
        connectionId(connectionId);
        state(sessionState);
        heartbeatIntervalInS(heartbeatIntervalInS);
        setCorrectedSequenceIndex(sessionState, sequenceIndex);
        enableLastMsgSeqNumProcessed(enableLastMsgSeqNumProcessed);
        fixDictionary(fixDictionary);
        address(address);
        refreshSequenceNumberCounters(counters);
        awaitingLogonReply(false);
        this.connectionType = connectionType;
    }

    public void lastReceivedMsgSeqNumOnly(final int value)
    {
        super.lastReceivedMsgSeqNumOnly(value);
    }

    public OnMessageInfo messageInfo()
    {
        return super.messageInfo();
    }

    public boolean areCountersClosed()
    {
        return super.areCountersClosed();
    }

    public void cancelOnDisconnectOption(final CancelOnDisconnectOption cancelOnDisconnectOption)
    {
        super.cancelOnDisconnectOption(cancelOnDisconnectOption);
    }

    public void cancelOnDisconnectTimeoutWindowInNs(final long cancelOnDisconnectTimeoutWindowInNs)
    {
        super.cancelOnDisconnectTimeoutWindowInNs(cancelOnDisconnectTimeoutWindowInNs);
    }

    public boolean onThrottleNotification(
        final long refMsgType,
        final int refSeqNum,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        // Accept the throttled message in terms of updating the sequence number and keeping the session alive
        // Don't waste time validating it in any other way.
        incNextReceivedInboundMessageTime(timeInNs());
        lastReceivedMsgSeqNum(refSeqNum);

        final int sequenceNumber = newSentSeqNum();

        final long position = outboundPublication.saveThrottleReject(
            libraryId,
            connectionId,
            refMsgType,
            refSeqNum,
            sequenceNumber,
            id(),
            sequenceIndex(),
            businessRejectRefIDBuffer,
            businessRejectRefIDOffset,
            businessRejectRefIDLength);

        if (position > 0)
        {
            lastSentMsgSeqNum(sequenceNumber);
            return true;
        }

        return false;
    }

    public void isSlowConsumer(final boolean hasBecomeSlow)
    {
        super.isSlowConsumer(hasBecomeSlow);
    }

    public long logoutAndDisconnect(final DisconnectReason reason)
    {
        return super.logoutAndDisconnect(reason);
    }

    public long requestDisconnect(final DisconnectReason reason)
    {
        return super.requestDisconnect(reason);
    }

    public void initiatorResetSeqNum(final boolean initiatorResetSeqNum)
    {
        super.initiatorResetSeqNum(initiatorResetSeqNum);
    }

    public void onReplayComplete(final long correlationId)
    {
        super.onReplayComplete(correlationId);
    }

    public void onSessionWriterLogout()
    {
        super.onSessionWriterLogout();
    }

    public ConnectionType connectionType()
    {
        return connectionType;
    }

    public void disconnectOnFirstMessageNotLogon(final boolean disconnectOnFirstMessageNotLogon)
    {
        super.disconnectOnFirstMessageNotLogon(disconnectOnFirstMessageNotLogon);
    }

}
