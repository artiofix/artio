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
package uk.co.real_logic.artio.system_tests;

import io.aeron.archive.ArchivingMediaDriver;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LowResourceEngineScheduler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.driver.Configuration.PUBLICATION_RESERVED_SESSION_ID_HIGH_PROP_NAME;
import static io.aeron.driver.Configuration.PUBLICATION_RESERVED_SESSION_ID_LOW_PROP_NAME;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.co.real_logic.artio.CommonConfiguration.optimalTmpDirName;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.configureAeronArchive;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.TEST_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.delete;

/**
 * Regression test for the Aeron session-id clash that occurred when two {@link FixLibrary} instances shared a single
 * {@link FixEngine} and both re-extended their persistent outbound recordings during recovery (issue #575).
 *
 * <p>When a library reconnects after an engine restart, {@code RecordingCoordinator} re-extends that library's
 * recording and needs a session-id for the new outbound publication. It draws one from the reserved range
 * {@code [aeron.publication.reserved.session.id.low, ...high)} - the only range the driver guarantees never to
 * auto-assign. The original implementation drew the id at random with no deduplication, so when two libraries
 * recovered at the same time both draws could land on the same id; the second {@code archive.extendRecording} was
 * then rejected by the archiver with "recording exists for streamId=... session-id=..." and the losing library never
 * reconnected.
 *
 * <p>This test pins the reserved range to exactly two ids - one per library - so that a colliding random draw was
 * almost guaranteed under the old logic. With the fix the coordinator scans the reserved range for an id that is not
 * already in use by another in-flight extend, so the two libraries are always assigned distinct ids and both recover
 * cleanly with no clash reported.
 */
public class MultiLibrarySharedEngineSessionIdClashSystemTest
{
    private static final String LOG_DIR = "multi-library-clash-logs";

    private static final int LIBRARY_1_ID = 100010;
    private static final int LIBRARY_2_ID = 100011;

    // Pin the reserved session-id band to exactly two ids - one per library. A clash here is only avoided if the
    // coordinator deduplicates the ids it hands out across the two concurrent extends.
    private static final int RESERVED_SESSION_ID_LOW = 1;
    private static final int RESERVED_SESSION_ID_HIGH = 3;

    private final EpochNanoClock nanoClock = new OffsetEpochNanoClock();
    private final List<Throwable> engineErrors = new CopyOnWriteArrayList<>();

    private String previousReservedLow;
    private String previousReservedHigh;

    private ArchivingMediaDriver mediaDriver;
    private FixEngine sharedEngine;
    private TestSystem testSystem;

    private FixLibrary library1;
    private FixLibrary library2;

    @BeforeEach
    public void setUp()
    {
        previousReservedLow = System.setProperty(
            PUBLICATION_RESERVED_SESSION_ID_LOW_PROP_NAME, Integer.toString(RESERVED_SESSION_ID_LOW));
        previousReservedHigh = System.setProperty(
            PUBLICATION_RESERVED_SESSION_ID_HIGH_PROP_NAME, Integer.toString(RESERVED_SESSION_ID_HIGH));

        delete(LOG_DIR);

        mediaDriver = launchMediaDriver();
        sharedEngine = launchSharedEngine(true);
        testSystem = new TestSystem();
    }

    @Test
    @Timeout(30)
    public void shouldNotClashOnReservedSessionIdWhenTwoLibrariesReExtendOnRecovery()
    {
        // Clean first start: the driver auto-allocates each library's outbound-publication session-id, avoiding the
        // reserved range, so the two recordings are created with distinct ids and there is no clash here.
        library1 = testSystem.add(connectLibrary(LIBRARY_1_ID));
        library2 = testSystem.add(connectLibrary(LIBRARY_2_ID));
        awaitConnected(library1);
        awaitConnected(library2);

        // Tear the libraries and the engine down cleanly, leaving the logs (and the recording-ids file) intact so the
        // engine reloads both libraries' recordings on restart. The media driver stays up, mirroring an in-process
        // gateway recovery rather than a full machine restart.
        closeLibraries();
        sharedEngine.close();
        engineErrors.clear();

        // Recovery: restart the engine against the same logs, then reconnect both libraries on their original
        // libraryIds. Each onLibraryConnect re-extends that library's recording and draws a reserved session-id;
        // with only two ids available the coordinator must hand out distinct ids for the two extends to succeed.
        sharedEngine = launchSharedEngine(false);
        library1 = testSystem.add(connectLibrary(LIBRARY_1_ID));
        library2 = testSystem.add(connectLibrary(LIBRARY_2_ID));

        awaitConnected(library1);
        awaitConnected(library2);

        assertTrue(library1.isConnected(), "library1 failed to recover");
        assertTrue(library2.isConnected(), "library2 failed to recover");
        assertNoClash();
    }

