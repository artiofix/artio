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
package uk.co.real_logic.artio.engine;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.UnavailableImageHandler;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.ErrorHandler;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.*;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.StreamInformation;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.framer.FramerContext;
import uk.co.real_logic.artio.engine.framer.PruneOperation;
import uk.co.real_logic.artio.engine.logger.*;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.protocol.Streams;

import java.util.ArrayList;
import java.util.List;

import static uk.co.real_logic.artio.dictionary.generation.Exceptions.suppressingClose;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;

public class EngineContext implements AutoCloseable
{

    private final PruneOperation.Formatters pruneOperationFormatters = new PruneOperation.Formatters();
    private final CompletionPosition inboundCompletionPosition = new CompletionPosition();
    private final CompletionPosition outboundLibraryCompletionPosition = new CompletionPosition();
    private final SequenceNumberExtractor sentSequenceNumberExtractor = new SequenceNumberExtractor();
    private final SequenceNumberExtractor recvSequenceNumberExtractor = new SequenceNumberExtractor();

    private final EpochNanoClock clock;
    private final EngineConfiguration configuration;
    private final ErrorHandler errorHandler;
    private final FixCounters fixCounters;
    private final Aeron aeron;
    private final ReplayerCommandQueue replayerCommandQueue;
    private final SenderSequenceNumbers senderSequenceNumbers;
    private final AeronArchive aeronArchive;
    private final RecordingCoordinator recordingCoordinator;
    private final ExclusivePublication replayPublication;
    private final SequenceNumberIndexWriter sentSequenceNumberIndex;
    private final SequenceNumberIndexWriter receivedSequenceNumberIndex;

    private final ReplayEvictionHandler inboundEvictionHandler;
    private final ReplayEvictionHandler outboundEvictionHandler;

    private Streams inboundLibraryStreams;
    private Streams outboundLibraryStreams;

    // Indexers are owned by the indexingAgent
    private ArchivingAgents inboundArchivingAgents;
    private ArchivingAgents outboundArchivingAgents;
    private Agent indexingAgent;
    private ReplayQuery pruneInboundReplayQuery;
    private ReplayQuery outboundReplayQuery;
    private FramerContext framerContext;
    private long inboundIndexRegistrationId;
    private long outboundIndexRegistrationId;

    EngineContext(
        final EngineConfiguration configuration,
        final ErrorHandler errorHandler,
        final ExclusivePublication replayPublication,
        final FixCounters fixCounters,
        final Aeron aeron,
        final AeronArchive aeronArchive,
        final RecordingCoordinator recordingCoordinator)
    {
        this.configuration = configuration;
        this.errorHandler = errorHandler;
        this.fixCounters = fixCounters;
        this.aeron = aeron;
        this.clock = configuration.epochNanoClock();
        this.replayPublication = replayPublication;
        this.aeronArchive = aeronArchive;
        this.recordingCoordinator = recordingCoordinator;

        inboundEvictionHandler = new ReplayEvictionHandler(errorHandler);
        outboundEvictionHandler = new ReplayEvictionHandler(errorHandler);
        replayerCommandQueue = new ReplayerCommandQueue(configuration.framerIdleStrategy());
        senderSequenceNumbers = new SenderSequenceNumbers(replayerCommandQueue);

        try
        {
            final EpochClock epochClock = new SystemEpochClock();
            final Long2LongHashMap connectionIdToFixPSessionId = new Long2LongHashMap(UNK_SESSION);
            final FixPProtocolType fixPProtocolType = configuration.supportedFixPProtocolType();
            final boolean indexChecksumEnabled = configuration.indexChecksumEnabled();
            sentSequenceNumberIndex = new SequenceNumberIndexWriter(
                sentSequenceNumberExtractor,
                configuration.sentSequenceNumberBuffer(),
                configuration.sentSequenceNumberIndex(),
                errorHandler,
                configuration.outboundLibraryStream(),
                recordingCoordinator.indexerOutboundRecordingIdLookup(),
                configuration.indexFileStateFlushTimeoutInMs(),
                epochClock,
                configuration.logFileDir(),
                connectionIdToFixPSessionId,
                fixPProtocolType,
                indexChecksumEnabled,
                configuration.logOutboundMessages());
            receivedSequenceNumberIndex = new SequenceNumberIndexWriter(
                recvSequenceNumberExtractor,
                configuration.receivedSequenceNumberBuffer(),
                configuration.receivedSequenceNumberIndex(),
                errorHandler,
                configuration.inboundLibraryStream(),
                recordingCoordinator.indexerInboundRecordingIdLookup(),
                configuration.indexFileStateFlushTimeoutInMs(),
                epochClock,
                null,
                connectionIdToFixPSessionId,
                fixPProtocolType,
                indexChecksumEnabled,
                configuration.logInboundMessages());

            newStreams();
            newArchivingAgent();
        }
        catch (final Exception e)
        {
            completeDuringStartup();

            suppressingClose(this, e);

            throw e;
        }
    }

