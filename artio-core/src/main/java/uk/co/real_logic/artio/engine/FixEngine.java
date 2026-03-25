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

import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.status.ReadablePosition;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.engine.framer.EngineStreamInfo;
import uk.co.real_logic.artio.engine.framer.FramerContext;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.timing.EngineTimers;

import java.io.File;
import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.suppressingClose;

/**
 * A FIX Engine is a process in the gateway that accepts or initiates FIX connections and
 * hands them off to different FixLibrary instances. The engine can replicate and/or durably
 * store streams of FIX messages for replay, archival, administrative or analytics purposes.
 * <p>
 * Each engine can have one or more associated libraries that manage sessions and perform business
 * logic. These may run in the same JVM process or a different JVM process.
 *
 * @see uk.co.real_logic.artio.library.FixLibrary
 */
public final class FixEngine extends GatewayProcess
{
    private final Object closeMutex = new Object();

    public static final int ENGINE_LIBRARY_ID = 0;

    private final DuplicateEngineChecker duplicateEngineChecker;
    private final EngineTimers timers;
    private final EngineConfiguration configuration;
    private final RecordingCoordinator recordingCoordinator;

    private final EngineScheduler scheduler;
    private FramerContext framerContext;
    private EngineContext engineContext;

    private volatile boolean startingClose = false;
    private volatile boolean isClosed = false;

    private final Object resetStateLock = new Object();
    private volatile boolean stateHasBeenReset = false;
    private boolean launched = false;

    /**
     * Launch the engine. This method starts up the engine threads and then returns.
     *
     * @param configuration the configuration to use for this engine.
     * @return the new FIX engine instance.
     */
    public static FixEngine launch(final EngineConfiguration configuration)
    {
        return new FixEngine(configuration);
    }

    /**
     * Query the engine for the list of libraries currently active.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return a list of currently active libraries.
     */
    public Reply<List<LibraryInfo>> libraries()
    {
        return framerContext.libraries();
    }

    /**
     * Unbinds the acceptor socket. This does not disconnect any currently connected TCP connections.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> unbind()
    {
        return unbind(false);
    }

    /**
     * Unbinds the acceptor socket, and optionally starts an end of day
     * operation if requested. The end of day operation performs the following
     * steps:
     *
     * <ol>
     *     <li>Sends a logout message to every FIX counter-party</li>
     *     <li>Disconnects each FIX connection after it receives either a logout reply
     *     or the heartbeat timeout elapses.</li>
     *     <li>Disconnects the libraries from the engine</li>
     * </ol>
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @param endOfDay if currently connected connections need to be logged out and disconnected
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> unbind(final boolean endOfDay)
    {
        return framerContext.unbind(endOfDay);
    }

    /**
     * Binds the acceptor socket to the configured address. This only needs to be called if you had called
     * {@link #unbind()} previously - {@link FixEngine#launch()} will bind the socket by default.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> bind()
    {
        return framerContext.bind();
    }

    /**
     * Resets the set of session ids. Use of just this method on its own can result in inconsistent Artio state.
     * It is recommended that you use the {@link #resetState(File)} method or {@link #pruneArchive(Long2LongHashMap)}
     * instead to reset state. This method is Deprecated and may be removed in a future version of Artio.
     *
     * @param backupLocation the location to backup the current session ids file to.
     *                       Can be null to indicate that no backup is required.
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    @Deprecated
    public Reply<?> resetSessionIds(final File backupLocation)
    {
        return framerContext.resetSessionIds(backupLocation);
    }

    /**
     * Resets the sequence number of a given session. Asynchronous method, the Reply instance
     * needs to be polled to ensure that it has completed. This operation can be used to reset the sequence numbers of
     * FIX or FIXP sessions.
     *
     * In the case of FIXP sessions the sequence numbers can only be reset when the session is
     * disconnected. The sequence number reset behaviour is dependent on the protocol.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @param sessionId the id of the session that you want to reset
     *
     * @return the reply object, or null if the request hasn't been successfully enqueued.
     */
    public Reply<?> resetSequenceNumber(final long sessionId)
    {
        return framerContext.resetSequenceNumber(sessionId);
    }

