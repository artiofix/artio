/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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

import io.aeron.Publication;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.Verify;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.AbstractRejectEncoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.AbstractResendRequestDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.engine.logger.Replayer;
import uk.co.real_logic.artio.fields.CalendricalUtil;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.CancelOnDisconnect;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.protocol.NotConnectedException;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.EpochFractionClock;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.lang.ref.WeakReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.lang.Integer.MIN_VALUE;
import static java.util.concurrent.TimeUnit.*;
import static uk.co.real_logic.artio.CommonConfiguration.NO_FORCED_HEARTBEAT_INTERVAL;
import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE;
import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.builder.Validation.CODEC_VALIDATION_DISABLED;
import static uk.co.real_logic.artio.builder.Validation.CODEC_VALIDATION_ENABLED;
import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;
import static uk.co.real_logic.artio.engine.EngineConfiguration.MAX_COD_TIMEOUT_IN_NS;
import static uk.co.real_logic.artio.engine.EngineConfiguration.validateMessageThrottleOptions;
import static uk.co.real_logic.artio.engine.SessionInfo.UNKNOWN_SEQUENCE_INDEX;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.NO_REQUIRED_POSITION;
import static uk.co.real_logic.artio.fields.RejectReason.*;
import static uk.co.real_logic.artio.library.SessionConfiguration.NO_RESEND_REQUEST_CHUNK_SIZE;
import static uk.co.real_logic.artio.messages.DisconnectReason.*;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;
import static uk.co.real_logic.artio.messages.SessionState.*;
import static uk.co.real_logic.artio.session.DirectSessionProxy.NO_LAST_MSG_SEQ_NUM_PROCESSED;
import static uk.co.real_logic.artio.session.InternalSession.*;

/**
 * Stores information about the current state of a session - no matter whether outbound or inbound.
 * <p>
 * Should only be accessed on a single thread.
 */
public class Session
{
    public static final int UNKNOWN = -1;
    public static final long UNKNOWN_TIME = -1;

    static final short ACTIVE_VALUE = 3;
    static final short LOGGING_OUT_VALUE = 5;
    static final short LOGGING_OUT_AND_DISCONNECTING_VALUE = 6;
    static final short AWAITING_LOGOUT_VALUE = 7;
    static final short DISCONNECTING_VALUE = 8;
    static final short DISCONNECTED_VALUE = 9;
    static final short DISABLED_VALUE = 10;
    static final short AWAITING_ASYNC_PROXY_LOGOUT_VALUE = 11;

    private static final long NO_OPERATION = MIN_VALUE;
    static final long LIBRARY_DISCONNECTED = NO_OPERATION + 1;
    private static final int INITIAL_SEQUENCE_NUMBER = 1;
    public static final long NO_REPLAY_CORRELATION_ID = 0;

    /**
     * The proportion of the maximum heartbeat interval before you send your heartbeat
     */
    private static final double HEARTBEAT_PAUSE_FACTOR = 0.8;

    static final String TEST_REQ_ID = "TEST";
    private static final char[] TEST_REQ_ID_CHARS = TEST_REQ_ID.toCharArray();
    private static final int NO_LOGOUT_REJECT_REASON = -1;

    private final UtcTimestampEncoder timestampEncoder;

    protected final SessionIdStrategy sessionIdStrategy;
    protected final GatewayPublication outboundPublication;
    protected final MutableAsciiBuffer asciiBuffer;
    protected final int libraryId;
    protected final SessionProxy proxy;

    private final EpochFractionClock epochFractionClock;
    private final EpochNanoClock clock;
    private final long sendingTimeWindowInMs;
    private final long reasonableTransmissionTimeInNs;
    private final GatewayPublication inboundPublication;
    private final SessionCustomisationStrategy customisationStrategy;
    private final OnMessageInfo messageInfo;
    private final CancelOnDisconnect cancelOnDisconnect;

    private boolean backpressuredResendRequestResponse = false;
    private boolean backpressuredOutboundValidResendRequest = false;
    private final ResendRequestResponse resendRequestResponse = new ResendRequestResponse();
    private final ResendRequestController resendRequestController;
    private final int forcedHeartbeatIntervalInS;
    private int configuredHeartbeatIntervalInS;
    private final boolean disableHeartbeatRepliesToTestRequests;
    private boolean disconnectOnFirstMessageNotLogon;

    private final BooleanSupplier saveSeqIndexSyncFunc = this::saveSeqIndexSync;
    private final Formatters formatters;
    private boolean initiatorResetSeqNum;

    private CompositeKey sessionKey;
    private SessionState state;
    private String beginString;
    private AtomicCounter receivedMsgSeqNo;
    private AtomicCounter sentMsgSeqNo;

    // Used to trigger a disconnect if we don't receive a resend within expected timeout
    private boolean awaitingResend = INITIAL_AWAITING_RESEND;
    // Equivalent of receivedMsgSeqNo for resent messages
    private int lastResentMsgSeqNo = INITIAL_LAST_RESENT_MSG_SEQ_NO;
    // The last msg seq no before you send the next chunk of the resend request
    private int lastResendChunkMsgSeqNum = INITIAL_LAST_RESEND_CHUNK_MSG_SEQ_NUM;
    // The last msg seq no before you hit the end of the resend request
    private int endOfResendRequestRange = INITIAL_END_OF_RESEND_REQUEST_RANGE;

    // Set when the tryResetSequenceNumbers() method is invoked in order to remember that we're awaiting a logon message
    // reply from the counter-party.
    private boolean awaitingLogonReply = false;

    private boolean awaitingHeartbeat = INITIAL_AWAITING_HEARTBEAT;

    private boolean enableLastMsgSeqNumProcessed;

    protected long connectionId;
    private long id = UNKNOWN;
    private int lastReceivedMsgSeqNum;
    private int lastMsgSeqNumProcessed;
    private int lastSentMsgSeqNum;
    private int sequenceIndex;
    // randomise the start position in order to reduce risk of clashing with another Session instance when you do
    // engine/library hand-over.
    private long nextReplayCorrelationId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private long heartbeatIntervalInNs;
    private long nextRequiredInboundMessageTimeInNs;
    private long sendingHeartbeatIntervalInNs;
    private long nextRequiredHeartbeatTimeInNs;

    private long awaitingLogoutTimeoutInNs;

    private String username;
    private String password;
    private String connectedHost;
    private int connectedPort;
    private long lastLogonTimeInNs = UNKNOWN_TIME;
    private long lastSequenceResetTimeInNs = UNKNOWN_TIME;
    private boolean closedResendInterval;
    private int resendRequestChunkSize;
    private boolean sendRedundantResendRequests;

    private boolean incorrectBeginString = false;

    private FixSessionOwner fixSessionOwner;

    private int logoutRejectReason = NO_LOGOUT_REJECT_REASON;
    private FixDictionary fixDictionary;

    private long cancelOnDisconnectTimeoutWindowInNs = MISSING_LONG;
    private boolean isSlowConsumer;
    CancelOnDisconnectOption cancelOnDisconnectOption;

    private int replaysInFlight = 0;
    protected ConnectionType connectionType;

    private DisconnectReason pendingDisconnectReason;

    private byte[] logoutText;

    Session(
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
        Verify.notNull(state, "session state");
        Verify.notNull(proxy, "session proxy");
        Verify.notNull(outboundPublication, "outboundPublication");
        Verify.notNull(receivedMsgSeqNo, "received MsgSeqNo counter");
        Verify.notNull(sentMsgSeqNo, "sent MsgSeqNo counter");
        Verify.notNull(messageInfo, "messageInfo");
        Verify.notNull(epochFractionClock, "epochFractionClock");
        Verify.notNull(connectionType, "connectionType");
        Verify.notNull(formatters, "formatters");

        this.initiatorResetSeqNum = initiatorResetSeqNum;
        this.resendRequestController = resendRequestController;
        this.forcedHeartbeatIntervalInS = forcedHeartbeatIntervalInS;
        this.disableHeartbeatRepliesToTestRequests = disableHeartbeatRepliesToTestRequests;
        this.messageInfo = messageInfo;
        this.proxy = proxy;
        this.outboundPublication = outboundPublication;
        this.sessionIdStrategy = sessionIdStrategy;
        this.sendingTimeWindowInMs = sendingTimeWindowInMs;
        this.receivedMsgSeqNo = receivedMsgSeqNo;
        this.sentMsgSeqNo = sentMsgSeqNo;
        this.libraryId = libraryId;
        this.lastSentMsgSeqNum = initialSentSequenceNumber - 1;
        this.reasonableTransmissionTimeInNs = MILLISECONDS.toNanos(reasonableTransmissionTimeInMs);
        this.enableLastMsgSeqNumProcessed = enableLastMsgSeqNumProcessed;
        this.asciiBuffer = asciiBuffer;
        this.clock = clock;
        this.inboundPublication = inboundPublication;
        this.customisationStrategy = customisationStrategy;
        this.connectionType = connectionType;
        this.disconnectOnFirstMessageNotLogon = disconnectOnFirstMessageNotLogon;
        this.formatters = formatters;

        connectionId(connectionId);
        setCorrectedSequenceIndex(state, sequenceIndex);
        state(state);
        heartbeatIntervalInS(heartbeatIntervalInS);
        lastMsgSeqNumProcessed = this.enableLastMsgSeqNumProcessed ? 0 : NO_LAST_MSG_SEQ_NUM_PROCESSED;
        timestampEncoder = new UtcTimestampEncoder(epochFractionClock.epochFractionPrecision());
        this.epochFractionClock = epochFractionClock;
        cancelOnDisconnect = new CancelOnDisconnect(
            clock,
            connectionType == ConnectionType.ACCEPTOR,
            deadlineInNs -> !Pressure.isBackPressured(proxy.sendCancelOnDisconnectTrigger(id(), deadlineInNs)));
    }

    // ---------- PUBLIC API ----------

    /**
     * Check if the session is connected to a counter-party. It is worth noting that this
     * method returns the currently known state of the session at a given point in time. It
     * is strictly possible that the counter-party has disconnected at the exact same point
     * in time but that Artio hasn't yet detected, for example if the underlying TCP connection
     * is closed without a `RST` packet being sent by the counter-party.
     *
     * @return true if the session is connected to counter-party, false otherwise.
     */
    public boolean isConnected()
    {
        final SessionState state = state();
        return state != CONNECTING && state != DISCONNECTED && state != DISABLED;
    }

    /**
     * Get the session's state.
     *
     * @return the session's state.
     */
    public SessionState state()
    {
        return state;
    }

    /**
     * Get whether the session is awaiting a resend / replay of messages.
     *
     * @return true iff the session is awaiting a resend / replay of messages.
     */
    public boolean awaitingResend()
    {
        return awaitingResend;
    }

    public boolean awaitingHeartbeat()
    {
        return awaitingHeartbeat;
    }

    public boolean closedResendInterval()
    {
        return closedResendInterval;
    }

    public int resendRequestChunkSize()
    {
        return resendRequestChunkSize;
    }

