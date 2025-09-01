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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import uk.co.real_logic.artio.FileSystemCorruptionException;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.engine.MappedFile;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.framer.FakeEpochClock;
import uk.co.real_logic.artio.messages.ApplicationHeartbeatEncoder;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.io.File;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.CommonContext.IPC_MEDIA;
import static org.agrona.IoUtil.deleteIfExists;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INDEX_CHECKSUM_ENABLED;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.engine.SectorFramer.SECTOR_SIZE;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.logger.ErrorHandlerVerifier.verify;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.SEQUENCE_NUMBER_OFFSET;

public class SequenceNumberIndexTest extends AbstractLogTest
{
    private static final int EXAMPLE_MESSAGES_IN_TERM = 18724;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String INDEX_FILE_PATH = IoUtil.tmpDirName() + "/SequenceNumberIndex";

    private final AtomicBuffer inMemoryBuffer = newBuffer();

    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private SequenceNumberIndexWriter writer;
    private SequenceNumberIndexReader reader;
    private final FakeEpochClock clock = new FakeEpochClock();

    private ArchivingMediaDriver mediaDriver;
    private AeronArchive aeronArchive;
    private Aeron aeron;
    private ExclusivePublication publication;
    private GatewayPublication gatewayPublication;
    private Subscription subscription;
    private RecordingIdLookup recordingIdLookup;

    @Before
    public void setUp()
    {
        mediaDriver = launchMediaDriver();
        aeronArchive = AeronArchive.connect(aeronArchiveContext());
        aeron = aeronArchive.context().aeron();

        aeronArchive.startRecording(IPC_CHANNEL, STREAM_ID, SourceLocation.LOCAL);

        publication = aeron.addExclusivePublication(IPC_CHANNEL, STREAM_ID);
        gatewayPublication = new GatewayPublication(
            publication,
            mock(AtomicCounter.class),
            YieldingIdleStrategy.INSTANCE,
            mock(EpochNanoClock.class),
            10);
        subscription = aeron.addSubscription(IPC_CHANNEL, STREAM_ID);

        buffer = new UnsafeBuffer(new byte[512]);

        deleteFiles();

        recordingIdLookup =
            new RecordingIdLookup(aeronArchive.archiveId(), YieldingIdleStrategy.INSTANCE, aeron.countersReader());
        writer = newWriter(inMemoryBuffer);
        reader = new SequenceNumberIndexReader(inMemoryBuffer, errorHandler, recordingIdLookup, null);
    }

    @After
    public void tearDown()
    {
        CloseHelper.close(writer);
        deleteFiles();

        verify(errorHandler, never()).onError(any());

        CloseHelper.close(aeronArchive);
        cleanupMediaDriver(mediaDriver);

        Mockito.framework().clearInlineMocks();
    }

    @Test
    public void shouldNotInitiallyKnowASequenceNumber()
    {
        assertUnknownSession();
    }

