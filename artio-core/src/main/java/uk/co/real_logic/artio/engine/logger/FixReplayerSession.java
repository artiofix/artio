/*
 * Copyright 2015-2020 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.engine.PossDupEnabler;
import uk.co.real_logic.artio.engine.ReplayHandler;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.framer.MessageTypeExtractor;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.LogTag.REPLAY_ATTEMPT;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.logger.Replayer.MESSAGE_FRAME_BLOCK_LENGTH;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataHeaderLength;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.metaDataSinceVersion;

class FixReplayerSession extends ReplayerSession
{
    static class Formatters
    {
        private final CharFormatter completeNotRecentFormatter = new CharFormatter(
            "ReplayerSession: completeReplay-!upToMostRecent replayedMessages=%s " +
            "endSeqNo=%s beginSeqNo=%s expectedCount=%s%n");
        private final CharFormatter completeReplayGapfillFormatter = new CharFormatter(
            "ReplayerSession: completeReplay-sendGapFill action=%s, replayedMessages=%s, " +
            "beginGapFillSeqNum=%s, newSequenceNumber=%s%n");
    }

    private static final int NONE = -1;
    private static final byte[] NO_BYTES = new byte[0];

    private enum State
    {
        INITIAL,
        REPLAYING,
        CHECK_REPLAY,
        SEND_COMPLETE_MESSAGE
    }

    // Safe to share between multiple instances due to single threaded nature of the replayer
    private static final FixMessageEncoder FIX_MESSAGE_ENCODER = new FixMessageEncoder();
    private static final FixMessageDecoder FIX_MESSAGE = new FixMessageDecoder();
    private static final AsciiBuffer ASCII_BUFFER = new MutableAsciiBuffer();

    private final GapFillEncoder gapFillEncoder;

    private final PossDupEnabler possDupEnabler;
    private final String message;
    private final ReplayHandler replayHandler;
    private final LongHashSet gapFillMessageTypes;
    private final ErrorHandler errorHandler;
    private final SequenceNumberExtractor sequenceNumberExtractor;
    private final Formatters formatters;
    private final AtomicCounter bytesInBuffer;
    private final int maxBytesInBuffer;

    private int lastSeqNo;

    private int beginGapFillSeqNum = NONE;

    private State state;

    FixReplayerSession(
        final BufferClaim bufferClaim,
        final IdleStrategy idleStrategy,
        final ReplayHandler replayHandler,
        final int maxClaimAttempts,
        final LongHashSet gapFillMessageTypes,
        final ExclusivePublication publication,
        final EpochClock clock,
        final int beginSeqNo,
        final int endSeqNo,
        final long connectionId,
        final long sessionId,
        final int sequenceIndex,
        final ReplayQuery replayQuery,
        final String message,
        final ErrorHandler errorHandler,
        final GapFillEncoder gapFillEncoder,
        final Formatters formatters,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer,
        final UtcTimestampEncoder utcTimestampEncoder)
    {
        super(connectionId, bufferClaim, idleStrategy, maxClaimAttempts, publication, replayQuery, beginSeqNo, endSeqNo,
            sessionId, sequenceIndex);
        this.replayHandler = replayHandler;
        this.gapFillMessageTypes = gapFillMessageTypes;
        this.message = message;
        this.errorHandler = errorHandler;
        this.gapFillEncoder = gapFillEncoder;
        this.formatters = formatters;
        this.maxBytesInBuffer = maxBytesInBuffer;
        this.bytesInBuffer = bytesInBuffer;

        sequenceNumberExtractor = new SequenceNumberExtractor(errorHandler);

        lastSeqNo = beginSeqNo - 1;

        possDupEnabler = new PossDupEnabler(
            utcTimestampEncoder,
            bufferClaim,
            this::claimMessageBuffer,
            this::onPreCommit,
            this::onIllegalState,
            this::onException,
            clock,
            publication.maxPayloadLength(),
            LogTag.FIX_MESSAGE);

        state = State.INITIAL;
    }

    MessageTracker messageTracker()
    {
        return new FixMessageTracker(REPLAY, this, sessionId);
    }

    private void onPreCommit(final MutableDirectBuffer buffer, final int offset)
    {
        final int frameOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        FIX_MESSAGE_ENCODER
            .wrap(buffer, frameOffset)
            .connection(connectionId);
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
        MESSAGE_HEADER.wrap(srcBuffer, srcOffset);
        final int actingBlockLength = MESSAGE_HEADER.blockLength();
        final int offset = srcOffset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int version = MESSAGE_HEADER.version();

        FIX_MESSAGE.wrap(
            srcBuffer,
            offset,
            actingBlockLength,
            version);

        final int metaDataAdjustment = version >= metaDataSinceVersion() ?
            metaDataHeaderLength() + FIX_MESSAGE.metaDataLength() : 0;
        final int messageFrameBlockLength = MESSAGE_FRAME_BLOCK_LENGTH + metaDataAdjustment;
        final int messageOffset = srcOffset + messageFrameBlockLength;
        final int messageLength = srcLength - messageFrameBlockLength;

        final int msgSeqNum = sequenceNumberExtractor.extract(srcBuffer, messageOffset, messageLength);
        final long messageType = MessageTypeExtractor.getMessageType(FIX_MESSAGE);

        ASCII_BUFFER.wrap(srcBuffer);
        replayHandler.onReplayedMessage(
            ASCII_BUFFER,
            messageOffset,
            messageLength,
            FIX_MESSAGE.libraryId(),
            FIX_MESSAGE.session(),
            FIX_MESSAGE.sequenceIndex(),
            messageType);

        if (gapFillMessageTypes.contains(messageType))
        {
            if (beginGapFillSeqNum == NONE)
            {
                beginGapFillSeqNum = lastSeqNo + 1;
            }

            lastSeqNo = msgSeqNum;
            return CONTINUE;
        }
        else
        {
            if (beginGapFillSeqNum != NONE)
            {
                sendGapFill(beginGapFillSeqNum, msgSeqNum);
            }
            else if (msgSeqNum > lastSeqNo + 1)
            {
                sendGapFill(lastSeqNo, msgSeqNum);
            }

            final Action action = possDupEnabler.enablePossDupFlag(
                srcBuffer, messageOffset, messageLength, srcOffset, srcLength, metaDataAdjustment);
            if (action != ABORT)
            {
                lastSeqNo = msgSeqNum;
            }

            return action;
        }
    }

    private Action sendGapFill(final int msgSeqNo, final int newSeqNo)
    {
        final long result = gapFillEncoder.encode(msgSeqNo, newSeqNo);
        final int gapFillLength = Encoder.length(result);
        final int gapFillOffset = Encoder.offset(result);

        if (claimMessageBuffer(
            MESSAGE_FRAME_BLOCK_LENGTH + gapFillLength + metaDataHeaderLength(), gapFillLength))
        {
            final int destOffset = bufferClaim.offset();
            final MutableDirectBuffer destBuffer = bufferClaim.buffer();
            final MutableAsciiBuffer gapFillBuffer = gapFillEncoder.buffer();

            FIX_MESSAGE_ENCODER
                .wrapAndApplyHeader(destBuffer, destOffset, MESSAGE_HEADER_ENCODER)
                .libraryId(ENGINE_LIBRARY_ID)
                .messageType(SEQUENCE_RESET_MESSAGE_TYPE)
                .session(this.sessionId)
                .sequenceIndex(this.sequenceIndex)
                .connection(this.connectionId)
                .timestamp(0)
                .status(MessageStatus.OK)
                .putMetaData(NO_BYTES, 0, 0)
                .putBody(gapFillBuffer, gapFillOffset, gapFillLength);

            bufferClaim.commit();

            DebugLogger.log(LogTag.FIX_MESSAGE, "Replayed: ", gapFillBuffer, gapFillOffset, gapFillLength);

            this.beginGapFillSeqNum = NONE;

            return CONTINUE;
        }
        else
        {
            DebugLogger.log(REPLAY, "Back pressured trying to sendGapFill");

            return ABORT;
        }
    }

    private boolean claimMessageBuffer(final int newLength, final int messageLength)
    {
        if (maxBytesInBuffer > (bytesInBuffer.get() + messageLength))
        {
            return claimBuffer(newLength);
        }

        return false;
    }

    boolean attemptReplay()
    {
        switch (state)
        {
            case INITIAL:
                state = State.REPLAYING;
                DebugLogger.log(REPLAY_ATTEMPT, "ReplayerSession: REPLAYING step");
                return attemptReplay();

            case REPLAYING:
                if (replayOperation.attemptReplay())
                {
                    state = State.CHECK_REPLAY;
                    DebugLogger.log(REPLAY_ATTEMPT, "ReplayerSession: CHECK_REPLAY step");
                    return attemptReplay();
                }
                return false;

            case CHECK_REPLAY:
                if (completeReplay())
                {
                    state = State.SEND_COMPLETE_MESSAGE;
                }
                return false;

            case SEND_COMPLETE_MESSAGE:
                return sendCompleteMessage();

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
        if (beginGapFillSeqNum != NONE)
        {
            final int newSequenceNumber = endSeqNo + 1;
            final Action action = sendGapFill(beginGapFillSeqNum, newSequenceNumber);

            DebugLogger.log(
                REPLAY,
                formatters.completeReplayGapfillFormatter,
                action.name(),
                replayedMessages,
                beginGapFillSeqNum,
                newSequenceNumber);

            return action != ABORT;
        }
        else
        {
            // Validate that we've replayed the correct number of messages.
            // If we have missing messages for some reason then just gap fill them.

            // We know precisely what number to gap fill up to.
            final int expectedCount = endSeqNo - beginSeqNo + 1;
            DebugLogger.log(
                REPLAY,
                formatters.completeNotRecentFormatter,
                replayedMessages,
                endSeqNo,
                beginSeqNo,
                expectedCount);

            if (replayedMessages != expectedCount)
            {
                if (replayedMessages == 0)
                {
                    final Action action = sendGapFill(beginSeqNo, endSeqNo + 1);
                    if (action == ABORT)
                    {
                        return false;
                    }
                }

                onIllegalState(
                    "[%s] Error in resend request, count(%d) < expectedCount (%d)",
                    message, replayedMessages, expectedCount);
            }
        }

        return true;
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
            ", sessionId=" + sessionId +
            ", sequenceIndex=" + sequenceIndex +
            '}';
    }
}
