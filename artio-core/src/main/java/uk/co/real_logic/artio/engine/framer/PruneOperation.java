/*
 * Copyright 2020 Adaptive Financial Consulting Ltd.
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

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.RecordingDescriptorConsumer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.RecordingCoordinator;
import uk.co.real_logic.artio.engine.ReplayerCommand;
import uk.co.real_logic.artio.engine.ReplayerCommandQueue;
import uk.co.real_logic.artio.engine.logger.ReplayQuery;
import uk.co.real_logic.artio.util.CharFormatter;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.archive.client.AeronArchive.segmentFileBasePosition;
import static uk.co.real_logic.artio.LogTag.STATE_CLEANUP;

/**
 * PruneOperation is sent to the replayer in order to find the outbound replay query positions.
 * Then it gets sent to the Framer to query the inbound replay positions, then it delegates to
 * aeron archiver to prune the archive
 */
public class PruneOperation
    implements ReplayerCommand, Reply<Long2LongHashMap>, RecordingDescriptorConsumer, AdminCommand
{

    public static final boolean STATE_CLEANUP_ENABLED = DebugLogger.isEnabled(STATE_CLEANUP);

    public static class Formatters
    {
        private final CharFormatter findingPositionsFormatter = new CharFormatter(
            "PruneOperation: allRecordingIds=%s,queried recordingIdToNewStartPosition=%s");
        private final CharFormatter foundPositionsFormatter = new CharFormatter(
            "PruneOperation: complete recordingIdToNewStartPosition=%s");
        private final CharFormatter filteredRecordingFormatter = new CharFormatter(
            "PruneOperation: filtered recordingId=%s,segmentStartPosition=%s,lowerBoundPrunePosition=%s" +
            ",segmentFileLength=%s,requestedNewStartPosition=%s,startPosition=%s");
        private final CharFormatter purgedSegmentsFormatter = new CharFormatter(
            "PruneOperation: purged recordingId=%s,oldStartPosition=%s,newStartPosition=%s,durationNs=%s");
    }

    private final Formatters formatters;
    private final ReplayQuery outboundReplayQuery;
    private final ReplayQuery inboundReplayQuery;
    private final Long2LongHashMap recordingIdToNewStartPosition = new Long2LongHashMap(Aeron.NULL_VALUE);
    private final Long2LongHashMap minimumPrunePositions;
    private final AeronArchive aeronArchive;
    private final ReplayerCommandQueue replayerCommandQueue;
    private final RecordingCoordinator recordingCoordinator;
    private final LongHashSet allRecordingIds = new LongHashSet();

    private volatile State replyState;

    private Long2LongHashMap result;
    private Exception error;

    private long requestedNewStartPosition;
    private long segmentStartPosition;
    private long lowerBoundPrunePosition;
    private int stashedSegmentFileLength;
    private long stashedStartPosition;

    public PruneOperation(final Formatters formatters, final Exception error)
    {
        this(formatters, null, null, null, null, null, null);

        this.error = error;
        replyState = State.ERRORED;
    }

    public PruneOperation(
        final Formatters formatters,
        final Long2LongHashMap minimumPrunePositions,
        final ReplayQuery outboundReplayQuery,
        final ReplayQuery inboundReplayQuery,
        final AeronArchive aeronArchive,
        final ReplayerCommandQueue replayerCommandQueue,
        final RecordingCoordinator recordingCoordinator)
    {
        this.formatters = formatters;
        this.outboundReplayQuery = outboundReplayQuery;
        this.inboundReplayQuery = inboundReplayQuery;
        this.minimumPrunePositions = minimumPrunePositions;
        this.aeronArchive = aeronArchive;
        this.replayerCommandQueue = replayerCommandQueue;
        this.recordingCoordinator = recordingCoordinator;
        replyState = State.EXECUTING;
    }

    @Override
    public Exception error()
    {
        return error;
    }

    @Override
    public Long2LongHashMap resultIfPresent()
    {
        return result;
    }

    @Override
    public State state()
    {
        return replyState;
    }

    // On Framer thread
    @Override
    public void execute(final Framer framer)
    {
        DebugLogger.log(STATE_CLEANUP, "PruneOperation: starting on Framer Thread");

        recordingCoordinator.forEachRecording((libraryId, recordingId) -> allRecordingIds.add(recordingId));

        // move over to the replayer thread
        if (!replayerCommandQueue.offer(this))
        {
            framer.schedule(() -> replayerCommandQueue.offer(this) ? Continuation.COMPLETE : BACK_PRESSURED);
        }
    }

    // On Replayer Thread
    @Override
    public void execute()
    {
        DebugLogger.log(STATE_CLEANUP, "PruneOperation: starting on Replayer Thread");

        inboundReplayQuery.queryStartPositions(recordingIdToNewStartPosition);
        outboundReplayQuery.queryStartPositions(recordingIdToNewStartPosition);

        findAllRecordingPositions();

        prune();
    }

    private void findAllRecordingPositions()
    {
        if (STATE_CLEANUP_ENABLED)
        {
            final CharFormatter formatter = formatters.findingPositionsFormatter
                .clear()
                .with(allRecordingIds.toString())
                .with(recordingIdToNewStartPosition.toString());
            DebugLogger.log(STATE_CLEANUP, formatter);
        }

        // newStartPositions currently contains the lowest start position for each replay index file.
        // if we have a recording with no replay index entries, for example if we've called
        // FixEngine.resetSequenceNumber for every session then those recording ids won't be in the map.

        final Long2LongHashMap.KeyIterator knownRecordingIds = recordingIdToNewStartPosition.keySet().iterator();
        while (knownRecordingIds.hasNext())
        {
            allRecordingIds.remove(knownRecordingIds.nextValue());
        }

        final LongHashSet.LongIterator it = allRecordingIds.iterator();
        while (it.hasNext())
        {
            final long recordingId = it.nextValue();
            final long recordingPosition = aeronArchive.getRecordingPosition(recordingId);
            if (recordingPosition != NULL_POSITION)
            {
                recordingIdToNewStartPosition.put(recordingId, recordingPosition);
            }
            else
            {
                final long stopPosition = aeronArchive.getStopPosition(recordingId);
                recordingIdToNewStartPosition.put(recordingId, stopPosition);
            }
        }

        if (STATE_CLEANUP_ENABLED)
        {
            final CharFormatter formatter = formatters.foundPositionsFormatter
                .clear()
                .with(recordingIdToNewStartPosition.toString());
            DebugLogger.log(STATE_CLEANUP, formatter);
        }
    }

    private void prune()
    {
        final Long2LongHashMap.EntryIterator it = recordingIdToNewStartPosition.entrySet().iterator();
        while (it.hasNext())
        {
            it.next();

            final long recordingId = it.getLongKey();
            long newStartPosition = it.getLongValue();
            if (minimumPrunePositions != null)
            {
                final long requestedMinimumPosition = minimumPrunePositions.get(recordingId);
                if (requestedMinimumPosition != Aeron.NULL_VALUE)
                {
                    newStartPosition = Math.min(newStartPosition, requestedMinimumPosition);
                }
            }

            try
            {
                requestedNewStartPosition = newStartPosition;
                listRecording(recordingId);

                final long segmentStartPosition = this.segmentStartPosition;
                // Don't prune if you're < a segment away from the start of the stream.
                if (segmentStartPosition < lowerBoundPrunePosition)
                {
                    if (STATE_CLEANUP_ENABLED)
                    {
                        formatters.filteredRecordingFormatter
                            .clear()
                            .with(recordingId)
                            .with(segmentStartPosition)
                            .with(lowerBoundPrunePosition)
                            .with(stashedSegmentFileLength)
                            .with(requestedNewStartPosition)
                            .with(stashedStartPosition);
                        DebugLogger.log(STATE_CLEANUP, formatters.filteredRecordingFormatter);
                    }

                    it.remove();
                }
                else
                {
                    final long start = System.nanoTime();
                    aeronArchive.purgeSegments(recordingId, segmentStartPosition);
                    if (STATE_CLEANUP_ENABLED)
                    {
                        final long durationNs = System.nanoTime() - start;
                        formatters.purgedSegmentsFormatter
                            .clear()
                            .with(recordingId)
                            .with(stashedStartPosition)
                            .with(segmentStartPosition)
                            .with(durationNs);
                        DebugLogger.log(STATE_CLEANUP, formatters.purgedSegmentsFormatter);
                    }
                    recordingIdToNewStartPosition.put(recordingId, segmentStartPosition);
                }
            }
            catch (final Exception e)
            {
                e.printStackTrace();
                onPruneError(e, it);
                return;
            }
        }
        result = recordingIdToNewStartPosition;
        replyState = State.COMPLETED;
    }

    private void listRecording(final long recordingId)
    {
        final int count = aeronArchive.listRecording(recordingId, this);
        if (count != 1)
        {
            throw new IllegalStateException("Unable to list the recording: " + recordingId);
        }
    }

    @Override
    public void onRecordingDescriptor(
        final long controlSessionId, final long correlationId, final long recordingId, final long startTimestamp,
        final long stopTimestamp, final long startPosition, final long stopPosition, final int initialTermId,
        final int segmentFileLength, final int termBufferLength, final int mtuLength, final int sessionId,
        final int streamId, final String strippedChannel, final String originalChannel, final String sourceIdentity)
    {
        segmentStartPosition = segmentFileBasePosition(
            startPosition, requestedNewStartPosition, termBufferLength, segmentFileLength);
        lowerBoundPrunePosition = segmentFileBasePosition(
            startPosition, startPosition, termBufferLength, segmentFileLength) + segmentFileLength;
        stashedSegmentFileLength = segmentFileLength;
        stashedStartPosition = startPosition;
    }

    private void onPruneError(final Exception e, final Long2LongHashMap.EntryIterator it)
    {
        it.remove();
        while (it.hasNext())
        {
            it.next();
            it.remove();
        }

        error = e;
        result = recordingIdToNewStartPosition;
        replyState = State.ERRORED;
    }

    @Override
    public String toString()
    {
        return "PruneOperation{" +
            "newStartPositions=" + recordingIdToNewStartPosition +
            ", minimumPrunePositions=" + minimumPrunePositions +
            ", replyState=" + replyState +
            ", result=" + result +
            ", error=" + error +
            '}';
    }
}
