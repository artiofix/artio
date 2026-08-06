/*
 * Copyright 2023 Adaptive Financial Consulting Limited.
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
import io.aeron.CommonContext;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongArrayList;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.session.Session;

import java.io.File;
import java.nio.ByteBuffer;

import static io.aeron.Aeron.NULL_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.TestFixtures.aeronArchiveContext;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.engine.EngineConfiguration.*;
import static uk.co.real_logic.artio.engine.logger.Replayer.MOST_RECENT_MESSAGE;

/**
 * Similar to {@link ReplayIndexTest}, but with small capacity for easier and faster testing of wrapping and lapping.
 */
public class SmallReplayIndexTest extends AbstractLogTest implements ReplayQueryListener
{
    private static final String CHANNEL = CommonContext.IPC_CHANNEL;

    private static final int INDEX_CAPACITY = 8;
    private static final int INDEX_SEGMENT_CAPACITY = 4;

    private final ExistingBufferFactory existingBufferFactory = spy(new ExistingBufferFactory()
    {
        public ByteBuffer map(final File fileName)
        {
            return LoggerUtil.mapExistingFile(fileName);
        }
    });
    private final BufferFactory newBufferFactory = spy(new BufferFactory()
    {
        public ByteBuffer map(final File file, final int size)
        {
            return LoggerUtil.map(file, size);
        }
    });

    private ReplayIndex replayIndex;

    private final UnsafeBuffer replayPositionBuffer =
        new UnsafeBuffer(new byte[EngineConfiguration.DEFAULT_REPLAY_POSITION_BUFFER_SIZE]);