    @Test
    public void shouldIndexNewSequenceNumber()
    {
        indexFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    @Test
    public void shouldIndexNewSequenceNumberFromThrottle()
    {
        long position = 0;
        while (position < 1)
        {
            position = gatewayPublication.saveThrottleReject(
                LIBRARY_ID,
                CONNECTION_ID,
                SessionConstants.TEST_REQUEST_MESSAGE_TYPE,
                1,
                SEQUENCE_NUMBER,
                SESSION_ID,
                SEQUENCE_INDEX,
                new UnsafeBuffer(new byte[0]), 0, 0);

            Thread.yield();
        }

        indexToPosition(publication.sessionId(), position);

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    @Test
    public void shouldStashNewSequenceNumberForLargeMessage()
    {
        final long position = indexLargeFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);

        assertEquals(position, reader.indexedPosition(publication.sessionId()));
    }

    @Test
    public void shouldStashSequenceNumbersAgainstASessionId()
    {
        indexFixMessage();

        assertLastKnownSequenceNumberIs(SESSION_ID_2, UNK_SESSION);
    }

    @Test
    public void shouldUpdateSequenceNumber()
    {
        final int updatedSequenceNumber = 8;

        indexFixMessage();

        bufferContainsExampleMessage(true, SESSION_ID, updatedSequenceNumber, SEQUENCE_INDEX);

        indexRecord();

        assertLastKnownSequenceNumberIs(SESSION_ID, updatedSequenceNumber);
    }

    @Test
    public void shouldRedactSequenceNumber()
    {
        final int sequenceNumberToRedact = 8;

        indexFixMessage();
        bufferContainsExampleMessage(true, SESSION_ID, sequenceNumberToRedact, SEQUENCE_INDEX);

        final long fixMessageToRedactPosition = indexRecord();

        indexRedactSequenceMessage(fixMessageToRedactPosition);

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    @Test
    public void shouldRedactSequenceNumberWhenFixMessageProcessedAfterRedact()
    {
        final int sequenceNumberToRedact = 8;

        indexFixMessage();
        bufferContainsExampleMessage(true, SESSION_ID, sequenceNumberToRedact, SEQUENCE_INDEX);

        final long fixMessageToRedactPosition = writeBuffer();

        indexRedactSequenceMessage(fixMessageToRedactPosition);

        // Indexer processes the fix message that has been redacted after the redact message itself due
        // to lack of synchronisation between streams.
        indexToPosition(this.publication.sessionId(), fixMessageToRedactPosition);

        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    private void indexRedactSequenceMessage(final long fixMessageToRedactPosition)
    {
        try (ExclusivePublication publication = aeron.addExclusivePublication(IPC_CHANNEL, STREAM_ID))
        {
            final GatewayPublication gatewayPublication = new GatewayPublication(
                publication, mock(AtomicCounter.class), YieldingIdleStrategy.INSTANCE,
                mock(EpochNanoClock.class), 1);

            final long redactMessagePosition = gatewayPublication.saveRedactSequenceUpdate(
                SESSION_ID, SEQUENCE_NUMBER, fixMessageToRedactPosition);

            indexToPosition(publication.sessionId(), redactMessagePosition);
        }
    }

    @Test
    public void shouldRedactSequenceNumberWhenFixMessageProcessedAfterRedactButNotBefore()
    {
        bufferContainsExampleMessage(false, SESSION_ID, SEQUENCE_NUMBER + 100, SEQUENCE_INDEX);
        final long messagePosition = writeBuffer();
        indexRedactSequenceMessage(messagePosition);
        indexToPosition(publication.sessionId(), messagePosition);
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
    }

    @Test
    public void shouldValidateBufferItReadsFrom()
    {
        final AtomicBuffer tableBuffer = newBuffer();

        new SequenceNumberIndexReader(tableBuffer, errorHandler, recordingIdLookup, null);

        verify(errorHandler, times(1), IllegalStateException.class);
    }

    @Test
    public void shouldSaveIndexUponClose()
    {
        indexFixMessage();

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertEquals(alignedEndPosition(), newReader.indexedPosition(publication.sessionId()));
    }

    @Test
    public void shouldRecordIndexedPosition()
    {
        indexFixMessage();

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
    }

    @Test
    public void shouldFlushIndexFileOnTimeout()
    {
        try
        {
            indexFixMessage();

            assertEquals(0, writer.doWork());

            clock.advanceMilliSeconds(DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS + 1);

            assertEquals(1, writer.doWork());

            final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
            assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
        }
        finally
        {
            writer.close();
        }
    }

    /**
     * Simulate scenario that you've crashed halfway through file flip.
     */
    @Test
    public void shouldAccountForPassingPlaceFile()
    {
        indexFixMessage();

        writer.close();

        // TODO: check that the passing place is used

        //assertTrue("Failed to recreate crash scenario", new File(INDEX_FILE_PATH).renameTo(writer.passingPlace()));

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER, newReader);
    }

    @Test
    public void shouldChecksumFileToDetectCorruption()
    {
        indexFixMessage();

        writer.close();

        corruptIndexFile(SEQUENCE_NUMBER_OFFSET, SECTOR_SIZE / 2);

        newInstanceAfterRestart();

        final ArgumentCaptor<FileSystemCorruptionException> exception =
            ArgumentCaptor.forClass(FileSystemCorruptionException.class);
        verify(errorHandler).onError(exception.capture());
        assertThat(
            exception.getValue().getMessage(),
            Matchers.containsString("The SequenceNumberIndex file is corrupted"));
        reset(errorHandler);
    }

    @Test
    public void shouldValidateHeader()
    {
        indexFixMessage();

        writer.close();

        corruptIndexFile(0, SequenceNumberIndexDescriptor.HEADER_SIZE);

        newInstanceAfterRestart();

        verify(errorHandler, times(3), IllegalStateException.class);
    }

    private void corruptIndexFile(final int from, final int length)
    {
        try (MappedFile mappedFile = newIndexFile())
        {
            mappedFile.buffer().putBytes(from, new byte[length]);
        }
    }

    @Test
    public void shouldSaveIndexUponRotate()
    {
        final int requiredMessagesToRoll = EXAMPLE_MESSAGES_IN_TERM;
        for (int i = 0; i <= requiredMessagesToRoll; i++)
        {
            bufferContainsExampleMessage(true, SESSION_ID, SEQUENCE_NUMBER + i, SEQUENCE_INDEX);
            indexRecord();
        }

        try (MappedFile mappedFile = newIndexFile())
        {
            final SequenceNumberIndexReader newReader = new SequenceNumberIndexReader(
                mappedFile.buffer(), errorHandler, recordingIdLookup, null);

            assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER + requiredMessagesToRoll, newReader);
        }
    }

    @Test
    public void shouldAlignMessagesAndNotOverlapCheckSums()
    {
        final int initialSequenceNumber = 1;
        final int sequenceNumberDiff = 3;
        final int recordsOverlappingABlock = SECTOR_SIZE / RECORD_SIZE + 1;
        for (int i = initialSequenceNumber; i <= recordsOverlappingABlock; i++)
        {
            bufferContainsExampleMessage(true, i, i + sequenceNumberDiff, SEQUENCE_INDEX);
            indexRecord();
        }

        writer.close();

        final SequenceNumberIndexReader newReader = newInstanceAfterRestart();
        for (int i = initialSequenceNumber; i <= recordsOverlappingABlock; i++)
        {
            assertLastKnownSequenceNumberIs(i, i + sequenceNumberDiff, newReader);
        }
    }

    @Test
    public void shouldResetSequenceNumbers()
    {
        indexFixMessage();

        writer.resetSequenceNumbers();

        assertUnknownSession();
    }

    @Test
    public void shouldResetSequenceNumberForSessionAfterRestart()
    {
        indexFixMessage();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);

        bufferContainsExampleMessage(false, SESSION_ID + 1, SEQUENCE_NUMBER + 5,
            SEQUENCE_INDEX);
        indexRecord();
        assertLastKnownSequenceNumberIs(SESSION_ID + 1, SEQUENCE_NUMBER + 5);

        writer.close();
        writer = newWriter(inMemoryBuffer);

        resetSequenceNumber(SESSION_ID);
        assertLastKnownSequenceNumberIs(SESSION_ID, 0);

        // this should write to old session place and not to same as previous call
        resetSequenceNumber(SESSION_ID_2);
        assertLastKnownSequenceNumberIs(SESSION_ID_2, 0);

        indexFixMessage();
        assertLastKnownSequenceNumberIs(SESSION_ID, SEQUENCE_NUMBER);
        assertLastKnownSequenceNumberIs(SESSION_ID_2, 0);
    }

