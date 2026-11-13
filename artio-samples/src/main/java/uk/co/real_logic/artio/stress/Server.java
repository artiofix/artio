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
package uk.co.real_logic.artio.stress;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.AcquiringSessionExistsHandler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.SampleUtil.blockingConnect;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysPersistent;

public class Server implements Agent
{
    private final ArchivingMediaDriver mediaDriver;
    private final FixEngine fixEngine;
    private final FixLibrary fixLibrary;

    public Server()
    {
        final AuthenticationStrategy authenticationStrategy = logon -> true;

        // Static configuration lasts the duration of a FIX-Gateway instance
        final String aeronChannel = "aeron:udp?endpoint=localhost:10000";
        final EngineConfiguration configuration = new EngineConfiguration()
            .bindTo("localhost", StressConfiguration.PORT)
            .logFileDir("stress-server-logs")
            .libraryAeronChannel(aeronChannel)
            .sessionPersistenceStrategy(alwaysPersistent())
            .authenticationStrategy(authenticationStrategy)
            .agentNamePrefix("server-");

        configuration.aeronArchiveContext()
            .controlRequestChannel(StressConfiguration.CONTROL_REQUEST_CHANNEL)
            .controlResponseChannel(StressConfiguration.SERVER_CONTROL_RESPONSE_CHANNEL);

        System.out.println("Server Logs at " + configuration.logFileDir());

        StressUtil.cleanupOldLogFileDir(configuration);

        final MediaDriver.Context context = new MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .threadingMode(ArchiveThreadingMode.SHARED)
            .controlChannel(StressConfiguration.CONTROL_REQUEST_CHANNEL)
            .replicationChannel(StressConfiguration.REPLICATION_CHANNEL)
            .deleteArchiveOnStart(true);

        mediaDriver = ArchivingMediaDriver.launch(context, archiveContext);
        fixEngine = FixEngine.launch(configuration);

        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration();
        libraryConfiguration
            .agentNamePrefix("server-");

        fixLibrary = blockingConnect(libraryConfiguration
            .sessionAcquireHandler((session, acquiredInfo) -> new StressSessionHandler(session))
            .sessionExistsHandler(new AcquiringSessionExistsHandler(true))
            .libraryAeronChannels(singletonList(aeronChannel)));
    }

    public static AgentRunner createServer(final IdleStrategy idleStrategy, final ErrorHandler errorHandler)
    {
        return new AgentRunner(idleStrategy, errorHandler, null, new Server());
    }

    @Override
    public int doWork()
    {
        return fixLibrary.poll(1);
    }

    @Override
    public String roleName()
    {
        return "stress server";
    }

    @Override
    public void onClose()
    {
        fixLibrary.close();
        fixEngine.close();
        mediaDriver.close();
    }
}
