/*
 * Copyright 2022 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.ReproductionClock;
import uk.co.real_logic.artio.engine.EngineReproductionConfiguration;
import uk.co.real_logic.artio.engine.RecordingCoordinator;
import uk.co.real_logic.artio.engine.logger.FixArchiveScanningAgent;

import static uk.co.real_logic.artio.engine.logger.FixMessageLogger.Configuration.DEFAULT_COMPACTION_SIZE;
import static uk.co.real_logic.artio.engine.logger.FixMessageLogger.Configuration.DEFAULT_MAXIMUM_BUFFER_SIZE;

class ReproductionPoller implements Continuation
{
    private final ReproductionClock clock;
    private final EngineReproductionConfiguration configuration;
    private final ReproductionProtocolHandler protocolHandler;
    private final String logFileDir;
    private final Aeron aeron;
    private final AeronArchive archive;
    private final String aeronChannel;
    private final int inboundLibraryStreamId;
    private final int reproductionReplayStream;
    private final IdleStrategy idleStrategy;

    // NB: we should never close this class as it closes the Archiver
    private enum State
    {
        AWAITING_SCANNER,
        POLLING,
        COMPLETE
    }

    private State state = State.AWAITING_SCANNER;
    private FixArchiveScanningAgent archiveScanningAgent;
    private StartReproduction startReproduction;
    private Int2ObjectHashMap<LiveLibraryInfo> idToLibrary;

    ReproductionPoller(
        final EngineReproductionConfiguration configuration,
        final TcpChannelSupplier channelSupplier,
        final IdleStrategy idleStrategy,
        final String logFileDir,
        final RecordingCoordinator recordingCoordinator,
        final String aeronChannel,
        final int inboundLibraryStreamId,
        final int reproductionReplayStream)
    {
        this.configuration = configuration;
        this.idleStrategy = idleStrategy;

        clock = configuration.clock();
        protocolHandler = new ReproductionProtocolHandler(
            (ReproductionTcpChannelSupplier)channelSupplier,
            clock,
            this::onError);
        this.logFileDir = logFileDir;
        this.aeron = recordingCoordinator.aeron();
        this.archive = recordingCoordinator.archive();
        this.aeronChannel = aeronChannel;
        this.inboundLibraryStreamId = inboundLibraryStreamId;
        this.reproductionReplayStream = reproductionReplayStream;
    }

    private void onError(final Throwable throwable)
    {
        throwable.printStackTrace();
        startReproduction.onError(throwable);
        state = State.COMPLETE;
    }

    @Override
    public long attempt()
    {
        switch (state)
        {
            case AWAITING_SCANNER:
                return Publication.BACK_PRESSURED;

            case POLLING:
                return pollArchive();

            case COMPLETE:
            default:
                return COMPLETE;
        }
    }

    private long pollArchive()
    {
        try
        {
            final FixArchiveScanningAgent archiveScanningAgent = this.archiveScanningAgent;
            // System.out.println("polling underneath");
            if (protocolHandler.operationInProgress())
            {
                // System.out.println("Op in progress BP");

                return Publication.BACK_PRESSURED;
            }

            protocolHandler.resetCount();
            final boolean complete = archiveScanningAgent.poll(1);
            protocolHandler.checkCount(1);
            if (complete)
            {
                startReproduction.onComplete();
                return complete();
            }
        }
        catch (final Throwable e)
        {
            startReproduction.onError(e);
            return complete();
        }

        return Publication.BACK_PRESSURED;
    }

    private long complete()
    {
        this.archiveScanningAgent = null;
        this.state = State.COMPLETE;
        return COMPLETE;
    }

    void start(final StartReproduction startReproduction, final Int2ObjectHashMap<LiveLibraryInfo> idToLibrary)
    {
        this.startReproduction = startReproduction;
        this.idToLibrary = idToLibrary;

        protocolHandler.idToLibrary(idToLibrary);

        archiveScanningAgent = new FixArchiveScanningAgent(
            idleStrategy,
            DEFAULT_COMPACTION_SIZE,
            DEFAULT_MAXIMUM_BUFFER_SIZE,
            1,
            logFileDir,
            aeron,
            archive);

        final IntHashSet queryStreamIds = new IntHashSet();
        queryStreamIds.add(inboundLibraryStreamId);

        archiveScanningAgent.setup(
            aeronChannel, queryStreamIds, protocolHandler, null, false, reproductionReplayStream);

        state = State.POLLING;
    }

    long newConnectionId()
    {
        return protocolHandler.newConnectionId();
    }
}