    private void newStreams()
    {
        final String libraryAeronChannel = configuration.libraryAeronChannel();
        final boolean printAeronStreamIdentifiers = configuration.printAeronStreamIdentifiers();

        inboundLibraryStreams = new Streams(
            aeron,
            libraryAeronChannel,
            printAeronStreamIdentifiers,
            fixCounters.failedInboundPublications(),
            configuration.inboundLibraryStream(),
            clock,
            configuration.inboundMaxClaimAttempts(),
            recordingCoordinator);
        outboundLibraryStreams = new Streams(
            aeron,
            libraryAeronChannel,
            printAeronStreamIdentifiers,
            fixCounters.failedOutboundPublications(),
            configuration.outboundLibraryStream(),
            clock,
            configuration.outboundMaxClaimAttempts(),
            recordingCoordinator);
    }

    private ReplayIndex newReplayIndex(
        final String logFileDir,
        final int streamId,
        final RecordingIdLookup recordingIdLookup,
        final Long2LongHashMap connectionIdToILinkUuid,
        final SequenceNumberIndexReader reader,
        final SequenceNumberExtractor sequenceNumberExtractor,
        final boolean indexChecksumEnabled,
        final ReplayEvictionHandler evictionHandler)
    {
        return new ReplayIndex(
            sequenceNumberExtractor,
            logFileDir,
            streamId,
            configuration.replayIndexFileRecordCapacity(),
            configuration.replayIndexSegmentRecordCapacity(),
            LoggerUtil::map,
            ReplayIndexDescriptor.replayPositionBuffer(logFileDir, streamId, configuration.replayPositionBufferSize()),
            errorHandler,
            recordingIdLookup,
            connectionIdToILinkUuid,
            configuration.supportedFixPProtocolType(),
            reader,
            configuration.timeIndexReplayFlushIntervalInNs(),
            indexChecksumEnabled,
            evictionHandler);
    }

    private ReplayQuery newReplayQuery(final IdleStrategy idleStrategy, final int streamId)
    {
        final String logFileDir = configuration.logFileDir();
        final int cacheSetSize = configuration.loggerCacheSetSize();
        final int cacheNumSets = configuration.loggerCacheNumSets();
        final int archiveReplayStream = configuration.archiveReplayStream();

        return new ReplayQuery(
            logFileDir,
            cacheNumSets,
            cacheSetSize,
            LoggerUtil::mapExistingFile,
            streamId,
            idleStrategy,
            aeronArchive,
            errorHandler,
            NoOpReplayQueryListener.INSTANCE,
            archiveReplayStream,
            configuration.replayIndexFileRecordCapacity(),
            configuration.replayIndexSegmentRecordCapacity());
    }

    public long inboundIndexRegistrationId()
    {
        return inboundIndexRegistrationId;
    }

    public long outboundIndexRegistrationId()
    {
        return outboundIndexRegistrationId;
    }

