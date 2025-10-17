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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.MessageTimingHandler;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.util.CharFormatter;

import java.io.IOException;
import java.nio.ByteBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE_TCP;
import static uk.co.real_logic.artio.dictionary.SessionConstants.LOGON_MESSAGE_TYPE;
import static uk.co.real_logic.artio.messages.DisconnectReason.EXCEPTION;
import static uk.co.real_logic.artio.messages.DisconnectReason.SLOW_CONSUMER;

class FixSenderEndPoint extends SenderEndPoint
{
    private static final boolean IS_SLOW_CONSUMER_LOG_TAG_ENABLED = DebugLogger.isEnabled(LogTag.SLOW_CONSUMER);

    private static final int ENQ_MSG = 1;
    private static final int ENQ_REPLAY_COMPLETE = 2;
    private static final int ENQ_START_REPLAY = 3;

    static final int ENQ_REPLAY_COMPLETE_LEN = SIZE_OF_INT + SIZE_OF_LONG;
    static final int ENQ_START_REPLAY_LEN = ENQ_REPLAY_COMPLETE_LEN;
    static final int ENQ_MESSAGE_BLOCK_LEN = SIZE_OF_INT + SIZE_OF_INT + SIZE_OF_INT + SIZE_OF_INT;

    protected static final int NO_REATTEMPT = 0;

    static class Formatters
    {
        final CharFormatter replayComplete = new CharFormatter(
            "SEP.replayComplete, connId=%s, corrId=%s");
        final CharFormatter validResendRequest = new CharFormatter(
            "SEP.validResendRequest, connId=%s, corrId=%s");
        final CharFormatter checkStartReplay = new CharFormatter(
            "SEP.onStartReplay, connId=%s, corrId=%s");
        final CharFormatter replaying = new CharFormatter(
            "SEP.replaying, connId=%s, replay=%s");
        final CharFormatter requiresRetry = new CharFormatter(
            "SEP.requiresRetry, connId=%s, retry=%s");
        final CharFormatter becomesSlow = new CharFormatter(
            "SEP.becomesSlow, connId=%s, becomeSlow=%s");
        final CharFormatter bufferSlowDisconnect = new CharFormatter(
            "SEP.bufferSlowDisconnect, conn=%s,sess=%s,bufferUsage=%s,maxBytesInBuffer=%s,replay=%s");
        final CharFormatter timeoutSlowDisconnect = new CharFormatter(
            "SEP.timeoutSlowDisconnect, conn=%s,sess=%s,time=%s,maxBytesInBuffer=%s,noWriteSince=%s");
    }

    public static final int THROTTLE_BUSINESS_REJECT_REASON = 99;

    private final long connectionId;
    private final AtomicCounter invalidLibraryAttempts;
    private final long slowConsumerTimeoutInMs;
    private final MessageTimingHandler messageTimingHandler;
    private final FixReceiverEndPoint receiverEndPoint;
    private final Formatters formatters;

    private long sessionId;
    private long sendingTimeoutTimeInMs;

    private FixThrottleRejectBuilder throttleRejectBuilder;
    private FixDictionary fixDictionary;
    private CompositeKey sessionKey;
    private EngineConfiguration configuration;

    private final ReattemptState normalBuffer = new ReattemptState();
    private final ReattemptState replayBuffer = new ReattemptState();

    private boolean replaying;
    private long replayCorrelationId;
    private boolean requiresRetry;
    private int reattemptBytesWritten = NO_REATTEMPT;

    private boolean disconnected;

    FixSenderEndPoint(
        final long connectionId,
        final int libraryId,
        final ExclusivePublication inboundPublication,
        final ReproductionLogWriter reproductionPublication,
        final TcpChannel channel,
        final AtomicCounter bytesInBuffer,
        final AtomicCounter invalidLibraryAttempts,
        final ErrorHandler errorHandler,
        final Framer framer,
        final int maxBytesInBuffer,
        final long slowConsumerTimeoutInMs,
        final long timeInMs,
        final SenderSequenceNumber senderSequenceNumber,
        final MessageTimingHandler messageTimingHandler,
        final FixReceiverEndPoint receiverEndPoint,
        final Formatters formatters)
    {
        super(connectionId, inboundPublication, reproductionPublication, libraryId, channel, bytesInBuffer,
            maxBytesInBuffer, errorHandler,
            framer, senderSequenceNumber);
        this.connectionId = connectionId;
        this.invalidLibraryAttempts = invalidLibraryAttempts;

        this.slowConsumerTimeoutInMs = slowConsumerTimeoutInMs;

        this.messageTimingHandler = messageTimingHandler;
        this.receiverEndPoint = receiverEndPoint;
        this.formatters = formatters;
        sendingTimeoutTimeInMs = timeInMs + slowConsumerTimeoutInMs;
    }

