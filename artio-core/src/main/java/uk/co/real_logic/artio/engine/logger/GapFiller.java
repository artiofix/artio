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

import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.decoder.AbstractResendRequestDecoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.engine.ReplayerCommandQueue;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;
import uk.co.real_logic.artio.engine.SenderSequenceNumbers;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.messages.ValidResendRequestDecoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.util.ArrayDeque;

import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.messages.MessageHeaderDecoder.ENCODED_LENGTH;

public class GapFiller extends AbstractReplayer
{
    private final Long2ObjectHashMap<ArrayDeque<GapFillerSession>> gapFillerSessions = new Long2ObjectHashMap<>();
    private final ReplayerCommandQueue replayerCommandQueue;
    private final GatewayPublication publication;
    private final String agentNamePrefix;
    private final ReplayTimestamper timestamper;

    public GapFiller(
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
        this.publication = publication;
        this.agentNamePrefix = agentNamePrefix;
        this.replayerCommandQueue = replayerCommandQueue;

        timestamper = new ReplayTimestamper(publication.dataPublication(), clock);
    }

    public void onFragment(final DirectBuffer buffer, final int start, final int length, final Header header)
    {
        // Avoid fragmented messages
        if ((header.flags() & BEGIN_FLAG) != BEGIN_FLAG)
        {
            return;
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

            onResendRequest(
                sessionId, connectionId, beginSeqNo, endSeqNo, sequenceIndex, correlationId);
        }
        else
        {
            fixSessionCodecsFactory.onFragment(buffer, start, length, header);
        }
    }

    private void onResendRequest(
        final long sessionId,
        final long connectionId,
        final int beginSeqNo,
        final int endSeqNo,
        final int sequenceIndex,
        final long correlationId)
    {
        final GapFillerSession gapFillerSession = new GapFillerSession(
            sessionId, connectionId, beginSeqNo, endSeqNo, sequenceIndex, correlationId);
        gapFillerSessions.computeIfAbsent(connectionId, k -> new ArrayDeque<>()).addLast(gapFillerSession);
    }

    public int doWork()
    {
        int workCount = 0;
        final long timeInNs = clock.nanoTime();

        trackDutyCycleTime(timeInNs);
        timestamper.sendTimestampMessage(timeInNs);

        workCount += replayerCommandQueue.poll();
        workCount += sendGapfills();
        return workCount;
    }

    private int sendGapfills()
    {
        int workCount = 0;

        final Long2ObjectHashMap<ArrayDeque<GapFillerSession>>.EntryIterator gapFillerSessionsIterator =
            gapFillerSessions.entrySet().iterator();

        while (gapFillerSessionsIterator.hasNext())
        {
            gapFillerSessionsIterator.next();

            final long connectionId = gapFillerSessionsIterator.getLongKey();
            final ArrayDeque<GapFillerSession> gapFillerSessionDeque = gapFillerSessionsIterator.getValue();

            if (checkDisconnected(connectionId))
            {
                gapFillerSessionsIterator.remove();
            }
            else
            {
                final GapFillerSession gapFillerSession = gapFillerSessionDeque.peekFirst();
                workCount += gapFillerSession.doWork();

                if (gapFillerSession.isDone())
                {
                    gapFillerSessionDeque.pollFirst();

                    if (gapFillerSessionDeque.isEmpty())
                    {
                        gapFillerSessionsIterator.remove();
                    }
                }
            }
        }

        return workCount;
    }

    public String roleName()
    {
        return agentNamePrefix + "GapFiller";
    }

    class GapFillerSession
    {
        private enum State
        {
            // NB: don't reorder this enum without reviewing onResendRequest
            INIT,
            ON_START_REPLAY,
            ON_GAP_FILL,
            ON_SEND_COMPLETE,
            DONE
        }

        final long sessionId;
        final long connectionId;
        final int beginSeqNo;
        final int endSeqNo;
        final int sequenceIndex;
        final long correlationId;
        private State state;
        private FixReplayerCodecs fixReplayerCodecs;

        GapFillerSession(
            final long sessionId,
            final long connectionId,
            final int beginSeqNo,
            final int endSeqNo,
            final int sequenceIndex,
            final long correlationId)
        {
            this.sessionId = sessionId;
            this.connectionId = connectionId;
            this.beginSeqNo = beginSeqNo;
            this.endSeqNo = endSeqNo;
            this.sequenceIndex = sequenceIndex;
            this.correlationId = correlationId;
            this.state = State.INIT;
        }

        boolean isDone()
        {
            return State.DONE == state;
        }

        int doWork()
        {
            switch (state)
            {
                case INIT:
                {
                    final SenderSequenceNumber senderSequenceNumber =
                        senderSequenceNumbers.senderSequenceNumber(connectionId);
                    if (null == senderSequenceNumber)
                    {
                        return 0;
                    }

                    if (senderSequenceNumber.fixP())
                    {
                        // It's a FIXP request, we don't support that configuration with no logging enabled yet.
                        state = State.DONE;
                        return 0;
                    }

                    fixReplayerCodecs = fixSessionCodecsFactory.get(sessionId);
                    if (null != fixReplayerCodecs)
                    {
                        state = State.ON_START_REPLAY;
                        return 1;
                    }

                    return 0;
                }

                case ON_START_REPLAY:
                {
                    if (trySendStartReplay(sessionId, connectionId, correlationId))
                    {
                        state = State.ON_GAP_FILL;
                        return 1;
                    }

                    return 0;
                }

                case ON_GAP_FILL:
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

                    if (0 < sentPosition)
                    {
                        state = State.ON_SEND_COMPLETE;
                        return 1;
                    }

                    return 0;
                }

                case ON_SEND_COMPLETE:
                {
                    if (sendCompleteMessage(connectionId, correlationId))
                    {
                        state = State.DONE;
                        return 1;
                    }

                    return 0;
                }
            }

            return 0;
        }
    }
}
