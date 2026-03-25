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
package uk.co.real_logic.artio.protocol;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.agrona.LangUtil;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.StreamInformation;
import uk.co.real_logic.artio.engine.RecordingCoordinator;

import java.nio.file.NoSuchFileException;

public final class Streams
{
    private final int streamId;
    private final EpochNanoClock clock;
    private final Aeron aeron;
    private final String aeronChannel;
    private final boolean printAeronStreamIdentifiers;
    private final AtomicCounter failedPublications;
    private final int maxClaimAttempts;
    private final RecordingCoordinator recordingCoordinator;

    public Streams(
        final Aeron aeron,
        final String aeronChannel,
        final boolean printAeronStreamIdentifiers,
        final AtomicCounter failedPublications,
        final int streamId,
        final EpochNanoClock clock,
        final int maxClaimAttempts,
        final RecordingCoordinator recordingCoordinator)
    {
        this.aeron = aeron;
        this.aeronChannel = aeronChannel;
        this.printAeronStreamIdentifiers = printAeronStreamIdentifiers;
        this.failedPublications = failedPublications;
        this.streamId = streamId;
        this.clock = clock;
        this.maxClaimAttempts = maxClaimAttempts;
        this.recordingCoordinator = recordingCoordinator;
    }

    public GatewayPublication gatewayPublication(
        final IdleStrategy idleStrategy, final ExclusivePublication dataPublication)
    {
        return new GatewayPublication(
            dataPublication,
            failedPublications,
            idleStrategy,
            clock,
            maxClaimAttempts);
    }

    public ExclusivePublication dataPublication(final String name)
    {
        final ExclusivePublication publication = recordingCoordinator.trackEngine(aeronChannel, streamId);
        StreamInformation.print(name, publication, printAeronStreamIdentifiers);
        return publication;
    }

    public ExclusivePublication reproductionPublication()
    {
        final ExclusivePublication publication = recordingCoordinator.reproductionPublication();
        StreamInformation.print("recordingPublication", publication, printAeronStreamIdentifiers);
        return publication;
    }

    public Subscription subscription(final String name)
    {
        while (true)
        {
            try
            {
                final String channel = new ChannelUriStringBuilder(aeronChannel)
                    .alias(name)
                    .build();
                final Subscription subscription = aeron.addSubscription(channel, streamId);
                StreamInformation.print(name, subscription, printAeronStreamIdentifiers);
                return subscription;
            }
            // Catch and retry, this has only ever been seen in a CI system with a virtual file-system where the
            // created file doesn't appear in the virtual file system
            catch (final Exception e)
            {
                // Thrown by the underlying
                //noinspection ConstantConditions
                if (e instanceof NoSuchFileException)
                {
                    final NoSuchFileException noSuchFileException = (NoSuchFileException)e;
                    if (noSuchFileException.getMessage().contains(".logbuffer"))
                    {
                        continue;
                    }
                }

                LangUtil.rethrowUnchecked(e);
            }
        }
    }
}
