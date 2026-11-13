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
package uk.co.real_logic.artio.library;

import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SystemEpochClock;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.GatewayProcess;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.ilink.ILink3Connection;
import uk.co.real_logic.artio.ilink.ILink3ConnectionConfiguration;
import uk.co.real_logic.artio.messages.MetaDataStatus;
import uk.co.real_logic.artio.messages.SessionReplyStatus;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionWriter;
import uk.co.real_logic.artio.timing.LibraryTimers;

import java.io.File;
import java.util.List;

import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;

/**
 * FIX Library instances represent a process in the gateway where session management,
 * message parsing and API users configure the gateway.
 * <p>
 * Libraries can be run in the same process as the engine, or in a
 * different process.
 * <p>
 * FixLibrary instances are not thread safe and should be run on
 * their own thread.
 *
 * @see uk.co.real_logic.artio.engine.FixEngine
 */
public class FixLibrary extends GatewayProcess
{
    public static final int NO_MESSAGE_REPLAY = -1;
    public static final int CURRENT_SEQUENCE = -2;

    // FixLibrary instances throw exceptions up to their invokers. Aeron catches exceptions in methods like
    // Image.controlledPoll. This thread local is used to indicate which thread is a client conductor thread
    // and thus can't safely rethrow the exception
    private static final ThreadLocal<Boolean> RETHROW_EXCEPTION = ThreadLocal.withInitial(() -> true);

    private final LibraryConfiguration configuration;
    private final LibraryScheduler scheduler;
    private final LibraryPoller poller;
    private boolean isPolling = false;

    static void setClientConductorThread()
    {
        RETHROW_EXCEPTION.set(false);
    }

    FixLibrary(final LibraryConfiguration configuration)
    {
        this.configuration = configuration;
        scheduler = configuration.scheduler();
        configuration.conclude();

        try
        {
            scheduler.configure(configuration.aeronContext());
            init(configuration, configuration.libraryId());
            final LibraryTimers timers = new LibraryTimers(
                configuration.epochNanoClock(), fixCounters.negativeTimestamps());
            initMonitoringAgent(timers.all(), configuration, null, null);

            final LibraryTransport transport = new LibraryTransport(configuration, fixCounters, aeron);
            final EpochClock epochClock = configuration.isReproductionEnabled() ?
                configuration.reproductionConfiguration().clock().toMillis() : new SystemEpochClock();
            poller = new LibraryPoller(
                configuration, timers, fixCounters, transport, this, epochClock, errorHandler);
        }
        catch (final Exception e)
        {
            try
            {
                closeAnythingHoldingFileHandles();
                deleteFiles();
            }
            catch (final Exception innerException)
            {
                innerException.addSuppressed(e);
                throw innerException;
            }
            throw e;
        }
    }

    private void closeAnythingHoldingFileHandles()
    {
        if (monitoringCompositeAgent == null)
        {
            monitoringFile.close();
        }
        else
        {
            monitoringCompositeAgent.onClose();
        }
    }

    private FixLibrary connect()
    {
        poller.startConnecting();
        scheduler.launch(configuration, errorHandler, monitoringCompositeAgent, conductorAgent());
        poller.onStart();
        return this;
    }

    // ------------- Public API -------------

    /**
     * Start connecting to an engine. This method returns a FixLibrary immediately even if it hasn't connected.
     *
     * You should call {@link #poll(int)} on a regular duty cycle until the connection completes.
     * {@link #isConnected()} can be polled in order to determine whether library is connected. Also the
     * {@link LibraryConnectHandler#onConnect(FixLibrary)} method will be invoked.
     *
     * @param configuration the configuration for this library instance.
     * @return the library instance.
     * @throws FixGatewayException
     *         if there's an error connecting to the FIX Gateway.
     */
    public static FixLibrary connect(final LibraryConfiguration configuration)
    {
        return new FixLibrary(configuration).connect();
    }

    /**
     * Poll the library all of its component sessions to process any messages
     * and events that have received from or should be sent to the engine.
     *
     * @param fragmentLimit the maximum number of events to read from the engine.
     * @return 0 if no work was performed, &gt; 0 otherwise.
     */
    public int poll(final int fragmentLimit)
    {
        isPolling = true;
        try
        {
            return poller.poll(fragmentLimit);
        }
        finally
        {
            isPolling = false;
        }
    }

    void clearPollStatus()
    {
        isPolling = false;
    }