    void onOutboundMessage(
        final int libraryId,
        final DirectBuffer directBuffer,
        final int offset,
        final int bodyLength,
        final int sequenceNumber,
        final int sequenceIndex,
        final long messageType,
        final long timeInMs,
        final int metaDataLength)
    {
        if (isWrongLibraryId(libraryId))
        {
            invalidLibraryAttempts.increment();
            return;
        }

        onMessage(directBuffer, offset, bodyLength, metaDataLength, sequenceNumber, timeInMs, false);

        if (messageType == LOGON_MESSAGE_TYPE)
        {
            receiverEndPoint.onLogonSent(sequenceIndex);
        }
    }

    public void onThrottleReject(
        final int libraryId,
        final long refMsgType,
        final int refSeqNum,
        final int sequenceNumber,
        final int sequenceIndex,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength,
        final long timeInMs)
    {
        if (isWrongLibraryId(libraryId))
        {
            invalidLibraryAttempts.increment();
            return;
        }

        final FixThrottleRejectBuilder throttleRejectBuilder = throttleRejectBuilder();
        if (!throttleRejectBuilder.build(
            refMsgType,
            refSeqNum,
            sequenceNumber,
            businessRejectRefIDBuffer,
            businessRejectRefIDOffset,
            businessRejectRefIDLength,
            false))
        {
            // failed to build reject due to configuration error
            return;
        }

        onOutboundMessage(
            libraryId,
            throttleRejectBuilder.buffer(),
            throttleRejectBuilder.offset(),
            throttleRejectBuilder.length(),
            sequenceNumber,
            sequenceIndex,
            throttleRejectBuilder.messageType(),
            timeInMs,
            0);
    }

    private FixThrottleRejectBuilder throttleRejectBuilder()
    {
        if (throttleRejectBuilder == null)
        {
            throttleRejectBuilder = new FixThrottleRejectBuilder(
                fixDictionary,
                errorHandler,
                sessionId,
                connectionId,
                new UtcTimestampEncoder(configuration.sessionEpochFractionFormat()),
                configuration.epochNanoClock(),
                configuration.throttleWindowInMs(), configuration.throttleLimitOfMessages()
            );
            configuration.sessionIdStrategy().setupSession(sessionKey, throttleRejectBuilder.header());
        }

        return throttleRejectBuilder;
    }

    boolean configureThrottle(final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        return throttleRejectBuilder().configureThrottle(throttleWindowInMs, throttleLimitOfMessages);
    }

