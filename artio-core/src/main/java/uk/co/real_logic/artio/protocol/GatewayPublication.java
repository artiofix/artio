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
package uk.co.real_logic.artio.protocol;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.RecordingCoordinator;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.messages.ControlNotificationEncoder.DisconnectedSessionsEncoder;
import uk.co.real_logic.artio.messages.ControlNotificationEncoder.SessionsEncoder;
import uk.co.real_logic.artio.util.CharFormatter;

import java.util.List;

import static io.aeron.logbuffer.LogBufferDescriptor.computeFragmentedFrameLength;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static io.aeron.protocol.DataHeaderFlyweight.END_FLAG;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.co.real_logic.artio.DebugLogger.*;
import static uk.co.real_logic.artio.LogTag.*;
import static uk.co.real_logic.artio.messages.ErrorDecoder.messageHeaderLength;
import static uk.co.real_logic.artio.messages.ErrorEncoder.BLOCK_LENGTH;
import static uk.co.real_logic.artio.messages.FixMessageEncoder.metaDataHeaderLength;

/**
 * A proxy for publishing messages fix related messages
 */
public class GatewayPublication extends ClaimablePublication
{
    public static final long NO_REWRITE_SEQUENCE_NUMBER = 0;

    public static final int FRAME_SIZE = FixMessageEncoder.BLOCK_LENGTH + FixMessageDecoder.metaDataHeaderLength() +
        FixMessageDecoder.bodyHeaderLength();

    public static final int FRAMED_MESSAGE_SIZE = MessageHeaderEncoder.ENCODED_LENGTH + FRAME_SIZE;

    private static final byte[] NO_BYTES = {};
    private static final DirectBuffer NO_METADATA = new UnsafeBuffer(NO_BYTES);

    private static final int HEARTBEAT_LENGTH = HEADER_LENGTH + ApplicationHeartbeatEncoder.BLOCK_LENGTH;
    private static final int LIBRARY_CONNECT_LENGTH =
        HEADER_LENGTH + LibraryConnectEncoder.BLOCK_LENGTH + LibraryConnectEncoder.libraryNameHeaderLength();
    private static final int DISCONNECT_LENGTH = HEADER_LENGTH + DisconnectEncoder.BLOCK_LENGTH;
    private static final int RELEASE_SESSION_LENGTH = HEADER_LENGTH + ReleaseSessionEncoder.BLOCK_LENGTH +
        ReleaseSessionEncoder.usernameHeaderLength() + ReleaseSessionEncoder.passwordHeaderLength();
    private static final int RELEASE_SESSION_REPLY_LENGTH = HEADER_LENGTH + ReleaseSessionReplyDecoder.BLOCK_LENGTH;
    private static final int REQUEST_SESSION_LENGTH = HEADER_LENGTH + RequestSessionEncoder.BLOCK_LENGTH;
    private static final int REQUEST_SESSION_REPLY_LENGTH = HEADER_LENGTH + RequestSessionReplyEncoder.BLOCK_LENGTH;
    private static final int CONNECT_FIXED_LENGTH =
        HEADER_LENGTH + ConnectEncoder.BLOCK_LENGTH + ConnectEncoder.addressHeaderLength();
    private static final int SLOW_STATUS_NOTIFICATION_LENGTH =
        HEADER_LENGTH + SlowStatusNotificationEncoder.BLOCK_LENGTH;
    private static final byte MIDDLE_FLAG = 0;
    private static final int MANAGE_SESSION_BLOCK_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH +
        ManageSessionEncoder.BLOCK_LENGTH + ManageSessionEncoder.localCompIdHeaderLength() * 11;
    private static final int INITIATE_CONNECTION_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH +
        InitiateConnectionEncoder.BLOCK_LENGTH + InitiateConnectionDecoder.hostHeaderLength() * 10;
    private static final int CONTROL_NOTIFICATION_LENGTH = HEADER_LENGTH + ControlNotificationEncoder.BLOCK_LENGTH +
        GroupSizeEncodingEncoder.ENCODED_LENGTH * 2;
    private static final int MID_CONNECTION_DISCONNECT_LENGTH =
        HEADER_LENGTH + MidConnectionDisconnectEncoder.BLOCK_LENGTH;
    private static final int FOLLOWER_SESSION_REQUEST_LENGTH =
        HEADER_LENGTH + FollowerSessionRequestEncoder.BLOCK_LENGTH + FollowerSessionRequestEncoder.headerHeaderLength();
    private static final int FOLLOWER_SESSION_REPLY_LENGTH =
        HEADER_LENGTH + FollowerSessionReplyEncoder.BLOCK_LENGTH;
    private static final int END_OF_DAY_LENGTH =
        HEADER_LENGTH + EndOfDayEncoder.BLOCK_LENGTH;
    private static final int WRITE_META_DATA_LENGTH =
        HEADER_LENGTH + WriteMetaDataEncoder.BLOCK_LENGTH + WriteMetaDataDecoder.metaDataHeaderLength();
    private static final int WRITE_META_DATA_REPLY_LENGTH =
        HEADER_LENGTH + WriteMetaDataReplyEncoder.BLOCK_LENGTH;
    private static final int READ_META_DATA_LENGTH =
        HEADER_LENGTH + ReadMetaDataEncoder.BLOCK_LENGTH;
    private static final int READ_META_DATA_REPLY_LENGTH =
        HEADER_LENGTH + ReadMetaDataReplyEncoder.BLOCK_LENGTH + ReadMetaDataReplyEncoder.metaDataHeaderLength();
    private static final int REPLAY_MESSAGES_LENGTH =
        HEADER_LENGTH + ReplayMessagesEncoder.BLOCK_LENGTH;
    private static final int REPLAY_MESSAGES_REPLY_LENGTH =
        HEADER_LENGTH + ReplayMessagesReplyEncoder.BLOCK_LENGTH;
    public static final int INITIATE_ILINK_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH +
        InitiateILinkConnectionEncoder.BLOCK_LENGTH + InitiateILinkConnectionEncoder.hostHeaderLength() +
        InitiateILinkConnectionEncoder.accessKeyIdHeaderLength() +
        InitiateILinkConnectionEncoder.backupHostHeaderLength();
    private static final int REDACT_SEQUENCE_NUMBER_LENGTH =
        HEADER_LENGTH + RedactSequenceUpdateEncoder.BLOCK_LENGTH;
    private static final int VALID_RESEND_REQUEST_LENGTH =
        HEADER_LENGTH + ValidResendRequestEncoder.BLOCK_LENGTH + ValidResendRequestEncoder.bodyHeaderLength();
    private static final int LIBRARY_EXTEND_POSITION_LENGTH =
        HEADER_LENGTH + LibraryExtendPositionEncoder.BLOCK_LENGTH;
    private static final int MANAGE_FIXP_CONNECTION_LENGTH =
        HEADER_LENGTH + ManageFixPConnectionDecoder.BLOCK_LENGTH;
    private static final int CANCEL_ON_DISCONNECT_TRIGGER_LENGTH =
        HEADER_LENGTH + CancelOnDisconnectTriggerEncoder.BLOCK_LENGTH;
    private static final int THROTTLE_NOTIFICATION_LENGTH = HEADER_LENGTH +
        ThrottleNotificationEncoder.BLOCK_LENGTH + ThrottleNotificationEncoder.businessRejectRefIDHeaderLength();
    private static final int THROTTLE_REJECT_LENGTH = HEADER_LENGTH +
        ThrottleRejectEncoder.BLOCK_LENGTH + ThrottleRejectEncoder.businessRejectRefIDHeaderLength();
    private static final int THROTTLE_CONFIGURATION_LENGTH = HEADER_LENGTH + ThrottleConfigurationEncoder.BLOCK_LENGTH;
    private static final int THROTTLE_CONFIGURATION_REPLY_LENGTH = HEADER_LENGTH +
        ThrottleConfigurationReplyEncoder.BLOCK_LENGTH;
    private static final int SEQ_INDEX_SYNC_LENGTH = HEADER_LENGTH + SeqIndexSyncEncoder.BLOCK_LENGTH;
    private static final int LIBRARY_TIMEOUT_LENGTH = HEADER_LENGTH + LibraryTimeoutEncoder.BLOCK_LENGTH +
        GroupSizeEncodingEncoder.ENCODED_LENGTH;

