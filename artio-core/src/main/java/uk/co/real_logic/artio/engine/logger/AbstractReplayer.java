/*
 * Copyright 2022 Adaptive Financial Consulting Ltd.
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
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.engine.SenderSequenceNumbers;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.function.Consumer;

import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;

abstract class AbstractReplayer implements Agent, ControlledFragmentHandler
{
    static final int POLL_LIMIT = 20;

    private static final int REPLAY_COMPLETE_LEN =
        MessageHeaderEncoder.ENCODED_LENGTH + ReplayCompleteEncoder.BLOCK_LENGTH;
    static final int START_REPLAY_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + StartReplayEncoder.BLOCK_LENGTH;

    final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    final ValidResendRequestDecoder validResendRequest = new ValidResendRequestDecoder();
    final StartReplayEncoder startReplayEncoder = new StartReplayEncoder();

    final Consumer<StringBuilder> validResendRequestAppendTo = validResendRequest::appendTo;

    // Safe to share between multiple ReplayerSession instances due to single threaded nature of the Replayer
    final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    final ReplayCompleteEncoder replayCompleteEncoder = new ReplayCompleteEncoder();
    final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();

    private final CharFormatter alreadyDisconnectedFormatter = new CharFormatter(
        "Not processing Resend Request for connId=%s because it has already disconnected");

    final CharFormatter completeReplayGapfillFormatter = new CharFormatter(
        "ReplayerSession: completeReplay-sendGapFill action=%s, replayedMessages=%s, " +
        "beginGapFillSeqNum=%s, newSequenceNumber=%s connId=%s");

    final ExclusivePublication publication;
    final FixSessionCodecsFactory fixSessionCodecsFactory;
    final BufferClaim bufferClaim;
    final SenderSequenceNumbers senderSequenceNumbers;

    protected final EpochNanoClock clock;
    private final DutyCycleTracker dutyCycleTracker;

    AbstractReplayer(
        final ExclusivePublication publication,
        final FixSessionCodecsFactory fixSessionCodecsFactory,
        final BufferClaim bufferClaim,
        final SenderSequenceNumbers senderSequenceNumbers,
        final EpochNanoClock clock,
        final DutyCycleTracker dutyCycleTracker)
    {
        this.publication = publication;
        this.fixSessionCodecsFactory = fixSessionCodecsFactory;
        this.bufferClaim = bufferClaim;
        this.senderSequenceNumbers = senderSequenceNumbers;
        this.clock = clock;
        this.dutyCycleTracker = dutyCycleTracker;
    }

    boolean trySendStartReplay(final long sessionId, final long connectionId, final long correlationId)
    {
        final long position = publication.tryClaim(START_REPLAY_LENGTH, bufferClaim);
        if (Pressure.isBackPressured(position))
        {
            return true;
        }

        final MutableDirectBuffer buffer = bufferClaim.buffer();
        final int offset = bufferClaim.offset();

        startReplayEncoder
            .wrapAndApplyHeader(buffer, offset, messageHeaderEncoder)
            .session(sessionId)
            .connection(connectionId)
            .correlationId(correlationId);

        DebugLogger.logSbeMessage(REPLAY, startReplayEncoder);

        bufferClaim.commit();

        return false;
    }

    public void onStart()
    {
        dutyCycleTracker.update(clock.nanoTime());
    }

    protected void trackDutyCycleTime(final long timeInNs)
    {
        dutyCycleTracker.measureAndUpdate(timeInNs);
    }

    public void onClose()
    {
        publication.close();
    }

    boolean checkDisconnected(final long connectionId)
    {
        if (senderSequenceNumbers.hasDisconnected(connectionId))
        {
            DebugLogger.log(REPLAY,
                alreadyDisconnectedFormatter,
                connectionId);

            return true;
        }

        return false;
    }

    boolean sendCompleteMessage(final long connectionId, final long correlationId)
    {
        if (publication.tryClaim(REPLAY_COMPLETE_LEN, bufferClaim) > 0)
        {
            final ReplayCompleteEncoder replayComplete = replayCompleteEncoder;
            replayComplete.wrapAndApplyHeader(
                    bufferClaim.buffer(),
                    bufferClaim.offset(),
                    messageHeaderEncoder)
                .libraryId(ENGINE_LIBRARY_ID)
                .connection(connectionId)
                .correlationId(correlationId);

            DebugLogger.logSbeMessage(LogTag.REPLAY, replayComplete);

            bufferClaim.commit();

            return true;
        }
        else
        {
            return false;
        }
    }

}
