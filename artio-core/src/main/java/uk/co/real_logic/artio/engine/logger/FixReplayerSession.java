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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.engine.PossDupEnabler;
import uk.co.real_logic.artio.engine.ReplayHandler;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.framer.FixThrottleRejectBuilder;
import uk.co.real_logic.artio.engine.framer.MessageTypeExtractor;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;
import static uk.co.real_logic.artio.LogTag.*;
import static uk.co.real_logic.artio.dictionary.SessionConstants.BUSINESS_MESSAGE_REJECT_MESSAGE_TYPE;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.framer.SenderEndPoint.NOT_LAST_REPLAY_MSG;
import static uk.co.real_logic.artio.engine.logger.Replayer.MESSAGE_FRAME_BLOCK_LENGTH;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataHeaderLength;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataSinceVersion;

class FixReplayerSession extends ReplayerSession
{
    private static final int NONE = -1;
    private static final byte[] NO_BYTES = new byte[0];

    private enum State
    {
        REPLAYING,
        CHECK_REPLAY,
        SEND_COMPLETE_MESSAGE,
        CLOSING
    }

    private final GapFillEncoder gapFillEncoder;
    private final PossDupEnabler possDupEnabler;
    private final EpochNanoClock clock;
    private final String message;
    private final ReplayHandler replayHandler;
    private final LongHashSet gapFillMessageTypes;
    private final ErrorHandler errorHandler;
    private final SequenceNumberExtractor sequenceNumberExtractor;
    private final FixThrottleRejectBuilder throttleRejectBuilder;
    private final int overriddenBeginSeqNo;

    private int lastSeqNo;
    private int headerSeqNum;

    private int beginGapFillSeqNum = NONE;

    private State state;

    FixReplayerSession(
        final BufferClaim bufferClaim,
        final IdleStrategy idleStrategy,
        final ReplayHandler replayHandler,
        final int maxClaimAttempts,
        final LongHashSet gapFillMessageTypes,
        final ExclusivePublication publication,
        final EpochNanoClock clock,
        final int beginSeqNo,
        final int endSeqNo,
        final long connectionId,
        final long correlationId,
        final long sessionId,
        final int sequenceIndex,
        final int overriddenBeginSeqNo,
        final ReplayQuery replayQuery,
        final String message,
        final ErrorHandler errorHandler,
        final GapFillEncoder gapFillEncoder,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final UtcTimestampEncoder utcTimestampEncoder,
        final Replayer replayer,
        final FixThrottleRejectBuilder throttleRejectBuilder)
    {
        super(connectionId, correlationId, bufferClaim, idleStrategy, maxClaimAttempts, publication, replayQuery,
            beginSeqNo, endSeqNo,
            sessionId, sequenceIndex, replayer, bytesInBuffer, maxBytesInBuffer);
        this.replayHandler = replayHandler;
        this.gapFillMessageTypes = gapFillMessageTypes;
        this.clock = clock;
        this.message = message;
        this.errorHandler = errorHandler;
        this.gapFillEncoder = gapFillEncoder;
        this.overriddenBeginSeqNo = overriddenBeginSeqNo;

        this.sequenceNumberExtractor = new SequenceNumberExtractor();

        if (beginSeqNo < overriddenBeginSeqNo)
        {
            this.beginGapFillSeqNum = beginSeqNo;
            this.lastSeqNo = overriddenBeginSeqNo - 1;
        }
        else
        {
            this.beginGapFillSeqNum = NONE;
            this.lastSeqNo = beginSeqNo - 1;
        }

        this.throttleRejectBuilder = throttleRejectBuilder;
        this.possDupEnabler = new PossDupEnabler(
            utcTimestampEncoder,
            bufferClaim,
            this::claimBuffer,
            this::onPreCommit,
            this::onIllegalState,
            this::onException,
            clock,
            publication.maxPayloadLength());

        state = State.REPLAYING;
    }

    void query()
    {
        final int adjustedBeginSeqNo = adjustBeginningSequenceNo(beginSeqNo, overriddenBeginSeqNo);
        replayOperation = replayQuery.query(
            sessionId,
            adjustedBeginSeqNo,
            sequenceIndex,
            endSeqNo,
            sequenceIndex,
            REPLAY,
            new FixMessageTracker(REPLAY_MESSAGE, this, sessionId));
    }

