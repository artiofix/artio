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
import io.aeron.Subscription;
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.collections.CollectionUtil;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.decoder.AbstractResendRequestDecoder;
import uk.co.real_logic.artio.engine.*;
import uk.co.real_logic.artio.engine.framer.FixThrottleRejectBuilder;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.Lazy;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.*;
import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;
import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.messages.MessageHeaderDecoder.ENCODED_LENGTH;
import static uk.co.real_logic.artio.util.MessageTypeEncoding.packAllMessageTypes;

/**
 * The replayer responds to resend requests with data from the log of sent messages.
 *
 * This agent subscribes to the stream of incoming fix data messages. It parses
 * Resend Request messages and searches the log, using the replay index to find
 * relevant messages to resend.
 */
public class Replayer extends AbstractReplayer
{
    public static final int MOST_RECENT_MESSAGE = 0;

    static final int MESSAGE_FRAME_BLOCK_LENGTH =
        ENCODED_LENGTH + FixMessageDecoder.BLOCK_LENGTH + FixMessageDecoder.bodyHeaderLength();
    static final int SIZE_OF_LENGTH_FIELD = FixMessageDecoder.bodyHeaderLength();

    // FIX specific state.
    private final LongHashSet gapFillMessageTypes;
    private final FixSessionCodecsFactory fixSessionCodecsFactory;
    private final CharFormatter receivedResendFormatter = new CharFormatter(
        "Received Resend Request for inclusive range: [%s, %s] gapfillingTo=%s connId=%s");

    // For FixReplayerSession, safe to share rather than allocate for each FixReplayerSession
    final CharFormatter completeNotRecentFormatter = new CharFormatter(
        "ReplayerSession: completeReplay-!upToMostRecent replayedMessages=%s " +
        "endSeqNo=%s beginSeqNo=%s overriddenBeginSeqNo=%s expectedCount=%s connId=%s");
    final FixMessageEncoder fixMessageEncoder = new FixMessageEncoder();
    final FixMessageDecoder fixMessageDecoder = new FixMessageDecoder();
    final ThrottleRejectDecoder throttleRejectDecoder = new ThrottleRejectDecoder();
    final AsciiBuffer sessionAsciiBuffer = new MutableAsciiBuffer();

    // Binary FIXP specific state
    private final IntHashSet gapfillOnRetransmitILinkTemplateIds;
    private final Lazy<FixPProtocol> binaryFixPProtocol;
    private final Lazy<FixPMessageDissector> binaryFixPDissector;
    private final Lazy<AbstractFixPParser> binaryFixPParser;
    private final Lazy<AbstractFixPProxy> binaryFixPProxy;
    private final Lazy<AbstractFixPOffsets> abstractBinaryFixPOffsets;
    private final LongHashSet fixPConnectionIds = new LongHashSet();
    private final ILinkConnectDecoder iLinkConnect = new ILinkConnectDecoder();
    private final InboundFixPConnectDecoder inboundFixPConnect = new InboundFixPConnectDecoder();
    private final ManageFixPConnectionDecoder manageFixPConnection = new ManageFixPConnectionDecoder();
    private final FixPMessageEncoder fixPMessageEncoder = new FixPMessageEncoder();
    private final ReplayTimestamper timestamper;

    private final List<ReplayChannel> closingChannels = new ArrayList<>();
    private final Long2ObjectHashMap<ReplayChannel> connectionIdToReplayerChannel = new Long2ObjectHashMap<>();
    private final RequestDisconnectDecoder requestDisconnect = new RequestDisconnectDecoder();
    private final DisconnectDecoder disconnect = new DisconnectDecoder();

    private final int maxBytesInBuffer;
    private final ReplayerCommandQueue replayerCommandQueue;
    private final AtomicCounter currentReplayCount;
    private final int maxConcurrentSessionReplays;
    private final EngineConfiguration configuration;
    private final ReplayQuery outboundReplayQuery;
    private final IdleStrategy idleStrategy;
    private final ErrorHandler errorHandler;
    private final int maxClaimAttempts;
    private final Subscription inboundSubscription;
    private final String agentNamePrefix;
    private final ReplayHandler replayHandler;
    private final FixPRetransmitHandler fixPRetransmitHandler;
    private final UtcTimestampEncoder utcTimestampEncoder;