    public void onMessage(
        final DirectBuffer directBuffer, final int offset, final int bodyLength, final int metaDataLength,
        final int seqNum, final long timeInMs, final boolean replay)
    {
        try
        {
            final int metaDataOffset = offset - FixMessageDecoder.bodyHeaderLength() - metaDataLength;

            if ((replaying && !replay) || (!replaying && replay) || requiresRetry)
            {
                enqueueMessage(directBuffer, offset, bodyLength, metaDataOffset, metaDataLength, seqNum, replay);

                if (requiresRetry && !disconnected)
                {
                    reattempt();
                }
                return;
            }

            if (checkLastReplayedMessage(seqNum, replay))
            {
                // back-pressure and retry the message sending.
                enqueueMessage(directBuffer, offset, bodyLength, metaDataOffset, metaDataLength, seqNum, replay);
                return;
            }

            final int written = writeBuffer(directBuffer, offset, bodyLength, seqNum, replay);
            final int reattemptBytesWritten = this.reattemptBytesWritten;
            final int totalWritten = reattemptBytesWritten + written;

            if (totalWritten < bodyLength)
            {
                this.reattemptBytesWritten = totalWritten;
                // set seqNum to 0 in order to avoid duplicate replayComplete sends
                final int enqSeqNum = replay ? NOT_LAST_REPLAY_MSG : seqNum;
                enqueueMessage(directBuffer, offset, bodyLength, metaDataOffset, metaDataLength, enqSeqNum, replay);

                tryLogBackPressure(seqNum, replay, written);
            }
            else
            {
                this.reattemptBytesWritten = NO_REATTEMPT;

                final MessageTimingHandler messageTimingHandler = this.messageTimingHandler;
                if (messageTimingHandler != null && !replay)
                {
                    messageTimingHandler.onMessage(
                        seqNum, connectionId, directBuffer, metaDataOffset, metaDataLength);
                }

                if (reattemptBytesWritten != NO_REATTEMPT)
                {
                    // Completed write of a back-pressured message
                    tryLogBackPressure(seqNum, replay, written);
                }
            }

            updateSendingTimeoutTimeInMs(timeInMs, written);
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
        }
    }

    private void tryLogBackPressure(final int seqNum, final boolean replay, final int written)
    {
        final ReproductionLogWriter reproductionLogWriter = this.reproductionLogWriter;
        if (reproductionLogWriter != null)
        {
            reproductionLogWriter.logBackPressure(connectionId, seqNum, replay, written);
        }
    }

    // return true iff back-pressured and needs retrying
    private boolean checkLastReplayedMessage(final int seqNum, final boolean replay)
    {
        if (replay && seqNum != NOT_LAST_REPLAY_MSG)
        {
            return super.onReplayComplete(replayCorrelationId) == ABORT;
        }

        return false;
    }

    private int writeBuffer(
        final DirectBuffer directBuffer, final int offset, final int messageSize, final int seqNum,
        final boolean replay) throws IOException
    {
        final ByteBuffer buffer = directBuffer.byteBuffer();
        final int bufferOffset = directBuffer.wrapAdjustment() + offset;
        final int startLimit = buffer.limit();
        final int startPosition = buffer.position();

        ByteBufferUtil.limit(buffer, bufferOffset + messageSize);
        final int writePosition = reattemptBytesWritten + bufferOffset;
        ByteBufferUtil.position(buffer, writePosition);

        final int written = channel.write(buffer, seqNum, replay);
        DebugLogger.log(FIX_MESSAGE_TCP, "Written  ", directBuffer, offset + reattemptBytesWritten, written);

        buffer.limit(startLimit).position(startPosition);

        return written;
    }

    private void enqueueMessage(
        final DirectBuffer srcBuffer, final int srcOffset, final int bodyLength,
        final int metaDataOffset, final int metaDataLength, final int sequenceNumber, final boolean replay)
    {
        final int totalLength = ENQ_MESSAGE_BLOCK_LEN + bodyLength + metaDataLength;
        final ReattemptState reattemptState = enqueue(totalLength, replay);

        int reattemptOffset = reattemptState.usage - totalLength;
        final ExpandableDirectByteBuffer buffer = reattemptState.buffer();

        buffer.putInt(reattemptOffset, ENQ_MSG);
        reattemptOffset += SIZE_OF_INT;

        buffer.putInt(reattemptOffset, sequenceNumber);
        reattemptOffset += SIZE_OF_INT;

        buffer.putInt(reattemptOffset, bodyLength);
        reattemptOffset += SIZE_OF_INT;

        buffer.putBytes(reattemptOffset, srcBuffer, srcOffset, bodyLength);
        reattemptOffset += bodyLength;

        buffer.putInt(reattemptOffset, metaDataLength);
        reattemptOffset += SIZE_OF_INT;

        buffer.putBytes(reattemptOffset, srcBuffer, metaDataOffset, metaDataLength);
    }

    private void enqueueReplayComplete(final long correlationId)
    {
        enqueueCorrelation(correlationId, ENQ_REPLAY_COMPLETE);
    }

    private void enqueueStartReplay(final long correlationId)
    {
        enqueueCorrelation(correlationId, ENQ_START_REPLAY);
    }

