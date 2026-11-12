/*
 * Copyright 2022 Monotonic Ltd.
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

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static uk.co.real_logic.artio.LogTag.ARCHIVE_SCAN;
import static uk.co.real_logic.artio.engine.logger.FixArchiveScanner.DEBUG_LOG_ARCHIVE_SCAN;

// Not part of public API, don't rely on API stability
public class FixArchiveScanningAgent implements AutoCloseable
{
    private final Aeron aeron;
    private final AeronArchive aeronArchive;
    private final IdleStrategy idleStrategy;
    private final int compactionSize;
    private final int maximumBufferSize;
    private final int fragmentLimit;
    private final String logFileDir;

    private final Long2ObjectHashMap<TimeIndexReader> streamIdToInboundTimeIndex = new Long2ObjectHashMap<>();

    private StreamTimestampZipper timestampZipper;
    private Subscription replaySubscription;
    private RecordingPoller[] pollers;
    private boolean follow;

    public FixArchiveScanningAgent(
        final IdleStrategy idleStrategy,
        final int compactionSize,
        final int maximumBufferSize,
        final int fragmentLimit,
        final String logFileDir,
        final Aeron aeron,
        final AeronArchive aeronArchive)
    {
        this.idleStrategy = idleStrategy;
        this.compactionSize = compactionSize;
        this.maximumBufferSize = maximumBufferSize;
        this.fragmentLimit = fragmentLimit;
        this.aeron = aeron;
        this.aeronArchive = aeronArchive;
        this.logFileDir = logFileDir;
    }

    public void setup(
        final String aeronChannel,
        final IntHashSet queryStreamIds,
        final FixMessageConsumer fixHandler,
        final FixPMessageConsumer fixPHandler,
        final boolean follow,
        final int archiveScannerStreamId)
    {
        if (fixHandler != null)
        {
            fixHandler.reset();
        }

        final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange =
            scanIndexIfPossible(fixHandler, follow, queryStreamIds);

        this.follow = follow;
        replaySubscription = aeron.addSubscription(IPC_CHANNEL, archiveScannerStreamId);
        pollers = makeRecordingPollers(
            aeronChannel, queryStreamIds, follow, recordingIdToPositionRange, replaySubscription);

        if (DEBUG_LOG_ARCHIVE_SCAN)
        {
            DebugLogger.log(ARCHIVE_SCAN, "Pollers: %s", pollers);
        }

        timestampZipper = new StreamTimestampZipper(
            fixHandler, fixPHandler, compactionSize, maximumBufferSize, !follow, pollers);
    }

    public boolean poll(final int fragmentLimit)
    {
        final int received = timestampZipper.poll(fragmentLimit);

        // Don't need to do this check in follow mode as we're just going to keep running and not terminate.
        // codeql[java/constant-loop-condition]
        if (0 == received && !follow)
        {
            if (checkCompletion(pollers))
            {
                Exceptions.closeAll(timestampZipper, replaySubscription);

                return true;
            }
        }

        return false;
    }

    private RecordingPoller[] makeRecordingPollers(
        final String aeronChannel,
        final IntHashSet queryStreamIds,
        final boolean follow,
        final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange,
        final Subscription replaySubscription)
    {
        return queryStreamIds
            .stream()
            .flatMap(id ->
                lookupArchiveLocations(
                    id, follow, aeronChannel, recordingIdToPositionRange)
                    .stream()
                    // filter out empty streams as
                    // 1) they are empty - we don't need to poll them
                    // 2) we use empty length later within polling as a test for polling being finished
                    .filter(archiveLocation -> archiveLocation.length() != 0L)
                    .map(archiveLocation -> new RecordingPoller(replaySubscription, id, archiveLocation)))
            .toArray(RecordingPoller[]::new);
    }

    private Long2ObjectHashMap<PositionRange> scanIndexIfPossible(
        final FixMessageConsumer fixHandler, final boolean follow, final IntHashSet queryStreamIds)
    {
        if (DEBUG_LOG_ARCHIVE_SCAN)
        {
            DebugLogger.log(ARCHIVE_SCAN,
                "checking index,follow=" +
                follow +
                ",logFileDir=" + logFileDir +
                ",queryStreamIds=" + queryStreamIds);
        }

        // Don't support scan + continuous update query for now
        if (follow)
        {
            return null;
        }

        // need to know index location to do a scan
        if (logFileDir == null)
        {
            return null;
        }

        try
        {
            final IndexQuery indexQuery = ArchiveScanPlanner.extractIndexQuery(fixHandler);
            if (DEBUG_LOG_ARCHIVE_SCAN)
            {
                DebugLogger.log(ARCHIVE_SCAN, "indexQuery = " + indexQuery);
            }

            if (indexQuery == null)
            {
                return null;
            }

            final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange = new Long2ObjectHashMap<>();
            for (final int streamId : queryStreamIds)
            {
                TimeIndexReader reader = streamIdToInboundTimeIndex.get(streamId);
                if (reader == null)
                {
                    reader = new TimeIndexReader(logFileDir, streamId);
                    streamIdToInboundTimeIndex.put(streamId, reader);
                }

                if (!reader.findPositionRange(indexQuery, recordingIdToPositionRange))
                {
                    return null;
                }
            }

            if (DEBUG_LOG_ARCHIVE_SCAN)
            {
                DebugLogger.log(ARCHIVE_SCAN, "recordingIdToPositionRange = " + recordingIdToPositionRange);
            }

            return recordingIdToPositionRange;
        }
        catch (final IllegalArgumentException e)
        {
            // Unable to create query plan
            return null;
        }
    }

    private boolean checkCompletion(final RecordingPoller[] pollers)
    {
        for (final RecordingPoller poller : pollers)
        {
            if (!poller.isComplete())
            {
                return false;
            }
        }

        return true;
    }

    @Override
    public void close()
    {
        aeronArchive.close();
    }

    private List<ArchiveLocation> lookupArchiveLocations(
        final int queryStreamId,
        final boolean follow,
        final String aeronChannel,
        final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange)
    {
        final List<ArchiveLocation> archiveLocations = new ArrayList<>();

        aeronArchive.listRecordings(0,
            Integer.MAX_VALUE,
            (controlSessionId,
            correlationId, recordingId, startTimestamp, stopTimestamp, startPosition, stopPosition,
            initialTermId, segmentFileLength, termBufferLength, mtuLength, sessionId, streamId,
            strippedChannel, originalChannel, sourceIdentity) ->
            {
                final ChannelUri uri = ChannelUri.parse(strippedChannel);
                uri.remove(CommonContext.SESSION_ID_PARAM_NAME);
                final String comparableChannel = uri.toString();

                if (streamId == queryStreamId && comparableChannel.contains(aeronChannel))
                {
                    archiveLocations.add(new ArchiveLocation(recordingId, startPosition, stopPosition));
                }
            });

        if (!follow)
        {
            for (final ArchiveLocation location : archiveLocations)
            {
                if (location.stopPosition == NULL_POSITION)
                {
                    location.stopPosition = aeronArchive.getRecordingPosition(location.recordingId);
                }
            }
        }

        // try to narrow down the scan range using the index
        if (recordingIdToPositionRange != null)
        {
            final Iterator<ArchiveLocation> iterator = archiveLocations.iterator();
            while (iterator.hasNext())
            {
                final ArchiveLocation location = iterator.next();

                final PositionRange positionRange = recordingIdToPositionRange.get(location.recordingId);
                if (positionRange == null)
                {
                    iterator.remove();
                }
                else
                {
                    final long startPosition = positionRange.startPosition();
                    final long endPosition = positionRange.endPosition();

                    if (location.stopPosition > endPosition)
                    {
                        location.stopPosition = endPosition;
                    }

                    if (location.startPosition < startPosition)
                    {
                        location.startPosition = startPosition;
                    }
                }
            }
        }

        return archiveLocations;
    }

    static class ArchiveLocation
    {
        final long recordingId;

        long startPosition;
        long stopPosition;

        ArchiveLocation(
            final long recordingId, final long startPosition, final long stopPosition)
        {
            this.recordingId = recordingId;
            this.startPosition = startPosition;
            this.stopPosition = stopPosition;
        }

        public long stopPosition()
        {
            return stopPosition;
        }

        public long length()
        {
            return startPosition == NULL_POSITION ? NULL_POSITION : stopPosition - startPosition;
        }

        @Override
        public String toString()
        {
            return "ArchiveLocation{" +
                "recordingId=" + recordingId +
                ", startPosition=" + startPosition +
                ", stopPosition=" + stopPosition +
                '}';
        }
    }

    class RecordingPoller implements StreamTimestampZipper.Poller
    {
        private final Subscription replaySubscription;
        private final int originalStreamId;

        private ArchiveLocation archiveLocation;

        long stopPosition;
        Image image;

        RecordingPoller(
            final Subscription replaySubscription,
            final int originalStreamId,
            final ArchiveLocation archiveLocation)
        {
            this.replaySubscription = replaySubscription;
            this.originalStreamId = originalStreamId;
            this.archiveLocation = archiveLocation;
        }

        @Override
        public boolean isComplete()
        {
            return stopPosition != NULL_POSITION && image == null && archiveLocation == null;
        }

        @Override
        public int poll(final FragmentAssembler fragmentAssembler, final int fragmentLimit)
        {
            if (image == null)
            {
                if (archiveLocation == null)
                {
                    return 0;
                }

                if (archiveLocation.length() != 0)
                {
                    final int sessionId = (int)aeronArchive.startReplay(
                        archiveLocation.recordingId,
                        archiveLocation.startPosition,
                        archiveLocation.length(),
                        IPC_CHANNEL,
                        replaySubscription.streamId());

                    image = lookupImage(sessionId);
                    stopPosition = archiveLocation.stopPosition;
                    archiveLocation = null;
                }

                return 1;
            }
            else
            {
                if (stopPosition != NULL_POSITION && image.position() >= stopPosition)
                {
                    image = null;
                    return 1;
                }
                else
                {
                    return image.poll(fragmentAssembler, FixArchiveScanningAgent.this.fragmentLimit);
                }
            }
        }

        @Override
        public int streamId()
        {
            return originalStreamId;
        }

        private Image lookupImage(final int sessionId)
        {
            Image image = null;

            while (image == null)
            {
                idleStrategy.idle();
                image = replaySubscription.imageBySessionId(sessionId);
            }
            idleStrategy.reset();

            return image;
        }

        @Override
        public void close()
        {
            // don't own replay subscription so no need to close it.
        }

        @Override
        public String toString()
        {
            return "RecordingPoller{" +
                "archiveLocations=" + archiveLocation +
                ", replaySubscription=" + replaySubscription +
                ", originalStreamId=" + originalStreamId +
                ", stopPosition=" + stopPosition +
                ", image=" + image +
                '}';
        }
    }
}