    /**
     * Check if the library is connected to an engine.
     * <p>
     * Note that this refers to whether a library is connected to a FIX Engine,
     * not whether of its sessions are connected.
     *
     * @return true if the library is connected to an engine, false otherwise.
     * @see Session#isConnected()
     * @see uk.co.real_logic.artio.engine.FixEngine
     */
    public boolean isConnected()
    {
        return poller.isConnected();
    }

    public boolean isAtEndOfDay()
    {
        return poller.isAtEndOfDay();
    }

    public boolean isClosed()
    {
        return poller.isClosed();
    }

    /**
     * Get the identifier of the library.
     *
     * @return the identifier of the library.
     */
    public int libraryId()
    {
        return poller.libraryId();
    }

    /**
     * Get a list of the currently active sessions.
     * <p>
     * Note: the list is unmodifiable.
     *
     * @return a list of the currently active sessions.
     */
    public List<Session> sessions()
    {
        return poller.sessions();
    }

    /**
     * Get a list of the initiator sessions that are currently pending. This means that the session has succesfully
     * connected via TCP but it hasn't completed the FIX initiator logon process yet. Once a session in this list
     * sends it's Logon message and receives a valid Logon message in response then its state becomes ACTIVE and
     * it is moved to the {@link #sessions()} list.
     * <p>
     * Note: the list is unmodifiable.
     *
     * @return a list of the initiator sessions that are currently pending.
     */
    public List<Session> pendingInitiatorSessions()
    {
        return poller.pendingInitiatorSessions();
    }

    /**
     * Close the Library. This will also remove all files associated with the library.
     */
    @Override
    public void close()
    {
        if (isPolling)
        {
            throw new IllegalArgumentException("You cannot close the library in the middle of a poll");
        }

        internalClose();
    }

    void internalClose()
    {
        closeAll(poller, () -> scheduler.close(libraryId()), super::close, this::deleteFiles);
    }

    private void deleteFiles()
    {
        final boolean deletedHistogramFile = removeParentDirectory(configuration.histogramLoggingFile());
        final boolean deletedMonitoringFile = removeParentDirectory(configuration.monitoringFile());

        checkFileDeletion(deletedHistogramFile, configuration.histogramLoggingFile());
        checkFileDeletion(deletedMonitoringFile, configuration.monitoringFile());
    }

    private void checkFileDeletion(final boolean deletedFile, final String path)
    {
        if (!deletedFile)
        {
            throw new FixGatewayException("Unable to delete: " + path);
        }
    }

    private boolean removeParentDirectory(final String path)
    {
        final File file = new File(path);
        if (file.exists() && !file.delete())
        {
            return false;
        }

        final File parentFile = file.getParentFile();
        if (parentFile != null && parentFile.exists() && parentFile.listFiles().length == 0)
        {
            IoUtil.delete(parentFile, true);
        }

        return true;
    }

    /**
     * Initiate a FIX session. Artio will connect to the specified FIX acceptor / server and attempt to logon.
     * This method returns a reply object wrapping the Session itself.
     *
     * @param configuration the configuration to use for the session.
     * @return the session object for the session that you've initiated. It can return the following errors:
     *         {@link IllegalStateException}
     *         if you're trying to initiate two sessions at the same time or if there's a timeout talking to
     *         the {@link uk.co.real_logic.artio.engine.FixEngine}.
     *         This probably indicates that there's a problem in your code or that your engine isn't running.
     *         {@link FixGatewayException}
     *         if you're unable to connect to the accepting gateway.
     *         This probably indicates a configuration problem related to the external gateway.
     * @see LibraryUtil#initiate(FixLibrary, SessionConfiguration, int, IdleStrategy)
     */
    public Reply<Session> initiate(final SessionConfiguration configuration)
    {
        return poller.initiate(configuration);
    }

    /**
     * Release this session object to the gateway to manage. If the release
     * operation has successfully completed then it will return {@link SessionReplyStatus#OK}.
     *
     * Similar to {@link #initiate(SessionConfiguration)} this is a non-blocking operation that
     * returns a reply object that indicates what has happened to its result.
     *
     * @param session the session to release
     * @param timeoutInMs the timeout for this operation
     * @return the result of this operation.
     */
    public Reply<SessionReplyStatus> releaseToGateway(final Session session, final long timeoutInMs)
    {
        CommonConfiguration.validateTimeout(timeoutInMs);
        return poller.releaseToGateway(session, timeoutInMs);
    }