    public boolean sendRedundantResendRequests()
    {
        return sendRedundantResendRequests;
    }

    /**
     * Get the username associated with this session.
     *
     * @return the username associated with this session.
     */
    public String username()
    {
        return username;
    }

    /**
     * Get the password associated with this session.
     *
     * @return the password associated with this session.
     */
    public String password()
    {
        return password;
    }

    /**
     * Get the sequence number of the last message to be sent from this session.
     *
     * @return the sequence number of the last message to be sent from this session.
     */
    public int lastSentMsgSeqNum()
    {
        return lastSentMsgSeqNum;
    }

    /**
     * Get the sequence number of the last message to be received by this session.
     *
     * @return the sequence number of the last message to be received by this session.
     */
    public int lastReceivedMsgSeqNum()
    {
        return lastReceivedMsgSeqNum;
    }

    /**
     * Get the heartbeat interval for this session in milliseconds. This can be configured locally
     * or agreed by the logon process.
     *
     * @return the heartbeat interval for this session in milliseconds.
     */
    public long heartbeatIntervalInMs()
    {
        return NANOSECONDS.toMillis(heartbeatIntervalInNs);
    }

    /**
     * Get the address of the remote host that your session is connected to.
     * <p>
     * If this is an offline session then this method will return <code>""</code>.
     *
     * @return the address of the remote host that your session is connected to.
     * @see Session#connectedPort()
     */
    public String connectedHost()
    {
        return connectedHost;
    }

    /**
     * Get the id of the connection associated with this session. Sessions always
     * have a connection id.
     * <p>
     * If this is an offline session then this method will return {@link GatewayProcess#NO_CONNECTION_ID}
     *
     * @return the id of the connection associated with this session.
     * @see Session#id()
     */
    public long connectionId()
    {
        return connectionId;
    }

    /**
     * Get the id of this session. If the session hasn't logged in yet, this
     * will return <code>Session.UNKNOWN</code>.
     *
     * @return the id of the session if known.
     * @see Session#UNKNOWN
     */
    public long id()
    {
        return id;
    }

    /**
     * Get the uniquely identifying key of the session. This contains any comp, sub or location
     * ids used to uniquely identify the session.
     *
     * @return the uniquely identifying key of the session
     */
    public CompositeKey compositeKey()
    {
        return sessionKey;
    }

    /**
     * Get the port of the remote host that your session is connected to.
     * <p>
     * If this is an offline session then this method will return {@link #UNKNOWN}
     *
     * @return the port of the remote host that your session is connected to.
     * @see Session#connectedHost()
     */
    public int connectedPort()
    {
        return connectedPort;
    }

    /**
     * Gets the cancel on disconnect option from the Logon message. Note: a missing COD option will result in
     * the value set in
     * {@link uk.co.real_logic.artio.engine.EngineConfiguration#cancelOnDisconnectOption(CancelOnDisconnectOption)}
     * for accepted and {@link CancelOnDisconnectOption#DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT} for initiated sessions.
     *
     * @return cancel on disconnect option received in the Logon message.
     */
    public CancelOnDisconnectOption cancelOnDisconnectOption()
    {
        return cancelOnDisconnectOption;
    }

    /**
     * Gets the cancel on disconnection timeout window in nanoseconds. This is the value received from the Logon
     * message. If the timeout is over the limit (60 seconds) then it will be set to 60 seconds.
     *
     * @return the cancel on disconnection timeout window in nanoseconds.
     */
    public long cancelOnDisconnectTimeoutWindowInNs()
    {
        return cancelOnDisconnectTimeoutWindowInNs;
    }

    /**
     * Gets the slow consumer status for this session. See
     * <a href="https://github.com/artiofix/artio/wiki/Performance-and-Fairness#slow-consumer-support">the wiki</a>
     * for details on what a slow consumer is.
     *
     * @return true if the session is a slow consumer, false otherwise.
     */
    public boolean isSlowConsumer()
    {
        return isSlowConsumer;
    }

    /**
     * Sends a logout message and puts the session into the awaiting logout state.
     * <p>
     * This method will eventually also disconnect the Session, but it won't disconnect the session until you
     * receive a logout message from your counter-party or the heartbeat timeout elapses. That's the difference
     * between this and <code>logoutAndDisconnect</code> - that method just disconnects you as soon as possible.
     *
     * @return the position of the sent message
     * @see Session#logoutAndDisconnect()
     */
    public long startLogout()
    {
        final long position = trySendLogout();
        if (position < 0)
        {
            state(LOGGING_OUT);
        }
        else
        {
            if (!proxy.isAsync())
            {
                onStartLogout();
            }
        }
        return position;
    }

    void onStartLogout()
    {
        awaitingLogoutTimeoutInNs = timeInNs() + heartbeatIntervalInNs;
        state(AWAITING_LOGOUT);
    }

    void onSessionWriterLogout()
    {
        // Your session has tried to write a logout response
        // This is that message being round-tripped via the cluster
        if (state() == AWAITING_ASYNC_PROXY_LOGOUT)
        {
            DisconnectReason reason = LOGOUT;
            if (pendingDisconnectReason != null)
            {
                reason = pendingDisconnectReason;
                pendingDisconnectReason = null;
            }
            requestDisconnect(reason);
        }
        else
        {
            onStartLogout();
        }
    }

    /**
     * Request the session be disconnected.
     *
     * @return the position within the Aeron stream where the disconnect is encoded. If this is &lt; 0 then the
     * operation has failed.
     * @see Session#logoutAndDisconnect()
     */
    public long requestDisconnect()
    {
        return requestDisconnect(APPLICATION_DISCONNECT);
    }

    long requestDisconnect(final DisconnectReason reason)
    {
        long position = NO_OPERATION;
        if (state() != DISCONNECTED)
        {
            position = proxy.sendRequestDisconnect(connectionId, reason);
            state(position < 0 ? DISCONNECTING : DISCONNECTED);
        }

        return position;
    }

    /**
     * Override Artio's message throttle configuration for a given session.
     *
     * @param throttleWindowInMs the time window to apply the throttle over.
     * @param throttleLimitOfMessages the maximum number of messages that can be received within the time window.
     * @return a reply object that represents the state of the operation.
     * @throws IllegalArgumentException if either parameter is &lt; 1.
     * @see uk.co.real_logic.artio.engine.EngineConfiguration#enableMessageThrottle(int, int)
     */
    public Reply<ThrottleConfigurationStatus> throttleMessagesAt(
        final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        validateMessageThrottleOptions(throttleWindowInMs, throttleLimitOfMessages);

        return fixSessionOwner.messageThrottle(
            id, throttleWindowInMs, throttleLimitOfMessages);
    }

    /**
     * Default version of {@link Session#logoutAndDisconnect(byte[])}
     * The result logout message does not have any value in tag 58
     *
     * @return the position within the Aeron stream where the disconnect is encoded.
     */
    public long logoutAndDisconnect()
    {
        return logoutAndDisconnect(APPLICATION_DISCONNECT);
    }

    /**
     * Send a logout message and immediately disconnect the session. You should normally use
     * the <code>startLogout</code> method and not this one.
     * <p>
     * This disconnects the session faster than <code>startLogout</code>. This approach does not linger
     * the Session, awaiting for the disconnect and it's possible that your counter-party misses the logout
     * message. This should only be used when you want to rapidly disconnect the session and are willing
     * to take the risk that the logout message is not received.
     *
     * @param text value to be assigned to tag 58 of logout message.
     *
     * @return the position within the Aeron stream where the disconnect is encoded.
     * @see Session#startLogout()
     */
    public long logoutAndDisconnect(final byte[] text)
    {
        return logoutAndDisconnect(APPLICATION_DISCONNECT, text);
    }

    long logoutAndDisconnect(final DisconnectReason reason)
    {
        return logoutAndDisconnect(reason, null);
    }

    long logoutAndDisconnect(final DisconnectReason reason, final byte[] text)
    {
        long position = NO_OPERATION;
        if (state() != DISCONNECTED)
        {
            text(text);
            position = trySendLogout(text);
            if (position < 0)
            {
                state(LOGGING_OUT_AND_DISCONNECTING);
            }
            else
            {
                // Delay disconnect until the reply logout has been round-tripped via the cluster
                if (proxy.isAsync())
                {
                    disconnectAfterAsyncLogout(reason);
                }
                else
                {
                    position = requestDisconnect(reason);
                }
            }
        }

        return position;
    }

    /**
     * Prepare header with session state
     *
     * @param header the encoder header
     * @return the sent sequence number for the header
     */
    public int prepare(final SessionHeaderEncoder header)
    {
        return prepare(header, clock.nanoTime());
    }

    private int prepare(final SessionHeaderEncoder header, final long sendingTimeNanos)
    {
        final int sentSeqNum = newSentSeqNum();
        header
            .msgSeqNum(sentSeqNum)
            .sendingTime(timestampEncoder.buffer(), timestampEncoder.updateFrom(sendingTimeNanos, NANOSECONDS));

        if (enableLastMsgSeqNumProcessed)
        {
            header.lastMsgSeqNumProcessed(lastMsgSeqNumProcessed);
        }

        if (!header.hasSenderCompID())
        {
            sessionIdStrategy.setupSession(sessionKey, header);
        }

        customisationStrategy.configureHeader(header, id);

        return sentSeqNum;
    }

    /**
     * Tries to send a message on this session. This send method returns after having attempted to write the message
     * into an in memory log buffer. If the return value returned is {@link Publication#BACK_PRESSURED} or
     * {@link Publication#ADMIN_ACTION} then the message won't have been written into the log buffer due to back
     * pressure issues. A retry can be attempted later.
     *
     * If the session is in a state other than ACTIVE (see {@link #isActive()} for details) then these messages are not
     * sent to a counter-party, they are just stored into the archive. When a session with persistent sequence numbers
     * reconnects the counter-party can read the archived messages by sending a resend request.
     *
     * @param encoder the encoder of the message to be sent
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws IndexOutOfBoundsException if the encoded message is too large, if this happens consider
     *                                   increasing {@link CommonConfiguration#sessionBufferSize(int)}
     * @throws NotConnectedException if the underlying Publication to the FixEngine has been closed or its max position
     *                               exceeded.
     */
    public long trySend(final Encoder encoder)
    {
        return trySend(encoder, null, 0);
    }

