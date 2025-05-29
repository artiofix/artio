/*
 * Copyright 2020 Monotonic Ltd.
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

import iLinkBinary.*;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.LangUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.engine.framer.ILink3Key;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.ilink.*;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.CharFormatter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

import static iLinkBinary.KeepAliveLapsed.Lapsed;
import static iLinkBinary.KeepAliveLapsed.NotLapsed;
import static iLinkBinary.RetransmitRequest508Decoder.lastUUIDNullValue;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static uk.co.real_logic.artio.LogTag.FIXP_SESSION;
import static uk.co.real_logic.artio.fixp.AbstractFixPOffsets.*;
import static uk.co.real_logic.artio.fixp.AbstractFixPParser.BOOLEAN_FLAG_TRUE;
import static uk.co.real_logic.artio.fixp.FixPProtocol.BUSINESS_MESSAGE_LOGGING_ENABLED;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.readSofhMessageSize;
import static uk.co.real_logic.artio.ilink.ILink3ConnectionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.messages.DisconnectReason.ENGINE_SHUTDOWN;
import static uk.co.real_logic.artio.messages.DisconnectReason.FAILED_AUTHENTICATION;

/**
 * External users should never rely on this API.
 */
public final class InternalILink3Connection extends InternalFixPConnection implements ILink3Connection
{
    private static final int KEEP_ALIVE_INTERVAL_LAPSED_ERROR_CODE = 20;
    private static final int INVALID_UUID_ERROR_CODE = 13;

    private static final String[] TERMINATE_ERROR_CODES = {
        "Finished: session is being terminated without finalization",
        "Unknown",
        "Unnegotiated: Sending any message when session has not been Negotiated",
        "NotEstablished: Sending any message (except Negotiate) when session has not been established",
        "AlreadyNegotiated: NegotiationResponse was already sent; Negotiate was redundant",
        "NegotiationInProgress: Previous Negotiate still being processed; Wait for NegotiationResponse or timeout",
        "AlreadyEstablished: EstablishmentAck was already sent; Establish was redundant",
        "EstablishInProgress: Previous Establish still being processed; Wait for EstablishmentAck or timeout",
        "CMEAdministeredPortClosure: due to invalid number of Negotiate/Establish attempts being exceeded",
        "Volume Controls - exceeding TPS limit as defined for volume controls (logout action)",
        "InvalidNextSeqNo - value is not greater than the one last used for same UUID or value sent by the client is" +
            " out of acceptable range (MIN, MAX)",
        "InvalidMsgSeqNo - value is lower than the last one used for the same UUID or value is not initialized to 1" +
            " at the beginning of the week or value sent by the client is out of acceptable range (MIN, MAX)",
        "InvalidLastSeqNo - value is lower than the last one used for the same UUID or value sent by the client is" +
            " out of acceptable range (MIN, MAX)",
        "InvalidUUID: UUID value does not match current UUID or value sent by the client is out of acceptable" +
            " range (MIN, MAX)",
        "InvalidTimestamp: Timestamp value does not match with RequestTimestamp sent by CME or value sent by the" +
            " client is out of acceptable range (MIN, MAX)",
        "RequiredUUIDMisssing: null value in UUID field",
        "RequiredRequestTimestampMissing: null value in RequestTimestamp field",
        "RequiredCodeMisssing: null value in Code field",
        "InvalidSOFH: Invalid message length or invalid encoding type specified",
        "DecodingError: Incoming message could not be decoded",
        "KeepAliveIntervalLapsed: KeepAliveInterval has lapsed without any response so terminating session",
        "RequiredNextSeqNoMissing: null value in NextSeqNo field",
        "RequiredKeepAliveIntervalLapsedMissing: null value in KeepAliveIntervalLapsed field",
        "Non-Negotiate/Establish message received when not Negotiated/Established",
        "TerminateInProgress: Previous Terminate still being processed; Wait for Terminate or timeout",
        "Other: any other error condition not mentioned above",
        "DisconnectFromPrimary: Backup session will be terminated as well",
    };

    private static final String[] ESTABLISH_AND_NEGOTIATE_REJECT_ERROR_CODES = {
        "HMACNotAuthenticated: failed authentication because identity is not recognized",
        "HMACNotAvailable: HMAC component is not responding (5sec)",
        "InvalidUUID: UUID is not greater than the one last used or value sent by the client is out of acceptable" +
            " range (MIN, MAX)",
        "InvalidTimestamp: Timestamp is not greater than the one last used or value sent by the client is out of" +
            " acceptable range (MIN, MAX)",
        "RequiredHMACSignatureMissing: empty bytes in HMACSignature field",

        "RequiredAccessKeyIDMissing: empty bytes in AccessKeyID field",
        "RequiredSessionMissing: empty bytes in Session field",
        "RequiredFirmMissing: empty bytes in Firm field",
        "RequiredUUIDMisssing: null value in UUID field",
        "RequiredRequestTimestampMissing: null value in RequestTimestamp field",

        "SessionBlocked: session and firm are not authorized for this port",
        "InvalidKeepAliveInterval: value is out of acceptable range (MIN, MAX)",
        "InvalidAccessKeyID: contains non-printable ASCII character",
        "InvalidSession: contains non-printable ASCII character",
        "InvalidFirm: contains non-printable ASCII character",

        "Volume Controls - exceeding TPS limit as defined for volume controls (reject action)",
        "SplitMessageRejected - Messages queued due to split message penalty being rejected because of" +
            " logout or disconnect",
        "SplitMessageQueue - Reached threshold of messages queued due to split message penalty",
        "RequiredTradingSystemNameMissing: empty bytes in TradingSystemName",
        "RequiredTradingSystemVersionMissing: empty bytes in TradingSystemVersion",

        "RequiredTradingSystemVendorMissing: empty bytes in TradingSystemVendor",
        "RequiredKeepAliveIntervalMissing: null value in KeepAliveInterval field",
        "RequiredNextSeqNoMissing: empty bytes in NextSeqNo field",
        "InvalidTradingSystemName: contains non-prinatable ASCII character",
        "InvalidTradingSystemVersion: contains non-prinatable ASCII character",

        "InvalidTradingSystemVendor: contains non-prinatable ASCII character",
        "26: Unknown",
        "DesignatedBackup - Using Designated backup before designated primary not allowed",
        "NegotiateNotAllowed - Not allowed to negotiate on backup when established on primary"
    };

    private static final UnsafeBuffer NO_BUFFER = new UnsafeBuffer();
    private static final long OK_POSITION = Long.MIN_VALUE;
    private static final int HEADER_LENGTH = SOFH_LENGTH + MessageHeaderEncoder.ENCODED_LENGTH;