    @AfterEach
    public void tearDown()
    {
        closeLibraries();
        closeAll(sharedEngine, () -> cleanupMediaDriver(mediaDriver));
        delete(LOG_DIR);

        restore(PUBLICATION_RESERVED_SESSION_ID_LOW_PROP_NAME, previousReservedLow);
        restore(PUBLICATION_RESERVED_SESSION_ID_HIGH_PROP_NAME, previousReservedHigh);
    }

    private FixEngine launchSharedEngine(final boolean deleteLogsOnStart)
    {
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(IPC_CHANNEL)
            .logFileDir(LOG_DIR)
            .deleteLogFileDirOnStart(deleteLogsOnStart)
            .logInboundMessages(true)
            .logOutboundMessages(true)
            .scheduler(new LowResourceEngineScheduler())
            .monitoringFile(optimalTmpDirName() + File.separator + "multi-library-clash" + File.separator + "counters")
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS);
        configuration.epochNanoClock(nanoClock);
        configuration.errorHandlerFactory(errorBuffer -> (ErrorHandler)engineErrors::add);
        configureAeronArchive(configuration.aeronArchiveContext());

        return FixEngine.launch(configuration);
    }

    private FixLibrary connectLibrary(final int libraryId)
    {
        final FakeHandler handler = new FakeHandler(new FakeOtfAcceptor());
        final LibraryConfiguration configuration = new LibraryConfiguration()
            .libraryId(libraryId)
            .sessionAcquireHandler(handler)
            .sessionExistsHandler(handler)
            .libraryAeronChannels(singletonList(IPC_CHANNEL))
            .libraryName("library-" + libraryId)
            .replyTimeoutInMs(TEST_REPLY_TIMEOUT_IN_MS);
        configuration.epochNanoClock(nanoClock);

        return FixLibrary.connect(configuration);
    }

    private void awaitConnected(final FixLibrary library)
    {
        assertEventuallyTrue(
            "Library failed to connect to the shared engine",
            () ->
            {
                testSystem.poll();
                return library.isConnected();
            },
            TEST_REPLY_TIMEOUT_IN_MS * 10);
    }

    private void assertNoClash()
    {
        assertTrue(
            engineErrors.stream().noneMatch(MultiLibrarySharedEngineSessionIdClashSystemTest::isClash),
            () -> "Unexpected reserved session-id clash on the shared outbound library stream: " +
            describe(engineErrors));
    }

    private void closeLibraries()
    {
        if (testSystem != null)
        {
            if (library1 != null)
            {
                testSystem.remove(library1);
            }
            if (library2 != null)
            {
                testSystem.remove(library2);
            }
        }
        closeAll(library1, library2);
        library1 = null;
        library2 = null;
    }

    private static boolean isClash(final Throwable error)
    {
        final String message = error.getMessage();
        return message != null &&
            (message.contains("clashing sessionId") || message.contains("recording exists for streamId"));
    }

    private static String describe(final List<Throwable> errors)
    {
        if (errors.isEmpty())
        {
            return "<none>";
        }
        final StringBuilder builder = new StringBuilder();
        for (final Throwable error : errors)
        {
            builder.append("\n  ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        }
        return builder.toString();
    }

    private static void restore(final String property, final String previousValue)
    {
        if (previousValue == null)
        {
            System.clearProperty(property);
        }
        else
        {
            System.setProperty(property, previousValue);
        }
    }
}
