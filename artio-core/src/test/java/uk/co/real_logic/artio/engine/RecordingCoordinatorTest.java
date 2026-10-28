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
import io.aeron.ChannelUri;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.CommonContext.SESSION_ID_PARAM_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.TestFixtures.*;

class RecordingCoordinatorTest
{
    private static final int LIBRARY_ID = 7;
    private static final int ARCHIVE_CONTROL_STREAM_ID = 100;

    @TempDir
    Path directory;

    @ParameterizedTest(name = "udp={0}, engineOwned={1}")
    @CsvSource({ "true, false", "false, false", "true, true" })
    void shouldExtendRecordingAfterRestart(final boolean udp, final boolean engineOwned)
    {
        final String engineDirectory = directory.resolve("engine-driver").toString();
        final String libraryDirectory = directory.resolve("library-driver").toString();
        final String channel = udp ? "aeron:udp?endpoint=localhost:" + unusedPort() : IPC_CHANNEL;
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(channel)
            .logFileDir(directory.toString());
        final int streamId = configuration.outboundLibraryStream();
        final Archive.Context archiveContext = archiveContext()
            .aeronDirectoryName(engineDirectory)
            .archiveDirectoryName(directory.resolve("archive").toString())
            .localControlStreamId(ARCHIVE_CONTROL_STREAM_ID)
            .archiveClientContext(new AeronArchive.Context().controlResponseChannel(IPC_CHANNEL))
            .controlChannelEnabled(false);

        try (
            ArchivingMediaDriver engineDriver = ArchivingMediaDriver.launch(
                mediaDriverContext(TERM_BUFFER_LENGTH, true).aeronDirectoryName(engineDirectory), archiveContext);
            MediaDriver libraryDriver = MediaDriver.launch(
                mediaDriverContext(TERM_BUFFER_LENGTH, true).aeronDirectoryName(libraryDirectory));
            Aeron engineAeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(engineDirectory));
            Aeron libraryAeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(
                udp && !engineOwned ? libraryDirectory : engineDirectory));
            Subscription receiver = engineAeron.addSubscription(channel, streamId))
        {
            final long recordingId;
            final long stopPosition;
            try (
                AeronArchive archive = connectArchive(engineDirectory);
                RecordingCoordinator coordinator = coordinator(engineAeron, archive, configuration);
                ExclusivePublication publication = engineOwned ? coordinator.trackEngine(channel, streamId) :
                    libraryAeron.addExclusivePublication(channel, streamId))
            {
                if (!engineOwned)
                {
                    assertNull(coordinator.trackLibrary(publication.sessionId(), LIBRARY_ID));
                }
                recordingId = coordinator.framerOutboundLookup().getRecordingId(publication.sessionId());
                stopPosition = publish(publication, 111);
                await(() -> archive.getRecordingPosition(recordingId) >= stopPosition);
            }

            // Reconstruct the coordinator from its persisted state, as on an engine restart.
            try (
                AeronArchive archive = connectArchive(engineDirectory);
                RecordingCoordinator coordinator = coordinator(engineAeron, archive, configuration))
            {
                assertEquals(stopPosition, archive.getStopPosition(recordingId));
                final ExclusivePublication publication;
                if (engineOwned)
                {
                    publication = coordinator.trackEngine(channel, streamId);
                }
                else
                {
                    final RecordingCoordinator.LibraryExtendPosition extend =
                        coordinator.trackLibrary(engineAeron.nextSessionId(streamId), LIBRARY_ID);
                    assertNotNull(extend);
                    assertEquals(recordingId, extend.recordingId);
                    assertEquals(stopPosition, extend.stopPosition);
                    final ChannelUri uri = ChannelUri.parse(channel);
                    uri.initialPosition(extend.stopPosition, extend.initialTermId, extend.termBufferLength);
                    RecordingCoordinator.setMtuLength(extend.mtuLength, uri);
                    uri.put(SESSION_ID_PARAM_NAME, Integer.toString(extend.newSessionId));
                    publication = libraryAeron.addExclusivePublication(uri.toString(), streamId);
                }

                try (ExclusivePublication resumedPublication = publication)
                {
                    if (!engineOwned)
                    {
                        assertNull(coordinator.trackLibrary(resumedPublication.sessionId(), LIBRARY_ID));
                    }
                    assertEquals(recordingId,
                        coordinator.framerOutboundLookup().getRecordingId(resumedPublication.sessionId()));
                    final long position = publish(resumedPublication, 222);
                    assertTrue(position > stopPosition);
                    await(() -> archive.getRecordingPosition(recordingId) >= position);

                    assertReplay(archive, recordingId, position, streamId + 1);
                }
            }
        }
    }

    private static void assertReplay(
        final AeronArchive archive, final long recordingId, final long position, final int streamId)
    {
        final List<Long> messages = new ArrayList<>();
        try (Subscription replay = archive.replay(recordingId, 0, position, IPC_CHANNEL, streamId))
        {
            await(() ->
            {
                replay.poll((buffer, offset, length, header) -> messages.add(buffer.getLong(offset)), 10);
                return messages.size() >= 2;
            });
        }
        assertEquals(List.of(111L, 222L), messages);
    }

    private static AeronArchive connectArchive(final String aeronDirectory)
    {
        return AeronArchive.connect(new AeronArchive.Context()
            .aeronDirectoryName(aeronDirectory)
            .controlRequestChannel(IPC_CHANNEL)
            .controlRequestStreamId(ARCHIVE_CONTROL_STREAM_ID)
            .controlResponseChannel(IPC_CHANNEL));
    }

    private static RecordingCoordinator coordinator(
        final Aeron aeron, final AeronArchive archive, final EngineConfiguration configuration)
    {
        final long deadline = System.nanoTime() + SECONDS.toNanos(10);
        final RecordingCoordinator coordinator = new RecordingCoordinator(
            aeron, archive, configuration, new IdleStrategy()
            {
                public void idle(final int workCount)
                {
                    if (workCount == 0)
                    {
                        idle();
                    }
                }

                public void idle()
                {
                    // Fail on the calling test thread instead of leaving a Framer thread blocked on recovery.
                    assertTrue(System.nanoTime() < deadline, "Timed out waiting for the recording counter");
                    Thread.yield();
                }

                public void reset()
                {
                }
            }, error -> fail("Unexpected recording error", error));
        coordinator.completionPositions(new Long2LongHashMap(-1), new Long2LongHashMap(-1));
        return coordinator;
    }

    private static long publish(final ExclusivePublication publication, final long value)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[Long.BYTES]);
        buffer.putLong(0, value);
        await(() -> publication.offer(buffer) > 0);
        return publication.position();
    }

    private static void await(final BooleanSupplier condition)
    {
        final long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (!condition.getAsBoolean())
        {
            assertTrue(System.nanoTime() < deadline, "Timed out awaiting archive progress");
            Thread.yield();
        }
    }
}
