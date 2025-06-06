/*
 * Copyright 2015-2025 Real Logic Limited., Adaptive Financial Consulting Ltd.
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

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.AsciiFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.fields.RejectReason.OTHER;
import static uk.co.real_logic.artio.fields.RejectReason.VALUE_IS_INCORRECT;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;
import static uk.co.real_logic.artio.session.Session.LIBRARY_DISCONNECTED;

/**
 * Encapsulates sending messages relating to sessions
 */
public class DirectSessionProxy implements SessionProxy
{
    static final int NO_LAST_MSG_SEQ_NUM_PROCESSED = -1;

    private static final byte[] INCORRECT_BEGIN_STRING = "Incorrect BeginString".getBytes(US_ASCII);
    private static final byte[] NEGATIVE_HEARTBEAT = "HeartBtInt must not be negative".getBytes(US_ASCII);
    private static final byte[] NO_MSG_SEQ_NO = "Received message without MsgSeqNum".getBytes(US_ASCII);
    private static final int REJECT_COUNT = RejectReason.values().length;
    private static final byte[][] NOT_LOGGED_ON_SESSION_REJECT_REASONS = new byte[REJECT_COUNT][];
    private static final byte[][] LOGGED_ON_SESSION_REJECT_REASONS = new byte[REJECT_COUNT][];
    private static final int OTHER_REJECT_INDEX = 19;

    static
    {
        final RejectReason[] reasons = RejectReason.values();
        for (int i = 0; i < REJECT_COUNT; i++)
        {
            final RejectReason reason = reasons[i];
            final String formattedReason = reason.name().replace('_', ' ').toLowerCase();

            NOT_LOGGED_ON_SESSION_REJECT_REASONS[i] = String.format(
                "Invalid Logon message: SendingTime accuracy problem, field=52, reason=%s",
                formattedReason
            ).getBytes(US_ASCII);

            if (reason == VALUE_IS_INCORRECT)
            {
                LOGGED_ON_SESSION_REJECT_REASONS[i] =
                    "Value is incorrect (out of range) for this tag".getBytes(US_ASCII);
            }
            else
            {
                LOGGED_ON_SESSION_REJECT_REASONS[i] = formattedReason.getBytes(US_ASCII);
            }
        }
    }

    private final UtcTimestampEncoder timestampEncoder;

    private FixDictionary dictionary;
    private AbstractLogonEncoder logon;
    private AbstractResendRequestEncoder resendRequest;
    private AbstractLogoutEncoder logout;
    private AbstractHeartbeatEncoder heartbeat;
    private AbstractRejectEncoder reject;
    private AbstractTestRequestEncoder testRequest;
    private AbstractSequenceResetEncoder sequenceReset;
    private List<SessionHeaderEncoder> headers;

    private final AsciiFormatter lowSequenceNumber;
    private final MutableAsciiBuffer buffer;
    private final ErrorHandler errorHandler;
    private final GatewayPublication gatewayPublication;
    private final SessionIdStrategy sessionIdStrategy;
    private final SessionCustomisationStrategy customisationStrategy;
    private final int libraryId;
    private final EpochNanoClock clock;
    private long connectionId;
    private long sessionId;
    private boolean libraryConnected = true;
    private boolean seqNumResetRequested = false;
    private long lastSentPosition = 0;

    public DirectSessionProxy(
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
        this.gatewayPublication = gatewayPublication;
        this.sessionIdStrategy = sessionIdStrategy;
        this.customisationStrategy = customisationStrategy;
        this.clock = clock;
        this.connectionId = connectionId;
        this.libraryId = libraryId;
        this.buffer = new MutableAsciiBuffer(new byte[sessionBufferSize]);
        this.errorHandler = errorHandler;
        lowSequenceNumber = new AsciiFormatter("MsgSeqNum too low, expecting %s but received %s");
        timestampEncoder = new UtcTimestampEncoder(epochFractionPrecision);
        timestampEncoder.initialise(clock.nanoTime(), TimeUnit.NANOSECONDS);
    }

    public void fixDictionary(final FixDictionary dictionary)
    {
        this.dictionary = dictionary;

        logon = dictionary.makeLogonEncoder();
        resendRequest = dictionary.makeResendRequestEncoder();
        logout = dictionary.makeLogoutEncoder();
        heartbeat = dictionary.makeHeartbeatEncoder();
        reject = dictionary.makeRejectEncoder();
        testRequest = dictionary.makeTestRequestEncoder();
        sequenceReset = dictionary.makeSequenceResetEncoder();

        headers = asList(
            logon.header(),
            resendRequest.header(),
            logout.header(),
            heartbeat.header(),
            reject.header(),
            testRequest.header(),
            sequenceReset.header());
    }

