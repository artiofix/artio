/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.builder.AbstractSequenceResetEncoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.engine.logger.FixMessageTracker;
import uk.co.real_logic.artio.engine.logger.ReplayOperation;
import uk.co.real_logic.artio.engine.logger.ReplayQuery;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.concurrent.TimeUnit;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.LogTag.CATCHUP;
import static uk.co.real_logic.artio.dictionary.SessionConstants.HEARTBEAT_MESSAGE_TYPE;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.messages.FixMessageDecoder.*;
import static uk.co.real_logic.artio.messages.MessageStatus.CATCHUP_REPLAY;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.MISSING_MESSAGES;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;

public class CatchupReplayer implements ControlledFragmentHandler, Continuation
{

    public static class Formatters
    {
        private final CharFormatter attemptFormatter =
            new CharFormatter("Attempt replay for sessionId=%s");
        private final CharFormatter okFormatter =
            new CharFormatter("OK for sessionId=%s");
        private final CharFormatter missingFormatter =
            new CharFormatter("Missing Messages for sessionId=%s");
        private final CharFormatter awaitIndexFormatter = new CharFormatter(
            "Awaiting index position: indexed=%s vs required=%s");
        private final CharFormatter replayQueryingFormatter = new CharFormatter(
            "Querying for sessionId=%s, currently at (%s, %s)");
    }

    private static final int ENCODE_BUFFER_SIZE = 8 * 1024;

    public static final int FRAME_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FixMessageEncoder.BLOCK_LENGTH + FixMessageEncoder.bodyHeaderLength() +
        metaDataHeaderLength();

    enum ReplayFor
    {
        REPLAY_MESSAGES
        {
            long sendOk(
                final GatewayPublication publication,
                final int libraryId,
                final long correlationId,
                final FixGatewaySession session)
            {
                return publication.saveReplayMessagesReply(
                    libraryId, correlationId, ReplayMessagesStatus.OK);
            }

            long sendMissing(
                final GatewayPublication publication,
                final int libraryId,
                final long correlationId,
                final FixGatewaySession session)
            {
                return publication.saveReplayMessagesReply(
                    libraryId, correlationId, ReplayMessagesStatus.MISSING_MESSAGES);
            }
        },
        REQUEST_SESSION
        {
            long sendOk(
                final GatewayPublication publication,
                final int libraryId,
                final long correlationId,
                final FixGatewaySession session)
            {
                final long position = publication.saveRequestSessionReply(libraryId, OK, correlationId);
                if (position > 0)
                {
                    session.play();
                }
                return position;
            }

            long sendMissing(
                final GatewayPublication publication,
                final int libraryId,
                final long correlationId,
                final FixGatewaySession session)
            {
                final long position = publication.saveRequestSessionReply(libraryId, MISSING_MESSAGES, correlationId);
                if (position > 0)
                {
                    session.play();
                }
                return position;
            }
        };

        abstract long sendOk(
            GatewayPublication publication,
            int libraryId,
            long correlationId,
            FixGatewaySession session);

        abstract long sendMissing(
            GatewayPublication publication,
            int libraryId,
            long correlationId,
            FixGatewaySession session);
    }

    private enum State
    {
        AWAITING_INDEX,
        REPLAY_QUERY,
        REPLAYING,
        CLOSING,
        SEND_MISSING,
        SEND_OK
    }

    private static final int OUT_OF_RANGE = -1;

    private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
    private final FixMessageDecoder messageDecoder = new FixMessageDecoder();
    private final FixMessageEncoder messageEncoder = new FixMessageEncoder();

    private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final ReplayQuery inboundMessages;
    private final GatewayPublication inboundPublication;
    private final ErrorHandler errorHandler;
    private final long correlationId;
    private final long connectionId;
    private final int libraryId;
    private final int replayToSequenceNumber;
    private final int replayToSequenceIndex;
    private final FixGatewaySession session;
    private final long catchupEndTimeInMs;
    private final long requiredPosition;
    private final SessionHeaderDecoder headerDecoder;
    private final ReplayFor replayFor;
    private final Formatters formatters;
    private final EpochFractionFormat epochFractionFormat;
    private final EpochNanoClock nanoClock;