    /**
     * Request a session be acquired from the Gateway. It returns a {@link LibraryReply} object.
     *
     * If this session is being managed by
     * the gateway then your {@link SessionAcquireHandler} will receive a callback
     * and the reply will be {@link SessionReplyStatus#OK}. You may also receive the reply of
     * {@link SessionReplyStatus#SEQUENCE_NUMBER_TOO_HIGH} if the sequence number you have passed in
     * is higher than the current sequence number known by the engine. This may happen to a sequence reset.
     * In this case you will still get the callback to the {@link SessionAcquireHandler} but won't get a
     * replay on any messages. You will also get a callback on the {@link SessionAcquireHandler} in the
     * {@link SessionReplyStatus#MISSING_MESSAGES} and
     * {@link SessionReplyStatus#INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES} case but you won't necessarily get the
     * requested replay of messages.
     *
     * If another library has acquired the session then this method will return
     * {@link SessionReplyStatus#OTHER_SESSION_OWNER}. If the connection id refers
     * to an unknown session then the method returns {@link SessionReplyStatus#UNKNOWN_SESSION}.
     * If this library instance is unknown to the gateway, for example if its heartbeating
     * mechanism has timed out due to {@link #poll(int)} not being called often enough.
     *
     * If you request a session that exists in the engine but which is not connected then an offline session will be
     * returned. This is a session whose state is disconnected and has no connection id, connectedHost or connectedPort.
     *
     * @param sessionId the id of the session to acquire.
     * @param resendFromSequenceNumber the last received message sequence number
     *                                   that you know about. You will get a stream
     *                                   of messages replayed to you from
     *                                   <code>resendFromSequenceNumber</code>
     *                                   to the latest message sequence number.
     *                                   If you don't care about message replay then
     *                                   use {@link FixLibrary#NO_MESSAGE_REPLAY} as the parameter.
     * @param resendFromSequenceIndex the index of the sequence within which the resendFromSequenceNumber
     *                      refers. if you wish to use the current sequence (ie all messages since the latest logon
     *                      then you can use {@link FixLibrary#CURRENT_SEQUENCE}.If you don't care about message replay
     *                      then use {@link FixLibrary#NO_MESSAGE_REPLAY} as the parameter.
     * @param timeoutInMs the timeout for this operation
     * @return the reply object representing the result of the request.
     */
    public Reply<SessionReplyStatus> requestSession(
        final long sessionId,
        final int resendFromSequenceNumber,
        final int resendFromSequenceIndex,
        final long timeoutInMs)
    {
        CommonConfiguration.validateTimeout(timeoutInMs);
        return poller.requestSession(sessionId, resendFromSequenceNumber, resendFromSequenceIndex, timeoutInMs);
    }

    /**
     * Creates a new SessionWriter for a specified session. This can be used in a clustered system to write messages
     * outbound for a system on its primary node. In a clustered system the <code>SessionProxy</code> would be hooked so
     * writing messages outbound on a normal Session object won't work.
     *
     * Messages written using this writer won't update the sent sequence number of any corresponding Session object.
     * This is designed to support the intended use-case of hooking the session proxy.
     *
     * @param sessionId the id of the session to use.
     * @param connectionId the id of the connection to use.
     * @param sequenceIndex the sequence index that the SessionWriter should start at.
     * @return the created SessionWriter
     */
    public SessionWriter sessionWriter(
        final long sessionId, final long connectionId, final int sequenceIndex)
    {
        return poller.sessionWriter(sessionId, connectionId, sequenceIndex);
    }

    /**
     * Create a SessionWriter for a Session from a different Artio instance. This SessionWriter can be used in a
     * clustered system to fill the archive on a follower node with FIX messages that have been replicated by a
     * leader node.
     *
     * Messages written using this writer will update the sent sequence number of any corresponding Session object.
     * This allows offline sessions to be created using this method and messages sent via either the SessionWriter
     * or the Session itself.
     *
     * For the FIXP version see {@link #followerFixPSession(FixPContext, long)}.
     *
     * @param headerEncoder the message header that contains fields that identify the Session. You could set the
     *                      senderCompId and targetCompId on this header for example if those are the fields used to
     *                      identify your session.
     * @param timeoutInMs the timeout required for this operation.
     * @return a <code>Reply</code> that will eventually contain the <code>SessionWriter</code>.
     */
    public Reply<SessionWriter> followerSession(
        final SessionHeaderEncoder headerEncoder, final long timeoutInMs)
    {
        return poller.followerSession(headerEncoder, timeoutInMs);
    }