    public void setupSession(final long sessionId, final CompositeKey sessionKey)
    {
        requireNonNull(sessionKey, "sessionKey");

        this.sessionId = sessionId;
        for (final SessionHeaderEncoder header : headers)
        {
            sessionIdStrategy.setupSession(sessionKey, header);
            customisationStrategy.configureHeader(header, sessionId);
        }
    }

    public void connectionId(final long connectionId)
    {
        this.connectionId = connectionId;
    }

    public long sendResendRequest(
        final int msgSeqNo,
        final int beginSeqNo,
        final int endSeqNo,
        final int sequenceIndex,
        final int lastMsgSeqNumProcessed)
    {
        resendRequest.beginSeqNo(beginSeqNo)
                     .endSeqNo(endSeqNo);

        return send(sequenceIndex, resendRequest, msgSeqNo, lastMsgSeqNumProcessed);
    }

    public long sendRequestDisconnect(final long connectionId, final DisconnectReason reason)
    {
        return gatewayPublication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

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
        logon
            .heartBtInt(heartbeatIntervalInS)
            .resetSeqNumFlag(resetSeqNumFlag)
            .encryptMethod(0); // may have been previously reset

        if (notNullOrEmpty(username))
        {
            if (!logon.supportsUsername())
            {
                onMissingFieldError("username");
            }
            else
            {
                logon.username(username);
            }
        }

        if (notNullOrEmpty(password))
        {
            if (!logon.supportsPassword())
            {
                onMissingFieldError("password");
            }
            else
            {
                logon.password(password);
            }
        }

        if (cancelOnDisconnectOption != null && logon.supportsCancelOnDisconnectType())
        {
            logon.cancelOnDisconnectType(cancelOnDisconnectOption.value());
        }

        if (cancelOnDisconnectTimeoutWindowInMs != MISSING_INT && logon.supportsCODTimeoutWindow())
        {
            logon.cODTimeoutWindow(cancelOnDisconnectTimeoutWindowInMs);
        }

        customisationStrategy.configureLogon(logon, sessionId);
        seqNumResetRequested = logon.resetSeqNumFlag(); // get customized or argument

        return send(sequenceIndex, logon, msgSeqNo, lastMsgSeqNumProcessed);
    }

    private void onMissingFieldError(final String field)
    {
        errorHandler.onError(new IllegalStateException(String.format(
            "Dictionary: %1$s does not support %2$s field but %2$s is provided",
            dictionary.getClass().getCanonicalName(),
            field
        )));
    }

    private boolean notNullOrEmpty(final String string)
    {
        return string != null && string.length() > 0;
    }

