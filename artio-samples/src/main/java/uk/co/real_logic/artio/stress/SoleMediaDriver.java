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

import static io.aeron.archive.Archive.Configuration.REPLICATION_CHANNEL_PROP_NAME;
import static io.aeron.archive.client.AeronArchive.Configuration.CONTROL_CHANNEL_PROP_NAME;
import static io.aeron.archive.client.AeronArchive.Configuration.CONTROL_RESPONSE_CHANNEL_PROP_NAME;
import static io.aeron.driver.ThreadingMode.SHARED;

public final class SoleMediaDriver
{
    public static void main(final String[] args)
    {
        System.setProperty(CONTROL_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:8010");
        System.setProperty(CONTROL_RESPONSE_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:8020");
        System.setProperty(REPLICATION_CHANNEL_PROP_NAME, "aeron:udp?endpoint=localhost:0");

        final MediaDriver.Context context = new MediaDriver.Context()
            .threadingMode(SHARED)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true);

        try (ArchivingMediaDriver ignore = ArchivingMediaDriver.launch(context, archiveContext))
        {
            StressUtil.awaitKeyPress();
        }
    }
}