    public Replayer(
        final ReplayQuery outboundReplayQuery,
        final ExclusivePublication publication,
        final BufferClaim bufferClaim,
        final IdleStrategy idleStrategy,
        final ErrorHandler errorHandler,
        final int maxClaimAttempts,
        final Subscription inboundSubscription,
        final String agentNamePrefix,
        final Set<String> gapfillOnReplayMessageTypes,
        final IntHashSet gapfillOnRetransmitILinkTemplateIds,
        final ReplayHandler replayHandler,
        final FixPRetransmitHandler fixPRetransmitHandler,
        final SenderSequenceNumbers senderSequenceNumbers,
        final FixSessionCodecsFactory fixSessionCodecsFactory,
        final int maxBytesInBuffer,
        final ReplayerCommandQueue replayerCommandQueue,
        final EpochFractionFormat epochFractionFormat,
        final AtomicCounter currentReplayCount,
        final int maxConcurrentSessionReplays,
        final EpochNanoClock clock,
        final FixPProtocolType fixPProtocolType,
        final EngineConfiguration configuration,
        final DutyCycleTracker dutyCycleTracker)
    {
        super(publication, fixSessionCodecsFactory, bufferClaim, senderSequenceNumbers, clock, dutyCycleTracker);
        this.outboundReplayQuery = outboundReplayQuery;
        this.idleStrategy = idleStrategy;
        this.errorHandler = errorHandler;
        this.maxClaimAttempts = maxClaimAttempts;
        this.inboundSubscription = inboundSubscription;
        this.agentNamePrefix = agentNamePrefix;
        this.gapfillOnRetransmitILinkTemplateIds = gapfillOnRetransmitILinkTemplateIds;
        this.replayHandler = replayHandler;
        this.fixPRetransmitHandler = fixPRetransmitHandler;
        this.fixSessionCodecsFactory = fixSessionCodecsFactory;
        this.maxBytesInBuffer = maxBytesInBuffer;
        this.replayerCommandQueue = replayerCommandQueue;
        this.currentReplayCount = currentReplayCount;
        this.maxConcurrentSessionReplays = maxConcurrentSessionReplays;
        this.configuration = configuration;

        gapFillMessageTypes = packAllMessageTypes(gapfillOnReplayMessageTypes);
        utcTimestampEncoder = new UtcTimestampEncoder(epochFractionFormat);

        binaryFixPProtocol = new Lazy<>(() -> FixPProtocolFactory.make(fixPProtocolType, errorHandler));
        binaryFixPDissector = new Lazy<>(() -> new FixPMessageDissector(binaryFixPProtocol.get().messageDecoders()));
        binaryFixPParser = new Lazy<>(() -> binaryFixPProtocol.get().makeParser(null));
        binaryFixPProxy = new Lazy<>(() -> binaryFixPProtocol.get().makeProxy(
            binaryFixPDissector.get(), publication, clock));
        abstractBinaryFixPOffsets = new Lazy<>(() -> binaryFixPProtocol.get().makeOffsets());

        timestamper = new ReplayTimestamper(publication, clock);
    }

    public Action onFragment(
        final DirectBuffer buffer, final int start, final int length, final Header header)
    {
        messageHeader.wrap(buffer, start);
        final int templateId = messageHeader.templateId();
        final int offset = start + ENCODED_LENGTH;
        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();

        switch (templateId)
        {
            case ValidResendRequestDecoder.TEMPLATE_ID:
            {
                validResendRequest.wrap(
                    buffer,
                    offset,
                    blockLength,
                    version);

                final long sessionId = validResendRequest.session();
                final long connectionId = validResendRequest.connection();
                final long beginSeqNo = validResendRequest.beginSequenceNumber();
                final long endSeqNo = validResendRequest.endSequenceNumber();
                final int sequenceIndex = validResendRequest.sequenceIndex();
                final long correlationId = validResendRequest.correlationId();
                final long overriddenBeginSeqNo = validResendRequest.overriddenBeginSequenceNumber();
                validResendRequest.wrapBody(asciiBuffer);

                if (IS_REPLAY_LOG_TAG_ENABLED)
                {
                    DebugLogger.logSbeDecoder(REPLAY, "Replayer:", validResendRequestAppendTo);
                }

                return onResendRequest(sessionId, connectionId, correlationId,
                    beginSeqNo, endSeqNo, sequenceIndex, overriddenBeginSeqNo, asciiBuffer);
            }

            case ILinkConnectDecoder.TEMPLATE_ID:
            {
                iLinkConnect.wrap(buffer, offset, blockLength, version);
                fixPConnectionIds.add(iLinkConnect.connection());
                return CONTINUE;
            }

            case InboundFixPConnectDecoder.TEMPLATE_ID:
            {
                inboundFixPConnect.wrap(buffer, offset, blockLength, version);
                fixPConnectionIds.add(inboundFixPConnect.connection());
                return CONTINUE;
            }

            case ManageFixPConnectionDecoder.TEMPLATE_ID:
            {
                manageFixPConnection.wrap(buffer, offset, blockLength, version);
                fixPConnectionIds.add(manageFixPConnection.connection());
                return CONTINUE;
            }

            case RequestDisconnectDecoder.TEMPLATE_ID:
            {
                requestDisconnect.wrap(buffer, offset, blockLength, version);
                final long connectionId = requestDisconnect.connection();
                onDisconnect(connectionId);
                return CONTINUE;
            }

            case DisconnectDecoder.TEMPLATE_ID:
            {
                disconnect.wrap(buffer, offset, blockLength, version);
                final long connectionId = disconnect.connection();
                onDisconnect(connectionId);
                return CONTINUE;
            }

            default:
            {
                return fixSessionCodecsFactory.onFragment(buffer, start, length, header);
            }
        }
    }