    private int replayFromSequenceNumber;
    private int replayFromSequenceIndex;
    private State state = State.AWAITING_INDEX;
    private String missingMessagesReason;

    private AbstractSequenceResetEncoder sequenceResetEncoder;
    private UtcTimestampEncoder timestampEncoder;
    private MutableAsciiBuffer encodeBuffer;

    private int heartbeatRangeSequenceNumberStart = OUT_OF_RANGE;

    private ReplayOperation replayOperation = null;
    private boolean replayHadMissingMessages;

    CatchupReplayer(
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final ReplayQuery inboundMessages,
        final GatewayPublication inboundPublication,
        final ErrorHandler errorHandler,
        final long correlationId,
        final long connectionId,
        final int libraryId,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final FixGatewaySession session,
        final long catchupEndTimeInMs,
        final ReplayFor replayFor,
        final Formatters formatters,
        final EpochFractionFormat epochFractionFormat,
        final EpochNanoClock nanoClock)
    {
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
        this.inboundMessages = inboundMessages;
        this.inboundPublication = inboundPublication;
        this.errorHandler = errorHandler;
        this.correlationId = correlationId;
        this.connectionId = connectionId;
        this.libraryId = libraryId;
        this.replayToSequenceNumber = replayToSequenceNumber;
        this.replayToSequenceIndex = replayToSequenceIndex;
        this.replayFromSequenceNumber = replayFromSequenceNumber;
        this.replayFromSequenceIndex = replayFromSequenceIndex;
        this.session = session;
        this.catchupEndTimeInMs = catchupEndTimeInMs;
        this.requiredPosition = inboundPublication.position();
        this.headerDecoder = session.fixDictionary().makeHeaderDecoder();
        this.replayFor = replayFor;
        this.formatters = formatters;
        this.epochFractionFormat = epochFractionFormat;
        this.nanoClock = nanoClock;
    }

    private void updateMessageHeader(final MutableDirectBuffer buffer, final int offset)
    {
        final int frameOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
        messageEncoder
            .wrap(buffer, frameOffset)
            .connection(connectionId)
            .libraryId(libraryId)
            .status(CATCHUP_REPLAY);
    }

    public Action onFragment(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final Header header)
    {
        messageHeaderDecoder.wrap(srcBuffer, srcOffset);

        final int version = messageHeaderDecoder.version();
        messageDecoder.wrap(
            srcBuffer,
            srcOffset + MessageHeaderDecoder.ENCODED_LENGTH,
            messageHeaderDecoder.blockLength(),
            version);

        if (messageDecoder.status() == CATCHUP_REPLAY)
        {
            return CONTINUE;
        }

        final long messageType = MessageTypeExtractor.getMessageType(messageDecoder);

        messageDecoder.skipMetaData();

        final int messageLength = messageDecoder.bodyLength();
        final int messageOffset = messageDecoder.limit() + bodyHeaderLength();

        asciiBuffer.wrap(srcBuffer, messageOffset, messageLength);
        headerDecoder.decode(asciiBuffer, 0, messageLength);

        if (messageType == HEARTBEAT_MESSAGE_TYPE)
        {
            if (heartbeatRangeSequenceNumberStart == OUT_OF_RANGE)
            {
                heartbeatRangeSequenceNumberStart = headerDecoder.msgSeqNum();
            }

            return CONTINUE;
        }
        else
        {
            if (heartbeatRangeSequenceNumberStart != OUT_OF_RANGE)
            {
                if (!sendGapFill())
                {
                    return ABORT;
                }
            }

            return processNormalMessage(
                srcBuffer, srcOffset, srcLength);
        }
    }