    private void enqueueCorrelation(final long correlationId, final int messageType)
    {
        final ReattemptState reattemptState = enqueue(ENQ_REPLAY_COMPLETE_LEN, true);

        int reattemptOffset = reattemptState.usage - ENQ_REPLAY_COMPLETE_LEN;
        final ExpandableDirectByteBuffer buffer = reattemptState.buffer();

        buffer.putInt(reattemptOffset, messageType);
        reattemptOffset += SIZE_OF_INT;

        buffer.putLong(reattemptOffset, correlationId);
    }

    private ReattemptState enqueue(final int length, final boolean replay)
    {
        // we only need re-attempting when we've got messages buffered for the current state
        final boolean currentStream = replay == replaying;
        if (!requiresRetry && currentStream)
        {
            requiresRetry(true);
            sendSlowStatus(true);
        }

        final ReattemptState reattemptState = reattemptState(replay);

        final int bufferUsage = reattemptState.usage + length;
        reattemptState.usage = bufferUsage;
        if (currentStream)
        {
            if (bufferUsage > maxBytesInBuffer)
            {
                if (IS_SLOW_CONSUMER_LOG_TAG_ENABLED)
                {
                    DebugLogger.log(LogTag.SLOW_CONSUMER, formatters.bufferSlowDisconnect.clear()
                        .with(connectionId)
                        .with(sessionId)
                        .with(bufferUsage)
                        .with(maxBytesInBuffer)
                        .with(replay));
                }
                disconnectEndpoint(SLOW_CONSUMER);
            }

            bytesInBuffer.setOrdered(bufferUsage);
        }
        return reattemptState;
    }

    private ReattemptState reattemptState(final boolean replay)
    {
        return replay ? replayBuffer : normalBuffer;
    }

    private boolean processReattemptBuffer(final boolean replay)
    {
        final ReattemptState reattemptState = reattemptState(replay);
        final ExpandableDirectByteBuffer buffer = reattemptState.buffer;
        final int reattemptBufferUsage = reattemptState.usage;
        if (reattemptBufferUsage == 0)
        {
            return true;
        }

        int offset = 0;
        while (offset < reattemptBufferUsage)
        {
            try
            {
                final int enqueueType = buffer.getInt(offset);
                if (enqueueType == ENQ_MSG)
                {
                    final int sequenceNumberOffset = offset + SIZE_OF_INT;
                    final int sequenceNumber = buffer.getInt(sequenceNumberOffset);

                    if (checkLastReplayedMessage(sequenceNumber, replay))
                    {
                        // back-pressure and retry
                        this.reattemptBytesWritten = 0;
                        break;
                    }
                    else if (replay)
                    {
                        // if we re-try sending the message then we don't want to retry this
                        buffer.putInt(sequenceNumberOffset, NOT_LAST_REPLAY_MSG);
                    }

                    final int bodyLengthOffset = sequenceNumberOffset + SIZE_OF_INT;
                    final int bodyLength = buffer.getInt(bodyLengthOffset);

                    final int bodyOffset = bodyLengthOffset + SIZE_OF_INT;
                    final int written = writeBuffer(buffer, bodyOffset, bodyLength, sequenceNumber, replay);
                    final int totalWritten = written + reattemptBytesWritten;
                    tryLogBackPressure(sequenceNumber, replay, written);
                    if (totalWritten < bodyLength)
                    {
                        this.reattemptBytesWritten = totalWritten;

                        break;
                    }
                    else
                    {
                        offset = onProcessMsgComplete(
                            replay, buffer, offset, sequenceNumber, bodyLength, bodyOffset, totalWritten);
                    }
                }
                else if (enqueueType == ENQ_REPLAY_COMPLETE)
                {
                    final int idOffset = offset + SIZE_OF_INT;
                    final long correlationId = buffer.getLong(idOffset);
                    this.reattemptBytesWritten = NO_REATTEMPT;

                    // Complete
                    final int endOfReplayEntry = idOffset + SIZE_OF_LONG;

                    // peek the next message to see if we need to continue replaying
                    // If not then we end the replay, otherwise we keep replaying
                    if (buffer.getInt(endOfReplayEntry) != ENQ_START_REPLAY)
                    {
                        replaying(false, correlationId);
                        reattemptState.shuffleWritten(endOfReplayEntry);
                        bytesInBuffer.setOrdered(normalBuffer.usage);
                        return true;
                    }
                }
                else if (enqueueType == ENQ_START_REPLAY)
                {
                    // We just ensure that we're still replaying and skip these messages
                    offset += ENQ_START_REPLAY_LEN;
                }
                else
                {
                    throw new IllegalStateException(
                        "enqueueType = " + enqueueType + ", usage = " + reattemptState.usage + ", offset = " + offset +
                        ", replay = " + replay);
                }
            }
            catch (final Throwable e)
            {
                onError(e);
                return true;
            }
        }

        final int usage = reattemptState.shuffleWritten(offset);
        bytesInBuffer.setOrdered(usage);
        return usage == 0;
    }