    private void onPreCommit(final MutableDirectBuffer buffer, final int offset)
    {
        final int frameOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        replayer.fixMessageEncoder
            .wrap(buffer, frameOffset)
            .connection(connectionId)
            .sequenceNumber(headerSeqNum);
    }

    private void onException(final Throwable e)
    {
        final String exMessage = String.format("[%s] Error replying to message", message);
        errorHandler.onError(new IllegalArgumentException(exMessage, e));
    }

    private void onIllegalState(final String message, final Object... arguments)
    {
        errorHandler.onError(new IllegalStateException(String.format(message, arguments)));
    }

    // Callback for the ReplayQuery:
    public Action onFragment(
        final DirectBuffer srcBuffer, final int srcOffset, final int srcLength, final Header header)
    {
        final MessageHeaderDecoder messageHeader = replayer.messageHeaderDecoder.wrap(srcBuffer, srcOffset);

        final int actingBlockLength = messageHeader.blockLength();
        final int templateId = messageHeader.templateId();
        final int offset = srcOffset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int version = messageHeader.version();

        switch (templateId)
        {
            case FixMessageDecoder.TEMPLATE_ID:
            {
                return onFixMessage(srcBuffer, srcOffset, srcLength, actingBlockLength, offset, version);
            }

            case ThrottleRejectDecoder.TEMPLATE_ID:
            {
                return onThrottleReject(srcBuffer, actingBlockLength, offset, version);
            }
        }

        return CONTINUE;
    }

