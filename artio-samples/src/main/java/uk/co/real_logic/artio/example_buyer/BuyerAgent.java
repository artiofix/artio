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
package uk.co.real_logic.artio.example_buyer;

import org.agrona.CloseHelper;
import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;

import java.io.File;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.CommonConfiguration.optimalTmpDirName;
import static uk.co.real_logic.artio.example_buyer.BuyerApplication.AERON_DIRECTORY_NAME;
import static uk.co.real_logic.artio.example_buyer.BuyerApplication.RECORDING_EVENTS_CHANNEL;
import static uk.co.real_logic.artio.example_exchange.ExchangeApplication.cleanupOldLogFileDir;

public class BuyerAgent implements Agent
{
    private FixEngine engine;
    private FixLibrary library;
    private final Buyer buyer = new Buyer();

    @Override
    public void onStart()
    {
        final EngineConfiguration engineConfiguration = new EngineConfiguration()
            .libraryAeronChannel(IPC_CHANNEL)
            .monitoringFile(optimalTmpDirName() + File.separator + "fix-buyer" + File.separator + "engineCounters")
            .logFileDir("buyer-logs");

        engineConfiguration
            .aeronContext()
            .aeronDirectoryName(AERON_DIRECTORY_NAME);

        engineConfiguration
            .aeronArchiveContext()
            .recordingEventsChannel(RECORDING_EVENTS_CHANNEL)
            .aeronDirectoryName(AERON_DIRECTORY_NAME);

        cleanupOldLogFileDir(engineConfiguration);

        engine = FixEngine.launch(engineConfiguration);

        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
            .libraryAeronChannels(singletonList(IPC_CHANNEL))
            .libraryConnectHandler(buyer)
            .sessionAcquireHandler(buyer);

        libraryConfiguration
            .aeronContext()
            .aeronDirectoryName(AERON_DIRECTORY_NAME);

        library = FixLibrary.connect(libraryConfiguration);
    }

    @Override
    public int doWork()
    {
        final int actions = library.poll(10);
        return actions + buyer.poll();
    }

    @Override
    public void onClose()
    {
        CloseHelper.close(engine);
    }

    @Override
    public String roleName()
    {
        return "Buyer";
    }
}