    /**
     * Tries to send a message on this session. See {{@link #trySend(Encoder)}} for scenarios where this could fail.
     *
     * @param encoder              the encoder of the message to be sent
     * @param metaDataBuffer       the metadata to associate with this message.
     * @param metaDataUpdateOffset the offset within the session's metadata buffer.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws IndexOutOfBoundsException if the encoded message is too large, if this happens consider
     *                                   increasing {@link CommonConfiguration#sessionBufferSize(int)}
     * @throws NotConnectedException if the underlying Publication to the FixEngine has been closed or its max position
     *                               exceeded.
     * @see uk.co.real_logic.artio.library.FixLibrary#writeMetaData(long, int, DirectBuffer, int, int)
     */
    public long trySend(
        final Encoder encoder,
        final DirectBuffer metaDataBuffer,
        final int metaDataUpdateOffset)
    {
        final long sendingTime = clock.nanoTime();
        final int sentSeqNum = prepare(encoder.header(), sendingTime);

        final long result = encoder.encode(asciiBuffer, 0);
        final int length = Encoder.length(result);
        final int offset = Encoder.offset(result);
        final long type = encoder.messageType();

        return trySend(asciiBuffer, offset, length, sentSeqNum, type, sendingTime,
            metaDataBuffer, metaDataUpdateOffset);
    }

    /**
     * Tries to send a message on this session. See {{@link #trySend(Encoder)}} for scenarios where this could fail.
     *
     * @param messageBuffer the buffer with the FIX message in to send
     * @param offset        the offset within the messageBuffer where the message starts
     * @param length        the length of the message within the messageBuffer
     * @param seqNum        the sequence number of the sent message
     * @param messageType   the long encoded message type.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws NotConnectedException if the underlying Publication to the FixEngine has been closed or its max position
     *                               exceeded.
     */
    public long trySend(
        final DirectBuffer messageBuffer, final int offset, final int length, final int seqNum, final long messageType)
    {
        return trySend(messageBuffer, offset, length, seqNum, messageType, null, 0);
    }

    /**
     * Tries to send a message on this session. See {{@link #trySend(Encoder)}} for scenarios where this could fail.
     *
     * @param messageBuffer        the buffer with the FIX message in to send
     * @param offset               the offset within the messageBuffer where the message starts
     * @param length               the length of the message within the messageBuffer
     * @param seqNum               the sequence number of the sent message
     * @param messageType          the long encoded message type.
     * @param metaDataBuffer       the metadata to associate with this message.
     * @param metaDataUpdateOffset the offset within the session's metadata buffer.
     * @return the position in the stream that corresponds to the end of this message or a negative
     * number indicating an error status.
     * @throws NotConnectedException if the underlying Publication to the FixEngine has been closed or its max position
     *                               exceeded.
     * @see uk.co.real_logic.artio.library.FixLibrary#writeMetaData(long, int, DirectBuffer, int, int)
     */
    public long trySend(
        final DirectBuffer messageBuffer,
        final int offset,
        final int length,
        final int seqNum,
        final long messageType,
        final DirectBuffer metaDataBuffer,
        final int metaDataUpdateOffset)
    {
        return trySend(messageBuffer, offset, length, seqNum, messageType, clock.nanoTime(),
            metaDataBuffer, metaDataUpdateOffset);
    }

    private long trySend(
        final DirectBuffer messageBuffer,
        final int offset,
        final int length,
        final int seqNum,
        final long messageType,
        final long timestamp,
        final DirectBuffer metaDataBuffer,
        final int metaDataUpdateOffset)
    {
        // If someone attempts to send a message during a logon / logout or offline then we should archive the message
        // but not send it.
        final long connectionId = this.state == ACTIVE ? this.connectionId : NO_CONNECTION_ID;
        final long sessionId = id();
        final int sequenceIndex1 = sequenceIndex();
        final long position = outboundPublication.saveMessage(
            messageBuffer,
            offset,
            length,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex1,
            connectionId,
            OK,
            seqNum,
            timestamp,
            metaDataBuffer,
            metaDataUpdateOffset);

        if (position > 0)
        {
            lastSentMsgSeqNum(seqNum, position);

            DebugLogger.logFixMessage(FIX_MESSAGE, messageType, "Sent ", messageBuffer, offset, length);
        }

        return position;
    }

    /**
     * Deprecated, uses should be removed. This method will be removed in a future version.
     *
     * Checking whether a session is connected should be replaced with calls to {@link #isActive()}.
     *
     * @return always returns true.
     */
    @Deprecated
    public boolean canSendMessage()
    {
        return true;
    }

    /**
     * Reset the sequence number, so that the specified sequence number will be the sequence
     * number of the next message. This sends a sequence reset message without the gap-fill flag (henceforth referred
     * to as SequenceReset-Reset messages). SequenceReset-Reset messages will be rejected by the counter-party if they
     * are lower than the current sequence number and can thus only be used to increase the sequence number of the
     * session if your session is online.
     * <p>
     * If you have an offline or disconnected session then Artio will treat this as
     * a sequence number reset of that session and a lower sequence number is valid. If you want to reset both outbound
     * and inbound messages then use the {@link #trySendSequenceReset(int, int)} method.
     * <p>
     * If the <code>nextSentMessageSequenceNumber</code> parameter is lower than the existing next sent sequence
     * number then this method treats that as a sequence number reset and increments the sequence index. If it is
     * higher then the sequence index is not modified.
     * <p>
     * If you want to reset the sequence number back to 1 whilst online according to the FIX protocol you should use
     * {@link #tryResetSequenceNumbers()}.
     *
     * @param nextSentMessageSequenceNumber the new sequence number of the next message to be
     *                                      sent.
     * @return the position in the stream that corresponds to the end of this message.
     */
    public long trySendSequenceReset(
        final int nextSentMessageSequenceNumber)
    {
        final boolean resetsSequenceNumbers = resetsSentSequenceNumbers(nextSentMessageSequenceNumber);
        final int newSequenceIndex = sequenceIndex() + (resetsSequenceNumbers ? 1 : 0);
        final long position = proxy.sendSequenceReset(
            lastSentMsgSeqNum, nextSentMessageSequenceNumber, newSequenceIndex, lastMsgSeqNumProcessed);
        if (resetsSequenceNumbers)
        {
            nextSequenceIndex(clock.nanoTime(), position);
        }
        lastSentMsgSeqNum(nextSentMessageSequenceNumber - 1, position);

        return position;
    }

    private boolean resetsSentSequenceNumbers(final int nextSentMessageSequenceNumber)
    {
        // we also consider an == case to be a reset, otherwise we stall sequence index
        // incrementing in the case that no messages have been exchanged since the last sequence number increment
        // this blocks the prune operation from progressing.
        return nextSentMessageSequenceNumber <= this.lastSentMsgSeqNum + 1;
    }

    /**
     * @param nextSentMessageSequenceNumber the new sequence number of the next message to be
     *                                      sent.
     * @return the position in the stream that corresponds to the end of this message.
     * @see #trySendSequenceReset(int)
     */
    @Deprecated
    public long sendSequenceReset(
        final int nextSentMessageSequenceNumber)
    {
        return trySendSequenceReset(nextSentMessageSequenceNumber);
    }

    /**
     * Acts like {@link #trySendSequenceReset(int)} but also resets the received sequence number. This method
     * can be used to reset sequence numbers of offline sessions. See {@link #trySendSequenceReset(int)} to understand
     * the impact on sequenceIndexes and when it is valid to use this method.
     *
     * If the return value of this method is negative it indicates back-pressure has been applied and this method
     * should be retried. See {@link Publication} for constant values indicating the meaning of the back-pressure
     * values.
     *
     * @param nextSentMessageSequenceNumber     the new sequence number of the next message to be
     *                                          sent.
     * @param nextReceivedMessageSequenceNumber the new sequence number of the next message to be
     *                                          received.
     * @return the position in the stream that corresponds to the end of this message.
     * @throws IllegalArgumentException if one of the received or sent sequence numbers is reset without the other
     *                                  being.
     */
    public long trySendSequenceReset(
        final int nextSentMessageSequenceNumber,
        final int nextReceivedMessageSequenceNumber)
    {
        final boolean resetsSentSequenceNumbers = resetsSentSequenceNumbers(nextSentMessageSequenceNumber);
        final boolean resetReceivedSequenceNumbers =
            nextReceivedMessageSequenceNumber <= this.lastReceivedMsgSeqNum + 1;
        if (resetsSentSequenceNumbers != resetReceivedSequenceNumbers)
        {
            throw new IllegalArgumentException("Cannot reset received but not sent sequence numbers");
        }

        final long position = trySendSequenceReset(nextSentMessageSequenceNumber);
        if (position >= 0)
        {
            final int newLastReceivedMessageSequenceNumber = nextReceivedMessageSequenceNumber - 1;
            // Do not reset the sequence index at this point, as it will have been done by the sent case.
            lastReceivedMsgSeqNumOnly(newLastReceivedMessageSequenceNumber);

            final long redactPosition = fixSessionOwner.inboundMessagePosition();
            if (redact(redactPosition))
            {
                fixSessionOwner.enqueueTask(() -> redact(redactPosition));
            }
        }
        return position;
    }

    /**
     * Useful for administrative operations that need to reset the received sequence number of the session in question.
     * This method can be used to reset sequence numbers of offline sessions.
     *
     * @param lastReceivedMsgSeqNum  the sequence number of the last message received.
     * @return the position in the stream that corresponds to the end of this message.
     */
    public long tryUpdateLastReceivedSequenceNumber(
        final int lastReceivedMsgSeqNum)
    {
        // Do not reset the sequence index at this point.
        final long position = saveRedact(NO_REQUIRED_POSITION, lastReceivedMsgSeqNum);
        if (position > 0)
        {
            if (this.lastReceivedMsgSeqNum > lastReceivedMsgSeqNum)
            {
                if (!saveSeqIndexSync())
                {
                    fixSessionOwner.enqueueTask(saveSeqIndexSyncFunc);
                }
            }
            lastReceivedMsgSeqNum(lastReceivedMsgSeqNum);
        }
        return position;
    }

    private boolean saveSeqIndexSync()
    {
        return outboundPublication.saveSeqIndexSync(libraryId, id, sequenceIndex + 1) > 0;
    }

    /**
     * @param nextSentMessageSequenceNumber the new sequence number of the next message to be
     *                                      sent.
     * @param nextReceivedMessageSequenceNumber the new sequence number of the next message to be
     *                                          received.
     * @return the position in the stream that corresponds to the end of this message.
     * @see #trySendSequenceReset(int, int)
     */
    @Deprecated
    public long sendSequenceReset(
        final int nextSentMessageSequenceNumber,
        final int nextReceivedMessageSequenceNumber)
    {
        return trySendSequenceReset(nextSentMessageSequenceNumber, nextReceivedMessageSequenceNumber);
    }

    private void nextSequenceIndex(final long messageTimeInNs, final long position)
    {
        if (position >= 0)
        {
            nextSequenceIndex(messageTimeInNs);
        }
    }

    private void nextSequenceIndex(final long messageTimeInNs)
    {
        sequenceIndex++;
        lastSequenceResetTimeInNs(messageTimeInNs);
    }

