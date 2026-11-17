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
package uk.co.real_logic.artio.client;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.SleepingIdleStrategy;
import uk.co.real_logic.artio.SampleUtil;
import uk.co.real_logic.artio.builder.TestRequestEncoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.session.Session;

import java.io.File;

import static io.aeron.driver.ThreadingMode.SHARED;
import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.CommonConfiguration.optimalTmpDirName;
import static uk.co.real_logic.artio.messages.SessionState.DISCONNECTED;
import static uk.co.real_logic.artio.server.SampleServer.*;

// NB: You should start the {@link SampleServer} class first before starting this client.
// This client uses a different media driver instance to the SampleServer, so it sets a bunch of
// channels to avoid clashing.
public final class SampleClient
{
    private static final TestReqIdFinder TEST_REQ_ID_FINDER = new TestReqIdFinder();

    public static void main(final String[] args)
    {
        // Static configuration lasts the duration of a FIX-Gateway instance
        final String aeronChannel = "aeron:udp?endpoint=localhost:10002";
        final EngineConfiguration configuration = new EngineConfiguration()
            .libraryAeronChannel(aeronChannel)
            .monitoringFile(optimalTmpDirName() + File.separator + "fix-client" + File.separator + "engineCounters")
            .logFileDir("client-logs");

        configuration.aeronArchiveContext()
            .aeronDirectoryName(AERON_DIR_NAME)
            .controlRequestChannel(CONTROL_REQUEST_CHANNEL)
            .controlResponseChannel(CONTROL_RESPONSE_CHANNEL);

        configuration.aeronContext()
            .aeronDirectoryName(AERON_DIR_NAME);

        cleanupOldLogFileDir(configuration);

        final MediaDriver.Context context = new MediaDriver.Context()
            .threadingMode(SHARED)
            .dirDeleteOnStart(true)
            .aeronDirectoryName(AERON_DIR_NAME);

        final Archive.Context archiveContext = new Archive.Context()
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true)
            .aeronDirectoryName(AERON_DIR_NAME)
            .archiveDirectoryName(ARCHIVE_DIR_NAME);

        archiveContext
            .controlChannel(CONTROL_REQUEST_CHANNEL)
            .replicationChannel(REPLICATION_CHANNEL)
            .recordingEventsChannel(RECORDING_EVENTS_CHANNEL);

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(context, archiveContext))
        {
            try (FixEngine ignore = FixEngine.launch(configuration))
            {
                // Each outbound session with an Exchange or broker is represented by
                // a Session object. Each session object can be configured with connection
                // details and credentials.
                final SessionConfiguration sessionConfig = SessionConfiguration.builder()
                    .address("localhost", 9999)
                    .targetCompId(ACCEPTOR_COMP_ID)
                    .senderCompId(INITIATOR_COMP_ID)
                    .build();

                final SleepingIdleStrategy idleStrategy = new SleepingIdleStrategy(100);

                final LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
                    .sessionAcquireHandler((session, acquiredInfo) -> TEST_REQ_ID_FINDER)
                    .libraryAeronChannels(singletonList(aeronChannel));

                libraryConfiguration.aeronContext()
                    .aeronDirectoryName(AERON_DIR_NAME);

                try (FixLibrary library = SampleUtil.blockingConnect(libraryConfiguration))
                {
                    // Whilst we only initiate a single Session for a given library here, it is
                    // perfectly possible to initiate multiple Sessions on a given library
                    // and manage them accordingly.
                    final Session session = LibraryUtil.initiate(
                        library,
                        sessionConfig,
                        10_000,
                        idleStrategy);

                    while (!session.isActive())
                    {
                        idleStrategy.idle(library.poll(1));
                    }

                    final TestRequestEncoder testRequest = new TestRequestEncoder();
                    testRequest.testReqID("Hello World");

                    session.trySend(testRequest);

                    while (!"Hello World".equals(TEST_REQ_ID_FINDER.testReqId()))
                    {
                        idleStrategy.idle(library.poll(1));
                    }

                    System.out.println("Success, received reply!");
                    System.out.println(TEST_REQ_ID_FINDER.testReqId());

                    session.startLogout();
                    session.requestDisconnect();

                    while (session.state() != DISCONNECTED)
                    {
                        idleStrategy.idle(library.poll(1));
                    }

                    System.out.println("Disconnected");
                }
            }
        }

        System.exit(0);
    }

    private static final String AERON_DIR_NAME = "client-aeron";
    private static final String ARCHIVE_DIR_NAME = "client-aeron-archive";
    private static final String CONTROL_REQUEST_CHANNEL = "aeron:udp?endpoint=localhost:7010";
    private static final String CONTROL_RESPONSE_CHANNEL = "aeron:udp?endpoint=localhost:7020";
    private static final String RECORDING_EVENTS_CHANNEL = "aeron:udp?control-mode=dynamic|control=localhost:7030";
    private static final String REPLICATION_CHANNEL = "aeron:udp?endpoint=localhost:0";
}
