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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Subscription;
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.decoder.AbstractResendRequestDecoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.engine.ReplayerCommandQueue;
import uk.co.real_logic.artio.engine.SenderSequenceNumbers;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.messages.ValidResendRequestDecoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.messages.MessageHeaderDecoder.ENCODED_LENGTH;

public class GapFiller extends AbstractReplayer
{
    private final ReplayerCommandQueue replayerCommandQueue;

    private final Subscription inboundSubscription;
    private final GatewayPublication publication;
    private final String agentNamePrefix;
    private final ReplayTimestamper timestamper;

    private enum AbortState
    {
        // NB: don't reorder this enum without reviewing onResendRequest
        ON_START_REPLAY,
        ON_GAP_FILL,
        ON_SEND_COMPLETE,
    }

    private AbortState abortState;

    public GapFiller(
        final Subscription inboundSubscription,
        final GatewayPublication publication,
        final String agentNamePrefix,
        final SenderSequenceNumbers senderSequenceNumbers,
        final ReplayerCommandQueue replayerCommandQueue,
        final FixSessionCodecsFactory fixSessionCodecsFactory,
        final EpochNanoClock clock,
        final DutyCycleTracker dutyCycleTracker)
    {
        super(publication.dataPublication(), fixSessionCodecsFactory, new BufferClaim(), senderSequenceNumbers,
            clock, dutyCycleTracker);
        this.inboundSubscription = inboundSubscription;
        this.publication = publication;
        this.agentNamePrefix = agentNamePrefix;
        this.replayerCommandQueue = replayerCommandQueue;

        timestamper = new ReplayTimestamper(publication.dataPublication(), clock);
    }

    public int doWork()
    {
        final long timeInNs = clock.nanoTime();

        trackDutyCycleTime(timeInNs);
        timestamper.sendTimestampMessage(timeInNs);

        return replayerCommandQueue.poll() + inboundSubscription.controlledPoll(this, POLL_LIMIT);
    }

    public Action onFragment(final DirectBuffer buffer, final int start, final int length, final Header header)
    {
        // Avoid fragmented messages
        if ((header.flags() & BEGIN_FLAG) != BEGIN_FLAG)
        {
            return CONTINUE;
        }

        messageHeader.wrap(buffer, start);
        final int templateId = messageHeader.templateId();
        final int offset = start + ENCODED_LENGTH;
        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();

        if (templateId == ValidResendRequestDecoder.TEMPLATE_ID)
        {
            validResendRequest.wrap(
                buffer,
                offset,
                blockLength,
                version);

            final long sessionId = validResendRequest.session();
            final long connectionId = validResendRequest.connection();
            final int beginSeqNo = (int)validResendRequest.beginSequenceNumber();
            final int endSeqNo = (int)validResendRequest.endSequenceNumber();
            final int sequenceIndex = validResendRequest.sequenceIndex();
            final long correlationId = validResendRequest.correlationId();
            validResendRequest.wrapBody(asciiBuffer);

            return onResendRequest(
                sessionId, connectionId, beginSeqNo, endSeqNo, sequenceIndex, correlationId);
        }
        else
        {
            return fixSessionCodecsFactory.onFragment(buffer, start, length, header);
        }
    }

    // TODO: backpressure handling
    private Action onResendRequest(
        final long sessionId,
        final long connectionId,
        final int beginSeqNo,
        final int endSeqNo,
        final int sequenceIndex,
        final long correlationId)
    {
        if (checkDisconnected(connectionId))
        {
            abortState = null;
            return CONTINUE;
        }

        final FixReplayerCodecs fixReplayerCodecs = fixSessionCodecsFactory.get(sessionId);
        if (fixReplayerCodecs == null)
        {
            // It's a FIXP request, we don't support that configuration with no logging enabled yet.
            abortState = null;
            return CONTINUE;
        }

        if (checkAbortState(AbortState.ON_START_REPLAY))
        {
            if (trySendStartReplay(sessionId, connectionId, correlationId))
            {
                abortState = AbortState.ON_START_REPLAY;
                return ABORT;
            }
        }

        if (checkAbortState(AbortState.ON_GAP_FILL))
        {
            final AbstractResendRequestDecoder resendRequest = fixReplayerCodecs.resendRequest();
            final GapFillEncoder encoder = fixReplayerCodecs.gapFillEncoder();

            resendRequest.decode(asciiBuffer, 0, asciiBuffer.capacity());

            final SessionHeaderDecoder reqHeader = resendRequest.header();

            // If the request was for an infinite replay then reply with the next expected sequence number
            final int gapFillMsgSeqNum = beginSeqNo;
            encoder.setupMessage(reqHeader);
            final long result = encoder.encode(gapFillMsgSeqNum, endSeqNo + 1);
            final int encodedLength = Encoder.length(result);
            final int encodedOffset = Encoder.offset(result);
            final long sentPosition = publication.saveMessage(
                encoder.buffer(), encodedOffset, encodedLength,
                ENGINE_LIBRARY_ID, SEQUENCE_RESET_MESSAGE_TYPE, sessionId, sequenceIndex, connectionId,
                MessageStatus.OK, gapFillMsgSeqNum);

            if (Pressure.isBackPressured(sentPosition))
            {
                abortState = AbortState.ON_GAP_FILL;
                return ABORT;
            }
        }

        if (sendCompleteMessage(connectionId, correlationId))
        {
            abortState = null;
            return CONTINUE;
        }
        else
        {
            abortState = AbortState.ON_SEND_COMPLETE;
            return ABORT;
        }
    }

    private boolean checkAbortState(final AbortState requiredState)
    {
        final AbortState abortState = this.abortState;
        // either the previous attempt wasn't abort or it got aborted at or before this point
        return abortState == null || abortState.compareTo(requiredState) <= 0;
    }

    public String roleName()
    {
        return agentNamePrefix + "GapFiller";
    }
}
