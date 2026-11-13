/*
 * Copyright 2021 Monotonic Ltd.
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

import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;

import java.io.File;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.co.real_logic.artio.engine.EngineConfiguration.*;

public class TimeIndexTest
{
    private static final int REC_ID = 1;
    private static final int REC_ID_2 = 2;
    private static final long GUARANTEED_FLUSH_IN_NS = DEFAULT_TIME_INDEX_FLUSH_INTERVAL_IN_NS + 1;

    private final ErrorHandler errorHandler = spy(new ErrorHandler()
    {
        @Override
        public void onError(final Throwable throwable)
        {
            throwable.printStackTrace();
        }
    });

    private final long[] timestampsInNs = LongStream
        .range(1, 6)
        .map(i -> i * GUARANTEED_FLUSH_IN_NS)
        .toArray();
    private final long[] positions = LongStream.range(1, 6).toArray();
    private final TimeIndexReader reader = new TimeIndexReader(DEFAULT_LOG_FILE_DIR, DEFAULT_OUTBOUND_LIBRARY_STREAM);
    private final IndexQuery query = new IndexQuery();

    private TimeIndexWriter writer;

    @AfterEach
    public void teardown()
    {
        verifyNoInteractions(errorHandler);
        Exceptions.closeAll(writer);
    }

    @BeforeEach
    public void setup()
    {
        final File logFileDir = new File(DEFAULT_LOG_FILE_DIR);
        if (logFileDir.exists())
        {
            IoUtil.delete(logFileDir, false);
        }
        assertTrue(logFileDir.mkdirs());

        newWriter();

        for (int i = 0; i < timestampsInNs.length; i++)
        {
            writer.onRecord(REC_ID, positions[i], timestampsInNs[i]);
            writer.onRecord(REC_ID_2, positions[i], timestampsInNs[i]);
            writer.doWork();
        }
    }

    private void newWriter()
    {
        writer = new TimeIndexWriter(
            DEFAULT_LOG_FILE_DIR,
            DEFAULT_OUTBOUND_LIBRARY_STREAM,
            DEFAULT_TIME_INDEX_FLUSH_INTERVAL_IN_NS,
            errorHandler);
    }

    @Test
    public void shouldReadWrittenTimestampsStartAndEnd()
    {
        query.from(timestampsInNs[2]);
        query.to(timestampsInNs[3]);

        shouldReadWrittenTimestamps(positions[1], positions[3]);
    }

    @Test
    public void shouldReloadTimestamps()
    {
        writer.close();
        newWriter();

        writer.onRecord(REC_ID, positions[4] + 1, timestampsInNs[4] + GUARANTEED_FLUSH_IN_NS);
        writer.doWork();

        query.from(timestampsInNs[2]);
        query.to(timestampsInNs[3]);

        shouldReadWrittenTimestamps(positions[1], positions[3]);
    }

    @Test
    public void shouldReadWrittenTimestampsStart()
    {
        query.to(timestampsInNs[3]);

        shouldReadWrittenTimestamps(0, positions[3]);
    }

    @Test
    public void shouldReadWrittenTimestampsEnd()
    {
        query.from(timestampsInNs[2]);

        shouldReadWrittenTimestamps(positions[1], Long.MAX_VALUE);
    }

    private void shouldReadWrittenTimestamps(final long startPosition, final long endPosition)
    {
        final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange = new Long2ObjectHashMap<>();
        reader.findPositionRange(query, recordingIdToPositionRange);

        assertEquals(2, recordingIdToPositionRange.size(), recordingIdToPositionRange.toString());

        assertPositions(startPosition, endPosition, recordingIdToPositionRange, REC_ID);
        assertPositions(startPosition, endPosition, recordingIdToPositionRange, REC_ID_2);
    }

    private void assertPositions(
        final long startPosition, final long endPosition,
        final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange, final int recId)
    {
        final PositionRange positionRange = recordingIdToPositionRange.get(recId);
        assertEquals(startPosition, positionRange.startPosition(), positionRange.toString());
        assertEquals(endPosition, positionRange.endPosition(), positionRange.toString());
    }
}