    /**
     * Resets both the receiver and sender sequence numbers of this session. When this session is online this is
     * equivalent to sending a Logon message with ResetSeqNum flag set to Y. This method
     * can be used to reset sequence numbers of offline sessions, when used as such, it is equivalent to calling
     * <code>trySendSequenceReset(1, 1)</code>.
     * <p>
     * If you want to send a sequence reset message to a connected FIX session then you should use
     * {@link #trySendSequenceReset(int, int)}. The key difference between these two methods is that this should be
     * used to reset sequence numbers back to 1, whilst {@link #trySendSequenceReset(int, int)} should be used to
     * increase the sequence numbers from their current position.
     *
     * @return the position in the stream that corresponds to the end of this message.
     */
    public long tryResetSequenceNumbers()
    {
        if (state == DISCONNECTED)
        {
            return trySendSequenceReset(1, 1);
        }
        else
        {
            final int sentSeqNum = 1;
            final long position = proxy.sendLogon(
                sentSeqNum,
                configuredHeartbeatIntervalInS,
                username(),
                password(),
                true,
                sequenceIndex() + 1, // the sequence index update is only saved if this message is sent
                lastMsgSeqNumProcessed,
                cancelOnDisconnectOption,
                getCancelOnDisconnectTimeoutWindowInMs());
            nextSequenceIndex(clock.nanoTime(), position);
            lastSentMsgSeqNum(sentSeqNum, position);
            if (position >= 0)
            {
                awaitingLogonReply(true);
            }

            return position;
        }
    }

    /**
     * @return the position in the stream that corresponds to the end of this message.
     * @see #tryResetSequenceNumbers()
     */
    @Deprecated
    public long resetSequenceNumbers()
    {
        return tryResetSequenceNumbers();
    }

    /**
     * Can be used to know when a session's state is ACTIVE. In other words when it is connected to a session
     * on the other side and the logon process has completed and the logout process hasn't started.
     *
     * @return true iff the state == ACTIVE.
     */
    public boolean isActive()
    {
        return state == ACTIVE;
    }

    public boolean isAcceptor()
    {
        return connectionType == ConnectionType.ACCEPTOR;
    }

    /**
     * Get the current sequence index. This is a number that increments everytime the
     * sequence numbers get reset. In combination with the sequence numbers it provides
     * a monotonically increasing sequence.
     *
     * @return the current sequence index
     */
    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    /**
     * Set the current sequence index. See {@link #sequenceIndex()} for details on the sequence index. Care should be
     * taken when setting this value here as it isn't persisted until the next message is sent.
     *
     * @param sequenceIndex the current sequence index
     */
    public void sequenceIndex(final int sequenceIndex)
    {
        this.sequenceIndex = sequenceIndex;
    }

    public void onDisconnect()
    {
        logoutRejectReason = NO_LOGOUT_REJECT_REASON;
        state(DISCONNECTED);
        address("", Session.UNKNOWN);
        connectionId(NO_CONNECTION_ID);
        replaysInFlight = 0;

        cancelOnDisconnect.checkCancelOnDisconnectDisconnect();
    }

    /**
     * Sets the sequence number of the last message received. It is exceedingly unlikely that any Artio users want to
     * use this method to set the sequence number. If you want to update this value in a way that will be reliably
     * persisted and update the state of your index then you should use {@link #tryUpdateLastReceivedSequenceNumber(int)}.
     *
     * This method does check and update the sequence index value.
     *
     * @param lastReceivedMsgSeqNum the sequence number of the last message received.
     * @return this
     */
    public Session lastReceivedMsgSeqNum(final int lastReceivedMsgSeqNum)
    {
        if (this.lastReceivedMsgSeqNum > lastReceivedMsgSeqNum)
        {
            nextSequenceIndex(clock.nanoTime());
        }

        lastReceivedMsgSeqNumOnly(lastReceivedMsgSeqNum);

        return this;
    }

    /**
     * This returns the time of the last received logon message for the current session. The source
     * of time here is configured from your {@link CommonConfiguration#epochNanoClock(EpochNanoClock)}.
     *
     * @return the time of the last received logon message for the current session.
     */
    public long lastLogonTimeInNs()
    {
        return lastLogonTimeInNs;
    }

    /**
     * This returns the time of the last sequence number reset. The source
     * of time here is configured from your {@link CommonConfiguration#epochNanoClock(EpochNanoClock)}.
     * The precision is in nanoseconds.
     *
     * @return the time of the last sequence number reset.
     */
    public long lastSequenceResetTimeInNs()
    {
        return lastSequenceResetTimeInNs;
    }

    /**
     * Deprecated because this is an unreliable and error prone way of setting sequence numbers that doesn't persist
     * over restarts. Please consider using {@link #trySendSequenceReset(int)} instead.
     *
     * @param lastSentMsgSeqNum the new value to set for the lastSentMsgSeqNum.
     * @return the lastSentMsgSeqNum
     */
    public int lastSentMsgSeqNum(final int lastSentMsgSeqNum)
    {
        this.lastSentMsgSeqNum = lastSentMsgSeqNum;
        sentMsgSeqNo.setOrdered(lastSentMsgSeqNum);
        incNextHeartbeatTime();

        return lastSentMsgSeqNum;
    }

    /**
     * Gets whether the session is replaying messages.
     *
     * @return true if the session is replaying messages, false otherwise.
     */
    public boolean isReplaying()
    {
        return replaysInFlight > 0;
    }

    public String toString()
    {
        return getClass().getSimpleName() + "{" +
            "connectionId=" + connectionId +
            ", sessionId=" + id +
            ", state=" + state +
            ", sequenceIndex=" + sequenceIndex +
            ", lastReceivedMsgSeqNum=" + lastReceivedMsgSeqNum +
            ", lastSentMsgSeqNum=" + lastSentMsgSeqNum +
            '}';
    }

    public String beginString()
    {
        return beginString;
    }

    public FixDictionary fixDictionary()
    {
        return fixDictionary;
    }

    public Reply<ReplayMessagesStatus> replayReceivedMessages(
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long timeout)
    {
        return fixSessionOwner.replayReceivedMessages(
            id,
            replayFromSequenceNumber,
            replayFromSequenceIndex,
            replayToSequenceNumber,
            replayToSequenceIndex,
            timeout);
    }

    // ---------- END OF PUBLIC API ----------

    // ---------- Event Handlers & Logic ----------
    Action onInvalidFixDisconnect()
    {
        return Pressure.apply(requestDisconnect(DisconnectReason.INVALID_FIX_MESSAGE));
    }

    private void lastSentMsgSeqNum(final int sentSeqNum, final long position)
    {
        if (position >= 0)
        {
            lastSentMsgSeqNum(sentSeqNum);
        }
    }

    Action onMessage(
        final int msgSeqNo,
        final char[] msgType,
        final long sendingTime,
        final long origSendingTime,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long position)
    {
        return onMessage(
            msgSeqNo, msgType, msgType.length, sendingTime, origSendingTime, isPossDupOrResend, possDup, position);
    }

    Action onMessage(
        final int msgSeqNo,
        final char[] msgType,
        final int msgTypeLength,
        final long sendingTime,
        final long origSendingTime,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long position)
    {
        final long timeInNs = timeInNs();
        final Action action = checkStateAndValidateMessage(msgSeqNo, timeInNs, msgType, msgTypeLength,
            sendingTime, origSendingTime, possDup, position);
        if (action != null)
        {
            return action;
        }
        return checkSeqNoChange(msgSeqNo, timeInNs, isPossDupOrResend, position);
    }

    Action checkStateAndValidateMessage(
        final int msgSeqNo,
        final long timeInNs,
        final char[] msgType,
        final int msgTypeLength,
        final long sendingTime,
        final long origSendingTime,
        final boolean possDup,
        final long position)
    {
        final SessionState state = state();
        if (state == SessionState.CONNECTED || (disconnectOnFirstMessageNotLogon && state == SENT_LOGON))
        {
            // Disconnect if the first message isn't a logon message
            return Pressure.apply(requestDisconnect(FIRST_MESSAGE_NOT_LOGON));
        }
        else if (state == DISCONNECTED || state == DISABLED)
        {
            // Ignore any messages sent by the counter-party after a logout has occurred.
            messageInfo.isValid(false);
            return CONTINUE;
        }
        else
        {
            return validateRequiredFieldsAndCodec(
                msgSeqNo, timeInNs, msgType, msgTypeLength, sendingTime, origSendingTime, possDup, position);
        }
    }

    private Action validateRequiredFieldsAndCodec(
        final int msgSeqNo,
        final long timeInNs,
        final char[] msgType,
        final int msgTypeLength,
        final long sendingTimeInMs,
        final long origSendingTimeInMs,
        final boolean possDup,
        final long position)
    {
        if (msgSeqNo == MISSING_INT)
        {
            final int sentSeqNum = newSentSeqNum();
            return checkPositionAndDisconnect(
                proxy.sendReceivedMessageWithoutSequenceNumber(sentSeqNum, sequenceIndex(), lastMsgSeqNumProcessed),
                MSG_SEQ_NO_MISSING);
        }

        if (CODEC_VALIDATION_ENABLED)
        {
            final Action validationResult = validateCodec(timeInNs, msgSeqNo, msgType, msgTypeLength, sendingTimeInMs,
                origSendingTimeInMs, possDup, position);
            if (validationResult != null)
            {
                return validationResult;
            }
        }

        return null;
    }

    // returns final state of session after validation or null if further processing required
    private Action validateCodec(
        final long timeInNs,
        final int msgSeqNum,
        final char[] msgType,
        final int msgTypeLength,
        final long sendingTimeInMs,
        final long origSendingTimeInMs,
        final boolean possDup,
        final long position)
    {
        if (possDup)
        {
            if (origSendingTimeInMs == UNKNOWN)
            {
                return onInvalidMessage(
                    msgSeqNum,
                    SessionConstants.ORIG_SENDING_TIME,
                    msgType,
                    msgTypeLength,
                    REQUIRED_TAG_MISSING.representation(),
                    position);
            }
            else if (origSendingTimeInMs > sendingTimeInMs)
            {
                return rejectDueToSendingTime(msgSeqNum, msgType, msgTypeLength, position);
            }
        }

        final long timeInMs = timeInNs / CalendricalUtil.NANOS_IN_MILLIS;
        if ((sendingTimeInMs < timeInMs - sendingTimeWindowInMs) ||
            (sendingTimeInMs > timeInMs + sendingTimeWindowInMs))
        {
            final Action action = rejectDueToSendingTime(msgSeqNum, msgType, msgTypeLength, position);
            if (action != ABORT)
            {
                logoutRejectReason(RejectReason.SENDINGTIME_ACCURACY_PROBLEM.representation());
                logoutAndDisconnect(INVALID_SENDING_TIME);
            }

            return action;
        }

        return null;
    }