    private int onProcessMsgComplete(
        final boolean replay,
        final ExpandableDirectByteBuffer buffer,
        final int offset,
        final int sequenceNumber,
        final int bodyLength,
        final int bodyOffset,
        final int totalWritten)
    {
        final int metaDataLengthOffset = bodyOffset + bodyLength;
        final int metaDataLength = buffer.getInt(metaDataLengthOffset);

        final int metaDataOffset = metaDataLengthOffset + SIZE_OF_INT;

        final MessageTimingHandler messageTimingHandler = this.messageTimingHandler;
        if (messageTimingHandler != null && !replay)
        {
            messageTimingHandler.onMessage(
                sequenceNumber, connectionId, buffer, metaDataOffset, metaDataLength);
        }

        this.reattemptBytesWritten = NO_REATTEMPT;

        return offset + ENQ_MESSAGE_BLOCK_LEN + totalWritten + metaDataLength;
    }

    public boolean reattempt()
    {
        return reattempt(replaying);
    }

    private boolean reattempt(final boolean replaying)
    {
        final boolean caughtUp = processReattemptBuffer(replaying);
        if (caughtUp)
        {
            if (requiresRetry)
            {
                // Do we need to try the other queue?
                final boolean other = !replaying;
                final ReattemptState reattemptState = reattemptState(other);
                final int usage = reattemptState.usage;
                if (usage == 0)
                {
                    requiresRetry(false);
                    sendSlowStatus(false);
                }
                else
                {
                    this.replaying(!replaying, replayCorrelationId);
                    bytesInBuffer.setOrdered(usage);
                }
            }
        }
        return caughtUp;
    }

    Action onReplayMessage(
        final DirectBuffer directBuffer,
        final int offset,
        final int bodyLength,
        final long timeInMs,
        final int sequenceNumber)
    {
        onMessage(directBuffer, offset, bodyLength, 0, sequenceNumber, timeInMs, true);

        return CONTINUE;
    }

    private void updateSendingTimeoutTimeInMs(final long timeInMs, final int written)
    {
        if (written > 0)
        {
            sendingTimeoutTimeInMs = timeInMs + slowConsumerTimeoutInMs;
        }
    }

    private void onError(final Throwable ex)
    {
        errorHandler.onError(new Exception(String.format(
            "Exception reported for sessionId=%d,connectionId=%d", sessionId, connectionId), ex));
        disconnectEndpoint(EXCEPTION);
    }

    public void close()
    {
        invalidLibraryAttempts.close();
        super.close();
    }

    private boolean isWrongLibraryId(final int libraryId)
    {
        return libraryId != this.libraryId;
    }

    // Only access on Framer thread
    boolean isSlowConsumer()
    {
        return bytesInBufferWeak() > 0;
    }

    long bytesInBuffer()
    {
        return bytesInBuffer.get();
    }

    private long bytesInBufferWeak()
    {
        return bytesInBuffer.getWeak();
    }

    void sessionId(final long sessionId)
    {
        this.sessionId = sessionId;
    }

    long sessionId()
    {
        return sessionId;
    }