    private boolean sendGapFill()
    {
        if (sequenceResetEncoder == null)
        {
            sequenceResetEncoder = session.fixDictionary().makeSequenceResetEncoder();
            timestampEncoder = new UtcTimestampEncoder(epochFractionFormat);
            encodeBuffer = new MutableAsciiBuffer(new byte[ENCODE_BUFFER_SIZE]);
            sequenceResetEncoder.gapFillFlag(true);

            final SessionHeaderEncoder header = sequenceResetEncoder.header()
                .possDupFlag(true);

            header.senderCompID(headerDecoder.senderCompID());
            if (headerDecoder.hasSenderSubID())
            {
                header.senderSubID(headerDecoder.senderSubID());
            }
            if (headerDecoder.hasSenderLocationID())
            {
                header.senderLocationID(headerDecoder.senderLocationID());
            }

            header.targetCompID(headerDecoder.targetCompID());
            if (headerDecoder.hasTargetSubID())
            {
                header.targetSubID(headerDecoder.targetSubID());
            }
            if (headerDecoder.hasTargetLocationID())
            {
                header.targetLocationID(headerDecoder.targetLocationID());
            }
        }

        final int heartbeatRangeSequenceNumberEnd = headerDecoder.msgSeqNum();

        sequenceResetEncoder.header().msgSeqNum(heartbeatRangeSequenceNumberStart);
        sequenceResetEncoder.newSeqNo(heartbeatRangeSequenceNumberEnd);
        sequenceResetEncoder.header().sendingTime(
            timestampEncoder.buffer(), timestampEncoder.encodeFrom(nanoClock.nanoTime(), TimeUnit.NANOSECONDS));

        final long result = sequenceResetEncoder.encode(encodeBuffer, 0);
        final int encodedLength = Encoder.length(result);
        final int encodedOffset = Encoder.offset(result);
        final boolean sent = inboundPublication.saveMessage(
            encodeBuffer, encodedOffset, encodedLength,
            libraryId, SEQUENCE_RESET_MESSAGE_TYPE,
            messageDecoder.session(), replayFromSequenceIndex, libraryId,
            CATCHUP_REPLAY, heartbeatRangeSequenceNumberEnd) > 0;

        if (sent)
        {
            heartbeatRangeSequenceNumberStart = OUT_OF_RANGE;
        }

        return sent;
    }

    private Action processNormalMessage(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength)
    {
        updateMessageHeader((MutableDirectBuffer)srcBuffer, srcOffset);

        final Action action = Pressure.apply(inboundPublication.offer(srcBuffer, srcOffset, srcLength));
        if (action == CONTINUE)
        {
            // store the point to continue from if an abort happens.
            replayFromSequenceNumber = headerDecoder.msgSeqNum() + 1;
            replayFromSequenceIndex = messageDecoder.sequenceIndex();
        }
        return action;
    }