    private void onDisconnect(final long connectionId)
    {
        fixPConnectionIds.remove(connectionId);

        final ReplayChannel replayChannel = connectionIdToReplayerChannel.remove(connectionId);
        if (replayChannel != null)
        {
            currentReplayCount.decrement();
            // replay was in progress at the time of disconnect
            if (!replayChannel.startClose())
            {
                closingChannels.add(replayChannel);
            }
        }
    }

    Action onResendRequest(
        final long sessionId,
        final long connectionId,
        final long correlationId,
        final long beginSeqNo,
        final long endSeqNo,
        final int sequenceIndex,
        final long overriddenBeginSeqNo,
        final AsciiBuffer asciiBuffer)
    {
        if (checkDisconnected(connectionId))
        {
            return CONTINUE;
        }

        final ReplayChannel replayChannel = connectionIdToReplayerChannel.get(connectionId);
        if (replayChannel != null)
        {
            final int enqueuedReplayCount = replayChannel.enqueuedReplayCount();
            if (enqueuedReplayCount >= maxConcurrentSessionReplays)
            {
                errorHandler.onError(new FixGatewayException(String.format(
                    "Ignore resend request for sessionId=%d,connectionId=%d as %d requests in flight",
                    sessionId,
                    connectionId,
                    enqueuedReplayCount)));
                return CONTINUE;
            }

            // Existing replay in progress
            final int length = asciiBuffer.capacity();
            final MutableAsciiBuffer copiedBuffer = new MutableAsciiBuffer(new byte[length]);
            copiedBuffer.putBytes(0, asciiBuffer, 0, length);

            replayChannel.enqueueReplay(new EnqueuedReplay(sessionId, connectionId, correlationId,
                beginSeqNo, endSeqNo, sequenceIndex, overriddenBeginSeqNo, copiedBuffer));

            return COMMIT;
        }
        else
        {
            // New replay
            try
            {
                final ReplayerSession session = processResendRequest(sessionId, connectionId, correlationId,
                    beginSeqNo, endSeqNo, sequenceIndex, overriddenBeginSeqNo, asciiBuffer);
                if (session == null)
                {
                    return ABORT;
                }

                final ReplayChannel channel = new ReplayChannel(session);
                connectionIdToReplayerChannel.put(connectionId, channel);
                currentReplayCount.increment();

                return COMMIT;
            }
            catch (final IllegalStateException e)
            {
                errorHandler.onError(e);
                sendStartReplay = true;
                return CONTINUE;
            }
        }
    }

    private ReplayerSession processResendRequest(
        final long sessionId,
        final long connectionId,
        final long correlationId,
        final long beginSeqNo,
        final long endSeqNo,
        final int sequenceIndex,
        final long overriddenBeginSeqNo,
        final AsciiBuffer asciiBuffer)
    {
        final FixReplayerCodecs sessionCodecs = fixSessionCodecsFactory.get(sessionId);
        if (sessionCodecs != null)
        {
            if (trySendStartReplay(sessionId, connectionId, correlationId))
            {
                return null;
            }

            final FixReplayerSession fixReplayerSession = processFixResendRequest(
                sessionId, connectionId, correlationId, (int)beginSeqNo, (int)endSeqNo, sequenceIndex,
                (int)overriddenBeginSeqNo, asciiBuffer, sessionCodecs);
            // Suppress resending of start replay if back-pressure happens here, ie if fixReplayerSession == null.
            sendStartReplay = fixReplayerSession != null;
            return fixReplayerSession;
        }
        else if (fixPConnectionIds.contains(connectionId))
        {
            DebugLogger.log(REPLAY,
                receivedResendFormatter,
                beginSeqNo,
                endSeqNo,
                overriddenBeginSeqNo,
                connectionId);

            final AtomicCounter bytesInBuffer = senderSequenceNumbers.bytesInBufferCounter(connectionId);
            if (bytesInBuffer == null)
            {
                return null;
            }

            final FixPReplayerSession session = new FixPReplayerSession(
                connectionId, correlationId, bufferClaim, idleStrategy, maxClaimAttempts, publication,
                outboundReplayQuery,
                (int)beginSeqNo, (int)endSeqNo, sessionId, this, gapfillOnRetransmitILinkTemplateIds,
                fixPMessageEncoder, binaryFixPParser.get(), binaryFixPProxy.get(), abstractBinaryFixPOffsets.get(),
                fixPRetransmitHandler, bytesInBuffer, configuration.senderMaxBytesInBuffer());

            session.query();

            return session;
        }

        // ManageSession and ValidResendRequest might race each other (different sessions), so it's possible we see
        // VRR before MS and sessionCodecs is null, simply wait in this case for the other image to catch up.
        return null;
    }