    private final FakeMessageHandler fakeHandler = new FakeMessageHandler();
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);

    private ArchivingMediaDriver mediaDriver;
    private AeronArchive aeronArchive;

    private ExclusivePublication publication;
    private Subscription subscription;
    private RecordingIdLookup recordingIdLookup;

    private void newReplayIndex()
    {
        replayIndex = new ReplayIndex(
            new SequenceNumberExtractor(),
            DEFAULT_LOG_FILE_DIR,
            STREAM_ID,
            INDEX_CAPACITY,
            INDEX_SEGMENT_CAPACITY,
            newBufferFactory,
            replayPositionBuffer,
            errorHandler,
            recordingIdLookup,
            new Long2LongHashMap(Session.UNKNOWN),
            FixPProtocolType.ILINK_3,
            mock(SequenceNumberIndexReader.class),
            DEFAULT_TIME_INDEX_FLUSH_INTERVAL_IN_NS,
            DEFAULT_INDEX_CHECKSUM_ENABLED,
            new ReplayEvictionHandler(errorHandler));
    }

    private Aeron aeron()
    {
        return aeronArchive.context().aeron();
    }

    private ReplayQuery query;

    private int seqNum;
    private Runnable onEndChangeRead;
    private int endChangeReadCount;
    private int lappedCount;

    @BeforeEach
    public void setUp()
    {
        mediaDriver = TestFixtures.launchMediaDriver();
        aeronArchive = AeronArchive.connect(aeronArchiveContext());

        recordingIdLookup =
            new RecordingIdLookup(aeronArchive.archiveId(), new YieldingIdleStrategy(), aeron().countersReader());

        aeronArchive.startRecording(CHANNEL, STREAM_ID, SourceLocation.LOCAL);

        final Aeron aeron = aeron();
        publication = aeron.addExclusivePublication(CHANNEL, STREAM_ID);
        subscription = aeron.addSubscription(CHANNEL, STREAM_ID);

        final File logFileDir = new File(DEFAULT_LOG_FILE_DIR);
        IoUtil.delete(logFileDir, false);

        newReplayIndex();
        query = new ReplayQuery(
            DEFAULT_LOG_FILE_DIR,
            DEFAULT_LOGGER_CACHE_NUM_SETS,
            DEFAULT_LOGGER_CACHE_SET_SIZE,
            existingBufferFactory,
            DEFAULT_OUTBOUND_LIBRARY_STREAM,
            new NoOpIdleStrategy(),
            aeronArchive,
            errorHandler,
            this,
            DEFAULT_ARCHIVE_REPLAY_STREAM,
            INDEX_CAPACITY,
            INDEX_SEGMENT_CAPACITY);
    }

    @AfterEach
    public void teardown()
    {
        Exceptions.closeAll(query, replayIndex, aeronArchive);
        cleanupMediaDriver(mediaDriver);
    }

    @Test
    @Timeout(20_000L)
    public void testQueryingAtEveryRingBufferPositionBeforeAndAfterWrapping()
    {
        final LongArrayList positions = new LongArrayList();

        for (int i = 1; i <= INDEX_CAPACITY * 2 + 1; i++)
        {
            final long position = indexExampleMessage(SESSION_ID, i, SEQUENCE_INDEX);
            positions.addLong(position);

            final int expectedCount = Math.min(i, INDEX_CAPACITY);
            fakeHandler.reset();
            assertEquals(expectedCount, query(1, SEQUENCE_INDEX, i, SEQUENCE_INDEX));
            assertEquals(expectedCount, query(1, SEQUENCE_INDEX, MOST_RECENT_MESSAGE, SEQUENCE_INDEX));
            verifyMessagesRead(expectedCount * 2);

            final long expectedStartPosition = positions.getLong(Math.max(i - INDEX_CAPACITY, 0));
            assertEquals(expectedStartPosition, queryStartPosition());
        }

        assertEquals(0, lappedCount);
    }

    @Test
    public void testConcurrentNotLappingWrites()
    {
        indexNextMessage();

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                indexNextMessage();
            }
            else if (endChangeReadCount == 3)
            {
                indexNextMessage();
                indexNextMessage();
            }
        };

        final int result = query(1, SEQUENCE_INDEX, MOST_RECENT_MESSAGE, SEQUENCE_INDEX);
        assertEquals(4, result);
        assertEquals(0, lappedCount);
        verifyMessagesRead(4);
    }

    @Test
    public void testConcurrentNotLappingWriteOfMaxRecords()
    {
        indexNextMessage();

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                for (int i = 0; i < INDEX_CAPACITY - 1; i++)
                {
                    indexNextMessage();
                }
            }
        };

        final int result = query(1, SEQUENCE_INDEX, MOST_RECENT_MESSAGE, SEQUENCE_INDEX);
        assertEquals(INDEX_CAPACITY, result);
        assertEquals(0, lappedCount);
        verifyMessagesRead(INDEX_CAPACITY);
    }

    @Test
    @Timeout(20_000L)
    public void testLappingQueryStartedBeforeWrapping()
    {
        for (int i = 0; i < INDEX_CAPACITY - 1; i++)
        {
            indexNextMessage();
        }

        final int lastSeqNum = seqNum;

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                indexNextMessage();
                indexNextMessage();
            }
        };

        final int lappedResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        final int stableResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        assertEquals(stableResult, lappedResult);
        assertEquals(INDEX_CAPACITY - 2, lappedResult);
        assertEquals(1, lappedCount);
        verifyMessagesRead((INDEX_CAPACITY - 2) * 2);
    }

    @Test
    @Timeout(20_000L)
    public void testLappingQueryStartedAfterWrapping()
    {
        for (int i = 0; i < INDEX_CAPACITY; i++)
        {
            indexNextMessage();
        }

        final int lastSeqNum = seqNum;

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                indexNextMessage();
            }
        };

        final int lappedResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        final int stableResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        assertEquals(stableResult, lappedResult);
        assertEquals(INDEX_CAPACITY - 1, lappedResult);
        assertEquals(1, lappedCount);
        verifyMessagesRead((INDEX_CAPACITY - 1) * 2);
    }

    @Test
    @Timeout(20_000L)
    public void testLappingMoreThanCapacity()
    {
        for (int i = 0; i < INDEX_CAPACITY; i++)
        {
            indexNextMessage();
        }

        final int lastSeqNum = seqNum;

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                for (int i = 0; i < INDEX_CAPACITY + 2; i++)
                {
                    indexNextMessage();
                }
            }
        };

        final int lappedResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        final int stableResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        assertEquals(stableResult, lappedResult);
        assertEquals(0, lappedResult);
        assertEquals(1, lappedCount);
        verifyNoMessageRead();
    }

    @Test
    @Timeout(20_000L)
    public void testGettingLappedTwice()
    {
        for (int i = 0; i < INDEX_CAPACITY; i++)
        {
            indexNextMessage();
        }

        final int lastSeqNum = seqNum;

        onEndChangeRead = () ->
        {
            if (endChangeReadCount == 1)
            {
                indexNextMessage();
            }
            else if (endChangeReadCount == 2)
            {
                for (int i = 0; i < INDEX_CAPACITY + 1; i++)
                {
                    indexNextMessage();
                }
            }
        };

        final int lappedResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        final int stableResult = query(1, SEQUENCE_INDEX, lastSeqNum, SEQUENCE_INDEX);
        assertEquals(stableResult, lappedResult);
        assertEquals(0, lappedResult);
        assertEquals(2, lappedCount);
        verifyNoMessageRead();
    }

    private long queryStartPosition()
    {
        final Long2LongHashMap startPositions = new Long2LongHashMap(NULL_VALUE);
        query.queryStartPositions(startPositions);
        return startPositions.get(0);
    }

    private void indexNextMessage()
    {
        final int sequenceNumber = ++seqNum;
        indexExampleMessage(SESSION_ID, sequenceNumber, SEQUENCE_INDEX);
    }

    private void verifyNoMessageRead()
    {
        verifyMessagesRead(0);
    }

    private void verifyMessagesRead(final int number)
    {
        assertEquals(fakeHandler.times(), number);
    }

    private void indexRecord()
    {
        indexRecord(1);
    }

    private void indexRecord(final int fragmentsToRead)
    {
        int read = 0;
        while (read < fragmentsToRead)
        {
            final int count = subscription.poll(replayIndex, 1);
            if (0 == count)
            {
                Thread.yield();
            }
            read += count;
        }
    }

    private long indexExampleMessage(final long sessionId, final int sequenceNumber, final int sequenceIndex)
    {
        return indexExampleMessage(sessionId, sequenceNumber, sequenceIndex, publication);
    }

    private long indexExampleMessage(
        final long sessionId,
        final int sequenceNumber,
        final int sequenceIndex,
        final ExclusivePublication publication)
    {
        bufferContainsExampleMessage(true, sessionId, sequenceNumber, sequenceIndex);

        final long position = publishBuffer(publication);

        indexRecord();

        return position - alignedEndPosition();
    }

    private long publishBuffer(final ExclusivePublication publication)
    {
        long position;
        while ((position = publication.offer(buffer, START, logEntryLength + PREFIX_LENGTH)) <= 0)
        {
            Thread.yield();
        }
        return position;
    }

    private int query(
        final int beginSequenceNumber,
        final int beginSequenceIndex,
        final int endSequenceNumber,
        final int endSequenceIndex)
    {
        return query(SESSION_ID, beginSequenceNumber, beginSequenceIndex, endSequenceNumber, endSequenceIndex);
    }

    private int query(
        final long sessionId,
        final int beginSequenceNumber,
        final int beginSequenceIndex,
        final int endSequenceNumber,
        final int endSequenceIndex)
    {
        final ReplayOperation operation = query.query(
            sessionId,
            beginSequenceNumber,
            beginSequenceIndex,
            endSequenceNumber,
            endSequenceIndex,
            REPLAY,
            new FixMessageTracker(REPLAY, fakeHandler, sessionId));

        final IdleStrategy idleStrategy = CommonConfiguration.backoffIdleStrategy();
        while (!operation.pollReplay())
        {
            idleStrategy.idle();
        }
        idleStrategy.reset();

        return operation.replayedMessages();
    }

    public void onEndChangeRead()
    {
        endChangeReadCount++;

        if (onEndChangeRead != null)
        {
            onEndChangeRead.run();
        }
    }

    public void onLapped()
    {
        lappedCount++;
    }

    static class FakeMessageHandler implements ControlledFragmentHandler
    {
        LongHashSet positions = new LongHashSet();
        int times = 0;

        public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            positions.add(header.position());
            times++;
            return Action.CONTINUE;
        }

        public int times()
        {
            return times;
        }

        public void reset()
        {
            positions.clear();
            times = 0;
        }
    }
}