    private final NotAppliedResponse response = new NotAppliedResponse();
    private final Deque<RetransmitRequest> retransmitRequests = new ArrayDeque<>();
    private final CharFormatter unknownMessage = new CharFormatter(
        "Unknown Message,templateId=%s,blockLength=%s,version=%s,seqNum=%s,possRetrans=%s%n");
    private final CharFormatter checkSeqNum = new CharFormatter("Checking msgSeqNum=%s,nextRecvSeqNo=%s%n");
    private final CharFormatter retransmitFilled =
        new CharFormatter("RetransmitFilled retransmitFillSeqNo=%s%n");
    private final CharFormatter retransmitFilledNext = new CharFormatter(
        "RetransmitFilledNext uuid=%s,lastUuid=%s,retransmitFillSeqNo=%s,fromSeqNo=%s,msgCount=%s%n");

    private final BusinessReject521Decoder businessReject = new BusinessReject521Decoder();
    private final Consumer<StringBuilder> businessRejectAppendTo = businessReject::appendTo;

    // Reorder buffer
    private int retransmitQueueOffset = 0;
    private final int maxRetransmitQueueSize;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final ExpandableArrayBuffer retransmitQueue = new ExpandableArrayBuffer();

    private final ILink3Proxy proxy;
    private final ILink3Offsets offsets;
    private final ILink3ConnectionConfiguration configuration;
    private final boolean newlyAllocated;
    private final long uuid;

    private final long lastUuid;
    private final long lastConnectionLastReceivedSequenceNumber;
    private final FixPKey key;

    private long retransmitUuid = NOT_AWAITING_RETRANSMIT;
    private long retransmitFillSeqNo = NOT_AWAITING_RETRANSMIT;
    private long retransmitContiguousSeqNo = NOT_AWAITING_RETRANSMIT;
    private long retransmitMaxSeqNo = NOT_AWAITING_RETRANSMIT;
    private long nextRetransmitSeqNo = NOT_AWAITING_RETRANSMIT;

    private long resendTimeInMs;
    private boolean backpressuredNotApplied = false;

    private String resendTerminateReason;
    private int resendTerminateErrorCodes;
    private long lastNegotiateRequestTimestamp;
    private long lastEstablishRequestTimestamp;

    public InternalILink3Connection(
        final FixPProtocol protocol,
        final ILink3ConnectionConfiguration configuration,
        final long connectionId,
        final InitiateILink3ConnectionReply initiateReply,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated,
        final long lastUuid,
        final EpochNanoClock epochNanoClock,
        final FixPMessageDissector dissector)
    {
        this(configuration, connectionId, initiateReply, outboundPublication, inboundPublication, libraryId,
            owner, uuid, lastReceivedSequenceNumber, lastSentSequenceNumber, newlyAllocated, lastUuid, epochNanoClock,
            new ILink3Proxy((Ilink3Protocol)protocol, connectionId, outboundPublication.dataPublication(), dissector,
            epochNanoClock), dissector);
        proxy.ids(connectionId, uuid);
    }

    InternalILink3Connection(
        final ILink3ConnectionConfiguration configuration,
        final long connectionId,
        final InitiateILink3ConnectionReply initiateReply,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated,
        final long lastUuid,
        final EpochNanoClock clock,
        final ILink3Proxy proxy,
        final FixPMessageDissector dissector)
    {
        super(connectionId, outboundPublication, inboundPublication, libraryId, clock, owner, proxy, dissector, 0);
        initiateReply(initiateReply);
        handler(configuration.handler());
        this.ourKeepAliveIntervalInMs = configuration.requestedKeepAliveIntervalInMs();
        this.counterpartyKeepAliveIntervalInMs = configuration.requestedKeepAliveIntervalInMs();

        this.configuration = configuration;
        this.maxRetransmitQueueSize = configuration.maxRetransmitQueueSize();
        this.newlyAllocated = newlyAllocated;
        this.proxy = proxy;

        offsets = new ILink3Offsets();
        nextSentSeqNo(calculateInitialSequenceNumber(
            lastSentSequenceNumber, configuration.initialSentSequenceNumber()));
        nextRecvSeqNo(calculateInitialSequenceNumber(
            lastReceivedSequenceNumber, configuration.initialReceivedSequenceNumber()));

        this.uuid = uuid;
        this.lastUuid = lastUuid;
        this.lastConnectionLastReceivedSequenceNumber = calculateSequenceNumber(lastReceivedSequenceNumber);

        state = State.CONNECTED;

        key = new ILink3Key(
            configuration.port(),
            configuration.host(),
            configuration.accessKeyId());
    }

    private long calculateSequenceNumber(final long lookedUpSequenceNumber)
    {
        return lookedUpSequenceNumber == Session.UNKNOWN ? 0 : lookedUpSequenceNumber;
    }

    // PUBLIC API

    public long tryClaim(
        final MessageEncoderFlyweight message, final int variableLength)
    {
        validateCanSend();

        final long timestamp = requestTimestampInNs();

        final long position = proxy.claimMessage(
            message.sbeBlockLength() + variableLength, message, timestamp);

        if (position > 0)
        {
            final int templateId = message.sbeTemplateId();
            final MutableDirectBuffer buffer = message.buffer();
            final int messageOffset = message.offset();

            clientSeqNum(templateId, buffer, messageOffset, nextSentSeqNo++);

            // NB: possRetrans field does not need to be set because it is always false in this claim API
            // and the false byte is 0, which is what Aeron buffers are initialised to.

            final int sendingTimeEpochOffset = offsets.sendingTimeEpochOffset(templateId);
            if (sendingTimeEpochOffset != MISSING_OFFSET)
            {
                buffer.putLong(messageOffset + sendingTimeEpochOffset, timestamp, LITTLE_ENDIAN);
            }
        }

        return position;
    }

    public void terminate(final String reason, final int errorCodes)
    {
        validateCanSend();

        sendTerminate(reason, errorCodes, State.UNBINDING, State.RESEND_TERMINATE);
    }

    public long tryRetransmitRequest(final long uuid, final long fromSeqNo, final int msgCount)
    {
        final int retransmitRequestMessageLimit = configuration.retransmitRequestMessageLimit();
        if (msgCount > retransmitRequestMessageLimit)
        {
            throw new IllegalArgumentException(
                "msgCount [" + msgCount + "] cannot be larger than " + retransmitRequestMessageLimit);
        }

        onAttemptedToSendMessage();
        final long requestTimestamp = requestTimestampInNs();
        final long thisUuid = this.uuid;
        final long lastUuid = lookupRetransmitLastUuid(uuid, thisUuid);
        final long position = proxy.sendRetransmitRequest(thisUuid, lastUuid, requestTimestamp, fromSeqNo, msgCount);
        if (!Pressure.isBackPressured(position))
        {
            retransmitFillTimeoutInNs(requestTimestamp);
            retransmitUuid = uuid;
            nextRetransmitSeqNo = fromSeqNo;
            retransmitFillSeqNo = fromSeqNo + msgCount - 1;
        }
        return position;
    }