    public long sendLogout(
        final int msgSeqNo,
        final int sequenceIndex,
        final int lastMsgSeqNumProcessed,
        final byte[] text)
    {
        return sendLogout(msgSeqNo, text, text == null ? 0 : text.length, sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendLogout(
        final int msgSeqNo, final int sequenceIndex, final int rejectReason, final int lastMsgSeqNumProcessed)
    {
        final int rejectReasonIndex = getRejectReasonIndex(rejectReason);
        final byte[] reasonText = LOGGED_ON_SESSION_REJECT_REASONS[rejectReasonIndex];
        return sendLogout(msgSeqNo, reasonText, reasonText.length, sequenceIndex, lastMsgSeqNumProcessed);
    }

    private long sendLogout(
        final int msgSeqNo, final byte[] text, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendLogout(msgSeqNo, text, text.length, sequenceIndex, lastMsgSeqNumProcessed);
    }

    private long sendLogout(
        final int msgSeqNo,
        final byte[] text,
        final int length,
        final int sequenceIndex,
        final int lastMsgSeqNumProcessed)
    {
        if (text != null)
        {
            logout.text(text, length);
        }

        customisationStrategy.configureLogout(logout, sessionId);
        return send(sequenceIndex, logout, msgSeqNo, lastMsgSeqNumProcessed);
    }

    public long sendLowSequenceNumberLogout(
        final int msgSeqNo,
        final int expectedSeqNo,
        final int receivedSeqNo,
        final int sequenceIndex,
        final int lastMsgSeqNumProcessed)
    {
        lowSequenceNumber
            .with(expectedSeqNo)
            .with(receivedSeqNo);

        final long position = sendLogout(
            msgSeqNo, lowSequenceNumber.value(), lowSequenceNumber.length(), sequenceIndex, lastMsgSeqNumProcessed);
        lowSequenceNumber.clear();

        return position;
    }

    public long sendIncorrectBeginStringLogout(
        final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendLogout(msgSeqNo, INCORRECT_BEGIN_STRING, sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendNegativeHeartbeatLogout(
        final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendLogout(msgSeqNo, NEGATIVE_HEARTBEAT, sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendReceivedMessageWithoutSequenceNumber(
        final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendLogout(msgSeqNo, NO_MSG_SEQ_NO, sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendRejectWhilstNotLoggedOn(
        final int msgSeqNo, final RejectReason reason, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendLogout(
            msgSeqNo, NOT_LOGGED_ON_SESSION_REJECT_REASONS[reason.ordinal()], sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendHeartbeat(final int msgSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        return sendHeartbeat(
            msgSeqNo, null, 0, sequenceIndex, lastMsgSeqNumProcessed);
    }

    public long sendHeartbeat(
        final int msgSeqNo, final char[] testReqId,
        final int testReqIdLength,
        final int sequenceIndex,
        final int lastMsgSeqNumProcessed)
    {
        if (testReqId != null)
        {
            heartbeat.testReqID(testReqId, testReqIdLength);
        }
        else
        {
            heartbeat.resetTestReqID();
        }

        return send(sequenceIndex, heartbeat, msgSeqNo, lastMsgSeqNumProcessed);
    }

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
        if (refTagId != MISSING_INT)
        {
            reject.refTagID(refTagId);
        }
        else
        {
            reject.resetRefTagID();
        }

        if (reject.supportsRefMsgType())
        {
            reject.refMsgType(refMsgType, refMsgTypeLength);
        }
        final int rejectReasonIndex = getRejectReasonIndex(rejectReason);
        reject.text(LOGGED_ON_SESSION_REJECT_REASONS[rejectReasonIndex]);

        reject.refSeqNum(refSeqNum);
        reject.sessionRejectReason(rejectReason);

        return send(sequenceIndex, reject, msgSeqNo, lastMsgSeqNumProcessed);
    }

    public long sendTestRequest(
        final int msgSeqNo, final CharSequence testReqID, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        testRequest.testReqID(testReqID);

        return send(sequenceIndex, testRequest, msgSeqNo, lastMsgSeqNumProcessed);
    }

    public long sendSequenceReset(
        final int msgSeqNo, final int newSeqNo, final int sequenceIndex, final int lastMsgSeqNumProcessed)
    {
        sequenceReset.newSeqNo(newSeqNo);

        return send(sequenceIndex, sequenceReset, msgSeqNo, lastMsgSeqNumProcessed);
    }

    private long send(
        final int sequenceIndex,
        final Encoder encoder,
        final int msgSeqNo,
        final int lastMsgSeqNumProcessed)
    {
        if (!libraryConnected)
        {
            return LIBRARY_DISCONNECTED;
        }

        final SessionHeaderEncoder header = encoder.header();
        final long messageType = encoder.messageType();
        final long sendingTimeNanos = clock.nanoTime();

        final UtcTimestampEncoder timestampEncoder = this.timestampEncoder;
        final int sendingTimeLength = timestampEncoder.updateFrom(sendingTimeNanos, TimeUnit.NANOSECONDS);
        header.sendingTime(timestampEncoder.buffer(), sendingTimeLength);
        header.msgSeqNum(msgSeqNo);
        if (lastMsgSeqNumProcessed != NO_LAST_MSG_SEQ_NUM_PROCESSED)
        {
            header.lastMsgSeqNumProcessed(lastMsgSeqNumProcessed);
        }

        final long result = encoder.encode(buffer, 0);
        final int length = Encoder.length(result);
        final int offset = Encoder.offset(result);
        final long position = gatewayPublication.saveMessage(
            buffer, offset, length,
            libraryId, messageType, sessionId, sequenceIndex, connectionId, OK, msgSeqNo, sendingTimeNanos);
        encoder.resetMessage();

        if (position > 0)
        {
            DebugLogger.logFixMessage(FIX_MESSAGE, messageType, "Sent ", buffer, offset, length);
            lastSentPosition = position;
        }

        return position;
    }

    private int getRejectReasonIndex(final int rejectReason)
    {
        return rejectReason == OTHER.representation() ? OTHER_REJECT_INDEX : rejectReason;
    }

    public long lastSentPosition()
    {
        return lastSentPosition;
    }

    public void libraryConnected(final boolean libraryConnected)
    {
        this.libraryConnected = libraryConnected;
    }

    public boolean seqNumResetRequested()
    {
        return seqNumResetRequested;
    }

    public long sendCancelOnDisconnectTrigger(final long sessionId, final long timeInNs)
    {
        return gatewayPublication.saveCancelOnDisconnectTrigger(sessionId, timeInNs);
    }

    public boolean isAsync()
    {
        return false;
    }
}
