/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
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

import io.aeron.Aeron;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.EngineConfiguration;

import java.io.File;

import static uk.co.real_logic.artio.engine.logger.ReplayQuery.aggregateLowerPosition;

public final class ReplayIndexPositionScanner
{
    public static void main(final String[] args)
    {
        final Long2LongHashMap recordingIdToNewStartPosition = new Long2LongHashMap(Aeron.NULL_VALUE);

        final String logFilePath = args[0];
        printFiles(logFilePath, CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM, "inbound",
            recordingIdToNewStartPosition);
        printFiles(logFilePath, CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM, "outbound",
            recordingIdToNewStartPosition);

        System.out.println("Aggregated recordingIdToNewStartPosition = " + recordingIdToNewStartPosition);
    }

    private static void printFiles(
        final String logFilePath,
        final int streamId,
        final String direction,
        final Long2LongHashMap aggregateRecordingIdToNewStartPosition)
    {
        final File logFileDir = new File(logFilePath);
        System.out.println("Scanning: " + direction);

        final long[] maxPosition = new long[]{0};

        ReplayIndexDescriptor.listReplayIndexSessionIds(logFileDir, streamId)
            .stream()
            .sorted()
            .forEach(fixSessionId ->
            {
                final File headerFile = ReplayIndexDescriptor.replayIndexHeaderFile(
                    logFilePath, fixSessionId, streamId);
                final ReplayIndexExtractor.StartPositionExtractor positionExtractor =
                    new ReplayIndexExtractor.StartPositionExtractor();
                ReplayIndexExtractor.extract(
                    headerFile,
                    EngineConfiguration.DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
                    EngineConfiguration.DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY,
                    fixSessionId,
                    streamId,
                    logFileDir.getPath(),
                    positionExtractor);

                System.out.println("file = " + headerFile);
                System.out.println("positionExtractor.highestSequenceIndex() = " +
                    positionExtractor.highestSequenceIndex());
                final Long2ObjectHashMap<PrunePosition> recordingIdToStartPosition =
                    positionExtractor.recordingIdToStartPosition();
                System.out.println("positionExtractor.recordingIdToStartPosition() = " +
                    recordingIdToStartPosition);

                aggregateLowerPosition(recordingIdToStartPosition, aggregateRecordingIdToNewStartPosition);

                final ReplayIndexExtractor.BoundaryPositionExtractor boundaryPositionExtractor =
                    new ReplayIndexExtractor.BoundaryPositionExtractor(false);
                ReplayIndexExtractor.extract(
                    headerFile,
                    EngineConfiguration.DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
                    EngineConfiguration.DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY,
                    fixSessionId,
                    streamId,
                    logFileDir.getPath(),
                    boundaryPositionExtractor);
                final Long2LongHashMap recordingIdToMaxPosition = boundaryPositionExtractor.recordingIdToPosition();
                System.out.println("boundaryPositionExtractor = " + recordingIdToMaxPosition);

                final Long2LongHashMap.ValueIterator it = recordingIdToMaxPosition.values().iterator();
                if (it.hasNext())
                {
                    final long position = it.nextValue();
                    maxPosition[0] = Math.max(maxPosition[0], position);
                }

                boundaryPositionExtractor.findInconsistentSequenceIndexPositions();
            });

        System.out.println("maxPosition = " + maxPosition[0]);
        System.out.println("\n\n");
    }
}