    private long lookupRetransmitLastUuid(final long uuid, final long thisUuid)
    {
        return uuid == thisUuid ? lastUUIDNullValue() : uuid;
    }

    private boolean sendTerminate(
        final String reason, final int errorCodes, final State finalState, final State resendState)
    {
        final long requestTimestamp = requestTimestampInNs();
        final long position = proxy.sendTerminate(
            reason,
            uuid,
            requestTimestamp,
            errorCodes);

        if (position > 0)
        {
            state = finalState;
            resendTerminateReason = null;
            resendTerminateErrorCodes = 0;
            return true;
        }
        else
        {
            state = resendState;
            resendTerminateReason = reason;
            resendTerminateErrorCodes = errorCodes;
            return false;
        }
    }

    public long uuid()
    {
        return uuid;
    }

    public long lastUuid()
    {
        return lastUuid;
    }

    public FixPKey key()
    {
        return key;
    }

    public long retransmitFillSeqNo()
    {
        return retransmitFillSeqNo;
    }

    public long nextRetransmitSeqNo()
    {
        return nextRetransmitSeqNo;
    }

    // END PUBLIC API

    public long nextReceiveMessageTimeInMs()
    {
        return nextReceiveMessageTimeInMs;
    }

    public long nextSendMessageTimeInMs()
    {
        return nextSendMessageTimeInMs;
    }

    int retransmitQueueSize()
    {
        return retransmitQueueOffset;
    }

    private long calculateInitialSequenceNumber(
        final long lastSequenceNumber, final long initialSequenceNumber)
    {
        if (!this.configuration.reEstablishLastConnection())
        {
            return 1;
        }

        if (initialSequenceNumber == AUTOMATIC_INITIAL_SEQUENCE_NUMBER)
        {
            if (lastSequenceNumber == Session.UNKNOWN)
            {
                return 1;
            }
            else
            {
                return lastSequenceNumber + 1;
            }
        }
        return initialSequenceNumber;
    }

    private boolean sendNegotiate()
    {
        final long requestTimestamp = requestTimestampInNs();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId + '\n' + firmId;
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);

        final long position = proxy.sendNegotiate(
            hMACSignature, configuration.accessKeyId(), uuid, requestTimestamp, sessionId, firmId);

        if (position > 0)
        {
            state = State.SENT_NEGOTIATE;
            resendTimeInMs = nextRecvTimeoutInMs();
            lastNegotiateRequestTimestamp = requestTimestamp;
            return true;
        }