    private static final boolean APPLICATION_HEARTBEAT_ATTEMPT_ENABLED = isEnabled(APPLICATION_HEARTBEAT_ATTEMPT);
    private static final boolean APPLICATION_HEARTBEAT_ENABLED = isEnabled(APPLICATION_HEARTBEAT);

    private final ManageSessionEncoder manageSessionEncoder = new ManageSessionEncoder();
    private final InitiateConnectionEncoder initiateConnection = new InitiateConnectionEncoder();
    private final RequestDisconnectEncoder requestDisconnect = new RequestDisconnectEncoder();
    private final MidConnectionDisconnectEncoder midConnectionDisconnect = new MidConnectionDisconnectEncoder();
    private final DisconnectEncoder disconnect = new DisconnectEncoder();
    private final FixMessageEncoder fixMessage = new FixMessageEncoder();
    private final ErrorEncoder error = new ErrorEncoder();
    private final ApplicationHeartbeatEncoder applicationHeartbeat = new ApplicationHeartbeatEncoder();
    private final LibraryConnectEncoder libraryConnect = new LibraryConnectEncoder();
    private final RequestSessionEncoder requestSession = new RequestSessionEncoder();
    private final RequestSessionReplyEncoder requestSessionReply = new RequestSessionReplyEncoder();
    private final ReleaseSessionEncoder releaseSession = new ReleaseSessionEncoder();
    private final ReleaseSessionReplyEncoder releaseSessionReply = new ReleaseSessionReplyEncoder();
    private final ConnectEncoder connect = new ConnectEncoder();
    private final ResetSessionIdsEncoder resetSessionIds = new ResetSessionIdsEncoder();
    private ExpandableArrayBuffer messageBuffer;
    private final ControlNotificationEncoder controlNotification = new ControlNotificationEncoder();
    private final LibraryTimeoutEncoder libraryTimeout = new LibraryTimeoutEncoder();
    private final ResetSequenceNumberEncoder resetSequenceNumber = new ResetSequenceNumberEncoder();
    private final ResetLibrarySequenceNumberEncoder resetLibrarySequenceNumber =
        new ResetLibrarySequenceNumberEncoder();
    private final SlowStatusNotificationEncoder slowStatusNotification = new SlowStatusNotificationEncoder();
    private final FollowerSessionRequestEncoder followerSessionRequest = new FollowerSessionRequestEncoder();
    private final FollowerSessionReplyEncoder followerSessionReply = new FollowerSessionReplyEncoder();
    private final EndOfDayEncoder endOfDay = new EndOfDayEncoder();
    private final WriteMetaDataEncoder writeMetaData = new WriteMetaDataEncoder();
    private final WriteMetaDataReplyEncoder writeMetaDataReply = new WriteMetaDataReplyEncoder();
    private final ReadMetaDataEncoder readMetaData = new ReadMetaDataEncoder();
    private final ReadMetaDataReplyEncoder readMetaDataReply = new ReadMetaDataReplyEncoder();
    private final ReplayMessagesEncoder replayMessages = new ReplayMessagesEncoder();
    private final ReplayMessagesReplyEncoder replayMessagesReply = new ReplayMessagesReplyEncoder();
    private final LibraryExtendPositionEncoder libraryExtendPosition = new LibraryExtendPositionEncoder();
    private final ManageFixPConnectionEncoder manageFixPConnection = new ManageFixPConnectionEncoder();
    private final CancelOnDisconnectTriggerEncoder cancelOnDisconnectTrigger = new CancelOnDisconnectTriggerEncoder();
    private final ThrottleNotificationEncoder throttleNotification = new ThrottleNotificationEncoder();
    private final ThrottleRejectEncoder throttleReject = new ThrottleRejectEncoder();
    private final ThrottleConfigurationEncoder throttleConfiguration = new ThrottleConfigurationEncoder();
    private final ThrottleConfigurationReplyEncoder throttleConfigurationReply =
        new ThrottleConfigurationReplyEncoder();
    private final SeqIndexSyncEncoder seqIndexSyncEncoder = new SeqIndexSyncEncoder();
    private final CharFormatter sendHeartbeatAttempt = new CharFormatter(
        "Failed to send heartbeat, id=%s,ts=%s,stream=%s");