    /**
     * This method resets the state of the FixEngine that also performs usual end of day processing
     * operations. These are:
     *
     * <ol>
     *     <li>Reset and optionally back up all Artio state (including session ids and sequence numbers).</li>
     *     <li>Truncate any recordings associated with this engine instance.</li>
     * </ol>
     *
     * It must only be called when the FixEngine object has been closed. Blocks until the operation is complete.
     *
     * @param backupLocation the directory that you wish to copy Artio's session state over to for later inspection.
     *                       If this is null no backup of data will be performed. If the directory exists it will be
     *                       re-used, if it doesn't it will be created.
     *
     * @throws IllegalStateException if this <code>FixEngine</code> hasn't been closed when this method is called.
     */
    public void resetState(final File backupLocation)
    {
        if (!isClosed())
        {
            throw new IllegalStateException("Engine should be closed before the state is reset");
        }

        synchronized (resetStateLock)
        {
            if (!stateHasBeenReset)
            {
                final ResetArchiveState resetArchiveState = new ResetArchiveState(
                    configuration, backupLocation, recordingCoordinator);
                resetArchiveState.resetState();

                stateHasBeenReset = true;
            }
        }
    }

    /**
     * Gets a ReadablePosition that exposes where the Artio indexer has indexed up to for a library id. This can be
     * used to track the persistence of messages that are sent via an Artio library.
     *
     * @param libraryId the library id of the library that you want to track the position for.
     * @return a reply that either succeeds with the ReadablePosition or errors if the Engine doesn't know about
     * the library in question.
     */
    public Reply<ReadablePosition> libraryIndexedPosition(final int libraryId)
    {
        return framerContext.libraryIndexedPosition(libraryId);
    }

    /**
     * Gets session info for all FIX sessions the FixEngine is aware of including offline ones.
     * Can be used to acquire offline sessions or for administration purposes.
     * The returned list is updated in a thread-safe manner when new sessions are created.
     *
     * @return the list of session infos.
     */
    public List<SessionInfo> allSessions()
    {
        return framerContext.allSessions();
    }

    /**
     * Gets session info for all FIXP sessions the FixEngine is aware of including offline ones.
     * The returned list is updated in a thread-safe manner when new sessions are created.
     *
     * @return the list of FIXP session infos.
     */
    public List<FixPSessionInfo> allFixPSessions()
    {
        return framerContext.allFixPSessions();
    }

    /**
     * Gets the session id associated with some combination of id fields.
     *
     * If the reply is <code>null</code> then the query hasn't been enqueued and the operation
     * should be retried on a duty cycle.
     *
     * @param localCompId the senderCompId of messages sent by the gateway on this session.
     * @param remoteCompId the senderCompId of messages received by the gateway on this session.
     * @param localSubId the senderSubId of messages sent by the gateway on this session
     *                   or <code>null</code> if not used in session identification.
     * @param remoteSubId the senderSubId of messages received by the gateway on this session
     *                    or <code>null</code> if not used in session identification.
     * @param localLocationId the senderLocationId of messages sent by the gateway on this session
     *                        or <code>null</code> if not used in session identification.
     * @param remoteLocationId the senderLocationId of messages received by the gateway on this session
     *                         or <code>null</code> if not used in session identification.
     * @return the reply object asynchronously wrapping the session id
     */
    public Reply<Long> lookupSessionId(
        final String localCompId,
        final String remoteCompId,
        final String localSubId,
        final String remoteSubId,
        final String localLocationId,
        final String remoteLocationId)
    {
        return framerContext.lookupSessionId(
            localCompId, remoteCompId, localSubId, remoteSubId, localLocationId, remoteLocationId);
    }

