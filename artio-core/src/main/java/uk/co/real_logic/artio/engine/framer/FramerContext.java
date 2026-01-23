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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.*;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.ReadablePosition;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.StreamInformation;
import uk.co.real_logic.artio.engine.*;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.protocol.Streams;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.timing.EngineTimers;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Context that injects all the necessary information into different Framer classes.
 *
 * This enables many classes in the framer package to be package scoped as they don't
 * need the Fix Engine itself to touch them. This isn't considered part of the public API
 * and shouldn't be relied upon by Artio users.
 */
public class FramerContext
{
    private static final int ADMIN_COMMAND_CAPACITY = 64;

    private final QueuedPipe<AdminCommand> adminCommands = new ManyToOneConcurrentArrayQueue<>(ADMIN_COMMAND_CAPACITY);

    private final Framer framer;

    private final EngineConfiguration configuration;
    private final GatewaySessions gatewaySessions;
    private final SequenceNumberIndexReader sentSequenceNumberIndex;
    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final GatewayPublication outboundPublication;
    private final GatewayPublication inboundPublication;
    private final FixContexts fixContexts;
    private final FixPContexts fixPContexts;

    private volatile boolean startingClose = false;

    public FramerContext(
        final EngineConfiguration configuration,
        final FixCounters fixCounters,
        final EngineContext engineContext,
        final ErrorHandler errorHandler,
        final Image replayImage,
        final EngineTimers timers,
        final AgentInvoker conductorAgentInvoker,
        final RecordingCoordinator recordingCoordinator,
        final Aeron aeron)
    {
        this.configuration = configuration;

        final SessionIdStrategy sessionIdStrategy = configuration.sessionIdStrategy();
        final SessionIdGenerator sessionIdGenerator = configuration.sessionIdGenerator();

        final IdleStrategy idleStrategy = configuration.framerIdleStrategy();
        final Streams outboundLibraryStreams = engineContext.outboundLibraryStreams();

        this.fixContexts = new FixContexts(
            configuration.sessionIdBuffer(), sessionIdStrategy, configuration.initialSequenceIndex(), errorHandler,
            configuration.isReproductionEnabled(), sessionIdGenerator);
        this.fixPContexts = new FixPContexts(
            configuration.fixPIdBuffer(),
            errorHandler,
            configuration.epochNanoClock());

        this.inboundPublication = engineContext.inboundPublication();
        this.outboundPublication = outboundLibraryStreams.gatewayPublication(idleStrategy,
            outboundLibraryStreams.dataPublication("outboundPublication"));

        final Subscription adminEngineSubscription = newAdminEngineSubscription(aeron);
        final AdminReplyPublication adminReplyPublication = newAdminReplyPublication(aeron, fixCounters, idleStrategy);

        sentSequenceNumberIndex = new SequenceNumberIndexReader(
            configuration.sentSequenceNumberBuffer(), errorHandler, recordingCoordinator.framerOutboundLookup(),
            configuration.logFileDir());
        receivedSequenceNumberIndex = new SequenceNumberIndexReader(
            configuration.receivedSequenceNumberBuffer(), errorHandler, recordingCoordinator.framerInboundLookup(),
            null);

        final ReproductionLogWriter reproductionLogWriter = reproductionLogWriter(outboundLibraryStreams);

        final FixEndPointFactory endPointFactory;
        final SystemEpochClock epochClock = new SystemEpochClock();
        if (configuration.acceptsFixP())
        {
            gatewaySessions = new FixPGatewaySessions(
                epochClock,
                inboundPublication,
                outboundPublication,
                errorHandler,
                sentSequenceNumberIndex,
                receivedSequenceNumberIndex,
                configuration,
                fixPContexts);
            endPointFactory = null;
        }
        else
        {
            gatewaySessions = new FixGatewaySessions(
                epochClock,
                inboundPublication,
                outboundPublication,
                sessionIdStrategy,
                configuration.sessionCustomisationStrategy(),
                fixCounters,
                configuration,
                errorHandler,
                fixContexts,
                configuration.sessionPersistenceStrategy(),
                sentSequenceNumberIndex,
                receivedSequenceNumberIndex,
                configuration.sessionEpochFractionFormat());

            endPointFactory = new FixEndPointFactory(
                configuration,
                fixContexts,
                inboundPublication,
                reproductionLogWriter,
                fixCounters,
                errorHandler,
                (FixGatewaySessions)gatewaySessions,
                engineContext.senderSequenceNumbers(),
                configuration.messageTimingHandler());
        }

        final FinalImagePositions finalImagePositions = new FinalImagePositions();

        framer = new Framer(
            epochClock,
            timers.outboundTimer(),
            timers.sendTimer(),
            configuration,
            adminEngineSubscription,
            adminReplyPublication,
            endPointFactory,
            engineContext.outboundLibrarySubscription(
                "outboundLibrarySubscription", finalImagePositions),
            replayImage,
            engineContext.inboundReplayQuery(false),
            outboundPublication,
            inboundPublication,
            this.adminCommands,
            sessionIdStrategy,
            fixContexts,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex,
            gatewaySessions,
            errorHandler,
            configuration.agentNamePrefix(),
            engineContext.inboundCompletionPosition(),
            engineContext.outboundLibraryCompletionPosition(),
            finalImagePositions,
            recordingCoordinator,
            fixPContexts,
            aeron.countersReader(),
            engineContext.inboundIndexRegistrationId(),
            engineContext.outboundIndexRegistrationId(),
            fixCounters,
            engineContext.senderSequenceNumbers(),
            conductorAgentInvoker,
            reproductionLogWriter);
    }

