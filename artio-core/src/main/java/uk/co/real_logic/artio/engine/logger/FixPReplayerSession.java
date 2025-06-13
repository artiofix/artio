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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.engine.FixPRetransmitHandler;
import uk.co.real_logic.artio.fixp.AbstractFixPOffsets;
import uk.co.real_logic.artio.fixp.AbstractFixPParser;
import uk.co.real_logic.artio.fixp.AbstractFixPProxy;
import uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader;
import uk.co.real_logic.artio.messages.FixPMessageDecoder;
import uk.co.real_logic.artio.messages.FixPMessageEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;

import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.LogTag.REPLAY_ATTEMPT;
import static uk.co.real_logic.artio.fixp.AbstractFixPParser.FIXP_MESSAGE_HEADER_LENGTH;

/**
 * In ILink cases the UUID is used as a sessionId.
 *
 * Supports CME's ILink3 and B3's Binary Entrypoint
 */
public class FixPReplayerSession extends ReplayerSession
{
    private final IntHashSet gapfillOnRetransmitILinkTemplateIds;
    private final FixPMessageEncoder fixPMessageEncoder;
    private final AbstractFixPParser binaryParser;
    private final AbstractFixPProxy binaryProxy;
    private final AbstractFixPOffsets fixPOffsets;
    private final FixPRetransmitHandler fixPRetransmitHandler;

    private boolean mustSendSequenceMessage = false;

    private enum State
    {
        REPLAYING,
        SEND_COMPLETE_MESSAGE,
        CLOSING
    }

    private State state;

    public FixPReplayerSession(
        final long connectionId,
        final long correlationId,
        final BufferClaim bufferClaim,
        final IdleStrategy idleStrategy,
        final int maxClaimAttempts,
        final ExclusivePublication publication,
        final ReplayQuery replayQuery,
        final int beginSeqNo,
        final int endSeqNo,
        final long sessionId,
        final Replayer replayer,
        final IntHashSet gapfillOnRetransmitILinkTemplateIds,
        final FixPMessageEncoder fixPMessageEncoder,
        final AbstractFixPParser binaryParser,
        final AbstractFixPProxy binaryProxy,
        final AbstractFixPOffsets fixPOffsets,
        final FixPRetransmitHandler fixPRetransmitHandler,
        final AtomicCounter bytesInBuffer,
        final int maxBytesInBuffer)
    {
        super(connectionId, correlationId, bufferClaim, idleStrategy, maxClaimAttempts, publication, replayQuery,
            beginSeqNo, endSeqNo,
            sessionId, 0, replayer, bytesInBuffer, maxBytesInBuffer);

        this.gapfillOnRetransmitILinkTemplateIds = gapfillOnRetransmitILinkTemplateIds;
        this.fixPMessageEncoder = fixPMessageEncoder;
        this.binaryParser = binaryParser;
        this.binaryProxy = binaryProxy;
        this.fixPOffsets = fixPOffsets;
        this.fixPRetransmitHandler = fixPRetransmitHandler;

        state = State.REPLAYING;
    }

    void query()
    {
        replayOperation = replayQuery.query(
            sessionId,
            beginSeqNo,
            sequenceIndex,
            endSeqNo,
            sequenceIndex,
            REPLAY,
            new FixPMessageTracker(this, binaryParser, (endSeqNo - beginSeqNo) + 1));
    }

    public boolean attemptReplay()
    {
        switch (state)
        {
            case SEND_COMPLETE_MESSAGE:
            {
                if (mustSendSequenceMessage)
                {
                    if (sendSequence(endSeqNo + 1))
                    {
                        mustSendSequenceMessage = false;
                    }
                    else
                    {
                        return false;
                    }
                }

                return sendCompleteMessage();
            }

            case REPLAYING:
            {
                if (replayOperation.pollReplay())
                {
                    DebugLogger.log(REPLAY_ATTEMPT, "ReplayerSession: REPLAYING step");
                    state = State.SEND_COMPLETE_MESSAGE;
                }
                return false;
            }

            case CLOSING:
            {
                return replayOperation.pollReplay();
            }

            default:
                return false;
        }
    }

    // Callback for replayed messages
    public Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final int messageLength = length - (MessageHeaderEncoder.ENCODED_LENGTH + FixPMessageDecoder.BLOCK_LENGTH);
        if (isBackpressured(messageLength))
        {
            return Action.ABORT;
        }

        final int encoderOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        final int headerOffset = encoderOffset + SimpleOpenFramingHeader.SOFH_LENGTH +
            FixPMessageDecoder.BLOCK_LENGTH;
        final int templateId = binaryParser.templateId(buffer, headerOffset);
        final int blockLength = binaryParser.blockLength(buffer, headerOffset);
        final int version = binaryParser.version(buffer, headerOffset);
        final int messageOffset = headerOffset + FIXP_MESSAGE_HEADER_LENGTH;

        fixPRetransmitHandler.onReplayedBusinessMessage(
            templateId,
            buffer,
            messageOffset,
            blockLength,
            version);

        if (gapfillOnRetransmitILinkTemplateIds.contains(templateId))
        {
            mustSendSequenceMessage = true;
            return Action.CONTINUE;
        }
        else
        {
            if (mustSendSequenceMessage)
            {
                final int seqNum = fixPOffsets.seqNum(templateId, buffer, messageOffset);
                if (seqNum != AbstractFixPOffsets.MISSING_OFFSET)
                {
                    if (sendSequence(seqNum))
                    {
                        mustSendSequenceMessage = false;
                    }
                    else
                    {
                        return Action.ABORT;
                    }
                }
            }

            // Update connection id in case we're replaying from a previous connection.
            fixPMessageEncoder
                .wrap((MutableDirectBuffer)buffer, encoderOffset)
                .connection(connectionId)
                /*.enqueueTime(epochNanoClock.nanoTime())*/;

            return Pressure.apply(publication.offer(buffer, offset, length));
        }
    }

    private boolean sendSequence(final int nextSentSequenceNumber)
    {
        binaryProxy.ids(connectionId, sessionId);
        return !Pressure.isBackPressured(binaryProxy.sendSequence(sessionId, nextSentSequenceNumber));
    }

    void startClose()
    {
        state = State.CLOSING;
        super.startClose();
    }
}
