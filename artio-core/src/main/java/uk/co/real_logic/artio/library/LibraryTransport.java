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
package uk.co.real_logic.artio.library;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.StreamInformation;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.protocol.Streams;

import static uk.co.real_logic.artio.LogTag.LIBRARY_CONNECT;

class LibraryTransport
{
    private static final String OUTBOUND_PUBLICATION = "library outboundPublication";

    private final LibraryConfiguration configuration;
    private final FixCounters fixCounters;
    private final Aeron aeron;
    private final EpochNanoClock clock;

    private Streams outboundLibraryStreams;
    private Subscription inboundSubscription;
    private GatewayPublication outboundPublication;
    private GatewayPublication inboundPublication;

    LibraryTransport(
        final LibraryConfiguration configuration,
        final FixCounters fixCounters,
        final Aeron aeron)
    {
        this.configuration = configuration;
        this.fixCounters = fixCounters;
        this.aeron = aeron;
        this.clock = configuration.epochNanoClock();
    }

    void initStreams(final String aeronChannel)
    {
        DebugLogger.log(LIBRARY_CONNECT, "Directed streams at ", aeronChannel);

        final int inboundLibraryStream = configuration.inboundLibraryStream();
        final int outboundLibraryStream = configuration.outboundLibraryStream();
        final boolean printAeronStreamIdentifiers = configuration.printAeronStreamIdentifiers();
        final IdleStrategy idleStrategy = configuration.libraryIdleStrategy();

        outboundLibraryStreams = new Streams(
            aeron,
            aeronChannel,
            printAeronStreamIdentifiers,
            fixCounters.failedOutboundPublications(),
            outboundLibraryStream,
            clock,
            configuration.outboundMaxClaimAttempts(),
            null);

        if (isReconnect())
        {
            inboundSubscription.close();
            outboundPublication.close();
            inboundPublication.close();
        }

        final String name = "library" + configuration.libraryId() + "InboundSubscription";
        final String channel = new ChannelUriStringBuilder(aeronChannel)
            .alias(name)
            .build();
        inboundSubscription = aeron.addSubscription(channel, inboundLibraryStream);
        StreamInformation.print(name, inboundSubscription, configuration);

        outboundPublication = outboundLibraryStreams.gatewayPublication(
            idleStrategy, outboundDataPublication(aeronChannel));

        final ExclusivePublication publication = aeron.addExclusivePublication(aeronChannel, inboundLibraryStream);
        StreamInformation.print("library inboundPublication", publication, printAeronStreamIdentifiers);
        inboundPublication = new GatewayPublication(
            publication,
            fixCounters.failedInboundPublications(),
            idleStrategy,
            clock,
            configuration.inboundMaxClaimAttempts());
    }

    void newOutboundPublication(final String aeronChannel)
    {
        outboundPublication.dataPublication(outboundDataPublication(aeronChannel));
    }

    private ExclusivePublication outboundDataPublication(final String aeronChannel)
    {
        final int outboundLibraryStream = configuration.outboundLibraryStream();
        final boolean printAeronStreamIdentifiers = configuration.printAeronStreamIdentifiers();

        final ExclusivePublication outboundData = aeron.addExclusivePublication(aeronChannel, outboundLibraryStream);
        StreamInformation.print(OUTBOUND_PUBLICATION, outboundData, printAeronStreamIdentifiers);
        return outboundData;
    }

    Subscription inboundSubscription()
    {
        return inboundSubscription;
    }

    GatewayPublication outboundPublication()
    {
        return outboundPublication;
    }

    GatewayPublication inboundPublication()
    {
        return inboundPublication;
    }

    boolean isReconnect()
    {
        return inboundSubscription != null;
    }
}
