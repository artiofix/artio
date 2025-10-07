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

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.SequenceNumberType;
import uk.co.real_logic.artio.messages.SessionState;

public interface EngineEndPointHandler
{
    Action onLibraryConnect(
        int libraryId,
        String libraryName,
        long correlationId,
        int aeronSessionId);

    Action onInitiateConnection(
        int libraryId,
        int port,
        String host,
        String senderCompId,
        String senderSubId,
        String senderLocationId,
        String targetCompId,
        String targetSubId,
        String targetLocationId,
        SequenceNumberType sequenceNumberType,
        int requestedInitialSequenceNumber,
        int requestedInitialSentSequenceNumber,
        boolean resetSequenceNumber,
        boolean closedResendInterval,
        int resendRequestChunkSize,
        boolean sendRedundantResendRequests,
        boolean enableLastMsgSeqNumProcessed,
        String username,
        String password,
        Class<? extends FixDictionary> fixDictionary,
        int heartbeatIntervalInS,
        long correlationId,
        Header header);

    Action onRequestDisconnect(int libraryId, long connectionId, DisconnectReason reason);

    Action onApplicationHeartbeat(int libraryId, int aeronSessionId, int messageTemplateId, long timestampInNs);

    Action onReleaseSession(
        int libraryId,
        long connectionId,
        long sessionId,
        long correlationId,
        SessionState state,
        boolean awaitingResend,
        long heartbeatIntervalInMs,
        int lastSentSequenceNumber,
        int lastReceivedSequenceNumber,
        String username,
        String password,
        Header header);

    Action onRequestSession(
        int libraryId,
        long sessionId,
        long correlationId,
        int lastReceivedSequenceNumber,
        int sequenceIndex);

    Action onMidConnectionDisconnect(int libraryId, long correlationId);

    Action onFollowerSessionRequest(
        int libraryId,
        long correlationId,
        FixPProtocolType fixPProtocolType,
        DirectBuffer srcBuffer,
        int srcOffset,
        int srcLength,
        Header header);

    Action onWriteMetaData(
        int libraryId,
        long sessionId,
        long correlationId,
        int metaDataOffset,
        DirectBuffer srcBuffer,
        int srcOffset,
        int srcLength);

    Action onReadMetaData(
        int libraryId,
        long sessionId,
        long correlationId);

    Action onReplayMessages(
        int libraryId,
        long sessionId,
        long correlationId,
        int replayFromSequenceNumber,
        int replayFromSequenceIndex,
        int replayToSequenceNumber,
        int replayToSequenceIndex,
        long latestReplyArrivalTimeInMs);

    Action onInitiateILinkConnection(
        int libraryId, int port, long correlationId,
        boolean reestablishConnection, boolean useBackupHost,
        String host, String accessKeyId, String backupHost);

    void onCancelOnDisconnectTrigger(long sessionId, long timeInNs);

    Action onThrottleReject(
        int libraryId,
        long connection,
        long refMsgType,
        int refSeqNum,
        int sequenceNumber,
        int sequenceIndex,
        DirectBuffer businessRejectRefIDBuffer,
        int businessRejectRefIDOffset,
        int businessRejectRefIDLength,
        Header header);

    Action onThrottleConfiguration(
        int libraryId, long correlationId, long session, int throttleWindowInMs, int throttleLimitOfMessages);

    Action onSeqIndexSync(int libraryId, long sessionId, int sequenceIndex);

    Action onValidResendRequest(long session, long connection, long correlationId, Header header);
}