    private ReproductionLogWriter reproductionLogWriter(final Streams outboundLibraryStreams)
    {
        if (configuration.writeReproductionLog())
        {
            return new ReproductionLogWriter(outboundLibraryStreams.reproductionPublication());
        }
        else
        {
            return null;
        }
    }

    private Subscription newAdminEngineSubscription(final Aeron aeron)
    {
        final Subscription adminEngineSubscription = aeron.addSubscription(
            configuration.libraryAeronChannel(), configuration.outboundAdminStream());
        StreamInformation.print(
            "adminEngineSubscription", adminEngineSubscription, configuration.printAeronStreamIdentifiers());
        return adminEngineSubscription;
    }

    private AdminReplyPublication newAdminReplyPublication(
        final Aeron aeron, final FixCounters fixCounters, final IdleStrategy idleStrategy)
    {
        final ExclusivePublication adminDataPublication = aeron.addExclusivePublication(
            configuration.libraryAeronChannel(), configuration.inboundAdminStream());
        StreamInformation.print(
            "adminEnginePublication", adminDataPublication, configuration.printAeronStreamIdentifiers());
        return new AdminReplyPublication(
            adminDataPublication,
            fixCounters.failedAdminReplyPublications(),
            idleStrategy,
            configuration.outboundMaxClaimAttempts());
    }

    public Agent framer()
    {
        return framer;
    }

    public Reply<List<LibraryInfo>> libraries()
    {
        final QueryLibrariesCommand reply = new QueryLibrariesCommand();

        if (adminCommands.offer(reply))
        {
            return reply;
        }

        return null;
    }

    public Reply<?> resetSequenceNumber(final long sessionId)
    {
        final ResetSequenceNumberCommand reply = new ResetSequenceNumberCommand(
            sessionId,
            gatewaySessions,
            configuration.acceptsFixP() ? fixPContexts : fixContexts,
            receivedSequenceNumberIndex,
            sentSequenceNumberIndex,
            inboundPublication,
            outboundPublication,
            configuration.epochNanoClock(),
            configuration.replyTimeoutInMs());

        if (adminCommands.offer(reply))
        {
            return reply;
        }

        return null;
    }

    public Reply<?> resetSessionIds(final File backupLocation)
    {
        if (backupLocation != null && !backupLocation.exists())
        {
            try
            {
                if (!backupLocation.createNewFile())
                {
                    throw new IllegalStateException("Could not create: " + backupLocation);
                }
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
        }

        final ResetSessionIdsCommand command = new ResetSessionIdsCommand(backupLocation);
        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }

    public void startClose()
    {
        final IdleStrategy idleStrategy = CommonConfiguration.backoffIdleStrategy();
        final DisconnectAllCommand command = new DisconnectAllCommand();
        while (!adminCommands.offer(command))
        {
            idleStrategy.idle();
        }
        idleStrategy.reset();

        startingClose = true;

        while (!command.hasCompleted())
        {
            idleStrategy.idle();
        }
        idleStrategy.reset();

        if (command.hasErrored())
        {
            LangUtil.rethrowUnchecked(command.error());
        }
    }

    public Reply<Long> lookupSessionId(
        final String localCompId,
        final String remoteCompId,
        final String localSubId,
        final String remoteSubId,
        final String localLocationId,
        final String remoteLocationId)
    {
        final LookupSessionIdCommand command = new LookupSessionIdCommand(
            localCompId,
            remoteCompId,
            localSubId,
            remoteSubId,
            localLocationId,
            remoteLocationId);

        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }

    public Reply<?> bind()
    {
        final BindCommand command = new BindCommand();

        if (!configuration.hasBindAddress())
        {
            command.onError(new IllegalStateException("Missing address: EngineConfiguration.bindTo()"));
            return command;
        }

        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }

    public Reply<?> unbind(final boolean disconnect)
    {
        final UnbindCommand command = new UnbindCommand(disconnect);

        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }

    public boolean offer(final AdminCommand command)
    {
        return adminCommands.offer(command);
    }

    public List<SessionInfo> allSessions()
    {
        return fixContexts.allSessions();
    }

    public List<FixPSessionInfo> allFixPSessions()
    {
        return fixPContexts.allSessions();
    }

    public Reply<ReadablePosition> libraryIndexedPosition(final int libraryId)
    {
        final PositionRequestCommand command = new PositionRequestCommand(
            libraryId);

        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }

    // Called on replayer thread
    public void resetOutboundReplayQuery(final long fixSessionId)
    {
        if (startingClose)
        {
            return;
        }

        final ResetReplayQueryCommand command = new ResetReplayQueryCommand(fixSessionId);

        final IdleStrategy idleStrategy = configuration.archiverIdleStrategy();
        // Need to poll the scheduler here in case we're in low resource mode where this blocking poll will
        // block the framer thread
        final EngineScheduler scheduler = configuration.scheduler();

        while (!adminCommands.offer(command) && !startingClose)
        {
            idleStrategy.idle(scheduler.pollFramer());
        }
        idleStrategy.reset();

        while (!command.isDone() && !startingClose)
        {
            idleStrategy.idle(scheduler.pollFramer());
        }
        idleStrategy.reset();
    }

    public Reply<?> startReproduction()
    {
        final StartReproduction command = new StartReproduction();
        while (!offer(command) && !startingClose)
        {
            Thread.yield();
        }
        return command;
    }

    public Reply<EngineStreamInfo> engineStreamInfo()
    {
        final EngineStreamInfoRequestCommand command = new EngineStreamInfoRequestCommand();

        if (adminCommands.offer(command))
        {
            return command;
        }

        return null;
    }
}