    /**
     * Create a FixPConnection for a FIXP Session from a different Artio instance. This FixPConnection can be used in a
     * clustered system to fill the archive on a follower node with FIX messages that have been replicated by a
     * leader node. This allows offline sessions to be created using this method.
     *
     * When a connection is re-established with the correct logon credentials then the messages written via this
     * offline session can be retransmitted. If a session already exists within the engine with the same identity then
     * no duplicate session will be created and the session id of the original session will be returned. In the case
     * that the session is currently connected, care must be taken to ensure that the version of the FIXP connection
     * is the same as the connected session id or an error will be returned.
     *
     * For the FIX version see {@link #followerSession(SessionHeaderEncoder, long)}.
     *
     * @param context the context that uniquely identifies the connection
     * @param testTimeoutInMs the timeout required for this operation.
     * @return the sessionId that can be used to request the offline session object using {@link #requestSession(long, int, int, long)}.
     * @throws IllegalArgumentException if the {@link uk.co.real_logic.artio.fixp.FixPProtocol} of the context is
     * different to the established protocol within the library.
     */
    public Reply<Long> followerFixPSession(
        final FixPContext context, final long testTimeoutInMs)
    {
        return poller.followerFixPSession(context, testTimeoutInMs);
    }

    /**
     * Write meta data associated with a session. Session meta-data is a sequence of bytes that application can
     * associate with a session. It shares it's lifecycle with the current session - so whenever sequence numbers or
     * seession ids are reset the old meta-data will be reset as well. If the session is persistent then the metadata
     * persists over restarts.
     *
     * You can use session meta data to store information like ids for internal systems that correspond to
     * FIX sessions.
     *
     * This method can be used both to update existing metadata and to initialise the sessions'
     * metadata. When updating any metadata before the <code>metaDataOffset</code> position within the metadata buffer
     * or after <code>metaDataOffset + length</code> will be left as previous. When i
     *
     * This is an asynchronous operation and the returned reply object should be checked for completion.
     *
     * @param sessionId the session id of the session that meta data is written to.
     * @param metaDataUpdateOffset the offset within the session's metadata buffer. <code>0</code> should be used for
     *                       initialization.
     * @param buffer the buffer where the meta data to be written is stored.
     * @param offset the offset within the buffer
     * @param length the length of the data within the buffer.
     * @return a Reply to indicate completion or an error code.
     */
    public Reply<MetaDataStatus> writeMetaData(
        final long sessionId,
        final int metaDataUpdateOffset,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        return poller.writeMetaData(sessionId, metaDataUpdateOffset, buffer, offset, length);
    }

    /**
     * Read the meta data associated with a session.
     *
     * @param sessionId the id of the session that meta data is read from.
     * @param handler the callback that has the returned metadata.
     */
    public void readMetaData(
        final long sessionId, final MetadataHandler handler)
    {
        poller.readMetaData(sessionId, handler);
    }

    public String currentAeronChannel()
    {
        return poller.currentAeronChannel();
    }

    /**
     * Initiate an ILink3 connection. Artio will connect to the iLink server and attempt to logon.
     * This method returns a reply object wrapping the Connection itself.
     *
     * NB: This is an experimental API and is subject to change or potentially removal.
     *
     * @param configuration the configuration for this Session.
     * @return a reply object wrapping the Connection itself.
     * @see <a href="https://github.com/artiofix/artio/wiki/ILink-3-Support">
     *     https://github.com/artiofix/artio/wiki/ILink-3-Support</a>
     */
    public Reply<ILink3Connection> initiate(final ILink3ConnectionConfiguration configuration)
    {
        return poller.initiate(configuration);
    }

    /**
     * Get a list of the currently active ILink3 Sessions.
     * <p>
     * Note: the list is unmodifiable.
     *
     * This method has been deprecated and will be replaced by {@link #fixPConnections()} it will continue to work
     * until then.
     *
     * @return a list of the currently active ILink3 Sessions
     */
    @Deprecated
    public List<ILink3Connection> iLink3Sessions()
    {
        return poller.iLink3Sessions();
    }

    /**
     * Get a list of the currently active FIXP connections.
     * <p>
     * Note: the list is unmodifiable.
     *
     * @return a list of the currently active FIXP connections.
     */
    public List<ILink3Connection> fixPConnections()
    {
        return poller.iLink3Sessions();
    }

    @Override
    protected boolean shouldRethrowExceptionInErrorHandler()
    {
        return RETHROW_EXCEPTION.get();
    }

    /**
     * Returns information about library streams. Internal API for testing.
     *
     * @return information about library streams
     */
    LibraryStreamInfo libraryStreamInfo()
    {
        return poller.libraryStreamInfo();
    }
}