    Action checkSeqNoChange(
        final int msgSeqNum, final long timeInNs, final boolean isPossDupOrResend, final long position)
    {
        if (awaitingResend)
        {
            incNextReceivedInboundMessageTime(timeInNs);

            if (msgSeqNum == endOfResendMsgSeqNum())
            {
                awaitingResend = false;
                lastResendChunkMsgSeqNum = 0;
                lastResentMsgSeqNo = 0;
                endOfResendRequestRange = 0;
            }
            else if (msgSeqNum == lastResendChunkMsgSeqNum)
            {
                final Action action = checkPosition(trySendResendRequest(
                    msgSeqNum + 1, // Effectively begin
                    endOfResendMsgSeqNum()));   // Effectively ideal end pre chunking
                if (action == CONTINUE)
                {
                    lastResentMsgSeqNo = msgSeqNum;
                }

                return action;
            }
            else if (msgSeqNum > endOfResendRequestRange)
            {
                messageInfo.isValid(false);
                if (sendRedundantResendRequests)
                {
                    return Pressure.apply(trySendResendRequest(endOfResendRequestRange + 1, msgSeqNum));
                }
                else
                {
                    // Here we setup the next resend request for when this chunk of messages ends.
                    if (closedResendInterval)
                    {
                        lastResendChunkMsgSeqNum = endOfResendRequestRange;
                        endOfResendRequestRange = msgSeqNum;
                    }
                    return checkNormalSeqNoChange(msgSeqNum, timeInNs, isPossDupOrResend, position);
                }
            }
            else
            {
                lastResentMsgSeqNo = msgSeqNum;
            }
        }
        else
        {
            return checkNormalSeqNoChange(msgSeqNum, timeInNs, isPossDupOrResend, position);
        }

        return CONTINUE;
    }

    private Action checkNormalSeqNoChange(
        final int msgSeqNum, final long time, final boolean isPossDupOrResend, final long position)
    {
        final int expectedSeqNo = expectedReceivedSeqNum();
        if (expectedSeqNo == msgSeqNum)
        {
            incNextReceivedInboundMessageTime(time);
            lastReceivedMsgSeqNum(msgSeqNum);
        }
        else if (expectedSeqNo < msgSeqNum)
        {
            return requestResend(expectedSeqNo, msgSeqNum);
        }
        else if (/* expectedSeqNo > msgSeqNo && */ !isPossDupOrResend)
        {
            return msgSeqNumTooLow(msgSeqNum, expectedSeqNo, position);
        }
        return CONTINUE;
    }

    private int endOfResendMsgSeqNum()
    {
        return endOfResendRequestRange;
    }

    private Action requestResend(final int expectedSeqNo, final int receivedMsgSeqNo)
    {
        final long position = trySendResendRequest(expectedSeqNo, receivedMsgSeqNo);
        if (position >= 0)
        {
            messageInfo.isValid(false);
            awaitingResend = true;
            lastResentMsgSeqNo = expectedSeqNo - 1;
            lastReceivedMsgSeqNum = receivedMsgSeqNo;
            endOfResendRequestRange = receivedMsgSeqNo;
        }
        return checkPosition(position);
    }

    private long trySendResendRequest(final int expectedSeqNo, final int receivedMsgSeqNo)
    {
        // Cap at a chunk size if specified, otherwise send 0 to indicate infinity or the receivedMsgSeqNo
        final boolean chunkedResend = resendRequestChunkSize != NO_RESEND_REQUEST_CHUNK_SIZE;
        final int cappedEndSeqNo = chunkedResend ? expectedSeqNo + resendRequestChunkSize - 1 : receivedMsgSeqNo;

        final int endSeqNo;
        if (cappedEndSeqNo < receivedMsgSeqNo)
        {
            endSeqNo = cappedEndSeqNo;
        }
        else
        {
            endSeqNo = closedResendInterval ? receivedMsgSeqNo : 0;
        }

        final long position = proxy.sendResendRequest(
            newSentSeqNum(),
            expectedSeqNo,
            endSeqNo,
            sequenceIndex(),
            lastMsgSeqNumProcessed);

        if (position > 0 && chunkedResend)
        {
            lastResendChunkMsgSeqNum = cappedEndSeqNo;
        }

        return position;
    }

    private Action msgSeqNumTooLow(final int msgSeqNo, final int expectedSeqNo, final long position)
    {
        if (redact(position))
        {
            return ABORT;
        }

        return checkPositionAndDisconnect(
            proxy.sendLowSequenceNumberLogout(
                newSentSeqNum(), expectedSeqNo, msgSeqNo, sequenceIndex(), lastMsgSeqNumProcessed),
            MSG_SEQ_NO_TOO_LOW);
    }

    // true if needs backpressure / retry
    private boolean redact(final long position)
    {
        messageInfo.isValid(false);

        return saveRedact(position, lastReceivedMsgSeqNum) < 0;
    }

    private long saveRedact(final long position, final int lastReceivedMsgSeqNum)
    {
        return inboundPublication.saveRedactSequenceUpdate(id, lastReceivedMsgSeqNum, position);
    }

    private Action checkPosition(final long position)
    {
        if (position < 0)
        {
            return ABORT;
        }
        else
        {
            lastSentMsgSeqNum(newSentSeqNum());
            return CONTINUE;
        }
    }

    private Action rejectDueToSendingTime(
        final int msgSeqNo, final char[] msgType, final int msgTypeLength, final long position)
    {
        return onInvalidMessage(
            msgSeqNo,
            SENDING_TIME,
            msgType,
            msgTypeLength,
            SENDINGTIME_ACCURACY_PROBLEM.representation(),
            position);
    }

    void incNextReceivedInboundMessageTime(final long timeInNs)
    {
        this.nextRequiredInboundMessageTimeInNs = timeInNs + heartbeatIntervalInNs + reasonableTransmissionTimeInNs;
    }

    Action onLogon(
        final int heartbeatIntervalInS,
        final int msgSeqNum,
        final long sendingTimeInMs,
        final long origSendingTimeInMs,
        final String username,
        final String password,
        final boolean isPossDupOrResend,
        final boolean resetSeqNumFlag,
        final boolean possDup,
        final long position,
        final CancelOnDisconnectOption cancelOnDisconnectOption,
        final int cancelOnDisconnectTimeoutWindowInMs)
    {
        // We aren't checking CODEC_VALIDATION_ENABLED here because these are required values in order to
        // have a stable FIX connection.
        Action action = validateOrRejectHeartbeat(heartbeatIntervalInS);
        if (action != null)
        {
            return action;
        }

        action = validateOrRejectSendingTime(sendingTimeInMs);
        if (action != null)
        {
            return action;
        }

        final long logonTimeInNs = clock.nanoTime();

        cancelOnDisconnectOption(cancelOnDisconnectOption);
        cancelOnDisconnectTimeoutWindowInNs(MILLISECONDS.toNanos(cancelOnDisconnectTimeoutWindowInMs));

        if (resetSeqNumFlag)
        {
            return onResetSeqNumLogon(heartbeatIntervalInS, username, password, logonTimeInNs, msgSeqNum);
        }

        if (state() == initialState())
        {
            // Initial incoming connection logic
            final int expectedMsgSeqNo = expectedReceivedSeqNum();
            if (expectedMsgSeqNo == msgSeqNum)
            {
                // Send outbound logon message and check if backpressured on the outward client side.
                action = respondToLogon(heartbeatIntervalInS);
                if (action == ABORT)
                {
                    return ABORT;
                }

                if (proxy.seqNumResetRequested())
                {
                    lastReceivedMsgSeqNum = 0;
                    nextSequenceIndex(logonTimeInNs);
                }

                // Don't configure this session as active until successful outbound publication
                setupCompleteLogonState(logonTimeInNs, heartbeatIntervalInS, username, password, timeInNs());
                // If this is the first logon message this session has received, even if sequence
                // index doesn't need incrementing we need to track the lastSequenceResetTime.
                // Other cases handled by nextSequenceIndex()
                if (lastReceivedMsgSeqNum == 0)
                {
                    lastSequenceResetTimeInNs(logonTimeInNs);
                }
                lastReceivedMsgSeqNum(msgSeqNum);

                return CONTINUE;
            }
            else if (expectedMsgSeqNo < msgSeqNum)
            {
                // If their sequence number is higher than expected, we still accept the logon.
                action = respondToLogon(heartbeatIntervalInS);
                if (action == ABORT)
                {
                    return ABORT;
                }

                final boolean requestSeqNumReset = proxy.seqNumResetRequested();
                if (requestSeqNumReset) // if we requested sequence number reset then do not await for replay
                {
                    lastReceivedMsgSeqNum = 0;
                    setupCompleteLogonStateReset(logonTimeInNs, heartbeatIntervalInS, username, password, timeInNs());
                    nextSequenceIndex(logonTimeInNs); // reset asked so reset
                    return CONTINUE;
                }
                else
                {
                    setupCompleteLogonState(logonTimeInNs, heartbeatIntervalInS, username, password, timeInNs());
                    action = requestResend(expectedMsgSeqNo, msgSeqNum);

                    return action;
                }
            }
            else // (msgSeqNo < expectedMsgSeqNo)
            {
                return msgSeqNumTooLow(msgSeqNum, expectedMsgSeqNo, position);
            }
        }
        else
        {
            // You've received a logon and you weren't expecting one and it hasn't got the resetSeqNumFlag set
            return onMessage(
                msgSeqNum, LOGON_MESSAGE_TYPE_CHARS, sendingTimeInMs, origSendingTimeInMs, isPossDupOrResend, possDup,
                position);
        }
    }

    void cancelOnDisconnectOption(final CancelOnDisconnectOption cancelOnDisconnectOption)
    {
        this.cancelOnDisconnectOption = cancelOnDisconnectOption;
        cancelOnDisconnect.cancelOnDisconnectOption(cancelOnDisconnectOption);
    }

    protected Action respondToLogon(final int heartbeatInterval)
    {
        if (connectionType == ConnectionType.ACCEPTOR)
        {
            return replyToLogon(heartbeatInterval);
        }
        else
        {
            // Initiator sends its logon first, so has no need to reply
            return null;
        }
    }

    protected SessionState initialState()
    {
        return connectionType == ConnectionType.ACCEPTOR ? SessionState.CONNECTED : SessionState.SENT_LOGON;
    }

    // Always resets the sequence number to 1
    private Action onResetSeqNumLogon(
        final int heartbeatInterval,
        final String username,
        final String password,
        final long logonTimeInNs,
        final int msgSeqNo)
    {
        if (awaitingLogonReply || lastSentMsgSeqNum == INITIAL_SEQUENCE_NUMBER)
        {
            lastReceivedMsgSeqNumOnly(msgSeqNo);
            awaitingLogonReply(false);
        }
        else
        {
            // if we have just received a reset request and not a response to one we just sent.
            final int logonSequenceIndex = isInitialRequest() ? sequenceIndex() : sequenceIndex() + 1;
            final long position = proxy.sendLogon(INITIAL_SEQUENCE_NUMBER, heartbeatInterval,
                null,
                null,
                true,
                logonSequenceIndex,
                lastMsgSeqNumProcessed,
                cancelOnDisconnectOption,
                getCancelOnDisconnectTimeoutWindowInMs());
            if (position < 0)
            {
                return ABORT;
            }

            lastSentMsgSeqNum(INITIAL_SEQUENCE_NUMBER);
            lastReceivedMsgSeqNum(msgSeqNo);
            lastLogonTimeInNs(logonTimeInNs);
            lastSequenceResetTimeInNs(logonTimeInNs);
        }

        // logon time becomes time of the confirmation message.
        setupCompleteLogonStateReset(logonTimeInNs, heartbeatInterval, username, password, timeInNs());

        return CONTINUE;
    }