    private final RedactSequenceUpdateEncoder redactSequenceUpdate = new RedactSequenceUpdateEncoder();
    private final ValidResendRequestEncoder validResendRequest = new ValidResendRequestEncoder();

    private final InitiateILinkConnectionEncoder initiateILinkConnection = new InitiateILinkConnectionEncoder();
    private final ILinkConnectEncoder iLinkConnect = new ILinkConnectEncoder();

    private final EpochNanoClock clock;
    private final int maxPayloadLength;

    public GatewayPublication(
        final ExclusivePublication dataPublication,
        final AtomicCounter fails,
        final IdleStrategy idleStrategy,
        final EpochNanoClock clock,
        final int maxClaimAttempts)
    {
        super(maxClaimAttempts, idleStrategy, fails, dataPublication);
        this.clock = clock;
        this.maxPayloadLength = dataPublication.maxPayloadLength();
    }

    public long saveMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int libraryId,
        final long messageType,
        final long sessionId,
        final int sequenceIndex,
        final long connectionId,
        final MessageStatus status,
        final int sequenceNumber)
    {
        return saveMessage(
            srcBuffer,
            srcOffset,
            srcLength,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex,
            connectionId,
            status,
            sequenceNumber,
            clock.nanoTime());
    }

    public long saveMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int libraryId,
        final long messageType,
        final long sessionId,
        final int sequenceIndex,
        final long connectionId,
        final MessageStatus status,
        final int sequenceNumber,
        final DirectBuffer metaDataBuffer,
        final int metaDataUpdateOffset)
    {
        return saveMessage(
            srcBuffer,
            srcOffset,
            srcLength,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex,
            connectionId,
            status,
            sequenceNumber,
            clock.nanoTime(),
            metaDataBuffer,
            metaDataUpdateOffset);
    }

    public long saveMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int libraryId,
        final long messageType,
        final long sessionId,
        final int sequenceIndex,
        final long connectionId,
        final MessageStatus status,
        final int sequenceNumber,
        final long timestamp)
    {
        return saveMessage(
            srcBuffer,
            srcOffset,
            srcLength,
            libraryId,
            messageType,
            sessionId,
            sequenceIndex,
            connectionId,
            status,
            sequenceNumber,
            timestamp,
            null,
            0);
    }

    public long saveMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int libraryId,
        final long messageType,
        final long sessionId,
        final int sequenceIndex,
        final long connectionId,
        final MessageStatus status,
        final int sequenceNumber,
        final long timestamp,
        final DirectBuffer srcMetaDataBuffer,
        final int metaDataUpdateOffset)
    {
        final int maxPayloadLength = this.maxPayloadLength;
        final DirectBuffer metaDataBuffer = srcMetaDataBuffer == null ? NO_METADATA : srcMetaDataBuffer;
        final int metaDataLength = metaDataBuffer.capacity();

        final BufferClaim bufferClaim = this.bufferClaim;
        final int framedLength = FRAMED_MESSAGE_SIZE + srcLength + metaDataLength;
        final boolean fragmented = framedLength > maxPayloadLength;
        final int claimLength = fragmented ? maxPayloadLength : framedLength;
        int srcFragmentLength = fragmented ? maxPayloadLength - (FRAMED_MESSAGE_SIZE + metaDataLength) : srcLength;
        int srcFragmentOffset = srcOffset;

        if (fragmented)
        {
            // Add a padding message at the end of the term buffer if needed.
            final int requiredLength = computeFragmentedFrameLength(framedLength, maxPayloadLength);
            final int termLength = dataPublication.termBufferLength();
            final int termOffset = dataPublication.termOffset();
            final int resultingOffset = termOffset + requiredLength;

            if (resultingOffset > termLength)
            {
                final long paddingPosition = dataPublication.appendPadding(termLength - termOffset);
                if (paddingPosition < 0)
                {
                    return paddingPosition;
                }
            }
        }

        long position = claim(claimLength);
        if (position < 0)
        {
            return position;
        }

        int offset = bufferClaim.offset();
        final MutableDirectBuffer destBuffer = bufferClaim.buffer();

        header.wrap(destBuffer, offset)
            .blockLength(fixMessage.sbeBlockLength())
            .templateId(fixMessage.sbeTemplateId())
            .schemaId(fixMessage.sbeSchemaId())
            .version(fixMessage.sbeSchemaVersion());

        offset += header.encodedLength();

        fixMessage.wrap(destBuffer, offset)
            .libraryId(libraryId)
            .messageType(messageType)
            .session(sessionId)
            .sequenceIndex(sequenceIndex)
            .connection(connectionId)
            .timestamp(timestamp)
            .status(status)
            .sequenceNumber(sequenceNumber)
            .metaDataUpdateOffset(metaDataUpdateOffset)
            .putMetaData(metaDataBuffer, 0, metaDataLength)
            .putBody(srcBuffer, srcFragmentOffset, srcFragmentLength);

        if (!fragmented)
        {
            bufferClaim.commit();
        }
        else
        {
            putBodyLength(srcLength, offset, metaDataLength, destBuffer);

            bufferClaim.flags((byte)BEGIN_FLAG).commit();

            int remaining = srcLength - srcFragmentLength;
            while (remaining > 0)
            {
                srcFragmentOffset += srcFragmentLength;
                srcFragmentLength = Math.min(remaining, maxPayloadLength);

                position = claim(srcFragmentLength);
                // NB: if multiple fragments are written but never finished then
                // the message gets thrown away in re-assembly.
                if (position < 0)
                {
                    return position;
                }

                remaining -= srcFragmentLength;
                bufferClaim.buffer().putBytes(bufferClaim.offset(), srcBuffer, srcFragmentOffset, srcFragmentLength);
                bufferClaim.flags(remaining > 0 ? MIDDLE_FLAG : (byte)END_FLAG).commit();
            }
        }
        DebugLogger.logFixMessage(FIX_MESSAGE_FLOW, messageType, "Enqueued ", srcBuffer, srcOffset, srcLength);
        return position;
    }

    private void putBodyLength(
        final int srcLength, final int offset, final int metaDataLength, final MutableDirectBuffer destBuffer)
    {
        final int position = offset + FixMessageEncoder.BLOCK_LENGTH + metaDataHeaderLength() + metaDataLength;
        destBuffer.putInt(position, srcLength, LITTLE_ENDIAN);
    }

    public long saveManageSession(
        final int libraryId,
        final long connection,
        final long session,
        final int lastSentSequenceNumber,
        final int lastReceivedSequenceNumber,
        final SessionStatus sessionStatus,
        final SlowStatus slowStatus,
        final ConnectionType connectionType,
        final SessionState sessionState,
        final boolean awaitingResend,
        final int heartbeatIntervalInS,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final long replyToId,
        final int sequenceIndex,
        final int lastResentMsgSeqNo,
        final int lastResendChunkMsgSeqNum,
        final int endOfResendRequestRange,
        final boolean awaitingHeartbeat,
        final int logonReceivedSequenceNumber,
        final int logonSequenceIndex,
        final long lastLogonTime,
        final long lastSequenceResetTime,
        final String localCompId,
        final String localSubId,
        final String localLocationId,
        final String remoteCompId,
        final String remoteSubId,
        final String remoteLocationId,
        final String address,
        final String username,
        final String password,
        final Class<? extends FixDictionary> fixDictionary,
        final MetaDataStatus metaDataStatus,
        final DirectBuffer metaData,
        final CancelOnDisconnectOption cancelOnDisconnectOption,
        final long cancelOnDisconnectTimeoutWindowInNs)
    {
        final byte[] localCompIdBytes = bytes(localCompId);
        final byte[] localSubIdBytes = bytes(localSubId);
        final byte[] localLocationIdBytes = bytes(localLocationId);
        final byte[] remoteCompIdBytes = bytes(remoteCompId);
        final byte[] remoteSubIdBytes = bytes(remoteSubId);
        final byte[] remoteLocationIdBytes = bytes(remoteLocationId);
        final byte[] addressBytes = bytes(address);
        final byte[] usernameBytes = bytes(username);
        final byte[] passwordBytes = bytes(password);
        final byte[] fixDictionaryBytes = bytes(fixDictionary.getName());

        final long position = claim(
            MANAGE_SESSION_BLOCK_LENGTH + localCompIdBytes.length + localSubIdBytes.length +
            localLocationIdBytes.length + remoteCompIdBytes.length + remoteSubIdBytes.length +
            remoteLocationIdBytes.length + addressBytes.length + usernameBytes.length + passwordBytes.length +
            fixDictionaryBytes.length +
            metaData.capacity());

        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        manageSessionEncoder
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connection)
            .session(session)
            .lastSentSequenceNumber(lastSentSequenceNumber)
            .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
            .sessionStatus(sessionStatus)
            .slowStatus(slowStatus)
            .connectionType(connectionType)
            .sessionState(sessionState)
            .awaitingResend(encodeAwaitingResend(awaitingResend))
            .heartbeatIntervalInS(heartbeatIntervalInS)
            .closedResendInterval(toBool(closedResendInterval))
            .resendRequestChunkSize(resendRequestChunkSize)
            .sendRedundantResendRequests(toBool(sendRedundantResendRequests))
            .enableLastMsgSeqNumProcessed(toBool(enableLastMsgSeqNumProcessed))
            .replyToId(replyToId)
            .sequenceIndex(sequenceIndex)
            .lastResentMsgSeqNo(lastResentMsgSeqNo)
            .lastResendChunkMsgSeqNum(lastResendChunkMsgSeqNum)
            .endOfResendRequestRange(endOfResendRequestRange)
            .awaitingHeartbeat(toBool(awaitingHeartbeat))
            .logonReceivedSequenceNumber(logonReceivedSequenceNumber)
            .logonSequenceIndex(logonSequenceIndex)
            .lastLogonTime(lastLogonTime)
            .lastSequenceResetTime(lastSequenceResetTime)
            .metaDataStatus(metaDataStatus)
            .cancelOnDisconnectOption(cancelOnDisconnectOption)
            .cancelOnDisconnectTimeoutInNs(cancelOnDisconnectTimeoutWindowInNs)
            .putLocalCompId(localCompIdBytes, 0, localCompIdBytes.length)
            .putLocalSubId(localSubIdBytes, 0, localSubIdBytes.length)
            .putLocalLocationId(localLocationIdBytes, 0, localLocationIdBytes.length)
            .putRemoteCompId(remoteCompIdBytes, 0, remoteCompIdBytes.length)
            .putRemoteSubId(remoteSubIdBytes, 0, remoteSubIdBytes.length)
            .putRemoteLocationId(remoteLocationIdBytes, 0, remoteLocationIdBytes.length)
            .putAddress(addressBytes, 0, addressBytes.length)
            .putUsername(usernameBytes, 0, usernameBytes.length)
            .putPassword(passwordBytes, 0, passwordBytes.length)
            .putFixDictionary(fixDictionaryBytes, 0, fixDictionaryBytes.length)
            .putMetaData(metaData, 0, metaData.capacity());

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, manageSessionEncoder);

        return position;
    }

    public long saveDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        final long position = claim(DISCONNECT_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        disconnect
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connectionId)
            .reason(reason);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, disconnect);

        return position;
    }

    public long saveConnect(final long connectionId, final long timeInNs, final String address)
    {
        final byte[] addressBytes = bytes(address);

        final long position = claim(CONNECT_FIXED_LENGTH + addressBytes.length);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        connect
            .wrapAndApplyHeader(buffer, offset, header)
            .connection(connectionId)
            .timestamp(timeInNs)
            .putAddress(addressBytes, 0, addressBytes.length);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, connect);

        return position;
    }

    public long saveResetSessionIds()
    {
        final long position = claim(HEADER_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        resetSessionIds.wrapAndApplyHeader(buffer, offset, header);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, resetSessionIds);

        return position;
    }

    public long saveResetSequenceNumber(final long sessionId)
    {
        final long position = claim(HEADER_LENGTH + ResetSequenceNumberEncoder.BLOCK_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        resetSequenceNumber
            .wrapAndApplyHeader(buffer, offset, header)
            .session(sessionId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, resetSequenceNumber);

        return position;
    }

    public long saveResetLibrarySequenceNumber(final int libraryId, final long sessionId)
    {
        final long position = claim(HEADER_LENGTH + ResetLibrarySequenceNumberEncoder.BLOCK_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        resetLibrarySequenceNumber.wrapAndApplyHeader(buffer, offset, header).libraryId(libraryId).session(sessionId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, resetLibrarySequenceNumber);

        return position;
    }

    public long saveRequestDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        final long position = claim(header.encodedLength() + RequestDisconnectEncoder.BLOCK_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        requestDisconnect
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connectionId)
            .reason(reason);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, requestDisconnect);

        return position;
    }

    public long saveMidConnectionDisconnect(final int libraryId, final long correlationId)
    {
        final long position = claim(MID_CONNECTION_DISCONNECT_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        midConnectionDisconnect
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, midConnectionDisconnect);

        return position;
    }

    public long saveInitiateConnection(
        final int libraryId,
        final String host,
        final int port,
        final String senderCompId,
        final String senderSubId,
        final String senderLocationId,
        final String targetCompId,
        final String targetSubId,
        final String targetLocationId,
        final SequenceNumberType sequenceNumberType,
        final boolean resetSequenceNumber,
        final int requestedInitialReceivedSequenceNumber,
        final int requestedInitialSentSequenceNumber,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final String username,
        final String password,
        final Class<? extends FixDictionary> fixDictionary,
        final int heartbeatIntervalInS,
        final long correlationId)
    {
        final byte[] hostBytes = bytes(host);
        final byte[] senderCompIdBytes = bytes(senderCompId);
        final byte[] senderSubIdBytes = bytes(senderSubId);
        final byte[] senderLocationIdBytes = bytes(senderLocationId);
        final byte[] targetCompIdBytes = bytes(targetCompId);
        final byte[] targetSubIdBytes = bytes(targetSubId);
        final byte[] targetLocationIdBytes = bytes(targetLocationId);
        final byte[] usernameBytes = bytes(username);
        final byte[] passwordBytes = bytes(password);
        final byte[] fixDictionaryBytes = bytes(fixDictionary.getName());

        final long position = claim(
            INITIATE_CONNECTION_LENGTH + hostBytes.length + senderCompIdBytes.length +
            senderSubIdBytes.length + senderLocationIdBytes.length + targetCompIdBytes.length +
            targetSubIdBytes.length + targetLocationIdBytes.length + usernameBytes.length + passwordBytes.length +
            fixDictionaryBytes.length);

        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        initiateConnection.wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .port(port)
            .requestedInitialReceivedSequenceNumber(requestedInitialReceivedSequenceNumber)
            .requestedInitialSentSequenceNumber(requestedInitialSentSequenceNumber)
            .sequenceNumberType(sequenceNumberType)
            .heartbeatIntervalInS(heartbeatIntervalInS)
            .resetSequenceNumber(resetSequenceNumber ? ResetSequenceNumber.YES : ResetSequenceNumber.NO)
            .correlationId(correlationId)
            .closedResendInterval(toBool(closedResendInterval))
            .resendRequestChunkSize(resendRequestChunkSize)
            .sendRedundantResendRequests(toBool(sendRedundantResendRequests))
            .enableLastMsgSeqNumProcessed(toBool(enableLastMsgSeqNumProcessed))
            .putHost(hostBytes, 0, hostBytes.length)
            .putSenderCompId(senderCompIdBytes, 0, senderCompIdBytes.length)
            .putSenderSubId(senderSubIdBytes, 0, senderSubIdBytes.length)
            .putSenderLocationId(senderLocationIdBytes, 0, senderLocationIdBytes.length)
            .putTargetCompId(targetCompIdBytes, 0, targetCompIdBytes.length)
            .putTargetSubId(targetSubIdBytes, 0, targetSubIdBytes.length)
            .putTargetLocationId(targetLocationIdBytes, 0, targetLocationIdBytes.length)
            .putUsername(usernameBytes, 0, usernameBytes.length)
            .putPassword(passwordBytes, 0, passwordBytes.length)
            .putFixDictionary(fixDictionaryBytes, 0, fixDictionaryBytes.length);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, initiateConnection);

        return position;
    }

    private Bool toBool(final boolean value)
    {
        return value ? Bool.TRUE : Bool.FALSE;
    }

    public long saveError(final GatewayError errorType, final int libraryId, final long replyToId, final String message)
    {
        final byte[] messageBytes = bytes(message);
        final int length = header.encodedLength() + BLOCK_LENGTH + messageHeaderLength() + messageBytes.length;
        final long position = claim(length);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        error.wrapAndApplyHeader(buffer, offset, header)
            .errorType(errorType)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .putMessage(messageBytes, 0, messageBytes.length);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, error);

        return position;
    }

    public long saveApplicationHeartbeat(final int libraryId, final long timestampInNs)
    {
        final long position = claim(HEARTBEAT_LENGTH);
        if (position < 0)
        {
            if (APPLICATION_HEARTBEAT_ATTEMPT_ENABLED)
            {
                final int streamId = dataPublication.streamId();
                log(APPLICATION_HEARTBEAT_ATTEMPT, sendHeartbeatAttempt, libraryId, timestampInNs, streamId);
            }

            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        applicationHeartbeat
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .timestampInNs(timestampInNs);

        bufferClaim.commit();

        if (APPLICATION_HEARTBEAT_ENABLED)
        {
            final int streamId = dataPublication.streamId();
            logSbeMessage(APPLICATION_HEARTBEAT, applicationHeartbeat, streamId);
        }

        return position;
    }

    public long saveLibraryConnect(final int libraryId, final String libraryName, final long correlationId)
    {
        final byte[] libraryNameBytes = bytes(libraryName);

        final long position = claim(LIBRARY_CONNECT_LENGTH + libraryNameBytes.length);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        libraryConnect
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .putLibraryName(libraryNameBytes, 0, libraryNameBytes.length)
            .correlationId(correlationId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, libraryConnect);

        return position;
    }

    public long saveReleaseSession(
        final int libraryId,
        final long connectionId,
        final long sessionId,
        final long correlationId,
        final SessionState state,
        final boolean awaitingResend,
        final long heartbeatIntervalInMs,
        final int lastSentSequenceNumber,
        final int lastReceivedSequenceNumber,
        final String username,
        final String password)
    {
        final byte[] usernameBytes = bytes(username);
        final byte[] passwordBytes = bytes(password);

        final long position = claim(RELEASE_SESSION_LENGTH + usernameBytes.length + passwordBytes.length);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        releaseSession.wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connectionId)
            .sessionId(sessionId)
            .correlationId(correlationId)
            .heartbeatIntervalInMs(heartbeatIntervalInMs)
            .state(state)
            .awaitingResend(encodeAwaitingResend(awaitingResend))
            .lastSentSequenceNumber(lastSentSequenceNumber)
            .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
            .putUsername(usernameBytes, 0, usernameBytes.length)
            .putPassword(passwordBytes, 0, passwordBytes.length);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, releaseSession);

        return position;
    }

    private AwaitingResend encodeAwaitingResend(final boolean awaitingResend)
    {
        return awaitingResend ? AwaitingResend.YES : AwaitingResend.NO;
    }

    public long saveReleaseSessionReply(final SessionReplyStatus status, final long replyToId)
    {
        final long position = claim(RELEASE_SESSION_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        releaseSessionReply.wrapAndApplyHeader(buffer, offset, header).replyToId(replyToId).status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, releaseSessionReply);

        return position;
    }

    public long saveRequestSession(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int lastReceivedSequenceNumber,
        final int sequenceIndex)
    {
        final long position = claim(REQUEST_SESSION_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        requestSession.wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .sessionId(sessionId)
            .correlationId(correlationId)
            .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
            .sequenceIndex(sequenceIndex);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, requestSession);

        return position;
    }

    public long saveRequestSessionReply(final int libraryId, final SessionReplyStatus status, final long replyToId)
    {
        final long position = claim(REQUEST_SESSION_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        requestSessionReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, requestSessionReply);

        return position;
    }

    public long saveLibraryTimeout(final LibraryInfo libraryInfo, final long connectCorrelationId)
    {
        final List<ConnectedSessionInfo> connectedSessionInfos = libraryInfo.sessions();
        final int sessionsCount = connectedSessionInfos.size();

        final int framedLength = LIBRARY_TIMEOUT_LENGTH + sessionsCount *
            LibraryTimeoutEncoder.SessionsEncoder.sbeBlockLength();
        final ExpandableArrayBuffer buffer = buffer(framedLength);

        libraryTimeout
            .wrapAndApplyHeader(buffer, 0, header)
            .libraryId(libraryInfo.libraryId())
            .connectCorrelationId(connectCorrelationId);

        final LibraryTimeoutEncoder.SessionsEncoder sessionsEncoder = libraryTimeout.sessionsCount(sessionsCount);
        for (int i = 0; i < sessionsCount; i++)
        {
            final SessionInfo session = connectedSessionInfos.get(i);
            sessionsEncoder.next().sessionId(session.sessionId());
        }

        final long position = dataPublication.offer(buffer, 0, framedLength);

        if (position > 0)
        {
            logSbeMessage(GATEWAY_MESSAGE, libraryTimeout);
        }

        return position;
    }

    public long saveControlNotification(
        final int libraryId,
        final InitialAcceptedSessionOwner initialAcceptedSessionOwner,
        final List<?> sessions,
        final LongHashSet disconnectedSessionIds)
    {
        final int disconnectedSessionsCount = disconnectedSessionIds.size();
        final int sessionsCount = sessions.size();
        final int framedLength = CONTROL_NOTIFICATION_LENGTH + sessionsCount * SessionsEncoder.sbeBlockLength() +
            disconnectedSessionsCount * DisconnectedSessionsEncoder.sbeBlockLength();
        final ExpandableArrayBuffer buffer = buffer(framedLength);

        controlNotification
            .wrapAndApplyHeader(buffer, 0, header)
            .libraryId(libraryId)
            .initialAcceptedSessionOwner(initialAcceptedSessionOwner);

        final SessionsEncoder sessionsEncoder = controlNotification.sessionsCount(sessionsCount);
        for (int i = 0; i < sessionsCount; i++)
        {
            final SessionInfo session = (SessionInfo)sessions.get(i);
            sessionsEncoder.next().sessionId(session.sessionId());
        }

        final DisconnectedSessionsEncoder disconnectedSessionsEncoder = controlNotification
            .disconnectedSessionsCount(disconnectedSessionsCount);
        final LongHashSet.LongIterator it = disconnectedSessionIds.iterator();
        while (it.hasNext())
        {
            disconnectedSessionsEncoder.next().sessionId(it.nextValue());
        }

        final long position = dataPublication.offer(buffer, 0, framedLength);

        if (position > 0)
        {
            logSbeMessage(GATEWAY_MESSAGE, controlNotification);
        }

        return position;
    }

    private ExpandableArrayBuffer buffer(final int framedLength)
    {
        ExpandableArrayBuffer messageBuffer = this.messageBuffer;
        if (messageBuffer == null)
        {
            messageBuffer = this.messageBuffer = new ExpandableArrayBuffer(framedLength);
            return messageBuffer;
        }

        messageBuffer.checkLimit(framedLength);
        return messageBuffer;
    }

    public long saveSlowStatusNotification(final int libraryId, final long connectionId, final SlowStatus status)
    {
        final long position = claim(SLOW_STATUS_NOTIFICATION_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        slowStatusNotification
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connectionId(connectionId)
            .status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, slowStatusNotification);

        return position;
    }

    public long saveFollowerSessionRequest(
        final int libraryId,
        final long correlationId,
        final FixPProtocolType protocolType,
        final DirectBuffer srcBuffer,
        final int srcLength,
        final int srcOffset)
    {
        final long position = claim(FOLLOWER_SESSION_REQUEST_LENGTH + srcLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        followerSessionRequest
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId)
            .protocolType(protocolType)
            .putHeader(srcBuffer, srcOffset, srcLength);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, followerSessionRequest);

        return position;
    }

    public long saveFollowerSessionReply(
        final int libraryId,
        final long replyToId,
        final long sessionId)
    {
        final long position = claim(FOLLOWER_SESSION_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        followerSessionReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .session(sessionId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, followerSessionReply);

        return position;
    }

    public long saveEndOfDay(final int libraryId)
    {
        final long position = claim(END_OF_DAY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        endOfDay
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, endOfDay);

        return position;
    }

    public long saveWriteMetaData(
        final int libraryId,
        final long sessionId,
        final int metaDataOffset,
        final long correlationId,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength)
    {
        final long position = claim(WRITE_META_DATA_LENGTH + srcLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        writeMetaData
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .session(sessionId)
            .correlationId(correlationId)
            .metaDataOffset(metaDataOffset)
            .putMetaData(srcBuffer, srcOffset, srcLength);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, writeMetaData);

        return position;
    }

    public long saveWriteMetaDataReply(
        final int libraryId,
        final long replyToId,
        final MetaDataStatus status)
    {
        final long position = claim(WRITE_META_DATA_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        writeMetaDataReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, writeMetaDataReply);

        return position;
    }

    public long saveReadMetaData(
        final int libraryId,
        final long sessionId,
        final long correlationId)
    {
        final long position = claim(READ_META_DATA_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        readMetaData
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .session(sessionId)
            .correlationId(correlationId);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, readMetaData);

        return position;
    }

    public long saveReadMetaDataReply(
        final int libraryId,
        final long replyToId,
        final MetaDataStatus status,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength)
    {
        final long position = claim(READ_META_DATA_REPLY_LENGTH + srcLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        readMetaDataReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .status(status)
            .putMetaData(srcBuffer, srcOffset, srcLength);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, readMetaDataReply);

        return position;
    }

    public long saveReplayMessages(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long latestReplyArrivalTimeInMs)
    {
        final long position = claim(REPLAY_MESSAGES_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        replayMessages
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .session(sessionId)
            .correlationId(correlationId)
            .replayFromSequenceNumber(replayFromSequenceNumber)
            .replayFromSequenceIndex(replayFromSequenceIndex)
            .replayToSequenceNumber(replayToSequenceNumber)
            .replayToSequenceIndex(replayToSequenceIndex)
            .latestReplyArrivalTimeInMs(latestReplyArrivalTimeInMs);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, replayMessages);

        return position;
    }

    public long saveReplayMessagesReply(
        final int libraryId, final long replyToId, final ReplayMessagesStatus status)
    {
        final long position = claim(REPLAY_MESSAGES_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        replayMessagesReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, replayMessagesReply);

        return position;
    }

    public long saveRedactSequenceUpdate(
        final long sessionId, final int correctSequenceNumber, final long messagePosition)
    {
        final long position = claim(REDACT_SEQUENCE_NUMBER_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        redactSequenceUpdate
            .wrapAndApplyHeader(buffer, offset, header)
            .session(sessionId)
            .correctSequenceNumber(correctSequenceNumber)
            .position(messagePosition);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, redactSequenceUpdate);

        return position;
    }

    public long saveValidResendRequest(
        final long sessionId,
        final long connectionId,
        final long beginSequenceNumber,
        final long endSequenceNumber,
        final int sequenceIndex,
        final long correlationId,
        final DirectBuffer bodyBuffer,
        final int bodyOffset,
        final int bodyLength)
    {
        return saveValidResendRequest(
            sessionId,
            connectionId,
            beginSequenceNumber,
            endSequenceNumber,
            sequenceIndex,
            correlationId,
            ValidResendRequestEncoder.overriddenBeginSequenceNumberNullValue(),
            bodyBuffer,
            bodyOffset,
            bodyLength
        );
    }

    public long saveValidResendRequest(
        final long sessionId,
        final long connectionId,
        final long beginSequenceNumber,
        final long endSequenceNumber,
        final int sequenceIndex,
        final long correlationId,
        final long overriddenBeginSequenceNumber,
        final DirectBuffer bodyBuffer,
        final int bodyOffset,
        final int bodyLength)
    {
        final long position = claim(VALID_RESEND_REQUEST_LENGTH + bodyLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        validResendRequest
            .wrapAndApplyHeader(buffer, offset, header)
            .session(sessionId)
            .connection(connectionId)
            .beginSequenceNumber(beginSequenceNumber)
            .endSequenceNumber(endSequenceNumber)
            .sequenceIndex(sequenceIndex)
            .correlationId(correlationId)
            .overriddenBeginSequenceNumber(overriddenBeginSequenceNumber)
            .putBody(bodyBuffer, bodyOffset, bodyLength);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, validResendRequest);

        return position;
    }

    public long saveInitiateILinkConnection(
        final int libraryId,
        final int port,
        final long correlationId,
        final boolean reEstablishLastConnection,
        final String host,
        final String accessKeyId,
        final boolean useBackupHost,
        final String backupHost)
    {
        final byte[] hostBytes = bytes(host);
        final byte[] accessKeyIdBytes = bytes(accessKeyId);
        final byte[] backupHostBytes = bytes(backupHost);
        final long position = claim(INITIATE_ILINK_LENGTH + hostBytes.length + accessKeyIdBytes.length +
            backupHostBytes.length);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        initiateILinkConnection
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .port(port)
            .correlationId(correlationId)
            .reestablishConnection(toBool(reEstablishLastConnection))
            .useBackupHost(toBool(useBackupHost))
            .putHost(hostBytes, 0, hostBytes.length)
            .putAccessKeyId(accessKeyIdBytes, 0, accessKeyIdBytes.length)
            .putBackupHost(backupHostBytes, 0, backupHostBytes.length);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, initiateILinkConnection);

        return position;
    }

    public long saveILinkConnect(
        final int libraryId,
        final long correlationId,
        final long connectionId,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated,
        final long lastUuid)
    {
        final long position = claim(
            MessageHeaderEncoder.ENCODED_LENGTH + ILinkConnectEncoder.BLOCK_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        iLinkConnect
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId)
            .connection(connectionId)
            .uuid(uuid)
            .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
            .lastSentSequenceNumber(lastSentSequenceNumber)
            .newlyAllocated(toBool(newlyAllocated))
            .lastUuid(lastUuid);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, iLinkConnect);

        return position;
    }

    public long saveLibraryExtendPosition(
        final int libraryId, final long correlationId, final RecordingCoordinator.LibraryExtendPosition extend)
    {
        final long position = claim(LIBRARY_EXTEND_POSITION_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        libraryExtendPosition
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId)
            .sessionId(extend.newSessionId)
            .stopPosition(extend.stopPosition)
            .initialTermId(extend.initialTermId)
            .termBufferLength(extend.termBufferLength)
            .mtuLength(extend.mtuLength);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, libraryExtendPosition);

        return position;
    }

    public long saveManageFixPConnection(
        final int libraryId,
        final long correlationId,
        final long connectionId,
        final long sessionId,
        final FixPProtocolType protocolType,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final byte[] firstMessage,
        final boolean offline)
    {
        final int messageLength = firstMessage.length;
        final long position = claim(MANAGE_FIXP_CONNECTION_LENGTH + messageLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        manageFixPConnection
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId)
            .connection(connectionId)
            .sessionId(sessionId)
            .protocolType(protocolType)
            .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
            .lastSentSequenceNumber(lastSentSequenceNumber)
            .lastConnectPayload(lastConnectPayload)
            .messageLength(messageLength)
            .offline(toBool(offline));

        buffer.putBytes(manageFixPConnection.limit(), firstMessage, 0, messageLength);

        bufferClaim.commit();

//        logSbeMessage(GATEWAY_MESSAGE, manageFixPConnection);

        return position;
    }

    public long saveCancelOnDisconnectTrigger(final long sessionId, final long timeInNs)
    {
        final long position = claim(CANCEL_ON_DISCONNECT_TRIGGER_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        cancelOnDisconnectTrigger
            .wrapAndApplyHeader(buffer, offset, header)
            .sessionId(sessionId)
            .timeInNs(timeInNs);

        bufferClaim.commit();

//        logSbeMessage(GATEWAY_MESSAGE, cancelOnDisconnectTrigger);

        return position;
    }

    public long saveThrottleNotification(
        final int libraryId,
        final long connectionId,
        final long refMsgType,
        final int refSeqNum,
        final long sessionId,
        final int sequenceIndex,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        final long position = claim(THROTTLE_NOTIFICATION_LENGTH + businessRejectRefIDLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        throttleNotification
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connectionId)
            .refMsgType(refMsgType)
            .refSeqNum(refSeqNum)
            .session(sessionId)
            .sequenceIndex(sequenceIndex)
            .putBusinessRejectRefID(businessRejectRefIDBuffer, businessRejectRefIDOffset, businessRejectRefIDLength);

        bufferClaim.commit();

        logSbeMessage(FIX_MESSAGE, refMsgType, throttleNotification);

        return position;
    }

    public long saveThrottleReject(
        final int libraryId,
        final long connectionId,
        final long refMsgType,
        final int refSeqNum,
        final int sequenceNumber,
        final long sessionId,
        final int sequenceIndex,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        final long position = claim(THROTTLE_REJECT_LENGTH + businessRejectRefIDLength);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        throttleReject
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .connection(connectionId)
            .refMsgType(refMsgType)
            .refSeqNum(refSeqNum)
            .sequenceNumber(sequenceNumber)
            .session(sessionId)
            .sequenceIndex(sequenceIndex)
            .putBusinessRejectRefID(businessRejectRefIDBuffer, businessRejectRefIDOffset, businessRejectRefIDLength);

        bufferClaim.commit();

        logSbeMessage(FIX_MESSAGE, refMsgType, throttleReject);

        return position;
    }

    public long saveThrottleConfiguration(
        final int libraryId,
        final long correlationId,
        final long sessionId,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages)
    {
        final long position = claim(THROTTLE_CONFIGURATION_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        throttleConfiguration
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .correlationId(correlationId)
            .session(sessionId)
            .throttleWindowInMs(throttleWindowInMs)
            .throttleLimitOfMessages(throttleLimitOfMessages);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, throttleConfiguration);

        return position;
    }

    public long saveSeqIndexSync(
        final int libraryId,
        final long sessionId,
        final int sequenceIndex)
    {
        final long position = claim(SEQ_INDEX_SYNC_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        seqIndexSyncEncoder
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .sessionId(sessionId)
            .sequenceIndex(sequenceIndex);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, seqIndexSyncEncoder);

        return position;
    }

    public long saveThrottleConfigurationReply(
        final int libraryId,
        final long replyToId,
        final ThrottleConfigurationStatus status)
    {
        final long position = claim(THROTTLE_CONFIGURATION_REPLY_LENGTH);
        if (position < 0)
        {
            return position;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        throttleConfigurationReply
            .wrapAndApplyHeader(buffer, offset, header)
            .libraryId(libraryId)
            .replyToId(replyToId)
            .status(status);

        bufferClaim.commit();

        logSbeMessage(GATEWAY_MESSAGE, throttleConfigurationReply);

        return position;
    }

    public int sessionId()
    {
        return dataPublication.sessionId();
    }

    public long position()
    {
        return dataPublication.position();
    }

    private byte[] bytes(final String host)
    {
        if (host == null)
        {
            return NO_BYTES;
        }

        return host.getBytes(UTF_8);
    }
}