    private void newArchivingAgent()
    {
        ReplayIndex inboundReplayIndex = null;
        ReplayIndex outboundReplayIndex = null;

        try
        {
            final String logFileDir = configuration.logFileDir();
            final boolean indexChecksumEnabled = configuration.indexChecksumEnabled();

            final Long2LongHashMap connectionIdToILinkUuid = new Long2LongHashMap(UNK_SESSION);
            final List<Index> inboundIndices = new ArrayList<>();
            if (configuration.logInboundMessages())
            {
                inboundReplayIndex = newReplayIndex(
                    logFileDir,
                    configuration.inboundLibraryStream(),
                    recordingCoordinator.indexerInboundRecordingIdLookup(),
                    connectionIdToILinkUuid,
                    receivedSequenceNumberIndex.reader(),
                    recvSequenceNumberExtractor,
                    indexChecksumEnabled,
                    inboundEvictionHandler);
                inboundIndices.add(inboundReplayIndex);
            }
            inboundIndices.add(receivedSequenceNumberIndex);

            final Subscription inboundIndexSubscription = inboundLibraryStreams.subscription("inboundIndexer");
            inboundIndexRegistrationId = inboundIndexSubscription.registrationId();

            final Indexer inboundIndexer = new Indexer(
                inboundIndices, configuration.agentNamePrefix(), configuration.archiveReplayStream());

            inboundArchivingAgents = new ArchivingAgents(
                inboundIndexer,
                null,
                inboundIndexSubscription,
                inboundCompletionPosition
            );

            final List<Index> outboundIndices = new ArrayList<>();
            if (configuration.logOutboundMessages())
            {
                outboundReplayIndex = newReplayIndex(
                    logFileDir,
                    configuration.outboundLibraryStream(),
                    recordingCoordinator.indexerOutboundRecordingIdLookup(),
                    connectionIdToILinkUuid,
                    sentSequenceNumberIndex.reader(),
                    sentSequenceNumberExtractor,
                    indexChecksumEnabled,
                    outboundEvictionHandler);
                outboundIndices.add(outboundReplayIndex);
            }
            outboundIndices.add(sentSequenceNumberIndex);

            final Subscription outboundIndexSubscription = outboundLibraryStreams.subscription("outboundIndexer");
            outboundIndexRegistrationId = outboundIndexSubscription.registrationId();

            final Indexer outboundIndexer = new Indexer(
                outboundIndices, configuration.agentNamePrefix(), configuration.archiveReplayStream());
            final AbstractReplayer replayer = replayer();
            outboundArchivingAgents = new ArchivingAgents(
                outboundIndexer, replayer, outboundIndexSubscription, outboundLibraryCompletionPosition);
        }
        catch (final Exception e)
        {
            suppressingClose(inboundReplayIndex, e);
            suppressingClose(outboundReplayIndex, e);
            throw e;
        }

        final List<Agent> agents = new ArrayList<>();
        agents.add(inboundArchivingAgents);
        agents.add(outboundArchivingAgents);

        indexingAgent = new CompositeAgent(agents);
    }

    private AbstractReplayer replayer()
    {
        final AbstractReplayer replayer;
        if (configuration.logOutboundMessages())
        {
            outboundReplayQuery = newReplayQuery(
                configuration.archiverIdleStrategy(), configuration.outboundLibraryStream());
            outboundEvictionHandler.replayQuery(outboundReplayQuery);
            try
            {
                final EpochFractionFormat epochFractionFormat = configuration.sessionEpochFractionFormat();
                replayer = new Replayer(
                    outboundReplayQuery,
                    replayPublication,
                    new BufferClaim(),
                    configuration.archiverIdleStrategy(),
                    errorHandler,
                    configuration.outboundMaxClaimAttempts(),
                    configuration.agentNamePrefix(),
                    configuration.gapfillOnReplayMessageTypes(),
                    configuration.gapfillOnRetransmitILinkTemplateIds(),
                    configuration.replayHandler(),
                    configuration.fixPRetransmitHandler(),
                    senderSequenceNumbers,
                    new FixSessionCodecsFactory(clock, epochFractionFormat),
                    configuration.senderMaxBytesInBuffer(),
                    replayerCommandQueue,
                    epochFractionFormat,
                    fixCounters.currentReplayCount(),
                    configuration.maxConcurrentSessionReplays(),
                    clock,
                    configuration.supportedFixPProtocolType(),
                    configuration,
                    fixCounters.getIndexerDutyCycleTracker(configuration.indexerCycleThresholdNs()));
            }
            catch (final Throwable e)
            {
                outboundReplayQuery.close();
                throw e;
            }
        }
        else
        {
            final GatewayPublication replayGatewayPublication = new GatewayPublication(
                replayPublication,
                fixCounters.failedReplayPublications(),
                configuration.archiverIdleStrategy(),
                clock,
                configuration.outboundMaxClaimAttempts());

            replayer = new GapFiller(
                replayGatewayPublication,
                configuration.agentNamePrefix(),
                senderSequenceNumbers,
                replayerCommandQueue,
                new FixSessionCodecsFactory(clock, configuration.sessionEpochFractionFormat()),
                clock,
                fixCounters.getIndexerDutyCycleTracker(configuration.indexerCycleThresholdNs()));
        }

        return replayer;
    }