    @Test
    public void shouldHandleMultipleImages()
    {
        indexApplicationHeartbeat();

        final ExclusivePublication lowPositionPublication = publication;
        final String channel = new ChannelUriStringBuilder()
            .media(IPC_MEDIA)
            .initialPosition(1024L * 1024 * 1024, 42, lowPositionPublication.termBufferLength())
            .build();
        publication = aeron.addExclusivePublication(channel, STREAM_ID);

        int seqNum = SEQUENCE_NUMBER;
        for (int i = 0; i < EXAMPLE_MESSAGES_IN_TERM; i++)
        {
            bufferContainsExampleMessage(true, SESSION_ID, seqNum++, SEQUENCE_INDEX);
            indexRecord();
        }

        assertEquals(0, writer.fileUpdateCount());

        bufferContainsExampleMessage(true, SESSION_ID, seqNum++, SEQUENCE_INDEX);
        indexRecord();

        assertEquals(1, writer.fileUpdateCount());

        publication = lowPositionPublication;

        for (int i = 0; i < EXAMPLE_MESSAGES_IN_TERM; i++)
        {
            bufferContainsExampleMessage(true, SESSION_ID, seqNum++, SEQUENCE_INDEX);
            indexRecord();
        }

        assertEquals(1, writer.fileUpdateCount());

        bufferContainsExampleMessage(true, SESSION_ID, seqNum++, SEQUENCE_INDEX);
        indexRecord();

        assertEquals(2, writer.fileUpdateCount());
    }