    private FixReplayerSession processFixResendRequest(
        final long sessionId,
        final long connectionId,
        final long correlationId,
        final int beginSeqNo,
        final int endSeqNo,
        final int sequenceIndex,
        final int overriddenBeginSeqNo,
        final AsciiBuffer asciiBuffer,
        final FixReplayerCodecs sessionCodecs)
    {
        final AtomicCounter bytesInBuffer = senderSequenceNumbers.bytesInBufferCounter(connectionId);
        if (bytesInBuffer == null)
        {
            return null;
        }

        DebugLogger.log(REPLAY,
            receivedResendFormatter,
            beginSeqNo,
            endSeqNo,
            overriddenBeginSeqNo,
            connectionId);

        final AbstractResendRequestDecoder resendRequest = sessionCodecs.resendRequest();
        resendRequest.reset();
        resendRequest.decode(asciiBuffer, 0, asciiBuffer.capacity());

        final GapFillEncoder encoder = sessionCodecs.makeGapFillEncoder();
        encoder.setupMessage(resendRequest.header());

        final FixThrottleRejectBuilder throttleRejectBuilder;

        if (configuration.throttleWindowInMs() == MISSING_INT)
        {
            throttleRejectBuilder = null;
        }
        else
        {
            throttleRejectBuilder = new FixThrottleRejectBuilder(
                sessionCodecs.dictionary(),
                errorHandler,
                sessionId,
                connectionId,
                utcTimestampEncoder,
                clock, configuration.throttleWindowInMs(), configuration.throttleLimitOfMessages());
            HeaderSetup.setup(resendRequest.header(), throttleRejectBuilder.header());
        }

        final String message = asciiBuffer.getAscii(0, asciiBuffer.capacity());
        final FixReplayerSession fixReplayerSession = new FixReplayerSession(
            bufferClaim,
            idleStrategy,
            replayHandler,
            maxClaimAttempts,
            gapFillMessageTypes,
            publication,
            clock,
            beginSeqNo,
            endSeqNo,
            connectionId,
            correlationId,
            sessionId,
            sequenceIndex,
            overriddenBeginSeqNo,
            outboundReplayQuery,
            message,
            errorHandler,
            encoder,
            bytesInBuffer,
            maxBytesInBuffer,
            utcTimestampEncoder,
            this,
            throttleRejectBuilder);

        fixReplayerSession.query();

        return fixReplayerSession;
    }

    public int doWork()
    {
        final long timeInNs = clock.nanoTime();

        trackDutyCycleTime(timeInNs);
        timestamper.sendTimestampMessage(timeInNs);

        int work = replayerCommandQueue.poll();
        work += pollReplayerChannels();
        return work + inboundSubscription.controlledPoll(this, POLL_LIMIT);
    }

    private int pollReplayerChannels()
    {
        final Long2ObjectHashMap<ReplayChannel>.EntryIterator replayerChannels =
            connectionIdToReplayerChannel.entrySet().iterator();
        final int size = connectionIdToReplayerChannel.size();

        while (replayerChannels.hasNext())
        {
            final ReplayChannel channel = replayerChannels.next().getValue();
            if (channel.attemptReplay())
            {
                // Replay complete
                final EnqueuedReplay enqueuedReplay = channel.pollReplay();
                if (enqueuedReplay == null)
                {
                    currentReplayCount.decrementOrdered();
                    replayerChannels.remove();
                }
                else
                {
                    try
                    {
                        final ReplayerSession session = processResendRequest(
                            enqueuedReplay.sessionId(),
                            enqueuedReplay.connectionId(),
                            enqueuedReplay.correlationId(),
                            enqueuedReplay.beginSeqNo(),
                            enqueuedReplay.endSeqNo(),
                            enqueuedReplay.sequenceIndex(),
                            enqueuedReplay.overriddenBeginSeqNo(),
                            enqueuedReplay.asciiBuffer());

                        channel.startReplay(session);
                    }
                    catch (final IllegalStateException e)
                    {
                        errorHandler.onError(e);
                    }
                }
            }
        }

        return size + CollectionUtil.removeIf(closingChannels, ReplayChannel::attemptReplay);
    }

    public void onClose()
    {
        connectionIdToReplayerChannel.values().forEach(ReplayChannel::closeNow);
        connectionIdToReplayerChannel.clear();
        currentReplayCount.set(0);
        currentReplayCount.close();
        outboundReplayQuery.close();
        super.onClose();
    }

    public String roleName()
    {
        return agentNamePrefix + "Replayer";
    }

}