    public void catchupIndices()
    {
        // when inbound logging disabled
        if (configuration.logInboundMessages())
        {
            inboundArchivingAgents.catchIndexUp(aeronArchive, errorHandler);
        }

        // when outbound logging disabled
        if (configuration.logOutboundMessages())
        {
            outboundArchivingAgents.catchIndexUp(aeronArchive, errorHandler);
        }
    }

    public Streams outboundLibraryStreams()
    {
        return outboundLibraryStreams;
    }

    // Each invocation should return a new instance of the subscription
    public Subscription outboundLibrarySubscription(
        final String name, final UnavailableImageHandler unavailableImageHandler)
    {
        final Subscription subscription = aeron.addSubscription(
            configuration.libraryAeronChannel(),
            configuration.outboundLibraryStream(),
            null,
            unavailableImageHandler);
        StreamInformation.print(name, subscription, configuration);
        return subscription;
    }

    public ReplayQuery inboundReplayQuery(final boolean replayerThread)
    {
        if (!configuration.canReplayInbound())
        {
            return null;
        }

        final ReplayQuery replayQuery = newReplayQuery(
            configuration.framerIdleStrategy(), configuration.inboundLibraryStream());
        if (replayerThread)
        {
            inboundEvictionHandler.replayQuery(replayQuery);
        }
        else
        {
            inboundEvictionHandler.framerReplayQuery(replayQuery);
        }
        return replayQuery;
    }

    public GatewayPublication inboundPublication()
    {
        return inboundLibraryStreams.gatewayPublication(
            configuration.framerIdleStrategy(), inboundLibraryStreams.dataPublication("inboundPublication"));
    }

    public CompletionPosition inboundCompletionPosition()
    {
        return inboundCompletionPosition;
    }

    public CompletionPosition outboundLibraryCompletionPosition()
    {
        return outboundLibraryCompletionPosition;
    }

    void completeDuringStartup()
    {
        inboundCompletionPosition.completeDuringStartup();
        outboundLibraryCompletionPosition.completeDuringStartup();
    }

    Agent indexingAgent()
    {
        return indexingAgent;
    }

    public SenderSequenceNumbers senderSequenceNumbers()
    {
        return senderSequenceNumbers;
    }

    public void framerContext(final FramerContext framerContext)
    {
        this.framerContext = framerContext;
        sentSequenceNumberIndex.framerContext(framerContext);
        inboundEvictionHandler.framerContext(framerContext);
        outboundEvictionHandler.framerContext(framerContext);
    }

    public Reply<Long2LongHashMap> pruneArchive(final Exception exception)
    {
        return new PruneOperation(pruneOperationFormatters, exception);
    }

    public Reply<Long2LongHashMap> pruneArchive(final Long2LongHashMap minimumPrunePositions)
    {
        if (pruneInboundReplayQuery == null)
        {
            pruneInboundReplayQuery = inboundReplayQuery(true);
        }

        final PruneOperation operation = new PruneOperation(
            pruneOperationFormatters,
            minimumPrunePositions,
            outboundReplayQuery,
            pruneInboundReplayQuery,
            aeronArchive,
            replayerCommandQueue,
            recordingCoordinator);

        if (!framerContext.offer(operation))
        {
            return null;
        }

        return operation;
    }

    public void close()
    {
        Exceptions.closeAll(
            sentSequenceNumberIndex, receivedSequenceNumberIndex, pruneInboundReplayQuery);
    }
}
