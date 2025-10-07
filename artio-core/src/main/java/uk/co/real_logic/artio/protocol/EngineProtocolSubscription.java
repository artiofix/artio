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

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.messages.*;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public final class EngineProtocolSubscription implements ControlledFragmentHandler
{
    private static final int FOLLOWER_SESSION_REQUEST_LENGTH =
        FollowerSessionRequestDecoder.BLOCK_LENGTH + FollowerSessionRequestDecoder.headerHeaderLength();
    private static final int WRITE_META_DATA_DATA_LENGTH =
        WriteMetaDataDecoder.BLOCK_LENGTH + WriteMetaDataDecoder.metaDataHeaderLength();

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final InitiateConnectionDecoder initiateConnection = new InitiateConnectionDecoder();
    private final RequestDisconnectDecoder requestDisconnect = new RequestDisconnectDecoder();
    private final MidConnectionDisconnectDecoder midConnectionDisconnect = new MidConnectionDisconnectDecoder();
    private final ApplicationHeartbeatDecoder applicationHeartbeat = new ApplicationHeartbeatDecoder();
    private final LibraryConnectDecoder libraryConnect = new LibraryConnectDecoder();
    private final ReleaseSessionDecoder releaseSession = new ReleaseSessionDecoder();
    private final RequestSessionDecoder requestSession = new RequestSessionDecoder();
    private final FollowerSessionRequestDecoder followerSessionRequest = new FollowerSessionRequestDecoder();
    private final WriteMetaDataDecoder writeMetaData = new WriteMetaDataDecoder();
    private final ReadMetaDataDecoder readMetaData = new ReadMetaDataDecoder();
    private final ReplayMessagesDecoder replayMessages = new ReplayMessagesDecoder();
    private final InitiateILinkConnectionDecoder initiateILinkConnection = new InitiateILinkConnectionDecoder();
    private final CancelOnDisconnectTriggerDecoder cancelOnDisconnectTrigger = new CancelOnDisconnectTriggerDecoder();
    private final ThrottleRejectDecoder throttleReject = new ThrottleRejectDecoder();
    private final ThrottleConfigurationDecoder throttleConfiguration = new ThrottleConfigurationDecoder();
    private final SeqIndexSyncDecoder seqIndexSync = new SeqIndexSyncDecoder();
    private final ValidResendRequestDecoder validResendRequest = new ValidResendRequestDecoder();

    private final EngineEndPointHandler handler;

    public EngineProtocolSubscription(final EngineEndPointHandler handler)
    {
        this.handler = handler;
    }

    @SuppressWarnings("FinalParameters")
    public Action onFragment(final DirectBuffer buffer, int offset, final int length, final Header header)
    {
        messageHeader.wrap(buffer, offset);

        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();
        offset += messageHeader.encodedLength();

        switch (messageHeader.templateId())
        {
            case RequestDisconnectDecoder.TEMPLATE_ID:
                return onRequestDisconnect(buffer, offset, blockLength, version, header);

            case InitiateConnectionDecoder.TEMPLATE_ID:
                return onInitiateConnection(buffer, offset, blockLength, version, header);

            case ApplicationHeartbeatDecoder.TEMPLATE_ID:
                return onApplicationHeartbeat(buffer, offset, blockLength, version, header);

            case LibraryConnectDecoder.TEMPLATE_ID:
                return onLibraryConnect(buffer, offset, blockLength, version, header);

            case ReleaseSessionDecoder.TEMPLATE_ID:
                return onReleaseSession(buffer, offset, blockLength, version, header);

            case RequestSessionDecoder.TEMPLATE_ID:
                return onRequestSession(buffer, offset, blockLength, version, header);

            case MidConnectionDisconnectDecoder.TEMPLATE_ID:
                return onMidConnectionDisconnect(buffer, offset, blockLength, version, header);

            case FollowerSessionRequestDecoder.TEMPLATE_ID:
                return onFollowerSessionRequest(buffer, offset, blockLength, version, header);

            case WriteMetaDataDecoder.TEMPLATE_ID:
                return onWriteMetaData(buffer, offset, blockLength, version, header);

            case ReadMetaDataDecoder.TEMPLATE_ID:
                return onReadMetaData(buffer, offset, blockLength, version, header);

            case ReplayMessagesDecoder.TEMPLATE_ID:
                return onReplayMessages(buffer, offset, blockLength, version, header);

            case InitiateILinkConnectionDecoder.TEMPLATE_ID:
                return onInitiateILinkConnection(buffer, offset, blockLength, version, header);

            case CancelOnDisconnectTriggerDecoder.TEMPLATE_ID:
                return onCancelOnDisconnectTrigger(buffer, offset, blockLength, version);

            case ThrottleRejectDecoder.TEMPLATE_ID:
                return onThrottleReject(buffer, offset, blockLength, version, header);

            case ThrottleConfigurationDecoder.TEMPLATE_ID:
                return onThrottleConfiguration(buffer, offset, blockLength, version, header);

            case SeqIndexSyncDecoder.TEMPLATE_ID:
                return onSeqIndexSync(buffer, offset, blockLength, version, header);

            case ValidResendRequestDecoder.TEMPLATE_ID:
                return onValidResendRequest(buffer, offset, blockLength, version, header);
        }

        return CONTINUE;
    }

    private Action onValidResendRequest(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version, final Header header)
    {
        final ValidResendRequestDecoder validResendRequest = this.validResendRequest;
        validResendRequest.wrap(buffer, offset, blockLength, version);

        return handler.onValidResendRequest(
            validResendRequest.session(),
            validResendRequest.connection(),
            validResendRequest.correlationId(),
            header);
    }

    private Action onSeqIndexSync(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version, final Header header)
    {
        final SeqIndexSyncDecoder seqIndexSync = this.seqIndexSync;
        seqIndexSync.wrap(buffer, offset, blockLength, version);

        final int libraryId = seqIndexSync.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), SeqIndexSyncDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }

        return handler.onSeqIndexSync(
            libraryId,
            seqIndexSync.sessionId(),
            seqIndexSync.sequenceIndex());
    }

    private Action onThrottleConfiguration(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version, final Header header)
    {
        final ThrottleConfigurationDecoder throttleConfiguration = this.throttleConfiguration;
        throttleConfiguration.wrap(buffer, offset, blockLength, version);

        final int libraryId = throttleConfiguration.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), ThrottleConfigurationDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }

        return handler.onThrottleConfiguration(
            libraryId,
            throttleConfiguration.correlationId(),
            throttleConfiguration.session(),
            throttleConfiguration.throttleWindowInMs(),
            throttleConfiguration.throttleLimitOfMessages());
    }

    private Action onThrottleReject(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version, final Header header)
    {
        final ThrottleRejectDecoder throttleReject = this.throttleReject;
        throttleReject.wrap(buffer, offset, blockLength, version);

        final int businessRejectRefIDOffset =
            throttleReject.limit() + ThrottleRejectDecoder.businessRejectRefIDHeaderLength();

        return handler.onThrottleReject(
            throttleReject.libraryId(),
            throttleReject.connection(),
            throttleReject.refMsgType(),
            throttleReject.refSeqNum(),
            throttleReject.sequenceNumber(),
            throttleReject.sequenceIndex(),
            buffer,
            businessRejectRefIDOffset,
            throttleReject.businessRejectRefIDLength(),
            header);
    }

    private Action onCancelOnDisconnectTrigger(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version)
    {
        final CancelOnDisconnectTriggerDecoder cancelOnDisconnectTrigger = this.cancelOnDisconnectTrigger;

        cancelOnDisconnectTrigger.wrap(buffer, offset, blockLength, version);
        handler.onCancelOnDisconnectTrigger(
            cancelOnDisconnectTrigger.sessionId(), cancelOnDisconnectTrigger.timeInNs());
        return CONTINUE;
    }

    private Action onApplicationHeartbeat(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final ApplicationHeartbeatDecoder applicationHeartbeat = this.applicationHeartbeat;
        applicationHeartbeat.wrap(buffer, offset, blockLength, version);
        final int libraryId = applicationHeartbeat.libraryId();
        final long timestampInNs = applicationHeartbeat.timestampInNs();
        final int sessionId = header.sessionId();
        handler.onApplicationHeartbeat(libraryId, sessionId, ApplicationHeartbeatDecoder.TEMPLATE_ID, timestampInNs);
        return CONTINUE;
    }

    private Action onLibraryConnect(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        libraryConnect.wrap(buffer, offset, blockLength, version);
        final int libraryId = libraryConnect.libraryId();
        final String libraryName = libraryConnect.libraryName();

        return handler.onLibraryConnect(
            libraryId,
            libraryName,
            libraryConnect.correlationId(),
            header.sessionId());
    }

    private Action onReleaseSession(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final ReleaseSessionDecoder releaseSession = this.releaseSession;
        releaseSession.wrap(buffer, offset, blockLength, version);
        final int libraryId = releaseSession.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), ReleaseSessionDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onReleaseSession(
            libraryId,
            releaseSession.connection(),
            releaseSession.sessionId(),
            releaseSession.correlationId(),
            releaseSession.state(),
            releaseSession.awaitingResend() == AwaitingResend.YES,
            releaseSession.heartbeatIntervalInMs(),
            releaseSession.lastSentSequenceNumber(),
            releaseSession.lastReceivedSequenceNumber(),
            releaseSession.username(),
            releaseSession.password(),
            header);
    }

    private Action onRequestSession(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final RequestSessionDecoder requestSession = this.requestSession;
        requestSession.wrap(buffer, offset, blockLength, version);
        final int libraryId = requestSession.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), RequestSessionDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages but not this message.
        }

        return handler.onRequestSession(
            libraryId,
            requestSession.sessionId(),
            requestSession.correlationId(),
            requestSession.lastReceivedSequenceNumber(),
            requestSession.sequenceIndex());
    }

    private Action onInitiateConnection(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final InitiateConnectionDecoder initiateConnection = this.initiateConnection;
        initiateConnection.wrap(buffer, offset, blockLength, version);
        final int libraryId = initiateConnection.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), InitiateConnectionDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but don't process this message.
        }

        return handler.onInitiateConnection(
            libraryId,
            initiateConnection.port(),
            initiateConnection.host(),
            initiateConnection.senderCompId(),
            initiateConnection.senderSubId(),
            initiateConnection.senderLocationId(),
            initiateConnection.targetCompId(),
            initiateConnection.targetSubId(),
            initiateConnection.targetLocationId(),
            initiateConnection.sequenceNumberType(),
            initiateConnection.requestedInitialReceivedSequenceNumber(),
            initiateConnection.requestedInitialSentSequenceNumber(),
            initiateConnection.resetSequenceNumber() == ResetSequenceNumber.YES,
            initiateConnection.closedResendInterval() == Bool.TRUE,
            initiateConnection.resendRequestChunkSize(),
            initiateConnection.sendRedundantResendRequests() == Bool.TRUE,
            initiateConnection.enableLastMsgSeqNumProcessed() == Bool.TRUE,
            initiateConnection.username(),
            initiateConnection.password(),
            FixDictionary.find(initiateConnection.fixDictionary()),
            initiateConnection.heartbeatIntervalInS(),
            initiateConnection.correlationId(),
            header
        );
    }

    private Action onRequestDisconnect(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final RequestDisconnectDecoder requestDisconnect = this.requestDisconnect;
        requestDisconnect.wrap(buffer, offset, blockLength, version);
        final int libraryId = requestDisconnect.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), RequestDisconnectDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onRequestDisconnect(
            libraryId,
            requestDisconnect.connection(),
            requestDisconnect.reason());
    }

    private Action onMidConnectionDisconnect(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final MidConnectionDisconnectDecoder midConnectionDisconnect = this.midConnectionDisconnect;
        midConnectionDisconnect.wrap(buffer, offset, blockLength, version);
        final int libraryId = midConnectionDisconnect.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), MidConnectionDisconnectDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onMidConnectionDisconnect(
            libraryId,
            midConnectionDisconnect.correlationId());
    }

    private Action onFollowerSessionRequest(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final FollowerSessionRequestDecoder followerSessionRequest = this.followerSessionRequest;
        followerSessionRequest.wrap(buffer, offset, blockLength, version);
        final int libraryId = followerSessionRequest.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), FollowerSessionRequestDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        final int messageLength = followerSessionRequest.headerLength();
        return handler.onFollowerSessionRequest(
            libraryId,
            followerSessionRequest.correlationId(),
            followerSessionRequest.protocolType(),
            buffer,
            offset + FOLLOWER_SESSION_REQUEST_LENGTH,
            messageLength,
            header);
    }

    private Action onWriteMetaData(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final WriteMetaDataDecoder writeMetaData = this.writeMetaData;
        writeMetaData.wrap(buffer, offset, blockLength, version);
        final int libraryId = writeMetaData.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), WriteMetaDataDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        final int metaDataLength = writeMetaData.metaDataLength();
        return handler.onWriteMetaData(
            libraryId,
            writeMetaData.session(),
            writeMetaData.correlationId(),
            writeMetaData.metaDataOffset(),
            buffer,
            offset + WRITE_META_DATA_DATA_LENGTH,
            metaDataLength);
    }

    private Action onReadMetaData(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final ReadMetaDataDecoder readMetaData = this.readMetaData;
        readMetaData.wrap(buffer, offset, blockLength, version);
        final int libraryId = readMetaData.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), ReadMetaDataDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onReadMetaData(
            libraryId,
            readMetaData.session(),
            readMetaData.correlationId());
    }

    private Action onReplayMessages(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final ReplayMessagesDecoder replayMessages = this.replayMessages;
        replayMessages.wrap(buffer, offset, blockLength, version);
        final int libraryId = replayMessages.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), ReplayMessagesDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onReplayMessages(
            libraryId,
            replayMessages.session(),
            replayMessages.correlationId(),
            replayMessages.replayFromSequenceNumber(),
            replayMessages.replayFromSequenceIndex(),
            replayMessages.replayToSequenceNumber(),
            replayMessages.replayToSequenceIndex(),
            replayMessages.latestReplyArrivalTimeInMs());
    }

    private Action onInitiateILinkConnection(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        final InitiateILinkConnectionDecoder initiateILinkConnection = this.initiateILinkConnection;
        initiateILinkConnection.wrap(buffer, offset, blockLength, version);
        final int libraryId = initiateILinkConnection.libraryId();
        final Action action = handler.onApplicationHeartbeat(
            libraryId, header.sessionId(), InitiateILinkConnectionDecoder.TEMPLATE_ID, 0);
        if (action != null)
        {
            return action; // Continue processing messages, but not this message.
        }
        return handler.onInitiateILinkConnection(
            libraryId,
            initiateILinkConnection.port(),
            initiateILinkConnection.correlationId(),
            initiateILinkConnection.reestablishConnection() == Bool.TRUE,
            initiateILinkConnection.useBackupHost() == Bool.TRUE,
            initiateILinkConnection.host(),
            initiateILinkConnection.accessKeyId(),
            initiateILinkConnection.backupHost());
    }

}
