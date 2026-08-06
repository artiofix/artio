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
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.ResetSequenceNumberEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.stream.IntStream;

import static io.aeron.Aeron.NULL_VALUE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS;
import static uk.co.real_logic.artio.LogTag.REPLAY;
import static uk.co.real_logic.artio.TestFixtures.aeronArchiveContext;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.largeTestReqId;
import static uk.co.real_logic.artio.engine.EngineConfiguration.*;
import static uk.co.real_logic.artio.engine.logger.Replayer.MOST_RECENT_MESSAGE;

public class ReplayIndexTest extends AbstractLogTest
{
    private static final String CHANNEL = CommonContext.IPC_CHANNEL;

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
    private final IndexedPositionConsumer positionConsumer = mock(IndexedPositionConsumer.class);
    private final IndexedPositionReader positionReader = new IndexedPositionReader(replayPositionBuffer);

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
            DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
            DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY,
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
            NoOpReplayQueryListener.INSTANCE,
            DEFAULT_ARCHIVE_REPLAY_STREAM,
            DEFAULT_REPLAY_INDEX_RECORD_CAPACITY,
            DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY);
    }

    @AfterEach
    public void teardown()
    {
        Exceptions.closeAll(query, replayIndex, aeronArchive);
        cleanupMediaDriver(mediaDriver);
    }

    @Test
    @Timeout(20_000L)
    public void shouldReturnRecordsMatchingQuery()
    {
        indexExampleMessage();

        final int msgCount = query();

        verifyMappedFile(SESSION_ID, 1);
        verifyMessagesRead(1);
        assertEquals(1, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldReturnLongRecordsMatchingQuery()
    {
        final String testReqId = largeTestReqId();

        bufferContainsExampleMessage(true, SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX, testReqId);
        publishBuffer(publication);
        indexRecord(11);

        final int msgCount = query();

        verifyMappedFile(SESSION_ID, 1);
        verifyMessagesRead(1);
        assertEquals(1, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldReadSecondRecord()
    {
        indexExampleMessage();

        final int endSequenceNumber = SEQUENCE_NUMBER + 1;
        indexExampleMessage(SESSION_ID, endSequenceNumber, SEQUENCE_INDEX);

        final int msgCount = query(SEQUENCE_NUMBER, SEQUENCE_INDEX, endSequenceNumber, SEQUENCE_INDEX);

        verifyMessagesRead(2);
        assertEquals(2, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldReadRecordsFromBeforeARestart()
    {
        indexExampleMessage();

        replayIndex.close();

        newReplayIndex();

        final int msgCount = query();

        verifyMappedFile(SESSION_ID, 1);
        verifyMessagesRead(1);
        assertEquals(1, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldReturnAllLogEntriesWhenMostResentMessageRequested()
    {
        indexExampleMessage();

        final int msgCount = query(SEQUENCE_NUMBER, SEQUENCE_INDEX, MOST_RECENT_MESSAGE, SEQUENCE_INDEX);

        verifyMappedFile(SESSION_ID, 1);
        verifyMessagesRead(1);
        assertEquals(1, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldNotReturnLogEntriesWithOtherSessionId()
    {
        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX);
        indexExampleMessage(SESSION_ID_2, SEQUENCE_NUMBER, SEQUENCE_INDEX);
        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER + 1, SEQUENCE_INDEX);

        final int msgCount = query(SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX, SEQUENCE_NUMBER + 1, SEQUENCE_INDEX);

        assertEquals(2, msgCount);
        verifyMappedFile(SESSION_ID, 1);
        verifyMessagesRead(2);
    }

    @Test
    @Timeout(20_000L)
    public void shouldNotReturnLogEntriesWithOutOfRangeSequenceNumbers()
    {
        indexExampleMessage();

        final int msgCount = query(SESSION_ID, 1001, SEQUENCE_INDEX, 1002, SEQUENCE_INDEX);

        assertEquals(0, msgCount);
        verifyNoMessageRead();
    }

    @Test
    @Timeout(20_000L)
    public void shouldQueryOverSequenceIndexBoundaries()
    {
        indexExampleMessage();

        final int nextSequenceIndex = SEQUENCE_INDEX + 1;
        final int endSequenceNumber = 1;

        indexExampleMessage(SESSION_ID, endSequenceNumber, nextSequenceIndex);

        final int msgCount = query(SEQUENCE_NUMBER, SEQUENCE_INDEX, endSequenceNumber, nextSequenceIndex);

        verifyMessagesRead(2);
        assertEquals(2, msgCount);
    }

    @Test
    @Timeout(20_000L)
    public void shouldNotStopIndexingWhenBufferFull()
    {
        final int totalMessages = DEFAULT_REPLAY_INDEX_RECORD_CAPACITY;
        final int beginSequenceNumber = totalMessages / 2;
        final int endSequenceNumber = totalMessages + 1;
        // +1 because these are inclusive
        final int expectedMessages = endSequenceNumber - beginSequenceNumber + 1;

        IntStream.rangeClosed(1, endSequenceNumber).forEach(
            (seqNum) -> indexExampleMessage(SESSION_ID, seqNum, SEQUENCE_INDEX));

        final int msgCount = query(beginSequenceNumber, SEQUENCE_INDEX, endSequenceNumber, SEQUENCE_INDEX);

        assertEquals(expectedMessages, msgCount);
        verifyMessagesRead(expectedMessages);
    }

    @Test
    @Timeout(20_000L)
    public void shouldBeQueryableWhenLastSegmentIsFull()
    {
        final int segmentCapacity = DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY;

        IntStream.rangeClosed(1, segmentCapacity)
            .forEach(seqNum -> indexExampleMessage(SESSION_ID, seqNum, SEQUENCE_INDEX));

        final int msgCount = query(1, SEQUENCE_INDEX, MOST_RECENT_MESSAGE, SEQUENCE_INDEX);

        assertEquals(segmentCapacity, msgCount);
        verifyMessagesRead(segmentCapacity);

        final Long2LongHashMap startPositions = new Long2LongHashMap(NULL_VALUE);
        query.queryStartPositions(startPositions);
        assertThat(startPositions, hasEntry(0L, 0L));
    }

    @Test
    @Timeout(20_000L)
    public void shouldUpdatePositionForIndexedRecord()
    {
        indexExampleMessage();

        positionReader.readLastPosition(positionConsumer);

        final int aeronSessionId = publication.sessionId();
        final long recordingId = recordingIdLookup.getRecordingId(aeronSessionId);

        verify(positionConsumer, Mockito.times(1))
            .accept(aeronSessionId, recordingId, alignedEndPosition());
    }

    @Test
    @Timeout(20_000L)
    public void shouldOnlyMapSessionFileOnce()
    {
        indexExampleMessage();

        indexExampleMessage();

        verifyMappedFile(SESSION_ID);
    }

    @Test
@Timeout(20_000L)
    public void shouldRecordIndexesForMultipleSessions()
    {
        indexExampleMessage();

        indexExampleMessage(SESSION_ID_2, SEQUENCE_NUMBER, SEQUENCE_INDEX);

        verifyMappedFile(SESSION_ID);
        verifyMappedFile(SESSION_ID_2);
    }

    @Test
    @Timeout(20_000L)
    public void shouldQueryStartPositions()
    {
        final ExclusivePublication otherPublication = aeron().addExclusivePublication(CHANNEL, STREAM_ID);
        final GatewayPublication gatewayPublication = newGatewayPublication(otherPublication);

        final int newSequenceIndex = SEQUENCE_INDEX + 1;
        final int newSequenceNumber = SEQUENCE_NUMBER + 1;

        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX);
        indexExampleMessage(SESSION_ID, newSequenceNumber, SEQUENCE_INDEX);

        final long prunePosition = indexExampleMessage(SESSION_ID_2, SEQUENCE_NUMBER, SEQUENCE_INDEX);

        indexExampleMessage(SESSION_ID_2, newSequenceNumber, SEQUENCE_INDEX);

        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER, newSequenceIndex, otherPublication);
        assertThat(gatewayPublication.saveResetSequenceNumber(SESSION_ID), greaterThan(0L));
        indexRecord();
        final long otherPrunePosition = indexExampleMessage(
            SESSION_ID, SEQUENCE_NUMBER, newSequenceIndex, otherPublication);

        final Long2LongHashMap startPositions = new Long2LongHashMap(NULL_VALUE);
        query.queryStartPositions(startPositions);

        captureRecordingIds();

        assertThat(startPositions, aMapWithSize(2));
        assertEquals(prunePosition, startPositions.get(recordingId));
        assertEquals(otherPrunePosition, startPositions.get(otherRecordingId));
    }

    @Test
    @Timeout(20_000L)
    public void shouldQueryStartPositionsInPresenceOfDuplicateSequenceIndices()
    {
        final int newSequenceIndex = SEQUENCE_INDEX + 1;

        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX);
        indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER + 1, SEQUENCE_INDEX);

        final long position = indexExampleMessage(SESSION_ID, 0, newSequenceIndex);
        indexExampleMessage(SESSION_ID, 1, newSequenceIndex);
        final long position2 = indexExampleMessage(SESSION_ID, 0, newSequenceIndex);
        indexExampleMessage(SESSION_ID, 1, newSequenceIndex);
        final long position3 = indexExampleMessage(SESSION_ID, 0, newSequenceIndex);
        indexExampleMessage(SESSION_ID, 1, newSequenceIndex);
        assertThat(position2, greaterThan(position));
        assertThat(position3, greaterThan(position2));

        final Long2LongHashMap startPositions = new Long2LongHashMap(NULL_VALUE);
        query.queryStartPositions(startPositions);

        captureRecordingId();

        assertEquals(position3, startPositions.get(recordingId));
    }

    @Test
    @Timeout(20_000L)
    public void shouldDeleteIndexSegmentsOnSequenceResetWhenNoSegmentsHaveBeenTouched()
    {
        int seqNo = 1;
        for (int i = 0; i < DEFAULT_REPLAY_INDEX_RECORD_CAPACITY; i++)
        {
            indexExampleMessage(SESSION_ID, seqNo++, SEQUENCE_INDEX);
        }

        assertTrue(logFile(SESSION_ID).exists());

        assertTrue(segmentFile(SESSION_ID, 0).exists());
        assertTrue(segmentFile(SESSION_ID, 1).exists());
        assertTrue(segmentFile(SESSION_ID, 2).exists());
        assertTrue(segmentFile(SESSION_ID, 3).exists());

        assertFalse(segmentFile(SESSION_ID, 4).exists());

        replayIndex.close();

        newReplayIndex();

        resetSequenceNumber();

        assertFalse(logFile(SESSION_ID).exists());

        assertFalse(segmentFile(SESSION_ID, 0).exists());
        assertFalse(segmentFile(SESSION_ID, 1).exists());
        assertFalse(segmentFile(SESSION_ID, 2).exists());
        assertFalse(segmentFile(SESSION_ID, 3).exists());
    }

    @Test
    @Timeout(20_000L)
    public void shouldDeleteIndexSegmentsOnSequenceResetWhenASegmentHasBeenTouched()
    {
        int seqNo = 1;
        for (int i = 0; i < DEFAULT_REPLAY_INDEX_RECORD_CAPACITY; i++)
        {
            indexExampleMessage(SESSION_ID, seqNo++, SEQUENCE_INDEX);
        }

        assertTrue(logFile(SESSION_ID).exists());

        assertTrue(segmentFile(SESSION_ID, 0).exists());
        assertTrue(segmentFile(SESSION_ID, 1).exists());
        assertTrue(segmentFile(SESSION_ID, 2).exists());
        assertTrue(segmentFile(SESSION_ID, 3).exists());

        assertFalse(segmentFile(SESSION_ID, 4).exists());

        replayIndex.close();

        newReplayIndex();

        indexExampleMessage(SESSION_ID, seqNo++, SEQUENCE_INDEX);

        resetSequenceNumber();

        assertFalse(logFile(SESSION_ID).exists());

        assertFalse(segmentFile(SESSION_ID, 0).exists());
        assertFalse(segmentFile(SESSION_ID, 1).exists());
        assertFalse(segmentFile(SESSION_ID, 2).exists());
        assertFalse(segmentFile(SESSION_ID, 3).exists());
    }

    private void resetSequenceNumber()
    {
        final int offset = START;

        final ResetSequenceNumberEncoder resetEncoder = new ResetSequenceNumberEncoder();
        resetEncoder
            .wrapAndApplyHeader(buffer, offset, header)
            .session(SESSION_ID);

        final int limit = resetEncoder.limit();
        final int length = limit - offset;

        while (publication.offer(buffer, offset, length) <= 0)
        {
            Thread.yield();
        }

        indexRecord();
    }

    private void captureRecordingIds()
    {
        final int recordingCount = captureRecordingId();
        assertEquals(2, recordingCount);
    }

    private int captureRecordingId()
    {
        final int recordingCount = aeronArchive.listRecordings(0, 2,
            (controlSessionId, correlationId, recordingId,
            startTimestamp, stopTimestamp, startPosition, stopPosition, initialTermId, segmentFileLength,
            termBufferLength, mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
            {
                if (sessionId == publication.sessionId())
                {
                    this.recordingId = recordingId;
                }
                else
                {
                    this.otherRecordingId = recordingId;
                }
            });
        return recordingCount;
    }

    private long recordingId;
    private long otherRecordingId;

    private GatewayPublication newGatewayPublication(final ExclusivePublication otherPublication)
    {
        return new GatewayPublication(
            otherPublication,
            mock(AtomicCounter.class),
            new YieldingIdleStrategy(),
            new OffsetEpochNanoClock(),
            DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS);
    }

    private long indexExampleMessage()
    {
        return indexExampleMessage(SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX);
    }

    private void verifyNoMessageRead()
    {
        verifyMessagesRead(0);
    }

    private void verifyMessagesRead(final int number)
    {
        assertEquals(fakeHandler.times(), number);
    }

    private void verifyMappedFile(final long sessionId, final int wantedNumberOfInvocations)
    {
        verify(existingBufferFactory, Mockito.times(wantedNumberOfInvocations)).map(logFile(sessionId));
    }

    private void verifyMappedFile(final long sessionId)
    {
        verify(newBufferFactory).map(eq(logFile(sessionId)), anyInt());
    }

    private File logFile(final long sessionId)
    {
        return ReplayIndexDescriptor.replayIndexHeaderFile(DEFAULT_LOG_FILE_DIR, sessionId, STREAM_ID);
    }

    private File segmentFile(final long sessionId, final int segmentIndex)
    {
        return ReplayIndexDescriptor.replayIndexSegmentFile(DEFAULT_LOG_FILE_DIR, sessionId, STREAM_ID, segmentIndex);
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

    private int query()
    {
        return query(SEQUENCE_NUMBER, SEQUENCE_INDEX, SEQUENCE_NUMBER, SEQUENCE_INDEX);
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

    static class FakeMessageHandler implements ControlledFragmentHandler
    {
        LongHashSet positions = new LongHashSet();
        int times = 0;

        public Action onFragment(
            final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            positions.add(header.position());
            times++;
            return Action.CONTINUE;
        }

        public int times()
        {
            return times;
        }
    }
}