    private void setupCompleteLogonStateReset(
        final long logonTime,
        final int heartbeatInterval,
        final String username,
        final String password,
        final long timeInNs)
    {
        setupCompleteLogonState(logonTime, heartbeatInterval, username, password, timeInNs);
        lastSequenceResetTimeInNs(logonTime);
    }

    private void setupCompleteLogonState(
        final long logonTimeInNs,
        final int heartbeatIntervalInS,
        final String username,
        final String password,
        final long timeInNs)
    {
        lastLogonTimeInNs(logonTimeInNs);
        setupLogonState(heartbeatIntervalInS, username, password, timeInNs);
    }

    private void setupLogonState(
        final int heartbeatIntervalInS, final String username, final String password, final long timeInNs)
    {
        incNextReceivedInboundMessageTime(timeInNs);
        heartbeatIntervalInS(heartbeatIntervalInS);
        state(ACTIVE);
        username(username);
        password(password);

        if (fixSessionOwner != null)
        {
            fixSessionOwner.onLogon(this);
        }
    }

    void setupSession(
        final long sessionId,
        final CompositeKey sessionKey,
        final WeakReference<SessionWriter> sessionWriterRef)
    {
        id(sessionId);
        this.sessionKey = sessionKey;
        proxy.setupSession(sessionId, sessionKey);

        if (sessionWriterRef != null)
        {
            final SessionWriter sessionWriter = sessionWriterRef.get();
            if (sessionWriter != null)
            {
                sessionWriter.linkTo((InternalSession)this);
            }
        }
    }

    private Action replyToLogon(final int heartbeatInterval)
    {
        return checkPosition(proxy.sendLogon(
            newSentSeqNum(), heartbeatInterval,
            null, null,
            false,
            sequenceIndex(), lastMsgSeqNumProcessed,
            cancelOnDisconnectOption, getCancelOnDisconnectTimeoutWindowInMs()));
    }

    int getCancelOnDisconnectTimeoutWindowInMs()
    {
        if (cancelOnDisconnectTimeoutWindowInNs == MISSING_LONG)
        {
            return MISSING_INT;
        }

        return (int)NANOSECONDS.toMillis(cancelOnDisconnectTimeoutWindowInNs);
    }

    private Action validateOrRejectSendingTime(final long sendingTimeInMs)
    {
        if (CODEC_VALIDATION_DISABLED && sendingTimeInMs == MISSING_LONG)
        {
            return null;
        }

        final long timeInMs = timeInNs() / CalendricalUtil.NANOS_IN_MILLIS;
        if ((sendingTimeInMs < (timeInMs + sendingTimeWindowInMs) &&
            sendingTimeInMs > (timeInMs - sendingTimeWindowInMs)))
        {
            return null;
        }

        return checkPositionAndDisconnect(
            proxy.sendRejectWhilstNotLoggedOn(
                newSentSeqNum(), SENDINGTIME_ACCURACY_PROBLEM, sequenceIndex(), lastMsgSeqNumProcessed),
            INVALID_SENDING_TIME);
    }

    private Action validateOrRejectHeartbeat(final int heartbeatIntervalInS)
    {
        if (heartbeatIntervalInS < 0)
        {
            messageInfo.isValid(false);

            return checkPositionAndDisconnect(
                proxy.sendNegativeHeartbeatLogout(newSentSeqNum(), sequenceIndex(), lastMsgSeqNumProcessed),
                NEGATIVE_HEARTBEAT_INTERVAL);
        }
        else
        {
            return null;
        }
    }

    private Action checkPositionAndDisconnect(final long position, final DisconnectReason reason)
    {
        final Action action = checkPosition(position);
        if (action != ABORT)
        {
            // Delay disconnect until the logout has been round-tripped via the cluster
            if (proxy.isAsync())
            {
                disconnectAfterAsyncLogout(reason);
            }
            else
            {
                requestDisconnect(reason);
            }
        }

        return action;
    }

    private void disconnectAfterAsyncLogout(final DisconnectReason reason)
    {
        state(AWAITING_ASYNC_PROXY_LOGOUT);
        pendingDisconnectReason = reason;
    }

    private boolean isInitialRequest()
    {
        return 0 == lastReceivedMsgSeqNum();
    }

    Action onLogout(
        final int msgSeqNo,
        final long sendingTimeInMs,
        final long origSendingTimeInMs,
        final boolean possDup,
        final long position)
    {
        final long timeInNs = timeInNs();
        final Action action = validateRequiredFieldsAndCodec(
            msgSeqNo,
            timeInNs,
            LOGON_MESSAGE_TYPE_CHARS,
            LOGON_MESSAGE_TYPE_CHARS.length,
            sendingTimeInMs,
            origSendingTimeInMs,
            possDup,
            position);
        if (action == ABORT)
        {
            return ABORT;
        }

        lastReceivedMsgSeqNum(msgSeqNo);

        cancelOnDisconnect.checkCancelOnDisconnectLogout(timeInNs);

        if (state() == AWAITING_LOGOUT)
        {
            requestDisconnect(LOGOUT);
        }
        else
        {
            logoutAndDisconnect(LOGOUT);
        }

        return CONTINUE;
    }

    Action onTestRequest(
        final int msgSeqNo,
        final char[] testReqId,
        final int testReqIdLength,
        final long sendingTime,
        final long origSendingTime,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long position)
    {
        final SessionState state = this.state;
        if (msgSeqNo == expectedReceivedSeqNum() && state != DISCONNECTED && state != DISABLED &&
            !disableHeartbeatRepliesToTestRequests)
        {
            final int sentSeqNum = newSentSeqNum();
            final long sentPosition = proxy.sendHeartbeat(
                sentSeqNum, testReqId, testReqIdLength, sequenceIndex(), lastMsgSeqNumProcessed);
            if (sentPosition < 0)
            {
                return ABORT;
            }
            else
            {
                lastSentMsgSeqNum(sentSeqNum);
            }
        }

        return onMessage(
            msgSeqNo, TEST_REQUEST_MESSAGE_TYPE_CHARS, sendingTime, origSendingTime, isPossDupOrResend, possDup,
            position);
    }

    Action onSequenceReset(
        final int msgSeqNo,
        final int newSeqNo,
        final boolean gapFillFlag,
        final boolean possDupFlag,
        final long position)
    {
        if (!gapFillFlag)
        {
            return applySequenceReset(msgSeqNo, newSeqNo, position);
        }
        else if (newSeqNo >= msgSeqNo)
        {
            return onGapFill(msgSeqNo, newSeqNo, possDupFlag, position);
        }
        else
        {
            return applySequenceReset(msgSeqNo, newSeqNo, position);
        }
    }

    private Action applySequenceReset(final int receivedMsgSeqNo, final int newSeqNo, final long position)
    {
        final int expectedMsgSeqNo = expectedReceivedSeqNum();

        if (newSeqNo > expectedMsgSeqNo)
        {
            lastReceivedMsgSeqNum(newSeqNo - 1);
        }
        else if (newSeqNo < expectedMsgSeqNo)
        {
            // per FIX spec inbound msgSeqNum should not be increased in the case
            // Test cases applicable to all FIX system: #11.c Receive Sequence-reset (Reset)
            if (redact(position))
            {
                return ABORT;
            }

            return checkPosition(proxy.sendReject(
                newSentSeqNum(),
                receivedMsgSeqNo,
                NEW_SEQ_NO,
                SEQUENCE_RESET_MESSAGE_TYPE_CHARS,
                SEQUENCE_RESET_MESSAGE_TYPE_CHARS.length,
                RejectReason.VALUE_IS_INCORRECT.representation(),
                sequenceIndex(),
                lastMsgSeqNumProcessed));
        }

        return CONTINUE;
    }

    private Action onGapFill(
        final int receivedMsgSeqNo, final int newSeqNo, final boolean possDupFlag, final long position)
    {
        final int impliedSeqNoFromNewSeqNo = newSeqNo - 1;
        final int expectedMsgSeqNo = awaitingResend ? lastResentMsgSeqNo + 1 : expectedReceivedSeqNum();
        // The gapfill has the wrong sequence number.
        if (receivedMsgSeqNo > expectedMsgSeqNo)
        {
            final Action action = checkPosition(trySendResendRequest(expectedMsgSeqNo, receivedMsgSeqNo - 1));
            if (action != ABORT)
            {
                if (awaitingResend)
                {
                    this.lastResentMsgSeqNo = impliedSeqNoFromNewSeqNo;
                }
                else
                {
                    lastReceivedMsgSeqNum(impliedSeqNoFromNewSeqNo);
                }
            }
            return action;
        }
        else if (receivedMsgSeqNo < expectedMsgSeqNo)
        {
            // Ignore the gapfill if it's a possibly a duplicate
            if (!possDupFlag)
            {
                return msgSeqNumTooLow(receivedMsgSeqNo, expectedMsgSeqNo, position);
            }
        }
        else // receivedMsgSeqNo == expectedMsgSeqNo
        {
            if (awaitingResend)
            {
                // NB: in the gapfill case the newSeqNo means the last message in the gap, not the sequence number
                // of the next message.
                // A Resend Request would have put it in the AWAITING_RESEND state, we're now active again.
                if (impliedSeqNoFromNewSeqNo == lastResendChunkMsgSeqNum)
                {
                    final Action action = checkPosition(trySendResendRequest(
                        newSeqNo,
                        endOfResendMsgSeqNum()));
                    if (action == CONTINUE)
                    {
                        lastResentMsgSeqNo = impliedSeqNoFromNewSeqNo;
                    }

                    return action;
                }
                // <= because sequence number can also be increased beyond the end of the sequence gap.
                else if (lastReceivedMsgSeqNum <= impliedSeqNoFromNewSeqNo)
                {
                    awaitingResend = false;
                    lastResentMsgSeqNo = 0;
                    lastResendChunkMsgSeqNum = 0;
                    endOfResendRequestRange = 0;
                    // if new sequence is beyond original sequence
                    // accept it so that new messages will not cause resend request
                    if (lastReceivedMsgSeqNum < impliedSeqNoFromNewSeqNo)
                    {
                        lastReceivedMsgSeqNum(impliedSeqNoFromNewSeqNo);
                    }
                }
                else
                {
                    lastResentMsgSeqNo = impliedSeqNoFromNewSeqNo;
                }
            }
            else
            {
                lastReceivedMsgSeqNum(impliedSeqNoFromNewSeqNo);
            }
        }

        return CONTINUE;
    }