        return false;
    }

    private boolean sendEstablish()
    {
        final long requestTimestamp = requestTimestampInNs();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String tradingSystemName = configuration.tradingSystemName();
        final String tradingSystemVersion = configuration.tradingSystemVersion();
        final String tradingSystemVendor = configuration.tradingSystemVendor();
        final int keepAliveInterval = configuration.requestedKeepAliveIntervalInMs();
        final String accessKeyId = configuration.accessKeyId();

        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId +
            '\n' + firmId + '\n' + tradingSystemName + '\n' + tradingSystemVersion + '\n' + tradingSystemVendor +
            '\n' + nextSentSeqNo + '\n' + keepAliveInterval;
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);

        final long position = proxy.sendEstablish(hMACSignature,
            accessKeyId,
            tradingSystemName,
            tradingSystemVendor,
            tradingSystemVersion,
            uuid,
            requestTimestamp,
            nextSentSeqNo,
            sessionId,
            firmId,
            keepAliveInterval);

        if (position > 0)
        {
            nextSendMessageTimeInMs = resendTimeInMs = nextSendTimeoutInMs();
            lastEstablishRequestTimestamp = requestTimestamp;
            state = State.SENT_ESTABLISH;
            return true;
        }

        return false;
    }

    private byte[] calculateHMAC(final String canonicalRequest)
    {
        final String userKey = configuration.userKey();

        try
        {
            final Mac sha256HMAC = getHmac();

            // Decode the key first, since it is base64url encoded
            final byte[] decodedUserKey = Base64.getUrlDecoder().decode(userKey);
            final SecretKeySpec secretKey = new SecretKeySpec(decodedUserKey, "HmacSHA256");
            sha256HMAC.init(secretKey);

            // Calculate HMAC
            return sha256HMAC.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        }
        catch (final InvalidKeyException | IllegalStateException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    private Mac getHmac()
    {
        try
        {
            return Mac.getInstance("HmacSHA256");
        }
        catch (final NoSuchAlgorithmException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    protected int poll(final long timeInMs)
    {
        final State state = this.state;
        switch (state)
        {
            case CONNECTED:
                return pollConnected();

            case SENT_NEGOTIATE:
                return pollSentNegotiate(timeInMs);

            case RETRY_NEGOTIATE:
                return pollRetryNegotiate(timeInMs);

            case NEGOTIATED:
                return sendEstablish() ? 1 : 0;

            case RETRY_ESTABLISH:
                return pollRetryEstablish(timeInMs);

            case SENT_ESTABLISH:
                return pollSentEstablish(timeInMs);

            case RESEND_TERMINATE:
                return pollResendTerminate();

            case RESEND_TERMINATE_ACK:
                return pollResendTerminateAck();

            default:
                return commonPoll(state, timeInMs);
        }
    }

    private int pollResendTerminateAck()
    {
        sendTerminateAck(resendTerminateReason, resendTerminateErrorCodes);
        return 0;
    }

    private int pollResendTerminate()
    {
        sendTerminate(resendTerminateReason, resendTerminateErrorCodes, State.UNBINDING, State.RESEND_TERMINATE);
        return 0;
    }

    protected void keepAliveExpiredTerminate()
    {
        final long expiry = 2 * counterpartyKeepAliveIntervalInMs;
        terminate(expiry + "ms expired without message", KEEP_ALIVE_INTERVAL_LAPSED_ERROR_CODE);
    }

    protected int pollEstablished(final long timeInMs)
    {
        int events = 0;
        final long retransmitFillTimeoutInMs = this.retransmitFillTimeoutInMs;
        if (retransmitFillTimeoutInMs != NOT_AWAITING_RETRANSMIT && timeInMs >= retransmitFillTimeoutInMs)
        {
            handler.onRetransmitTimeout(this);
            events++;
            // suppress future calls.
            this.retransmitFillTimeoutInMs = NOT_AWAITING_RETRANSMIT;
        }

        if (timeInMs > nextReceiveMessageTimeInMs)
        {
            sendSequence(Lapsed);

            onReceivedMessage();

            this.state = State.AWAITING_KEEPALIVE;
            events++;
        }
        else if (timeInMs > nextSendMessageTimeInMs)
        {
            sendSequence(NotLapsed);
            events++;
        }

        return events;
    }

    private int pollSentEstablish(final long timeInMs)
    {
        if (timeInMs > resendTimeInMs)
        {
            if (sendEstablish())
            {
                this.state = State.RETRY_ESTABLISH;
                return 1;
            }
        }
        return 0;
    }

    private int pollRetryEstablish(final long timeInMs)
    {
        if (timeInMs > resendTimeInMs)
        {
            onEstablishFailure();
            fullyUnbind();
            return 1;
        }
        return 0;
    }

    private int pollRetryNegotiate(final long timeInMs)
    {
        if (timeInMs > resendTimeInMs)
        {
            onNegotiateFailure();
            fullyUnbind();
            return 1;
        }
        return 0;
    }

    private int pollSentNegotiate(final long timeInMs)
    {
        if (timeInMs > resendTimeInMs)
        {
            if (sendNegotiate())
            {
                this.state = State.RETRY_NEGOTIATE;
                return 1;
            }
        }
        return 0;
    }

    private int pollConnected()
    {
        if (!configuration.reEstablishLastConnection() || newlyAllocated)
        {
            return sendNegotiate() ? 1 : 0;
        }
        else
        {
            return sendEstablish() ? 1 : 0;
        }
    }

    protected int pollExtraEstablished(final long timeInMs)
    {
        final long retransmitFillTimeoutInMs = this.retransmitFillTimeoutInMs;
        if (retransmitFillTimeoutInMs != NOT_AWAITING_RETRANSMIT && timeInMs >= retransmitFillTimeoutInMs)
        {
            handler.onRetransmitTimeout(this);
            // suppress future calls.
            this.retransmitFillTimeoutInMs = NOT_AWAITING_RETRANSMIT;
            return 1;
        }
        return 0;
    }

    public long trySendSequence()
    {
        return sendSequence(NotLapsed);
    }

    protected long sendSequence(final boolean lapsed)
    {
        return sendSequence(lapsed ? Lapsed : NotLapsed);
    }

    private long sendSequence(final KeepAliveLapsed keepAliveIntervalLapsed)
    {
        final long position = proxy.sendSequence(uuid, nextSentSeqNo, FTI.Primary, keepAliveIntervalLapsed);
        if (position > 0)
        {
            onAttemptedToSendMessage();
        }

        // Will be retried on next poll if enqueue back pressured.
        return position;
    }

    // EVENT HANDLERS

    public Action onNegotiationResponse(
        final long uUID,
        final long requestTimestamp,
        final int secretKeySecureIDExpiration,
        final long previousSeqNo,
        final long previousUUID)
    {
        if (checkBoundaryErrors("Negotiate", uUID, requestTimestamp, lastNegotiateRequestTimestamp))
        {
            return CONTINUE;
        }

        state = State.NEGOTIATED;
        sendEstablish();

        return CONTINUE;
    }

    private boolean checkBoundaryErrors(
        final String name, final long uUID, final long requestTimestamp, final long expectedRequestTimestamp)
    {
        if (uUID != uuid())
        {
            connectionError(new IllegalResponseException("Invalid " + name + ".uuid=" + uUID + ",expected=" + uuid()));
            return true;
        }

        if (expectedRequestTimestamp != requestTimestamp)
        {
            connectionError(new IllegalResponseException(
                "Invalid " + name + ".requestTimestamp=" + requestTimestamp +
                ",expected=" + expectedRequestTimestamp));
            return true;
        }

        return false;
    }

    public Action onNegotiationReject(
        final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        state = State.NEGOTIATE_REJECTED;
        return onReject(
            uUID,
            requestTimestamp,
            lastNegotiateRequestTimestamp,
            "Negotiate rejected: " + reason,
            errorCodes);
    }

    private void connectionError(final Exception error)
    {
        onReplyError(error);

        requestDisconnect(FAILED_AUTHENTICATION);
        owner.remove(this);
    }

    public Action onEstablishmentAck(
        final long uUID,
        final long requestTimestamp,
        final long nextSeqNo,
        final long previousSeqNo,
        final long previousUUID,
        final int keepAliveInterval,
        final int secretKeySecureIDExpiration)
    {
        if (checkBoundaryErrors("EstablishmentAck", uUID, requestTimestamp, lastEstablishRequestTimestamp))
        {
            return CONTINUE;
        }

        state = State.ESTABLISHED;
        initiateReply.onComplete(this);
        initiateReply = null;
        nextReceiveMessageTimeInMs = nextRecvTimeoutInMs();

        if (previousUUID == lastUuid)
        {
            if (previousSeqNo > lastConnectionLastReceivedSequenceNumber)
            {
                final long impliedNextRecvSeqNo = previousSeqNo + 1;
                final long oldNextRecvSeqNo = lastConnectionLastReceivedSequenceNumber + 1;
                final long retransmitLastUuid = uUID == previousUUID ? lastUUIDNullValue() : previousUUID;
                return onInvalidSequenceNumber(
                    retransmitLastUuid, impliedNextRecvSeqNo, oldNextRecvSeqNo, impliedNextRecvSeqNo);
            }
        }

        checkLowSequenceNumberCase(nextSeqNo, nextRecvSeqNo);
        return CONTINUE;
    }

    public Action onEstablishmentReject(
        final String reason, final long uUID, final long requestTimestamp, final long nextSeqNo, final int errorCodes)
    {
        state = State.ESTABLISH_REJECTED;
        final String reasonMsg = "Establishment rejected: " + reason + ",nextSeqNo=" + nextSeqNo;
        return onReject(uUID, requestTimestamp, lastEstablishRequestTimestamp, reasonMsg, errorCodes);
    }

    private Action onReject(
        final long msgUuid,
        final long msgRequestTimestamp,
        final long expectedRequestTimestamp,
        final String reasonMsg,
        final int errorCodes)
    {
        final StringBuilder msgBuilder = new StringBuilder(reasonMsg);
        if (msgUuid != uuid)
        {
            msgBuilder
                .append("Incorrect uuid=")
                .append(msgUuid)
                .append(",expected=")
                .append(uuid)
                .append(",");
        }

        if (msgRequestTimestamp != expectedRequestTimestamp)
        {
            msgBuilder
                .append("Incorrect requestTimestamp=")
                .append(msgRequestTimestamp)
                .append(",expected=")
                .append(expectedRequestTimestamp)
                .append(",");
        }

        msgBuilder
            .append(",errorCodes=")
            .append(errorCodes)
            .append(",errorMessage=")
            .append(ESTABLISH_AND_NEGOTIATE_REJECT_ERROR_CODES[errorCodes]);

        connectionError(new IllegalResponseException(msgBuilder.toString()));

        return CONTINUE;
    }

    public Action onTerminate(final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        // We initiated termination
        if (state == State.UNBINDING)
        {
            fullyUnbind();
        }
        // The counter-party initiated termination
        else
        {
            final String terminateErrorReason = terminateErrorReason(errorCodes);
            if (terminateErrorReason != null)
            {
                DebugLogger.log(FIXP_SESSION, terminateErrorReason);
            }

            sendTerminateAck(reason, errorCodes);
        }

        checkUuid(uUID);

        return CONTINUE;
    }

    private String terminateErrorReason(final int errorCodes)
    {
        if (errorCodes >= 2 && errorCodes < TERMINATE_ERROR_CODES.length)
        {
            return TERMINATE_ERROR_CODES[errorCodes];
        }

        return null;
    }

    private void sendTerminateAck(final String reason, final int errorCodes)
    {
        if (sendTerminate(reason, errorCodes, State.UNBOUND, State.RESEND_TERMINATE_ACK))
        {
            if (initiateReply != null)
            {
                final String terminateErrorReason = terminateErrorReason(errorCodes);
                String message = "Connection Terminated: reason=" + reason;
                if (terminateErrorReason != null)
                {
                    message += ",errorCode=" + terminateErrorReason;
                }
                onReplyError(new IllegalStateException(message));
            }

            fullyUnbind();
        }
    }

    private void checkUuid(final long uUID)
    {
        if (uUID != uuid())
        {
            handler.onError(this, new IllegalResponseException("Invalid uuid=" + uUID + ",expected=" + uuid()));
        }
    }

    public Action onSequence(
        final long uUID, final long nextSeqNo, final FTI fti, final KeepAliveLapsed keepAliveLapsed)
    {
        if (uUID == uuid())
        {
            onReceivedMessage();

            if (checkLowSequenceNumberCase(nextSeqNo, nextRecvSeqNo))
            {
                final long expectedNextRecvSeqNo = this.nextRecvSeqNo;
                nextRecvSeqNo(nextSeqNo);

                if (expectedNextRecvSeqNo < nextSeqNo)
                {
                    // sequence gap, initiate retransmission.
                    return onInvalidSequenceNumber(lastUUIDNullValue(), nextSeqNo, expectedNextRecvSeqNo, nextSeqNo);
                }
                else if (retransmitFillSeqNo != NOT_AWAITING_RETRANSMIT)
                {
                    // implied expectedNextRecvSeqNo == nextSeqNo
                    // Sequence number at this point indicates that CME won't send any more retransmit requests
                    // and we should consider the retransmit to be filled.
                    return onRetransmitFilled();
                }
            }
            else
            {
                // low sequence number triggered disconnect
                return handler.onSequence(this, nextSeqNo);
            }

            // Reply to any warning messages to keep the session alive.
            if (keepAliveLapsed == Lapsed)
            {
                sendSequence(NotLapsed);
            }

            return handler.onSequence(this, nextSeqNo);
        }

        return CONTINUE;
    }

    private boolean checkLowSequenceNumberCase(final long seqNo, final long nextRecvSeqNo)
    {
        if (seqNo < nextRecvSeqNo)
        {
            terminate(String.format(
                "seqNo=%s,expecting=%s",
                seqNo,
                this.nextRecvSeqNo), 0);

            return false;
        }

        return true;
    }

    public Action onNotApplied(final long uUID, final long fromSeqNo, final long msgCount)
    {
        // Don't invoke the handler on the backpressured retry
        if (!backpressuredNotApplied)
        {
            // Stop messages from being sent whilst a retransmit is underway.
            final State oldState = this.state;
            state = State.RETRANSMITTING;
            final Action action = handler.onNotApplied(this, fromSeqNo, msgCount, response);
            if (action == ABORT)
            {
                state = oldState;
                return ABORT;
            }
            onReceivedMessage();
        }

        final long position;
        if (response.shouldRetransmit())
        {
            position = inboundPublication.saveValidResendRequest(
                uUID,
                connectionId,
                fromSeqNo,
                fromSeqNo + msgCount - 1,
                0,
                0,
                NO_BUFFER,
                0,
                0);
        }
        else
        {
            position = sendSequence(NotLapsed);
            if (position > 0)
            {
                state = State.ESTABLISHED;
            }
        }

        final boolean backPressured = Pressure.isBackPressured(position);
        backpressuredNotApplied = backPressured;

        return backPressured ? ABORT : CONTINUE;
    }

    protected void onReplayComplete()
    {
        state = State.ESTABLISHED;
    }

//    private

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int templateId,
        final int blockLength,
        final int version,
        final int totalLength)
    {
        onReceivedMessage();

        if (state == State.ESTABLISHED)
        {
            final long seqNum = exchangeSeqNum(buffer, offset);
            if (seqNum == MISSING_OFFSET)
            {
                return CONTINUE;
            }

            final long uuid = offsets.uuid(templateId, buffer, offset);
            if (uuid == MISSING_OFFSET)
            {
                return CONTINUE;
            }

            final int possRetrans = offsets.possRetrans(templateId, buffer, offset);
            if (possRetrans == BOOLEAN_FLAG_TRUE)
            {
                return onPossRetransMessage(
                    buffer, offset, templateId, blockLength, version, totalLength, seqNum, uuid);
            }

            if (uuid != this.uuid)
            {
                final Action action = handler.onError(this, new IllegalStateException(String.format(
                    "Have received invalid uuid: %d when it should be %d, disconnecting",
                    uuid,
                    this.uuid)));
                if (action != ABORT)
                {
                    terminate("Invalid UUID", INVALID_UUID_ERROR_CODE);
                }
                return action;
            }

            final long nextRecvSeqNo = this.nextRecvSeqNo;
            DebugLogger.log(FIXP_SESSION, checkSeqNum, seqNum, nextRecvSeqNo);
            if (checkLowSequenceNumberCase(seqNum, nextRecvSeqNo))
            {
                if (nextRecvSeqNo == seqNum)
                {
                    nextRecvSeqNo(seqNum + 1);

                    checkBusinessRejectSequenceNumber(buffer, offset, templateId, blockLength, version);
                    if (retransmitFillSeqNo == NOT_AWAITING_RETRANSMIT)
                    {
                        return onBusinessMessage(buffer, offset, templateId, blockLength, version, false,
                            totalLength);
                    }
                    else
                    {
                        enqueueRetransmitMessage(buffer, offset, totalLength, seqNum);

                        return CONTINUE;
                    }
                }
                else /* nextRecvSeqNo > seqNum */
                {
                    // We could queue this instead of just passing it on to the customer's application but this
                    // hasn't been requested as of yet
                    checkBusinessRejectSequenceNumber(buffer, offset, templateId, blockLength, version);

                    enqueueRetransmitMessage(buffer, offset, totalLength, seqNum);
                    if (retransmitFillSeqNo != NOT_AWAITING_RETRANSMIT)
                    {
                        // Detected a gap within the normal sequence of messages,
                        // we need to keep track of the sequence number position of this message to ensure that we
                        // don't accidentally hand it off during the process of the current retransmit enqueue buffer.
                        retransmitMaxSeqNo = nextRecvSeqNo - 1;
                    }

                    return onInvalidSequenceNumber(seqNum);
                }
            }
            else
            {
                return CONTINUE;
            }
        }
        else
        {
            onUnexpectedMessage(buffer, offset, templateId, blockLength, version);
            return CONTINUE;
        }
    }

    private void onUnexpectedMessage(
        final DirectBuffer buffer, final int offset, final int templateId, final int blockLength, final int version)
    {
        final long seqNum = offsets.seqNum(templateId, buffer, offset);
        final boolean possRetrans = offsets.possRetrans(templateId, buffer, offset) == BOOLEAN_FLAG_TRUE;

        if (DebugLogger.isEnabled(FIXP_SESSION))
        {
            unknownMessage.clear()
                .with(templateId)
                .with(blockLength)
                .with(version)
                .with(seqNum)
                .with(possRetrans);
            DebugLogger.log(FIXP_SESSION, unknownMessage);

            if (templateId == BusinessReject521Decoder.TEMPLATE_ID)
            {
                businessReject.wrap(buffer, offset, blockLength, version);
                DebugLogger.logSbeDecoder(FIXP_SESSION, "> ", businessRejectAppendTo);
            }
        }
    }

    private Action onPossRetransMessage(
        final DirectBuffer buffer,
        final int offset,
        final int templateId,
        final int blockLength,
        final int version,
        final int totalLength,
        final long seqNum,
        final long uuid)
    {
        final long expectedSeqNo = this.nextRetransmitSeqNo;
        if (seqNum > expectedSeqNo)
        {
            retransmitContiguousSeqNo = expectedSeqNo - 1;

            final long retransmitLastUuid = lookupRetransmitLastUuid(uuid, this.uuid);
            final Action action = onInvalidSequenceNumber(
                retransmitLastUuid, seqNum, expectedSeqNo, nextRecvSeqNo);
            if (action == ABORT)
            {
                return ABORT;
            }

            enqueueRetransmitMessage(buffer, offset, totalLength, seqNum);
        }
        else
        {
            final long nextRetransmitContiguousSeqNo = retransmitContiguousSeqNo + 1;

            // nextRetransmitContiguousSeqNo == 0 if all the received messages are in order
            if (nextRetransmitContiguousSeqNo == 0 || seqNum == nextRetransmitContiguousSeqNo)
            {
                final Action action = onBusinessMessage(buffer, offset, templateId, blockLength, version, true,
                    totalLength);
                if (action == ABORT)
                {
                    return ABORT;
                }
            }
            else
            {
                enqueueRetransmitMessage(buffer, offset, totalLength, seqNum);
            }

            if (seqNum == nextRetransmitContiguousSeqNo)
            {
                retransmitContiguousSeqNo = seqNum;
            }
        }

        if (seqNum == retransmitFillSeqNo)
        {
            return onRetransmitFilled();
        }
        else
        {
            nextRetransmitSeqNo = seqNum + 1;
        }

        return CONTINUE;
    }

    // returns true if the enqueue is successful
    private void enqueueRetransmitMessage(
        final DirectBuffer buffer, final int offset, final int totalLength, final long seqNum)
    {
        final int newQueueSize = retransmitQueueOffset + totalLength;

        if (newQueueSize > maxRetransmitQueueSize)
        {
            // We've hit the maximum size of the retransmit queue, at this point we need to make sure that we don't
            // drop the messages but we can't enqueue them, so we enqueue another retransmit request.

            final RetransmitRequest retransmitRequest = retransmitRequests.peekFirst();
            if (retransmitRequest == null)
            {
                addRetransmitRequest(lastUUIDNullValue(), seqNum, 1);
            }
            else
            {
                final long fromSeqNo = retransmitRequest.fromSeqNo;
                final int msgCount = retransmitRequest.msgCount;
                // last message of the retransmit = fromSeqNo + msgCount - 1 and this is the next message
                if (seqNum == (fromSeqNo + msgCount))
                {
                    retransmitRequest.msgCount++;
                }
                else
                {
                    addRetransmitRequest(retransmitRequest.lastUuid, seqNum, 1);
                }
            }

            return;
        }

        final int headerOffset = offset - HEADER_LENGTH;
        retransmitQueue.putBytes(retransmitQueueOffset, buffer, headerOffset, totalLength);
        retransmitQueueOffset = newQueueSize;
    }

    private Action onBusinessMessage(
        final DirectBuffer buffer,
        final int offset,
        final int templateId,
        final int blockLength,
        final int version,
        final boolean possRetrans,
        final int totalLength)
    {
        if (BUSINESS_MESSAGE_LOGGING_ENABLED)
        {
            dissector.onBusinessMessage(templateId, buffer, offset, blockLength, version, true);
        }

        final FixPMessageHeader messageHeader = this.messageHeader;
        messageHeader.messageSize(totalLength);
        return handler.onBusinessMessage(this, templateId, buffer, offset, blockLength, version, possRetrans,
            messageHeader);
    }

    private void checkBusinessRejectSequenceNumber(
        final DirectBuffer buffer, final int offset, final int templateId, final int blockLength, final int version)
    {
        if (templateId == BusinessReject521Decoder.TEMPLATE_ID)
        {
            businessReject.wrap(buffer, offset, blockLength, version);
            if (businessReject.refSeqNum() == BusinessReject521Decoder.refSeqNumNullValue())
            {
                nextSentSeqNo--;
            }
        }
    }

    private Action onInvalidSequenceNumber(final long seqNum)
    {
        return onInvalidSequenceNumber(seqNum, seqNum + 1);
    }

    private Action onInvalidSequenceNumber(final long msgSeqNum, final long newNextRecvSeqNo)
    {
        return onInvalidSequenceNumber(lastUUIDNullValue(), msgSeqNum, nextRecvSeqNo, newNextRecvSeqNo);
    }

    private Action onInvalidSequenceNumber(
        final long lastUuid, final long msgSeqNum, final long oldNextRecvSeqNo, final long newNextRecvSeqNo)
    {
        final long fromSeqNo = oldNextRecvSeqNo;
        final int totalMsgCount = (int)(msgSeqNum - oldNextRecvSeqNo);
        final int msgCount = Math.min(totalMsgCount, configuration.retransmitRequestMessageLimit());

        if (retransmitFillSeqNo == NOT_AWAITING_RETRANSMIT)
        {
            final long requestTimestamp = requestTimestampInNs();
            final long position = sendRetransmitRequest(lastUuid, fromSeqNo, msgCount, requestTimestamp);
            if (!Pressure.isBackPressured(position))
            {
                addRemainingRetransmitRequests(lastUuid, fromSeqNo, msgCount, totalMsgCount);
                nextRecvSeqNoForCurrentUuid(newNextRecvSeqNo, lastUuid);
                retransmitUuid(lastUuid);
                nextRetransmitSeqNo = fromSeqNo;
                retransmitFillTimeoutInNs(requestTimestamp);
                retransmitFillSeqNo = fromSeqNo + msgCount - 1;
                return CONTINUE;
            }
            else
            {
                return ABORT;
            }
        }
        else
        {
            addRetransmitRequest(lastUuid, fromSeqNo, msgCount);
            addRemainingRetransmitRequests(lastUuid, fromSeqNo, msgCount, totalMsgCount);
            nextRecvSeqNoForCurrentUuid(newNextRecvSeqNo, lastUuid);

            return CONTINUE;
        }
    }

    private void retransmitUuid(final long lastUuid)
    {
        retransmitUuid = lastUuid == lastUUIDNullValue() ? this.uuid : lastUuid;
    }

    private void nextRecvSeqNoForCurrentUuid(final long newNextRecvSeqNo, final long lastUuid)
    {
        // we don't want to update newNextRecvSeqNo for an On EstablishAck retransmit request for the last uuid
        if (lastUuid == lastUUIDNullValue())
        {
            nextRecvSeqNo(newNextRecvSeqNo);
        }
    }

    private Action onRetransmitFilled()
    {
        processRetransmitQueue();

        final RetransmitRequest retransmitRequest = retransmitRequests.peekFirst();
        if (retransmitRequest == null)
        {
            DebugLogger.log(FIXP_SESSION, retransmitFilled, retransmitFillSeqNo);
            final long nextSeqNoAfterRetransmit = retransmitFillSeqNo + 1;
            if (retransmitUuid == this.uuid && nextRecvSeqNo < nextSeqNoAfterRetransmit)
            {
                nextRecvSeqNo = nextSeqNoAfterRetransmit;
            }
            retransmitUuid = NOT_AWAITING_RETRANSMIT;
            nextRetransmitSeqNo = NOT_AWAITING_RETRANSMIT;
            retransmitFillSeqNo = NOT_AWAITING_RETRANSMIT;
            retransmitFillTimeoutInMs = NOT_AWAITING_RETRANSMIT;
        }
        else
        {
            final long lastUuid = retransmitRequest.lastUuid;
            final long fromSeqNo = retransmitRequest.fromSeqNo;
            final int msgCount = retransmitRequest.msgCount;
            final long requestTimestamp = requestTimestampInNs();
            final long position = sendRetransmitRequest(
                lastUuid, fromSeqNo, msgCount, requestTimestamp);

            if (!Pressure.isBackPressured(position))
            {
                if (DebugLogger.isEnabled(FIXP_SESSION))
                {
                    retransmitFilledNext
                        .clear()
                        .with(uuid)
                        .with(lastUuid)
                        .with(retransmitFillSeqNo)
                        .with(fromSeqNo)
                        .with(msgCount);
                    DebugLogger.log(FIXP_SESSION, retransmitFilledNext);
                }
                retransmitRequests.pollFirst();
                retransmitFillTimeoutInNs(requestTimestamp);
                retransmitUuid(lastUuid);
                nextRetransmitSeqNo = fromSeqNo;
                retransmitFillSeqNo = fromSeqNo + msgCount - 1;

                return CONTINUE;
            }
            else
            {
                return ABORT;
            }
        }

        return CONTINUE;
    }

    private void retransmitFillTimeoutInNs(final long requestTimestampInNs)
    {
        retransmitFillTimeoutInMs = NANOSECONDS.toMillis(requestTimestampInNs) +
            configuration.retransmitNotificationTimeoutInMs();
    }

    private void processRetransmitQueue()
    {
        if (retransmitContiguousSeqNo == NOT_AWAITING_RETRANSMIT)
        {
            processInOrderRetransmitQueue();
        }
        else if (retransmitRequests.isEmpty())
        {
            processOutOfOrderRetransmitQueue();
        }
    }

    private void processOutOfOrderRetransmitQueue()
    {
        // A retransmit within a retransmit happened - messages might be out of order and need sorting.
        final ExpandableArrayBuffer retransmitQueue = this.retransmitQueue;
        final MessageHeaderDecoder headerDecoder = this.headerDecoder;
        final SortedSet<RetransmitQueueEntry> entries = new TreeSet<>();
        long retransmitContiguousSeqNo = this.retransmitContiguousSeqNo;

        int offset = 0;
        while (offset < retransmitQueueOffset)
        {
            final int length = readSofhMessageSize(retransmitQueue, offset);

            final int headerOffset = offset + SOFH_LENGTH;
            headerDecoder.wrap(retransmitQueue, headerOffset);
            final int blockLength = headerDecoder.blockLength();
            final int templateId = headerDecoder.templateId();
            final int version = headerDecoder.version();

            final int messageOffset = headerOffset + MessageHeaderDecoder.ENCODED_LENGTH;
            final int seqNum = offsets.seqNum(templateId, retransmitQueue, messageOffset);
            final long messageUuid = offsets.uuid(templateId, retransmitQueue, messageOffset);
            if (messageUuid == retransmitUuid && seqNum == retransmitContiguousSeqNo + 1)
            {
                final Action action = onBusinessMessage(
                    retransmitQueue, messageOffset, templateId, blockLength, version, false,
                    length);
                if (action == ABORT)
                {
                    this.retransmitContiguousSeqNo = retransmitContiguousSeqNo;
                    retransmitQueueOffset = offset;
                    return;
                }
                retransmitContiguousSeqNo++;
            }
            else
            {
                entries.add(new RetransmitQueueEntry(messageUuid, seqNum, offset));
            }

            offset += length;
        }

        for (final RetransmitQueueEntry entry : entries)
        {
            final int entryOffset = entry.offset;
            final int messageSize = readSofhMessageSize(retransmitQueue, entryOffset);
            final int headerOffset = entryOffset + SOFH_LENGTH;
            headerDecoder.wrap(retransmitQueue, headerOffset);
            final int blockLength = headerDecoder.blockLength();
            final int templateId = headerDecoder.templateId();
            final int version = headerDecoder.version();

            final int messageOffset = headerOffset + MessageHeaderDecoder.ENCODED_LENGTH;
            final Action action = onBusinessMessage(
                retransmitQueue, messageOffset, templateId, blockLength, version, false,
                messageSize);
            if (action == ABORT)
            {
                this.retransmitContiguousSeqNo = NOT_AWAITING_RETRANSMIT;
                retransmitQueueOffset = 0;
                return;
            }
        }

        this.retransmitContiguousSeqNo = NOT_AWAITING_RETRANSMIT;
        retransmitQueueOffset = 0;
    }

    private void processInOrderRetransmitQueue()
    {
        final long expectedFirstSeqNo = retransmitFillSeqNo + 1;

        // Simple retransmit queue case - messages are all in order and can all be sent.
        final ExpandableArrayBuffer retransmitQueue = this.retransmitQueue;
        final MessageHeaderDecoder headerDecoder = this.headerDecoder;
        int offset = 0;
        boolean first = true;
        while (offset < retransmitQueueOffset)
        {
            final int length = readSofhMessageSize(retransmitQueue, offset);

            final int headerOffset = offset + SOFH_LENGTH;
            headerDecoder.wrap(retransmitQueue, headerOffset);
            final int blockLength = headerDecoder.blockLength();
            final int templateId = headerDecoder.templateId();
            final int version = headerDecoder.version();

            final int messageOffset = headerOffset + MessageHeaderDecoder.ENCODED_LENGTH;
            final int seqNum = offsets.seqNum(templateId, retransmitQueue, messageOffset);

            if (first)
            {
                if (seqNum > expectedFirstSeqNo)
                {
                    // If a resend request over 2500 in size has been sent then we will have batched the resend
                    // request into chunks and we don't want to resend a contiguous queue until we receive the all the
                    // resend messages
                    return;
                }

                first = false;
            }

            if (retransmitMaxSeqNo == NOT_AWAITING_RETRANSMIT || seqNum <= retransmitMaxSeqNo)
            {
                final Action action = onBusinessMessage(
                    retransmitQueue, messageOffset, templateId, blockLength, version, false,
                    length);
                if (action == ABORT)
                {
                    break;
                }

                offset += length;
            }
            else
            {
                break;
            }
        }

        // shuffle up remaining bytes
        final int remainder = retransmitQueueOffset - offset;
        if (remainder > 0)
        {
            retransmitQueue.putBytes(0, retransmitQueue, offset, remainder);
            retransmitQueueOffset = remainder;
        }
        else
        {
            retransmitQueueOffset = 0;
        }
        retransmitMaxSeqNo = NOT_AWAITING_RETRANSMIT;
    }

    private void addRemainingRetransmitRequests(
        final long lastUuid,
        final long initialFromSeqNo,
        final int initialMessagesRequested,
        final int totalMessageCount)
    {
        final int retransmitRequestMsgLimit = configuration.retransmitRequestMessageLimit();

        long fromSeqNo = initialFromSeqNo + initialMessagesRequested;
        int messagesRequested = initialMessagesRequested;

        while (messagesRequested < totalMessageCount)
        {
            final int msgCount = Math.min(totalMessageCount - messagesRequested, retransmitRequestMsgLimit);
            addRetransmitRequest(lastUuid, fromSeqNo, msgCount);

            messagesRequested += msgCount;
            fromSeqNo += msgCount;
        }
    }

    private void addRetransmitRequest(final long lastUuid, final long fromSeqNo, final int msgCount)
    {
        retransmitRequests.offerLast(new RetransmitRequest(lastUuid, fromSeqNo, msgCount));
    }

    private long sendRetransmitRequest(
        final long lastUuid, final long fromSeqNo, final int msgCount, final long requestTimestamp)
    {
        onAttemptedToSendMessage();
        return proxy.sendRetransmitRequest(uuid, lastUuid, requestTimestamp, fromSeqNo, msgCount);
    }

    public Action onRetransmission(
        final long uUID, final long lastUUID, final long requestTimestamp, final long fromSeqNo, final int msgCount)
    {
        return CONTINUE;
    }

    static final class RetransmitRequest
    {
        final long lastUuid;
        final long fromSeqNo;
        int msgCount;

        RetransmitRequest(final long lastUuid, final long fromSeqNo, final int msgCount)
        {
            this.lastUuid = lastUuid;
            this.fromSeqNo = fromSeqNo;
            this.msgCount = msgCount;
        }

        public String toString()
        {
            return "RetransmitRequest{" +
                "lastUuid=" + lastUuid +
                ", fromSeqNo=" + fromSeqNo +
                ", msgCount=" + msgCount +
                '}';
        }
    }

    static final class RetransmitQueueEntry implements Comparable<RetransmitQueueEntry>
    {
        final long uuid;
        final long seqNum;
        final int offset;

        RetransmitQueueEntry(final long uuid, final long seqNum, final int offset)
        {
            this.uuid = uuid;
            this.seqNum = seqNum;
            this.offset = offset;
        }

        public int compareTo(final RetransmitQueueEntry o)
        {
            final int uuidCompare = Long.compare(uuid, o.uuid);
            if (uuidCompare == 0)
            {
                return Long.compare(seqNum, o.seqNum);
            }
            else
            {
                return uuidCompare;
            }
        }
    }

    public Action onRetransmitReject(
        final String reason, final long uuid, final long lastUuid, final long requestTimestamp, final int errorCodes)
    {
        checkUuid(uuid);

        final Action action = handler.onRetransmitReject(this, reason, requestTimestamp, errorCodes);
        if (action == ABORT)
        {
            return ABORT;
        }

        return onRetransmitFilled();
    }

    protected void onOfflineReconnect(final long connectionId, final FixPContext context)
    {
        Ilink3Protocol.unsupported();
    }

    public String toString()
    {
        return "InternalILink3Session{" +
            "configuration=" + configuration +
            ", connectionId=" + connectionId +
            ", libraryId=" + libraryId +
            ", uuid=" + uuid +
            ", state=" + state +
            ", nextRecvSeqNo=" + nextRecvSeqNo +
            ", nextSentSeqNo=" + nextSentSeqNo +
            ", retransmitFillSeqNo=" + retransmitFillSeqNo +
            ", nextRetransmitSeqNo=" + nextRetransmitSeqNo +
            ", nextReceiveMessageTimeInMs=" + nextReceiveMessageTimeInMs +
            ", nextSendMessageTimeInMs=" + nextSendMessageTimeInMs +
            '}';
    }

    public long startEndOfDay()
    {
        return requestDisconnect(ENGINE_SHUTDOWN);
    }
}