    private Action onFixMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final int actingBlockLength,
        final int offset,
        final int version)
    {
        final FixMessageDecoder fixMessageDecoder = replayer.fixMessageDecoder;
        fixMessageDecoder.wrap(
            srcBuffer,
            offset,
            actingBlockLength,
            version);

        if (fixMessageDecoder.status() == MessageStatus.OK)
        {
            final int metaDataAdjustment = version >= metaDataSinceVersion() ?
                metaDataHeaderLength() + fixMessageDecoder.metaDataLength() : 0;
            final int messageFrameBlockLength = MESSAGE_FRAME_BLOCK_LENGTH + metaDataAdjustment;
            final int messageOffset = srcOffset + messageFrameBlockLength;
            final int messageLength = srcLength - messageFrameBlockLength;

            final int msgSeqNum = sequenceNumberExtractor.extract(srcBuffer, messageOffset, messageLength);
            final long messageType = MessageTypeExtractor.getMessageType(fixMessageDecoder);

            final AsciiBuffer asciiBuffer = replayer.sessionAsciiBuffer;
            asciiBuffer.wrap(srcBuffer);
            replayHandler.onReplayedMessage(
                asciiBuffer,
                messageOffset,
                messageLength,
                fixMessageDecoder.libraryId(),
                fixMessageDecoder.session(),
                fixMessageDecoder.sequenceIndex(),
                messageType);

            if (gapFillMessageTypes.contains(messageType))
            {
                if (beginGapFillSeqNum == NONE)
                {
                    beginGapFillSeqNum(lastSeqNo + 1);
                }

                lastSeqNo = msgSeqNum;
                return CONTINUE;
            }
            else
            {
                if (beginGapFillSeqNum != NONE)
                {
                    sendGapFill(beginGapFillSeqNum, msgSeqNum, false);
                }
                else if (msgSeqNum > lastSeqNo + 1)
                {
                    if (lastSeqNo == 0)
                    {
                        // In the unlikely event of requesting a resend-request that is larger than the replay index
                        // size the gap-fill at the beginning of the resend-request can hit this condition.
                        lastSeqNo = 1;
                    }
                    sendGapFill(lastSeqNo, msgSeqNum, false);
                }

                headerSeqNum = msgSeqNum == endSeqNo ? msgSeqNum : NOT_LAST_REPLAY_MSG;
                final Action action = possDupEnabler.enablePossDupFlag(
                    srcBuffer, messageOffset, messageLength, srcOffset, srcLength, metaDataAdjustment, messageType);
                if (action != ABORT)
                {
                    lastSeqNo = msgSeqNum;
                }

                return action;
            }
        }

        return CONTINUE;
    }

    private Action onThrottleReject(
        final DirectBuffer srcBuffer, final int actingBlockLength, final int offset, final int version)
    {
        final ThrottleRejectDecoder throttleRejectDecoder = replayer.throttleRejectDecoder;
        throttleRejectDecoder.wrap(
            srcBuffer,
            offset,
            actingBlockLength,
            version);
        final int msgSeqNum = throttleRejectDecoder.sequenceNumber();

        if (gapFillMessageTypes.contains(BUSINESS_MESSAGE_REJECT_MESSAGE_TYPE))
        {
            if (beginGapFillSeqNum == NONE)
            {
                beginGapFillSeqNum(lastSeqNo + 1);
            }

            lastSeqNo = msgSeqNum;
            return CONTINUE;
        }
        else
        {
            if (beginGapFillSeqNum != NONE)
            {
                sendGapFill(beginGapFillSeqNum, msgSeqNum, false);
            }
            else if (msgSeqNum > lastSeqNo + 1)
            {
                sendGapFill(lastSeqNo, msgSeqNum, false);
            }

            final int businessRejectRefIDOffset = throttleRejectDecoder.limit() +
                ThrottleNotificationDecoder.businessRejectRefIDHeaderLength();
            throttleRejectBuilder.build(
                throttleRejectDecoder.refMsgType(),
                throttleRejectDecoder.refSeqNum(),
                throttleRejectDecoder.sequenceNumber(),
                srcBuffer,
                businessRejectRefIDOffset,
                throttleRejectDecoder.businessRejectRefIDLength(),
                true);

            final Action action = sendFixMessage(
                throttleRejectBuilder.buffer(),
                throttleRejectBuilder.offset(),
                throttleRejectBuilder.length(),
                BUSINESS_MESSAGE_REJECT_MESSAGE_TYPE,
                NOT_LAST_REPLAY_MSG);
            if (action == CONTINUE)
            {
                lastSeqNo = msgSeqNum;
            }
            return action;
        }
    }

    private Action sendGapFill(final int msgSeqNo, final int newSeqNo, final boolean lastMessage)
    {
        final long result = gapFillEncoder.encode(msgSeqNo, newSeqNo);
        final int gapFillLength = Encoder.length(result);
        final int gapFillOffset = Encoder.offset(result);
        final MutableAsciiBuffer buffer = gapFillEncoder.buffer();
        final int sequenceNumber = lastMessage ? msgSeqNo : NOT_LAST_REPLAY_MSG;

        final Action action = sendFixMessage(buffer, gapFillOffset, gapFillLength, SEQUENCE_RESET_MESSAGE_TYPE,
            sequenceNumber);
        if (action == CONTINUE)
        {
            this.beginGapFillSeqNum(NONE);
        }
        return action;
    }

    private Action sendFixMessage(
        final MutableAsciiBuffer fixBuffer, final int fixOffset, final int fixLength, final long messageType,
        final int sequenceNumber)
    {
        if (claimBuffer(
            MESSAGE_FRAME_BLOCK_LENGTH + fixLength + metaDataHeaderLength(), fixLength))
        {
            final int destOffset = bufferClaim.offset();
            final MutableDirectBuffer destBuffer = bufferClaim.buffer();

            replayer.fixMessageEncoder
                .wrapAndApplyHeader(destBuffer, destOffset, replayer.messageHeaderEncoder)
                .session(this.sessionId)
                .connection(this.connectionId)
                .timestamp(clock.nanoTime())
                .status(MessageStatus.OK)
                .libraryId(ENGINE_LIBRARY_ID)
                .sequenceIndex(this.sequenceIndex)
                .sequenceNumber(sequenceNumber)
                .messageType(messageType)
                .putMetaData(NO_BYTES, 0, 0)
                .putBody(fixBuffer, fixOffset, fixLength);

            bufferClaim.commit();

            DebugLogger.logFixMessage(
                LogTag.FIX_MESSAGE, messageType, "Replayed: ", fixBuffer, fixOffset, fixLength);

            return CONTINUE;
        }
        else
        {
            DebugLogger.log(REPLAY, "Back pressured trying to sendFixMessage");

            return ABORT;
        }
    }

    boolean attemptReplay()
    {
        switch (state)
        {
            case REPLAYING:
                DebugLogger.log(REPLAY_ATTEMPT, "ReplayerSession: REPLAYING step");
                if (replayOperation.pollReplay())
                {
                    state = State.CHECK_REPLAY;
                    return attemptReplay();
                }
                return false;

            case CHECK_REPLAY:
                DebugLogger.log(REPLAY_ATTEMPT, "ReplayerSession: CHECK_REPLAY step");
                if (completeReplay())
                {
                    state = State.SEND_COMPLETE_MESSAGE;
                }
                return false;

            case SEND_COMPLETE_MESSAGE:
                return sendCompleteMessage();

            case CLOSING:
            {
                return replayOperation.pollReplay();
            }

            default:
                return false;
        }
    }

    private boolean completeReplay()
    {
        // Load state needed to complete the replay
        final int replayedMessages = replayOperation.replayedMessages();

        // If the last N messages were admin messages then we need to send a gapfill
        // after the replay query has run.
        final int newSequenceNumber = endSeqNo + 1;
        if (beginGapFillSeqNum != NONE)
        {
            if (newSequenceNumber > beginGapFillSeqNum)
            {
                final Action action = sendGapFill(beginGapFillSeqNum, newSequenceNumber, true);

                if (IS_REPLAY_LOG_TAG_ENABLED)
                {
                    DebugLogger.log(
                        REPLAY,
                        replayer.completeReplayGapfillFormatter.clear().with(action.name()).with(replayedMessages)
                        .with(beginGapFillSeqNum).with(newSequenceNumber).with(connectionId));
                }

                return action != ABORT;
            }
        }
        else
        {
            // Validate that we've replayed the correct number of messages.
            // If we have missing messages for some reason then just gap fill them.

            // We know precisely what number to gap fill up to.
            final int adjustedBeginSeqNo = adjustBeginningSequenceNo(beginSeqNo, overriddenBeginSeqNo);
            final int expectedCount = endSeqNo - adjustedBeginSeqNo + 1;

            if (IS_REPLAY_LOG_TAG_ENABLED)
            {
                DebugLogger.log(
                    REPLAY,
                    replayer.completeNotRecentFormatter.clear().with(replayedMessages)
                    .with(endSeqNo).with(beginSeqNo).with(overriddenBeginSeqNo)
                    .with(expectedCount).with(connectionId));
            }

            if (replayedMessages != expectedCount)
            {
                if (lastSeqNo < endSeqNo)
                {
                    final Action action = sendGapFill(lastSeqNo + 1, newSequenceNumber, true);
                    if (action == ABORT)
                    {
                        return false;
                    }
                }

                onIllegalState(
                    "[%s] Error in resend request, count(%d) < expectedCount (%d), newSequenceNumber(%d), " +
                    "endSeqNo(%d)",
                    message, replayedMessages, expectedCount, newSequenceNumber, endSeqNo);
            }
        }

        return true;
    }

    void startClose()
    {
        state = State.CLOSING;
        super.startClose();
    }

    public String toString()
    {
        return "FixReplayerSession{" +
            "message='" + message + '\'' +
            ", gapFillMessageTypes=" + gapFillMessageTypes +
            ", bytesInBuffer=" + bytesInBuffer +
            ", maxBytesInBuffer=" + maxBytesInBuffer +
            ", lastSeqNo=" + lastSeqNo +
            ", beginGapFillSeqNum=" + beginGapFillSeqNum +
            ", state=" + state +
            ", connectionId=" + connectionId +
            ", beginSeqNo=" + beginSeqNo +
            ", endSeqNo=" + endSeqNo +
            ", overriddenBeginSeqNo=" + overriddenBeginSeqNo +
            ", sessionId=" + sessionId +
            ", sequenceIndex=" + sequenceIndex +
            '}';
    }

    public void beginGapFillSeqNum(final int beginGapFillSeqNum)
    {
        this.beginGapFillSeqNum = beginGapFillSeqNum;
    }


    static int adjustBeginningSequenceNo(final int beginSeqNo, final int overriddenBeginSeqNo)
    {
        return overriddenBeginSeqNo != (int)ValidResendRequestEncoder.overriddenBeginSequenceNumberNullValue() ?
                overriddenBeginSeqNo : beginSeqNo;
    }
}