    Action onResendRequest(
        final int msgSeqNum,
        final int beginSeqNum,
        final int endSeqNum,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long sendingTime,
        final long origSendingTime,
        final long position,
        final AsciiBuffer messageBuffer,
        final int messageOffset,
        final int messageLength,
        final AbstractResendRequestDecoder resendRequest)
    {
        final long timeInNs = timeInNs();
        final Action action = checkStateAndValidateMessage(
            msgSeqNum,
            timeInNs,
            RESEND_REQUEST_MESSAGE_TYPE_CHARS,
            RESEND_REQUEST_MESSAGE_TYPE_CHARS.length,
            sendingTime,
            origSendingTime,
            possDup,
            position);

        // validate here, so that out of sequence resendrequest is executed
        // can't rely on own resend request
        if (action != null || !messageInfo.isValid())
        {
            return action;
        }

        final int oldLastReceivedMsgSeqNum = this.lastReceivedMsgSeqNum;
        final Action checkSeqAction = checkNormalSeqNoChange(msgSeqNum, timeInNs, isPossDupOrResend, position);
        if (checkSeqAction == ABORT)
        {
            lastReceivedMsgSeqNum(oldLastReceivedMsgSeqNum);
            return checkSeqAction;
        }

        final boolean replayUpToMostRecent = endSeqNum == Replayer.MOST_RECENT_MESSAGE;
        // Validate endSeqNo
        if (!replayUpToMostRecent)
        {
            if (endSeqNum < beginSeqNum) // Just an invalid range.
            {
                return sendReject(msgSeqNum, END_SEQ_NO, VALUE_IS_INCORRECT, oldLastReceivedMsgSeqNum);
            }
            if (beginSeqNum > lastSentMsgSeqNum) // begin too high - reject
            {
                return sendReject(msgSeqNum, BEGIN_SEQ_NO, VALUE_IS_INCORRECT, oldLastReceivedMsgSeqNum);
            }
        }

        // Min: end too high - replay the valid range and ignore the invalid chunk.
        final int correctedEndSeqNo = replayUpToMostRecent ? lastSentMsgSeqNum : Math.min(lastSentMsgSeqNum, endSeqNum);

        final ResendRequestResponse resendRequestResponse = this.resendRequestResponse;
        if (!backpressuredResendRequestResponse)
        {
            resendRequestController.onResend(this, resendRequest, correctedEndSeqNo, resendRequestResponse);
        }

        if (resendRequestResponse.result())
        {
            int overriddenBeginSeqNum = resendRequestResponse.overriddenBeginSequenceNumber();
            if (overriddenBeginSeqNum != ResendRequestResponse.USE_BEGIN_SEQ_NO)
            {
                overriddenBeginSeqNum = Math.max(overriddenBeginSeqNum, beginSeqNum);
                overriddenBeginSeqNum = Math.min(overriddenBeginSeqNum, correctedEndSeqNo);
            }

            final long correlationId = generateReplayCorrelationId();

            // Notify the sender end point that a replay is going to happen.
            if (!backpressuredResendRequestResponse || backpressuredOutboundValidResendRequest)
            {
                if (saveValidResendRequest(beginSeqNum, messageBuffer, messageOffset, messageLength, correctedEndSeqNo,
                    correlationId, overriddenBeginSeqNum, outboundPublication))
                {
                    lastReceivedMsgSeqNum(oldLastReceivedMsgSeqNum);
                    backpressuredResendRequestResponse = true;
                    backpressuredOutboundValidResendRequest = true;
                    return ABORT;
                }

                backpressuredOutboundValidResendRequest = false;
            }

            if (saveValidResendRequest(beginSeqNum, messageBuffer, messageOffset, messageLength, correctedEndSeqNo,
                correlationId, overriddenBeginSeqNum, inboundPublication))
            {
                lastReceivedMsgSeqNum(oldLastReceivedMsgSeqNum);
                backpressuredResendRequestResponse = true;
                return ABORT;
            }

            backpressuredResendRequestResponse = false;
            replaysInFlight++;
            return CONTINUE;
        }
        else
        {
            final AbstractRejectEncoder rejectEncoder = resendRequestResponse.rejectEncoder();
            if (rejectEncoder != null)
            {
                return sendCustomReject(oldLastReceivedMsgSeqNum, rejectEncoder);
            }

            return sendReject(msgSeqNum, resendRequestResponse.refTagId(), OTHER, oldLastReceivedMsgSeqNum);
        }
    }

    private Action sendCustomReject(final int oldLastReceivedMsgSeqNum, final AbstractRejectEncoder rejectEncoder)
    {
        final long rejectPosition = trySend(rejectEncoder);

        backpressuredResendRequestResponse = Pressure.isBackPressured(rejectPosition);
        if (backpressuredResendRequestResponse)
        {
            lastReceivedMsgSeqNum(oldLastReceivedMsgSeqNum);
            return ABORT;
        }

        return CONTINUE;
    }

    private boolean saveValidResendRequest(
        final int beginSeqNum, final AsciiBuffer messageBuffer, final int messageOffset, final int messageLength,
        final int correctedEndSeqNo, final long correlationId, final int overriddenBeginSeqNum,
        final GatewayPublication publication)
    {
        return Pressure.isBackPressured(publication.saveValidResendRequest(
            id,
            connectionId,
            beginSeqNum,
            correctedEndSeqNo,
            sequenceIndex,
            correlationId,
            overriddenBeginSeqNum,
            messageBuffer,
            messageOffset,
            messageLength));
    }

    private long generateReplayCorrelationId()
    {
        final long replayCorrelationId = nextReplayCorrelationId;
        nextReplayCorrelationId++;
        if (nextReplayCorrelationId == NO_REPLAY_CORRELATION_ID)
        {
            nextReplayCorrelationId++;
        }
        return replayCorrelationId;
    }

    private Action sendReject(
        final int msgSeqNum,
        final int endSeqNo,
        final RejectReason valueIsIncorrect,
        final int oldLastReceivedMsgSeqNum)
    {
        if (proxy.sendReject(
            newSentSeqNum(),
            msgSeqNum,
            endSeqNo,
            RESEND_REQUEST_MESSAGE_TYPE_CHARS,
            RESEND_REQUEST_MESSAGE_TYPE_CHARS.length,
            valueIsIncorrect.representation(),
            sequenceIndex(),
            lastMsgSeqNumProcessed) < 0)
        {
            backpressuredResendRequestResponse = true;
            lastReceivedMsgSeqNum(oldLastReceivedMsgSeqNum);
            return ABORT;
        }
        else
        {
            backpressuredResendRequestResponse = false;
            lastSentMsgSeqNum(newSentSeqNum());
            return CONTINUE;
        }
    }

    Action onReject(
        final int msgSeqNo,
        final long sendingTime,
        final long origSendingTime,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long position)
    {
        return onMessage(msgSeqNo, REJECT_MESSAGE_TYPE_CHARS, sendingTime, origSendingTime, isPossDupOrResend,
            possDup, position);
    }

    boolean onBeginString(final char[] value, final int length, final boolean isLogon)
    {
        final boolean isValid = CodecUtil.equals(value, beginString, length);
        if (!isValid)
        {
            if (!isLogon)
            {
                final int sentMsgSeqNum = newSentSeqNum();
                final long position = proxy.sendIncorrectBeginStringLogout(
                    sentMsgSeqNum, sequenceIndex(), lastMsgSeqNumProcessed);
                if (position < 0)
                {
                    incorrectBeginString = true;
                    state(DISCONNECTING);
                    return false;
                }
                else
                {
                    lastSentMsgSeqNum(sentMsgSeqNum);
                }
            }

            requestDisconnect(INCORRECT_BEGIN_STRING);
        }

        return isValid;
    }

    private void incNextHeartbeatTime()
    {
        nextRequiredHeartbeatTimeInNs = timeInNs() + sendingHeartbeatIntervalInNs;
    }

    private long trySendLogout()
    {
        return trySendLogout(null);
    }

    private long trySendLogout(final byte[] text)
    {
        final int sentSeqNum = newSentSeqNum();
        final long position = (logoutRejectReason == NO_LOGOUT_REJECT_REASON) ?
            proxy.sendLogout(sentSeqNum, sequenceIndex(), lastMsgSeqNumProcessed, text) :
            proxy.sendLogout(sentSeqNum, sequenceIndex(), logoutRejectReason, lastMsgSeqNumProcessed);
        if (position >= 0)
        {
            lastSentMsgSeqNum(sentSeqNum);
        }

        return position;
    }

    // ---------- Setters ----------

    void heartbeatIntervalInS(final int heartbeatIntervalInS)
    {
        this.configuredHeartbeatIntervalInS = heartbeatIntervalInS;
        this.heartbeatIntervalInNs = SECONDS.toNanos(
            forcedHeartbeatIntervalInS != NO_FORCED_HEARTBEAT_INTERVAL ? forcedHeartbeatIntervalInS :
            heartbeatIntervalInS);

        final long timeInNs = timeInNs();
        incNextReceivedInboundMessageTime(timeInNs);
        sendingHeartbeatIntervalInNs = (long)(heartbeatIntervalInNs * HEARTBEAT_PAUSE_FACTOR);
        nextRequiredHeartbeatTimeInNs = timeInNs + sendingHeartbeatIntervalInNs;
    }

    protected Session state(final SessionState state)
    {
        this.state = state;
        return this;
    }

    void id(final long id)
    {
        this.id = id;
    }

    protected long timeInNs()
    {
        return clock.nanoTime();
    }

    // Does not check the sequence index
    void lastReceivedMsgSeqNumOnly(final int value)
    {
        this.lastReceivedMsgSeqNum = value;
        receivedMsgSeqNo.setOrdered(value);
    }

    int expectedReceivedSeqNum()
    {
        return lastReceivedMsgSeqNum + 1;
    }

    int newSentSeqNum()
    {
        return lastSentMsgSeqNum + 1;
    }

    private void incReceivedSeqNum()
    {
        lastReceivedMsgSeqNum++;
        receivedMsgSeqNo.increment();
    }

    void lastSequenceResetTimeInNs(final long lastSequenceResetTimeInNs)
    {
        this.lastSequenceResetTimeInNs = lastSequenceResetTimeInNs;
    }

    Action onInvalidMessage(
        final int refSeqNum,
        final int refTagId,
        final char[] refMsgType,
        final int refMsgTypeLength,
        final int rejectReason,
        final long position)
    {
        messageInfo.isValid(false);

        final Action action = checkPosition(proxy.sendReject(
            newSentSeqNum(),
            refSeqNum,
            refTagId,
            refMsgType,
            refMsgTypeLength,
            rejectReason,
            sequenceIndex(),
            lastMsgSeqNumProcessed));

        if (action != ABORT)
        {
            incReceivedSeqNum();
        }

        return action;
    }

    Action onHeartbeat(
        final int msgSeqNum,
        final char[] testReqID,
        final int testReqIDLength,
        final long sendingTime,
        final long origSendingTime,
        final boolean isPossDupOrResend,
        final boolean possDup,
        final long position)
    {
        if (awaitingHeartbeat && CodecUtil.equals(testReqID, TEST_REQ_ID_CHARS, testReqIDLength))
        {
            awaitingHeartbeat = false;
        }

        return onMessage(
            msgSeqNum, HEARTBEAT_MESSAGE_TYPE_CHARS, sendingTime, origSendingTime, isPossDupOrResend, possDup,
            position);
    }