    boolean poll(final long timeInMs)
    {
        reattempt();

        if (isSlowConsumer() && timeInMs > sendingTimeoutTimeInMs)
        {
            if (IS_SLOW_CONSUMER_LOG_TAG_ENABLED)
            {
                DebugLogger.log(LogTag.SLOW_CONSUMER, formatters.timeoutSlowDisconnect.clear()
                    .with(connectionId)
                    .with(sessionId)
                    .with(timeInMs)
                    .with(maxBytesInBuffer)
                    .with(sendingTimeoutTimeInMs - slowConsumerTimeoutInMs));
            }
            disconnectEndpoint(SLOW_CONSUMER);

            return true;
        }

        return false;
    }

    private void disconnectEndpoint(final DisconnectReason reason)
    {
        receiverEndPoint.completeDisconnect(reason);
        disconnected = true;
    }

    public Action onReplayComplete(final long correlationId)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.REPLAY,
                formatters.replayComplete.clear().with(connectionId).with(correlationId));
        }

        // can receive this when we're not replaying, but if we've already detected the end
        // of the current replay then replayCorrelationId = correlationId
        if ((!replaying && replayCorrelationId != correlationId) || !reattempt(true))
        {
            enqueueReplayComplete(correlationId);
        }
        else
        {
            replaying(false, correlationId);

            channel.onReplayComplete(correlationId);
        }

        return CONTINUE;
    }

    void fixDictionary(final FixDictionary fixDictionary)
    {
        this.fixDictionary = fixDictionary;
    }

    void onLogon(final CompositeKey sessionKey, final EngineConfiguration configuration)
    {
        this.sessionKey = sessionKey;
        this.configuration = configuration;
    }

    // Received on outbound publication when a replay starts
    public void onValidResendRequest(final long correlationId)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.REPLAY, formatters.validResendRequest.clear()
                .with(connectionId).with(correlationId));
        }
    }

    // Receive from replayer
    public void onStartReplay(final long correlationId)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.REPLAY, formatters.checkStartReplay.clear()
                .with(connectionId).with(correlationId));
        }

        // We start the replay with this message, rather than VRR because it doesn't race with replay complete.
        if (replaying || requiresRetry)
        {
            // Always goes on the replaying buffer
            enqueueStartReplay(correlationId);
        }
        else
        {
            replaying(true, correlationId);
        }
    }

    private void replaying(final boolean replaying, final long correlationId)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.REPLAY,
                formatters.replaying.clear().with(connectionId).with(replaying));
        }

        this.replaying = replaying;
        this.replayCorrelationId = correlationId;
    }

    private void requiresRetry(final boolean requiresRetry)
    {
        if (IS_REPLAY_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.REPLAY,
                formatters.requiresRetry.clear().with(connectionId).with(requiresRetry));
        }

        this.requiresRetry = requiresRetry;
    }

    public String toString()
    {
        return "FixSenderEndPoint{" +
            "connectionId=" + connectionId +
            ", sessionId=" + sessionId +
            ", sessionKey=" + sessionKey +
            "} " + super.toString();
    }

    boolean isReplaying()
    {
        return replaying;
    }

    boolean requiresRetry()
    {
        return requiresRetry;
    }

    int reattemptBytesWritten()
    {
        return reattemptBytesWritten;
    }

    static class ReattemptState
    {
        ExpandableDirectByteBuffer buffer;
        int usage;

        ExpandableDirectByteBuffer buffer()
        {
            ExpandableDirectByteBuffer buffer = this.buffer;
            if (buffer == null)
            {
                buffer = this.buffer = new ExpandableDirectByteBuffer();
            }

            buffer.checkLimit(usage);

            return buffer;
        }

        int shuffleWritten(final int written)
        {
            int usage = this.usage;
            if (written > 0)
            {
                usage -= written;
                buffer.putBytes(0, buffer, written, usage);
                this.usage = usage;
            }
            return usage;
        }
    }

    protected void sendSlowStatus(final boolean hasBecomeSlow)
    {
        if (IS_SLOW_CONSUMER_LOG_TAG_ENABLED)
        {
            DebugLogger.log(LogTag.SLOW_CONSUMER, formatters.becomesSlow.clear()
                .with(connectionId)
                .with(hasBecomeSlow));
        }

        super.sendSlowStatus(hasBecomeSlow);
    }
}