    private void indexApplicationHeartbeat()
    {
        offset = START;

        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final ApplicationHeartbeatEncoder encoder = new ApplicationHeartbeatEncoder();
        encoder.wrapAndApplyHeader(buffer, offset, headerEncoder)
            .libraryId(0)
            .timestampInNs(1234);

        offset += headerEncoder.encodedLength() + encoder.encodedLength();

        indexRecord();
    }

    private void resetSequenceNumber(final long sessionId)
    {
        final long position = gatewayPublication.saveResetSequenceNumber(sessionId);
        indexToPosition(gatewayPublication.sessionId(), position);
    }

    private SequenceNumberIndexReader newInstanceAfterRestart()
    {
        final AtomicBuffer inMemoryBuffer = newBuffer();
        newWriter(inMemoryBuffer).close();
        return new SequenceNumberIndexReader(inMemoryBuffer, errorHandler, recordingIdLookup, null);
    }

    private SequenceNumberIndexWriter newWriter(final AtomicBuffer inMemoryBuffer)
    {
        final MappedFile indexFile = newIndexFile();
        return new SequenceNumberIndexWriter(new SequenceNumberExtractor(),
            inMemoryBuffer, indexFile, errorHandler, STREAM_ID, recordingIdLookup,
            DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS, clock, null,
            new Long2LongHashMap(UNK_SESSION),
            FixPProtocolType.ILINK_3, DEFAULT_INDEX_CHECKSUM_ENABLED, true);
    }

    private MappedFile newIndexFile()
    {
        return MappedFile.map(INDEX_FILE_PATH, BUFFER_SIZE);
    }

    private UnsafeBuffer newBuffer()
    {
        return new UnsafeBuffer(new byte[BUFFER_SIZE]);
    }

    private void assertUnknownSession()
    {
        assertLastKnownSequenceNumberIs(SESSION_ID, UNK_SESSION);
    }

    private void indexFixMessage()
    {
        bufferContainsExampleMessage(true);
        indexRecord();
    }

    private long indexLargeFixMessage()
    {
        buffer = new UnsafeBuffer(new byte[BIG_BUFFER_LENGTH]);

        final String testReqId = largeTestReqId();
        bufferContainsExampleMessage(true, SESSION_ID, SEQUENCE_NUMBER, SEQUENCE_INDEX, testReqId);

        return indexRecord();
    }

    private long indexRecord()
    {
        final long position = writeBuffer();
        indexToPosition(publication.sessionId(), position);
        return position;
    }

    private void indexToPosition(final int aeronSessionId, final long position)
    {
        Image image = null;
        while (image == null || image.position() < position)
        {
            if (image == null)
            {
                image = subscription.imageBySessionId(aeronSessionId);
            }

            if (image != null)
            {
                image.poll(writer, 1);
            }
        }
    }

    private long writeBuffer()
    {
        long position = 0;
        while (position < 1)
        {
            position = publication.offer(buffer, START, fragmentLength());

            Thread.yield();
        }
        return position;
    }

    private void assertLastKnownSequenceNumberIs(final long sessionId, final int expectedSequenceNumber)
    {
        assertLastKnownSequenceNumberIs(sessionId, expectedSequenceNumber, reader);
    }

    private void assertLastKnownSequenceNumberIs(
        final long sessionId,
        final long expectedSequenceNumber,
        final SequenceNumberIndexReader reader)
    {
        final int number = reader.lastKnownSequenceNumber(sessionId);
        assertEquals(expectedSequenceNumber, number);
    }

    private void deleteFiles()
    {
        deleteIfExists(new File(INDEX_FILE_PATH));
        deleteIfExists(writableFile(INDEX_FILE_PATH));
        deleteIfExists(passingFile(INDEX_FILE_PATH));
    }
}