    public long attempt()
    {
        if (DebugLogger.isEnabled(CATCHUP))
        {
            DebugLogger.log(CATCHUP, formatters.attemptFormatter.clear().with(session.sessionId()));
        }

        switch (state)
        {
            case AWAITING_INDEX:
            {
                final long indexedPosition = receivedSequenceNumberIndex.indexedPosition(
                    inboundPublication.sessionId());

                if (indexedPosition >= requiredPosition)
                {
                    state = State.REPLAY_QUERY;
                }
                else
                {
                    DebugLogger.log(CATCHUP,
                        formatters.awaitIndexFormatter,
                        indexedPosition,
                        requiredPosition);
                }

                return BACK_PRESSURED;
            }

            case REPLAY_QUERY:
            {
                if (notLoggingInboundMessages())
                {
                    return switchToMissingMessages("Not logging inbound messages");
                }

                DebugLogger.log(CATCHUP,
                    formatters.replayQueryingFormatter,
                    session.sessionId(), replayToSequenceNumber, replayToSequenceIndex);

                replayOperation = inboundMessages.query(
                    session.sessionId(),
                    replayFromSequenceNumber,
                    replayFromSequenceIndex,
                    replayToSequenceNumber,
                    replayToSequenceIndex,
                    CATCHUP,
                    new FixMessageTracker(CATCHUP, this, session.sessionId()));

                state = replayOperation == null ? State.SEND_MISSING : State.REPLAYING;

                return BACK_PRESSURED;
            }

            case REPLAYING:
            {
                // Timeout the catchup operations
                if (System.currentTimeMillis() > catchupEndTimeInMs)
                {
                    return switchToMissingMessages("Catchup operation timed out");
                }

                if (replayOperation.pollReplay())
                {
                    // Replay of all ranges is complete, now decide OK vs MISSING
                    replayHadMissingMessages = hasMissingMessages();

                    // Start closing the Aeron replay session / image
                    replayOperation.startClose();
                    state = State.CLOSING;
                    return BACK_PRESSURED;
                }
                else
                {
                    // Incomplete operation
                    return BACK_PRESSURED;
                }
            }

            case SEND_MISSING:
            {
                return sendMissingMessages();
            }

            case SEND_OK:
            {
                return sendOk(inboundPublication, correlationId, session);
            }

            case CLOSING:
            {
                // Drive ReplayOperation through its close state machine
                if (!replayOperation.pollReplay())
                {
                    return BACK_PRESSURED;
                }

                // Close complete; now send reply
                if (replayHadMissingMessages)
                {
                    return switchToMissingMessages("Is missing messages from replay index query");
                }
                else
                {
                    state = State.SEND_OK;
                    return sendOk(inboundPublication, correlationId, session);
                }
            }

            // Javac required fall-through case that should never be reached
            default:
            {
                return 1;
            }
        }
    }

    private long switchToMissingMessages(final String reason)
    {
        state = State.SEND_MISSING;
        missingMessagesReason = reason;
        return sendMissingMessages();
    }

    private boolean hasMissingMessages()
    {
        return replayFromSequenceIndex < replayToSequenceIndex || replayFromSequenceNumber < replayToSequenceNumber;
    }

    private boolean notLoggingInboundMessages()
    {
        return inboundMessages == null;
    }

    private long sendOk(
        final GatewayPublication publication,
        final long correlationId,
        final FixGatewaySession session)
    {
        if (DebugLogger.isEnabled(CATCHUP))
        {
            DebugLogger.log(CATCHUP, formatters.okFormatter.clear().with(session.sessionId()));
        }

        return replayFor.sendOk(publication, libraryId, correlationId, session);
    }

    static long sendOk(
        final GatewayPublication publication,
        final long correlationId,
        final FixGatewaySession session,
        final int libraryId,
        final CatchupReplayer.Formatters formatters)
    {
        if (DebugLogger.isEnabled(CATCHUP))
        {
            DebugLogger.log(CATCHUP, formatters.okFormatter.clear().with(session.sessionId()));
        }

        return ReplayFor.REQUEST_SESSION.sendOk(publication, libraryId, correlationId, session);
    }

    private long sendMissingMessages()
    {
        if (DebugLogger.isEnabled(CATCHUP))
        {
            DebugLogger.log(CATCHUP, formatters.missingFormatter.clear().with(session.sessionId()));
        }

        final long position = replayFor.sendMissing(inboundPublication, libraryId, correlationId, session);

        if (position > 0)
        {
            errorHandler.onError(new IllegalStateException(String.format(
                "Failed to read correct number of messages for sessionId=%d," +
                " finished at [%d, %d] instead of [%d, %d] - %s",
                session.sessionId(),
                replayFromSequenceIndex,
                replayFromSequenceNumber,
                replayToSequenceIndex,
                replayToSequenceNumber,
                missingMessagesReason)));

            missingMessagesReason = null;
        }

        return position;
    }

    public void close()
    {
        if (replayOperation != null)
        {
            replayOperation.closeNow();
        }
    }
}