    private FixEngine(final EngineConfiguration configuration)
    {
        {
            try
            {
                configuration.conclude();
                this.configuration = configuration;
                duplicateEngineChecker = new DuplicateEngineChecker(
                    configuration.duplicateEngineTimeoutInMs(),
                    configuration.logFileDir(),
                    configuration.errorIfDuplicateEngineDetected());
                duplicateEngineChecker.check();

                scheduler = configuration.scheduler();
                scheduler.configure(configuration.aeronContext());
                init(configuration, ENGINE_LIBRARY_ID);
                timers = new EngineTimers(configuration.epochNanoClock(), fixCounters.negativeTimestamps());
                final AeronArchive.Context archiveContext = configuration.aeronArchiveContext();
                final AeronArchive aeronArchive =
                    configuration.logAnyMessages() ? AeronArchive.connect(archiveContext.aeron(aeron)) : null;
                recordingCoordinator = new RecordingCoordinator(
                    aeron,
                    aeronArchive,
                    configuration,
                    configuration.archiverIdleStrategy(),
                    errorHandler);

                final ExclusivePublication replayPublication = replayPublication();
                engineContext = new EngineContext(
                    configuration,
                    errorHandler,
                    replayPublication,
                    fixCounters,
                    aeron,
                    aeronArchive,
                    recordingCoordinator);
                engineContext.catchupIndices();
                initFramer(configuration, fixCounters, replayPublication.sessionId());
                initMonitoringAgent(timers.all(), configuration, aeronArchive, duplicateEngineChecker);

                recordingCoordinator.monitoringAgent(monitoringAgent);

                launch();
            }
            catch (final Exception e)
            {
                if (engineContext != null)
                {
                    engineContext.completeDuringStartup();
                }

                suppressingClose(this, e);

                throw e;
            }
        }
    }

    private ExclusivePublication replayPublication()
    {
        final ExclusivePublication publication = aeron.addExclusivePublication(
            IPC_CHANNEL, configuration.outboundReplayStream());
        StreamInformation.print("replayPublication", publication, configuration);
        return publication;
    }

    private void initFramer(
        final EngineConfiguration configuration, final FixCounters fixCounters, final int replaySessionId)
    {
        framerContext = new FramerContext(
            configuration,
            fixCounters,
            engineContext,
            errorHandler,
            replayImage("replay", replaySessionId),
            timers,
            aeron.conductorAgentInvoker(),
            recordingCoordinator,
            aeron);

        engineContext.framerContext(framerContext);
    }

    private Image replayImage(final String name, final int replaySessionId)
    {
        final String channel = new ChannelUriStringBuilder(IPC_CHANNEL)
            .alias(name)
            .build();
        final Subscription subscription = aeron.addSubscription(
            channel, configuration.outboundReplayStream());
        StreamInformation.print(name, subscription, configuration);

        // Await replay publication
        while (true)
        {
            final Image image = subscription.imageBySessionId(replaySessionId);
            if (image != null)
            {
                return image;
            }

            invokeAeronConductor();

            Thread.yield();
        }
    }

    // To be invoked by called called before a scheduler has launched
    private void invokeAeronConductor()
    {
        final AgentInvoker invoker = aeron.conductorAgentInvoker();
        if (invoker != null)
        {
            invoker.invoke();
        }
    }

    private FixEngine launch()
    {
        scheduler.launch(
            configuration,
            errorHandler,
            framerContext.framer(),
            engineContext.indexingAgent(),
            monitoringCompositeAgent,
            conductorAgent(),
            recordingCoordinator);

        launched = true;

        return this;
    }