    Action onInvalidMessageType(
        final int msgSeqNum, final char[] msgType, final int msgTypeLength, final long position)
    {
        return onInvalidMessage(
            msgSeqNum,
            MISSING_INT,
            msgType,
            msgTypeLength,
            INVALID_MSGTYPE.representation(),
            position);
    }

    void disable()
    {
        state(SessionState.DISABLED);
        close();
    }

    int poll(final long timeInNs)
    {
        final short state = state().value();

        final ConnectionType connectionType = this.connectionType;
        int actions = connectionType == ConnectionType.INITIATOR ? initiatorPoll() : 0;

        switch (state)
        {
            case DISCONNECTING_VALUE:
            {
                return actions + onDisconnecting();
            }

            case LOGGING_OUT_VALUE:
            {
                startLogout();

                return actions + 1;
            }

            case LOGGING_OUT_AND_DISCONNECTING_VALUE:
            {
                logoutAndDisconnect(APPLICATION_DISCONNECT, this.logoutText);

                return actions + 1;
            }

            case AWAITING_LOGOUT_VALUE:
            {
                if (timeInNs > awaitingLogoutTimeoutInNs)
                {
                    if (!Pressure.isBackPressured(requestDisconnect()))
                    {
                        state(DISCONNECTING);
                    }
                }

                return actions + 1;
            }

            case DISCONNECTED_VALUE:
            case DISABLED_VALUE:
            // Don't trigger repeated logout message sends whilst the logout is round-tripping the cluster
            case AWAITING_ASYNC_PROXY_LOGOUT_VALUE:
            {
                return actions;
            }

            default:
            {
                final boolean isActive = state == ACTIVE_VALUE;
                if (isActive && timeInNs >= nextRequiredHeartbeatTimeInNs)
                {
                    // Drop when back pressured: retried on duty cycle
                    final int sentSeqNum = newSentSeqNum();
                    final long position = proxy.sendHeartbeat(sentSeqNum, sequenceIndex(), lastMsgSeqNumProcessed);
                    lastSentMsgSeqNum(sentSeqNum, position);
                    actions++;
                }

                if (timeInNs >= nextRequiredInboundMessageTimeInNs)
                {
                    if (awaitingHeartbeat)
                    {
                        // Artio disconnects and logs out the counter-party at this point.
                        // FIX spec (volume 2, page 16 of 4.4) just says "the connection should be
                        //considered lost and corrective action be initiated".

                        // Drop when back pressured: retried on duty cycle
                        logoutAndDisconnect(DisconnectReason.FIX_HEARTBEAT_TIMEOUT);
                        actions++;
                    }
                    else if (isActive)
                    {
                        final int sentSeqNum = newSentSeqNum();
                        if (proxy.sendTestRequest(
                            sentSeqNum, TEST_REQ_ID, sequenceIndex(), lastMsgSeqNumProcessed) >= 0)
                        {
                            lastSentMsgSeqNum(sentSeqNum);
                            awaitingHeartbeat = true;
                            incNextReceivedInboundMessageTime(timeInNs);
                        }
                    }
                    actions++;
                }

                return actions;
            }
        }
    }

    private int initiatorPoll()
    {
        int actions = 0;
        if (state() == SessionState.CONNECTED && id() != UNKNOWN)
        {
            final int sentSeqNum = initiatorResetSeqNum ? 1 : newSentSeqNum();
            final long position = proxy.sendLogon(sentSeqNum, configuredHeartbeatIntervalInS,
                username(),
                password(),
                initiatorResetSeqNum,
                sequenceIndex(),
                lastMsgSeqNumProcessed(),
                cancelOnDisconnectOption,
                getCancelOnDisconnectTimeoutWindowInMs());

            if (position >= 0)
            {
                lastSentMsgSeqNum(sentSeqNum);
                state(SessionState.SENT_LOGON);
            }
            actions++;
        }

        return actions;
    }

    private int onDisconnecting()
    {
        if (incorrectBeginString)
        {
            final int sentMsgSeqNum = newSentSeqNum();
            final long position = proxy.sendIncorrectBeginStringLogout(
                sentMsgSeqNum, sequenceIndex(), lastMsgSeqNumProcessed);
            if (position < 0)
            {
                return 1;
            }
            lastSentMsgSeqNum(sentMsgSeqNum);
            requestDisconnect(INCORRECT_BEGIN_STRING);
        }
        else
        {
            requestDisconnect();
        }

        return 1;
    }

    void libraryConnected(final boolean libraryConnected)
    {
        proxy.libraryConnected(libraryConnected);
    }

    protected long sendingTime(final long sendingTime, final long origSendingTime)
    {
        return UNKNOWN == origSendingTime ? sendingTime : origSendingTime;
    }

    void sessionProcessHandler(final FixSessionOwner fixSessionOwner)
    {
        this.fixSessionOwner = fixSessionOwner;
        cancelOnDisconnect.enqueueTask(fixSessionOwner::enqueueTask);
    }

    void logoutRejectReason(final int logoutRejectReason)
    {
        this.logoutRejectReason = logoutRejectReason;
    }

    void address(final String connectedHost, final int connectedPort)
    {
        this.connectedHost = connectedHost;
        this.connectedPort = connectedPort;
    }

    void username(final String username)
    {
        this.username = username;
    }

    void password(final String password)
    {
        this.password = password;
    }

    void lastLogonTimeInNs(final long logonTimeInNs)
    {
        this.lastLogonTimeInNs = logonTimeInNs;
    }

    void awaitingResend(final boolean awaitingResend)
    {
        this.awaitingResend = awaitingResend;
    }

    void resendRequestChunkSize(final int resendRequestChunkSize)
    {
        this.resendRequestChunkSize = resendRequestChunkSize;
    }

    void closedResendInterval(final boolean closedResendInterval)
    {
        this.closedResendInterval = closedResendInterval;
    }

    void sendRedundantResendRequests(final boolean sendRedundantResendRequests)
    {
        this.sendRedundantResendRequests = sendRedundantResendRequests;
    }

    void updateLastMessageProcessed()
    {
        if (enableLastMsgSeqNumProcessed)
        {
            lastMsgSeqNumProcessed = lastReceivedMsgSeqNum;
        }
    }

    void initialLastReceivedMsgSeqNum(final int lastReceivedMsgSeqNum)
    {
        lastReceivedMsgSeqNum(lastReceivedMsgSeqNum);
        updateLastMessageProcessed();
    }

    int lastMsgSeqNumProcessed()
    {
        return lastMsgSeqNumProcessed;
    }

    void lastResentMsgSeqNo(final int lastResentMsgSeqNo)
    {
        this.lastResentMsgSeqNo = lastResentMsgSeqNo;
    }

    int lastResentMsgSeqNo()
    {
        return lastResentMsgSeqNo;
    }

    void lastResendChunkMsgSeqNum(final int lastResendChunkMsgSeqNum)
    {
        this.lastResendChunkMsgSeqNum = lastResendChunkMsgSeqNum;
    }

    int lastResendChunkMsgSeqNum()
    {
        return lastResendChunkMsgSeqNum;
    }

    void endOfResendRequestRange(final int endOfResendRequestRange)
    {
        this.endOfResendRequestRange = endOfResendRequestRange;
    }

    int endOfResendRequestRange()
    {
        return endOfResendRequestRange;
    }

    void awaitingHeartbeat(final boolean awaitingHeartbeat)
    {
        this.awaitingHeartbeat = awaitingHeartbeat;
    }

    void cancelOnDisconnectTimeoutWindowInNs(final long cancelOnDisconnectTimeoutWindowInNs)
    {
        this.cancelOnDisconnectTimeoutWindowInNs = Math.min(
            MAX_COD_TIMEOUT_IN_NS, cancelOnDisconnectTimeoutWindowInNs);
        cancelOnDisconnect.cancelOnDisconnectTimeoutWindowInNs(this.cancelOnDisconnectTimeoutWindowInNs);
    }

    void fixDictionary(final FixDictionary fixDictionary)
    {
        this.fixDictionary = fixDictionary;
        proxy.fixDictionary(fixDictionary);
        this.beginString = fixDictionary.beginString();
    }

    void connectionId(final long connectionId)
    {
        this.connectionId = connectionId;
        proxy.connectionId(connectionId);
    }

    void enableLastMsgSeqNumProcessed(final boolean enableLastMsgSeqNumProcessed)
    {
        this.enableLastMsgSeqNumProcessed = enableLastMsgSeqNumProcessed;
    }

    void awaitingLogonReply(final boolean awaitingLogonReply)
    {
        this.awaitingLogonReply = awaitingLogonReply;
    }

    OnMessageInfo messageInfo()
    {
        return messageInfo;
    }

    void refreshSequenceNumberCounters(final FixCounters counters)
    {
        closeCounters();
        receivedMsgSeqNo = counters.receivedMsgSeqNo(connectionId, id);
        sentMsgSeqNo = counters.sentMsgSeqNo(connectionId, id);
    }

    /**
     * Close the session object and release its resources.
     * <p>
     * API users should never have to call this method.
     */
    void close()
    {
        closeCounters();
    }

    private void closeCounters()
    {
        sentMsgSeqNo.close();
        receivedMsgSeqNo.close();
    }

    boolean areCountersClosed()
    {
        return sentMsgSeqNo.isClosed() || receivedMsgSeqNo.isClosed();
    }

    void isSlowConsumer(final boolean hasBecomeSlow)
    {
        this.isSlowConsumer = hasBecomeSlow;
    }

    void initiatorResetSeqNum(final boolean initiatorResetSeqNum)
    {
        this.initiatorResetSeqNum = initiatorResetSeqNum;
    }

    void onReplayComplete(final long correlationId)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(REPLAY, formatters.replayComplete.clear().with(replaysInFlight).with(connectionId)
                .with(correlationId));
        }

        // replaysInFlight gets reset to 0 when a disconnect happens, stop this from racing with a replay complete
        // message
        if (replaysInFlight > 0)
        {
            replaysInFlight--;
        }
        resendRequestController.onResendComplete(this, replaysInFlight);
    }

    void disconnectOnFirstMessageNotLogon(final boolean disconnectOnFirstMessageNotLogon)
    {
        this.disconnectOnFirstMessageNotLogon = disconnectOnFirstMessageNotLogon;
    }

    private void text(final byte[] logoutText)
    {
        this.logoutText = logoutText;
    }

    void setCorrectedSequenceIndex(final SessionState state, final int sequenceIndex)
    {
        // If we're an offline session that has never been corrected then we need to set the initial sequence index.
        if (state == DISCONNECTED && sequenceIndex == UNKNOWN_SEQUENCE_INDEX)
        {
            sequenceIndex(0);
        }
        else
        {
            sequenceIndex(sequenceIndex);
        }
    }
}