    /**
     * Close the engine down, including stopping other running threads. This also stops accepting new connections, and
     * logs out and disconnects all currently active FIX sessions.
     *
     * NB: graceful shutdown of the FixEngine will wait for logouts to occur. This entails communicating with all
     * <code>FixLibrary</code> instances currently live in order for them to gracefully close as well. Therefore if you
     * close a <code>FixLibrary</code> before you call this method then the close operation could be delayed by up to
     * {@link uk.co.real_logic.artio.CommonConfiguration#replyTimeoutInMs()} in order for the <code>FixEngine</code>
     * to timeout the <code>FixLibrary</code>. In order for graceful shutdown to successfully occur you should also
     * ensure that any connected <code>FixLibrary</code> instances are regularly polled on their duty cycle.
     *
     * This does not remove files associated with the engine, that are persistent over multiple runs of the engine.
     */
    public void close()
    {
        synchronized (closeMutex)
        {
            if (!isClosed)
            {
                startingClose = true;

                DebugLogger.log(LogTag.CLOSE, "Shutdown initiated through FixEngine.close()");

                if (launched)
                {
                    framerContext.startClose();
                }

                try
                {
                    closeAll(scheduler, engineContext, configuration, super::close, duplicateEngineChecker::finalClose);
                }
                finally
                {
                    isClosed = true;
                }
            }
        }
    }

    /**
     * Find out whether the {@link #close()} operation has been called.
     *
     * @return true if the {@link #close()} operation has been called, false otherwise.
     */
    public boolean isClosed()
    {
        return isClosed;
    }

    /**
     * Frees up space from the Aeron archive of messages. This operation does not remove all entries from the Aeron
     * archive logs: only entries that are not part of the latest sequence index. That means that resend requests for
     * the current sequence index can still be processed.
     *
     * Archive logs are pruned in chunks called segments - see the Aeron Archiver documentation for
     * details. This means that if there are less than a segment's worth of messages that can be
     * freed up then no space is pruned.
     *
     * The recordingIdToMinimumPrunePositions parameter can be useful for users who are doing a Streaming backup of the
     * Aeron Archiver log. If you are streaming off the recorded streams from Artio to an external location, eg: a cloud
     * backup location, then this parameter allows you to stop Artio from accidentally pruning away parts of the
     * recorded Stream that this streaming backup is processing by providing the position that this streaming
     * recording is at.
     *
     * @param recordingIdToMinimumPrunePositions the minimum positions to prune or <code>null</code> otherwise.
     *                                           If you're archiving segments of the
     *                                           Aeron archive log then this parameter can be used in order to stop
     *                                           those segments from being removed. The hashmap should be initialised
     *                                           with <code>new Long2LongHashMap(Aeron.NULL_VALUE)</code>.
     * @return the positions pruned up to. This is a map from recording id to a pruned position if pruning has occurred.
     *         It may be empty if no recordings have been pruned. <code>Aeron.NULL_VALUE</code> is used to denote
     *         missing values in the map.
     */
    public Reply<Long2LongHashMap> pruneArchive(final Long2LongHashMap recordingIdToMinimumPrunePositions)
    {
        if (startingClose)
        {
            return engineContext.pruneArchive(new IllegalStateException("Unable to prune archive during shutdown."));
        }

        if (isClosed)
        {
            return engineContext.pruneArchive(new IllegalStateException("Unable to prune archive when closed."));
        }

        return engineContext.pruneArchive(recordingIdToMinimumPrunePositions);
    }

    public EngineConfiguration configuration()
    {
        return configuration;
    }

    // Exceptions here are always on internal FixEngine threads and should not cause those threads to terminate.
    protected boolean shouldRethrowExceptionInErrorHandler()
    {
        return false;
    }

    /**
     * Start a reproduction. In order for this operation to be used then
     * {@link EngineConfiguration#reproduceInbound(long, long)} must be enabled.
     *
     * @return the reply for the operation. This reply completes when the reproduction completes or results in an error
     * if there is an error running the reproduction operation.
     */
    public Reply<?> startReproduction()
    {
        if (configuration.reproductionConfiguration() == null)
        {
            throw new IllegalStateException(
                "Engine must be configured to support reproduction");
        }

        return framerContext.startReproduction();
    }

    /**
     * Returns information about engine streams. Internal API for testing.
     *
     * @return a reply that should eventually contain information about engine streams or {@code null} if back-pressured
     */
    Reply<EngineStreamInfo> engineStreamInfo()
    {
        return framerContext.engineStreamInfo();
    }
}
