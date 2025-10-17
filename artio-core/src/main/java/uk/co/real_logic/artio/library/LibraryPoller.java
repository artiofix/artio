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

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.ControlledFragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.DutyCycleTracker;
import io.aeron.exceptions.RegistrationException;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.*;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.RecordingCoordinator;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.ilink.ILink3Connection;
import uk.co.real_logic.artio.ilink.ILink3ConnectionConfiguration;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.messages.ControlNotificationDecoder.SessionsDecoder;
import uk.co.real_logic.artio.protocol.*;
import uk.co.real_logic.artio.session.*;
import uk.co.real_logic.artio.timing.LibraryTimers;
import uk.co.real_logic.artio.timing.Timer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.EpochFractionClock;
import uk.co.real_logic.artio.util.EpochFractionClocks;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.*;
import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.GatewayProcess.NO_CORRELATION_ID;
import static uk.co.real_logic.artio.LogTag.*;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.ENGINE;
import static uk.co.real_logic.artio.messages.SessionState.ACTIVE;
import static uk.co.real_logic.artio.session.Session.UNKNOWN_TIME;

final class LibraryPoller implements LibraryEndPointHandler, ProtocolHandler, AutoCloseable
{
    /**
     * Has connected to an engine instance
     */
    private static final int CONNECTED = 0;

    /**
     * Currently connecting to an engine instance
     */
    private static final int ATTEMPT_CONNECT = 1;

    /**
     * Currently connecting to an engine instance
     */
    private static final int CONNECTING = 2;

    /**
     * Currently connecting to an engine instance
     */
    private static final int ATTEMPT_CURRENT_NODE = 3;

    /**
     * Was explicitly closed
     */
    private static final int CLOSED = 4;

    /**
     * Engine is disconnected by either closing or unbind operation
     */
    private static final int ENGINE_DISCONNECT = 5;

    private static final long NO_MESSAGE_TIMESTAMP = -1;

    private static final InternalFixPConnection[] EMPTY_FIXP_CONNECTIONS = new InternalFixPConnection[0];
    private static final InternalSession[] EMPTY_SESSIONS = new InternalSession[0];

    private final Long2ObjectHashMap<WeakReference<InternalSession>> sessionIdToCachedSession =
        new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<WeakReference<SessionWriter>> sessionIdToFollowerSessionWriter =
        new Long2ObjectHashMap<>(0, Hashing.DEFAULT_LOAD_FACTOR);
    private final Long2ObjectHashMap<SessionSubscriber> connectionIdToSession = new Long2ObjectHashMap<>();
    private InternalFixPConnection[] fixPConnections = EMPTY_FIXP_CONNECTIONS;
    private final List<InternalFixPConnection> unmodifiableFixPConnections =
        new UnmodifiableWrapper<>(() -> fixPConnections);

    private InternalSession[] sessions = EMPTY_SESSIONS;
    private InternalSession[] pendingInitiatorSessions = EMPTY_SESSIONS;
    private final List<Session> unmodifiableSessions = new UnmodifiableWrapper<>(() -> sessions);
    private final List<Session> unmodifiablePendingInitiatorSessions =
        new UnmodifiableWrapper<>(() -> pendingInitiatorSessions);

    private final Long2ObjectHashMap<FixPSubscription> connectionIdToFixPSubscription =
        new Long2ObjectHashMap<>();

    // Used when checking the consistency of the session ids
    private final LongHashSet sessionIds = new LongHashSet();
    private final LongHashSet disconnectedSessionIds = new LongHashSet();

    // Uniquely identifies library session
    private final int libraryId;
    private final EpochNanoClock epochNanoClock;
    private final EpochClock epochClock;
    private final EpochFractionClock epochFractionClock;
    private final LibraryConfiguration configuration;
    private final SessionIdStrategy sessionIdStrategy;
    private final Timer sessionTimer;
    private final Timer receiveTimer;
    private final SessionExistsHandler sessionExistsHandler;
    private final boolean enginesAreClustered;
    private final ErrorHandler errorHandler;
    private final FixCounters fixCounters;
    private final DutyCycleTracker dutyCycleTracker;

    private final boolean isReproductionEnabled;
    private final ReproductionClock reproductionClock;

    private final Long2ObjectHashMap<LibraryReply<?>> correlationIdToReply = new Long2ObjectHashMap<>();
    private final List<BooleanSupplier> tasks = new ArrayList<>();
    private final LibraryTransport transport;
    private final FixLibrary fixLibrary;
    private final Runnable onDisconnectFunc = this::onDisconnect;
    private final SessionAcquiredInfo sessionAcquiredInfo = new SessionAcquiredInfo();

    private final CharFormatter receivedFormatter = new CharFormatter("(%s) Received %s");
    private final CharFormatter disconnectedFormatter = new CharFormatter("%s: Disconnected from [%s]");
    private final CharFormatter connectedFormatter = new CharFormatter("%s: Connected to [%s]");
    private final CharFormatter attemptConnectFormatter = new CharFormatter("%s: Attempting to connect to %s");
    private final CharFormatter attemptNextFormatter = new CharFormatter(
        "%s: Attempting connect to next engine (%s) in round-robin");
    private final CharFormatter initiatorConnectFormatter = new CharFormatter("Init Connect: %s, %s");
    private final CharFormatter acceptorConnectFormatter = new CharFormatter("Acct Connect: %s, %s");
    private final CharFormatter controlNotificationFormatter = new CharFormatter(
        "%s: Received Control Notification from engine at timeInMs %s");
    private final CharFormatter applicationHeartbeatFormatter = new CharFormatter(
        "%s: Received Heartbeat(msg=%s) from engine at %sms, sent at %sns");
    private final CharFormatter applicationHeartbeatFormatterNoTimestamp = new CharFormatter(
        "%s: Received Heartbeat(msg=%s) from engine at %sms");
    private final CharFormatter reconnectFormatter = new CharFormatter("Reconnect: %s, %s, %s");
    private final CharFormatter onDisconnectFormatter = new CharFormatter(
        "%s: Session Disconnect @ Library %s, %s");
    private final CharFormatter sessionExistsFormatter = new CharFormatter(
        "onSessionExists: conn=%s, sess=%s, sentSeqNo=%s, recvSeqNo=%s");

    /**
     * Correlation Id is initialised to a random number to reduce the chance of correlation id collision.
     */
    private long currentCorrelationId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private InitialAcceptedSessionOwner initialAcceptedSessionOwner;
    private int state = CONNECTING;

    // State changed upon connect/reconnect
    private LivenessDetector livenessDetector;
    private Subscription inboundSubscription;
    private GatewayPublication inboundPublication;
    private GatewayPublication outboundPublication;
    private String currentAeronChannel;
    private long nextSendLibraryConnectTime;
    private long nextEngineAttemptTime;

    // Combined with Library Id, uniquely identifies library connection
    private long connectCorrelationId = NO_CORRELATION_ID;

    // State changed during end of day operation
    private int sessionLogoutIndex = 0;
    private Iterator<FixPSubscription> fixpLogoutIterator = null;

    private FixPProtocol fixPProtocol;
    private FixPMessageDissector commonFixPDissector;
    private AbstractFixPParser commonFixPParser;
    private AbstractFixPProxy commonFixPProxy;

    private final FixPSessionOwner fixPSessionOwner = new FixPSessionOwner()
    {
        public void enqueueTask(final BooleanSupplier task)
        {
            enqueueTask(task);
        }

        public void remove(final InternalFixPConnection connection)
        {
            fixPConnections = ArrayUtil.remove(fixPConnections, connection);
            connectionIdToFixPSubscription.remove(connection.connectionId());
        }

        public Reply<ThrottleConfigurationStatus> messageThrottle(
            final long sessionId, final int throttleWindowInMs, final int throttleLimitOfMessages)
        {
            return new ThrottleConfigurationReply(
                LibraryPoller.this,
                timeInMs() + configuration.replyTimeoutInMs(),
                sessionId,
                throttleWindowInMs,
                throttleLimitOfMessages);
        }
    };

    private final InternalSession.Formatters formatters = new InternalSession.Formatters();

    LibraryPoller(
        final LibraryConfiguration configuration,
        final LibraryTimers timers,
        final FixCounters fixCounters,
        final LibraryTransport transport,
        final FixLibrary fixLibrary,
        final EpochClock epochClock,
        final ErrorHandler errorHandler)
    {
        this.libraryId = configuration.libraryId();
        this.fixCounters = fixCounters;
        this.transport = transport;
        this.fixLibrary = fixLibrary;

        this.sessionTimer = timers.sessionTimer();
        this.receiveTimer = timers.receiveTimer();

        this.configuration = configuration;
        this.sessionIdStrategy = configuration.sessionIdStrategy();
        this.sessionExistsHandler = configuration.sessionExistsHandler();
        this.epochClock = epochClock;
        epochNanoClock = configuration.epochNanoClock();
        this.enginesAreClustered = configuration.libraryAeronChannels().size() > 1;
        this.errorHandler = errorHandler;
        this.epochFractionClock = EpochFractionClocks.create(
            epochClock, configuration.epochNanoClock(), configuration.sessionEpochFractionFormat());
        this.isReproductionEnabled = configuration.isReproductionEnabled();
        this.reproductionClock = isReproductionEnabled ? configuration.reproductionConfiguration().clock() : null;

        this.dutyCycleTracker = fixCounters.getLibraryDutyCycleTracker(
            configuration.libraryId(), configuration.libraryCycleThresholdNs());
    }

    boolean isConnected()
    {
        return state == CONNECTED;
    }

    boolean isClosed()
    {
        return state == CLOSED;
    }

    boolean isAtEndOfDay()
    {
        return state == ENGINE_DISCONNECT;
    }

    int libraryId()
    {
        return libraryId;
    }

    List<Session> sessions()
    {
        return unmodifiableSessions;
    }

    List<Session> pendingInitiatorSessions()
    {
        return unmodifiablePendingInitiatorSessions;
    }

    Reply<Session> initiate(final SessionConfiguration configuration)
    {
        requireNonNull(configuration, "configuration");
        validateEndOfDay();

        return new InitiateSessionReply(this, timeInMs() + configuration.timeoutInMs(), configuration);
    }

    public Reply<ILink3Connection> initiate(final ILink3ConnectionConfiguration configuration)
    {
        requireNonNull(configuration, "configuration");
        validateEndOfDay();

        return new InitiateILink3ConnectionReply(
            this, timeInMs() + configuration.requestedKeepAliveIntervalInMs(), configuration);
    }

    Reply<SessionReplyStatus> releaseToGateway(final Session session, final long timeoutInMs)
    {
        requireNonNull(session, "session");
        validateEndOfDay();

        return new ReleaseToGatewayReply(
            this, timeInMs() + timeoutInMs, (InternalSession)session);
    }

    Reply<SessionReplyStatus> requestSession(
        final long sessionId,
        final int resendFromSequenceNumber,
        final int resendFromSequenceIndex,
        final long timeoutInMs)
    {
        validateEndOfDay();

        return new RequestSessionReply(
            this,
            timeInMs() + timeoutInMs,
            sessionId,
            resendFromSequenceNumber,
            resendFromSequenceIndex);
    }

    SessionWriter sessionWriter(final long sessionId, final long connectionId, final int sequenceIndex)
    {
        checkState();

        return new SessionWriter(
            libraryId,
            sessionId,
            connectionId,
            sessionBuffer(),
            outboundPublication,
            sequenceIndex);
    }

    Reply<SessionWriter> followerSession(final SessionHeaderEncoder headerEncoder, final long timeoutInMs)
    {
        validateEndOfDay();

        return new FollowerSessionReply(
            this, timeInMs() + timeoutInMs, headerEncoder);
    }

    public Reply<Long> followerFixPSession(final FixPContext context, final long testTimeoutInMs)
    {
        validateEndOfDay();

        final FixPProtocolType protocolType = context.protocolType();
        validateFixP(protocolType);
        initFixP(protocolType);

        final byte[] firstMessage = commonFixPProxy.encodeFirstMessage(context);

        return new FixPFollowerSessionReply(
            this, timeInMs() + testTimeoutInMs, firstMessage, protocolType);
    }

    Reply<MetaDataStatus> writeMetaData(
        final long sessionId, final int metaDataOffset, final DirectBuffer buffer, final int offset, final int length)
    {
        if (metaDataOffset < 0)
        {
            throw new IllegalArgumentException("metaDataOffset should never be negative and is " + metaDataOffset);
        }

        return new WriteMetaDataReply(
            this,
            timeInMs() + configuration.replyTimeoutInMs(),
            sessionId,
            metaDataOffset,
            buffer,
            offset,
            length);
    }

    public void readMetaData(final long sessionId, final MetadataHandler handler)
    {
        new ReadMetaDataReply(
            this, timeInMs() + configuration.replyTimeoutInMs(), sessionId, handler);
    }

    void disableSession(final InternalSession session)
    {
        sessions = ArrayUtil.remove(sessions, session);
        session.disable();
        cacheSession(session);
    }

    private void cacheSession(final InternalSession session)
    {
        sessionIdToCachedSession.put(session.id(), new WeakReference<>(session));
    }

    long saveWriteMetaData(
        final long sessionId,
        final int metaDataOffset,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long correlationId)
    {
        checkState();

        return outboundPublication.saveWriteMetaData(
            libraryId,
            sessionId,
            metaDataOffset,
            correlationId,
            buffer,
            offset,
            length);
    }

    long saveReadMetaData(final long sessionId, final long correlationId)
    {
        checkState();

        return outboundPublication.saveReadMetaData(
            libraryId,
            sessionId,
            correlationId);
    }

    long saveReleaseSession(final Session session, final long correlationId)
    {
        checkState();

        return outboundPublication.saveReleaseSession(
            libraryId,
            session.connectionId(),
            session.id(),
            correlationId,
            session.state(),
            session.awaitingResend(),
            session.heartbeatIntervalInMs(),
            session.lastSentMsgSeqNum(),
            session.lastReceivedMsgSeqNum(),
            session.username(),
            session.password());
    }

    long saveInitiateConnection(
        final String host,
        final int port,
        final long correlationId,
        final SessionConfiguration configuration)
    {
        checkState();

        return outboundPublication.saveInitiateConnection(
            libraryId,
            host,
            port,
            configuration.senderCompId(),
            configuration.senderSubId(),
            configuration.senderLocationId(),
            configuration.targetCompId(),
            configuration.targetSubId(),
            configuration.targetLocationId(),
            configuration.sequenceNumberType(),
            configuration.resetSeqNum(),
            configuration.initialReceivedSequenceNumber(),
            configuration.initialSentSequenceNumber(),
            configuration.closedResendInterval(),
            configuration.resendRequestChunkSize(),
            configuration.sendRedundantResendRequests(),
            configuration.enableLastMsgSeqNumProcessed(),
            configuration.username(),
            configuration.password(),
            configuration.fixDictionary(),
            this.configuration.defaultHeartbeatIntervalInS(),
            correlationId);
    }

    long saveReplayMessages(
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long latestReplyArrivalTimeInMs)
    {
        checkState();

        return outboundPublication.saveReplayMessages(
            libraryId,
            sessionId,
            correlationId,
            replayFromSequenceNumber,
            replayFromSequenceIndex,
            replayToSequenceNumber,
            replayToSequenceIndex,
            latestReplyArrivalTimeInMs);
    }

    long saveThrottleConfiguration(
        final long correlationId,
        final long sessionId,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages)
    {
        checkState();

        return outboundPublication.saveThrottleConfiguration(
            libraryId,
            correlationId,
            sessionId,
            throttleWindowInMs,
            throttleLimitOfMessages);
    }

    void onInitiatorSessionTimeout(final long correlationId, final long connectionId)
    {
        checkState();

        if (connectionId == NO_CONNECTION_ID)
        {
            onTimeoutWaitingForConnection(correlationId);
        }
        else
        {
            // We've timed out when the TCP connection has completed, but the logon hasn't finished.

            if (!saveNoLogonRequestDisconnect(connectionId))
            {
                tasks.add(() -> saveNoLogonRequestDisconnect(connectionId));
            }
        }
    }

    void onTimeoutWaitingForConnection(final long correlationId)
    {
        if (!saveMidConnectionDisconnect(correlationId))
        {
            tasks.add(() -> saveMidConnectionDisconnect(correlationId));
        }
    }

    private boolean saveMidConnectionDisconnect(final long correlationId)
    {
        return outboundPublication.saveMidConnectionDisconnect(libraryId, correlationId) > 0;
    }

    private boolean saveNoLogonRequestDisconnect(final long connectionId)
    {
        return outboundPublication.saveRequestDisconnect(libraryId, connectionId, DisconnectReason.NO_LOGON) > 0;
    }

    long saveRequestSession(
        final long sessionId,
        final long correlationId,
        final int lastReceivedSequenceNumber,
        final int sequenceIndex)
    {
        checkState();

        return outboundPublication.saveRequestSession(
            libraryId, sessionId, correlationId, lastReceivedSequenceNumber, sequenceIndex);
    }

    long saveFollowerSessionRequest(
        final long correlationId,
        final FixPProtocolType protocolType,
        final MutableAsciiBuffer buffer, final int offset, final int length)
    {
        checkState();

        return outboundPublication.saveFollowerSessionRequest(
            libraryId,
            correlationId,
            protocolType,
            buffer,
            length, offset
        );
    }

    int poll(final int fragmentLimit)
    {
        final long timeInNs = epochNanoClock.nanoTime();
        final long timeInMs = timeInMs();

        dutyCycleTracker.measureAndUpdate(timeInNs);

        switch (state)
        {
            case CONNECTED:
                return pollWithoutReconnect(timeInNs, timeInMs, fragmentLimit);

            case ATTEMPT_CONNECT:
                startConnecting(timeInMs);
                return pollWithoutReconnect(timeInNs, timeInMs, fragmentLimit);

            case CONNECTING:
                nextConnectingStep(timeInMs);
                return pollWithoutReconnect(timeInNs, timeInMs, fragmentLimit);

            case ATTEMPT_CURRENT_NODE:
                connectToNewEngine(timeInMs);
                state = CONNECTING;
                return pollWithoutReconnect(timeInNs, timeInMs, fragmentLimit);

            case ENGINE_DISCONNECT:
                attemptEngineCloseBasedLogout();
                return pollWithoutReconnect(timeInNs, timeInMs, fragmentLimit);

            case CLOSED:
            default:
                return 0;
        }
    }

    private int pollWithoutReconnect(final long timeInNs, final long timeInMs, final int fragmentLimit)
    {
        int operations = 0;
        operations += inboundSubscription.controlledPoll(outboundSubscription, fragmentLimit);
        operations += livenessDetector.poll(timeInMs);
        operations += pollSessions(timeInNs);
        operations += pollPendingInitiatorSessions(timeInNs);
        operations += checkReplies(timeInMs);
        return operations;
    }

    // -----------------------------------------------------------------------
    //                     BEGIN CONNECTION LOGIC
    // -----------------------------------------------------------------------

    void onStart()
    {
        dutyCycleTracker.update(epochNanoClock.nanoTime());
    }

    void startConnecting()
    {
        startConnecting(timeInMs());
    }

    // Called when you first connect or try to start a reconnect attempt
    private void startConnecting(final long timeInMs)
    {
        try
        {
            state = CONNECTING;
            currentAeronChannel = configuration.libraryAeronChannels().get(0);
            DebugLogger.log(
                LIBRARY_CONNECT,
                attemptConnectFormatter,
                libraryId,
                currentAeronChannel);

            initStreams();
            newLivenessDetector();
            resetNextEngineTimer(timeInMs);

            sendLibraryConnect(timeInMs);
        }
        catch (final Exception ex)
        {
            // We won't be returning an instance of ourselves to callers in the connect,
            // so we must clean up after ourselves
            try
            {
                closeWithParent();
            }
            catch (final Exception closeException)
            {
                ex.addSuppressed(closeException);
            }

            LangUtil.rethrowUnchecked(ex);
        }
    }

    // NB: not a reconnect, an nth attempt at a connect
    private void nextConnectingStep(final long timeInMs)
    {
        if (timeInMs > nextEngineAttemptTime)
        {
            attemptNextEngine();

            connectToNewEngine(timeInMs);
        }
        else if (timeInMs > nextSendLibraryConnectTime)
        {
            sendLibraryConnect(timeInMs);
        }
    }

    private void connectToNewEngine(final long timeInMs)
    {
        initStreams();
        newLivenessDetector();

        sendLibraryConnect(timeInMs);

        resetNextEngineTimer(timeInMs);
    }

    private void attemptNextEngine()
    {
        if (enginesAreClustered)
        {
            final List<String> aeronChannels = configuration.libraryAeronChannels();
            final int nextIndex = (aeronChannels.indexOf(currentAeronChannel) + 1) % aeronChannels.size();
            currentAeronChannel = aeronChannels.get(nextIndex);
            DebugLogger.log(
                LIBRARY_CONNECT,
                attemptNextFormatter,
                libraryId,
                currentAeronChannel);
        }
    }

    private void resetNextEngineTimer(final long timeInMs)
    {
        nextEngineAttemptTime = configuration.replyTimeoutInMs() + timeInMs;
    }

    private void initStreams()
    {
        if (enginesAreClustered || isFirstConnect())
        {
            transport.initStreams(currentAeronChannel);
            inboundSubscription = transport.inboundSubscription();
            inboundPublication = transport.inboundPublication();
            outboundPublication = transport.outboundPublication();
        }
    }

    private void newLivenessDetector()
    {
        livenessDetector = LivenessDetector.forLibrary(
            outboundPublication,
            libraryId,
            configuration.replyTimeoutInMs(),
            onDisconnectFunc,
            epochNanoClock);
    }

    private boolean isFirstConnect()
    {
        return !transport.isReconnect();
    }

    // return true if sent, false otherwise
    private void sendLibraryConnect(final long timeInMs)
    {
        try
        {
            final long correlationId = ++currentCorrelationId;
            if (outboundPublication.saveLibraryConnect(libraryId, configuration.libraryName(), correlationId) < 0)
            {
                connectToNextEngineNow(timeInMs);
            }
            else
            {
                this.connectCorrelationId = correlationId;
                nextSendLibraryConnectTime = configuration.connectAttemptTimeoutInMs() + timeInMs;
            }
        }
        catch (final NotConnectedException e)
        {
            connectToNextEngineNow(timeInMs);
        }
    }

    private void connectToNextEngineNow(final long timeInMs)
    {
        nextEngineAttemptTime = timeInMs;
    }

    private void onConnect()
    {
        DebugLogger.log(
            LIBRARY_CONNECT,
            connectedFormatter,
            libraryId,
            currentAeronChannel);
        configuration.libraryConnectHandler().onConnect(fixLibrary);
        setLibraryConnected(true);
    }

    private void onDisconnect()
    {
        DebugLogger.log(
            LIBRARY_CONNECT,
            disconnectedFormatter,
            libraryId,
            currentAeronChannel);
        configuration.libraryConnectHandler().onDisconnect(fixLibrary);
        setLibraryConnected(false);

        state = ATTEMPT_CONNECT;
    }

    private void setLibraryConnected(final boolean libraryConnected)
    {
        final InternalSession[] sessions = this.sessions;
        for (int i = 0, size = sessions.length; i < size; i++)
        {
            final InternalSession session = sessions[i];
            session.libraryConnected(libraryConnected);
        }
    }

    String currentAeronChannel()
    {
        return currentAeronChannel;
    }

    // -----------------------------------------------------------------------
    //                     END CONNECTION LOGIC
    // -----------------------------------------------------------------------

    private int pollSessions(final long timeInNs)
    {
        final InternalSession[] sessions = this.sessions;
        int total = 0;

        for (int i = 0, size = sessions.length; i < size; i++)
        {
            final InternalSession session = sessions[i];
            total += session.poll(timeInNs);
        }

        final long timeInMs = System.currentTimeMillis();
        final InternalFixPConnection[] binaryFixPConnections = this.fixPConnections;
        for (int i = 0, size = binaryFixPConnections.length; i < size; i++)
        {
            final InternalFixPConnection connection = binaryFixPConnections[i];
            total += connection.poll(timeInMs);
        }

        return total;
    }

    private int pollPendingInitiatorSessions(final long timeInNs)
    {
        InternalSession[] pendingSessions = this.pendingInitiatorSessions;
        int total = 0;

        for (int i = 0, size = pendingSessions.length; i < size;)
        {
            final InternalSession session = pendingSessions[i];
            total += session.poll(timeInNs);
            if (session.state() == ACTIVE)
            {
                this.pendingInitiatorSessions = pendingSessions = ArrayUtil.remove(pendingSessions, i);
                size--;
                sessions = ArrayUtil.add(sessions, session);
            }
            else
            {
                i++;
            }
        }

        return total;
    }

    long timeInMs()
    {
        return epochClock.time();
    }

    private int checkReplies(final long timeInMs)
    {
        int count = 0;
        final Long2ObjectHashMap<LibraryReply<?>>.ValueIterator iterator = correlationIdToReply.values().iterator();
        while (iterator.hasNext())
        {
            final LibraryReply<?> reply = iterator.next();
            if (reply.poll(timeInMs))
            {
                iterator.remove();
                count++;
            }
        }

        CollectionUtil.removeIf(tasks, BooleanSupplier::getAsBoolean);

        return count;
    }

    long register(final LibraryReply<?> reply)
    {
        final long correlationId = ++currentCorrelationId;
        correlationIdToReply.put(correlationId, reply);
        return correlationId;
    }

    void deregister(final long correlationId)
    {
        correlationIdToReply.remove(correlationId);
    }

    // -----------------------------------------------------------------------
    //                     BEGIN EVENT HANDLERS
    // -----------------------------------------------------------------------

    private final ControlledFragmentHandler outboundSubscription = new ControlledFragmentAssembler(
        ProtocolSubscription.of(this, new LibraryProtocolSubscription(this)));

    public Action onManageSession(
        final int libraryId,
        final long connectionId,
        final long sessionId,
        final int lastSentSeqNum, final int lastRecvSeqNum,
        final SessionStatus sessionStatus,
        final SlowStatus slowStatus,
        final ConnectionType connectionType,
        final SessionState sessionState,
        final int heartbeatIntervalInS,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final long correlationId,
        final int sequenceIndex,
        final boolean awaitingResend,
        final int lastResentMsgSeqNo, final int lastResendChunkMsgSeqNum, final int endOfResendRequestRange,
        final boolean awaitingHeartbeat,
        final int logonReceivedSequenceNumber,
        final int logonSequenceIndex,
        final long lastLogonTime, final long lastSequenceResetTime,
        final String localCompId, final String localSubId, final String localLocationId,
        final String remoteCompId, final String remoteSubId, final String remoteLocationId,
        final String address, final String username, final String password,
        final Class<? extends FixDictionary> fixDictionaryType,
        final MetaDataStatus metaDataStatus,
        final DirectBuffer metaDataBuffer,
        final int metaDataOffset,
        final int metaDataLength,
        final CancelOnDisconnectOption cancelOnDisconnectOption,
        final long cancelOnDisconnectTimeoutInNs)
    {
        if (state == CONNECTED)
        {
            final FixDictionary fixDictionary = FixDictionary.of(fixDictionaryType);
            if (libraryId == ENGINE_LIBRARY_ID)
            {
                // Simple case of engine notifying that it has a session available.
                sessionExistsHandler.onSessionExists(
                    fixLibrary,
                    sessionId,
                    localCompId,
                    localSubId,
                    localLocationId,
                    remoteCompId,
                    remoteSubId,
                    remoteLocationId,
                    logonReceivedSequenceNumber,
                    logonSequenceIndex);
            }
            else if (libraryId == this.libraryId)
            {
                if (sessionStatus == SessionStatus.SESSION_HANDOVER)
                {
                    sessionAcquiredInfo.wrap(
                        slowStatus, metaDataStatus, metaDataBuffer, metaDataOffset, metaDataLength);
                    onHandoverSession(
                        libraryId,
                        connectionId,
                        sessionId,
                        lastSentSeqNum, lastRecvSeqNum,
                        connectionType,
                        sessionState,
                        heartbeatIntervalInS,
                        closedResendInterval,
                        resendRequestChunkSize,
                        sendRedundantResendRequests,
                        enableLastMsgSeqNumProcessed,
                        correlationId,
                        sequenceIndex,
                        awaitingResend,
                        lastResentMsgSeqNo,
                        lastResendChunkMsgSeqNum,
                        endOfResendRequestRange,
                        awaitingHeartbeat,
                        lastLogonTime, lastSequenceResetTime,
                        localCompId, localSubId, localLocationId,
                        remoteCompId, remoteSubId, remoteLocationId,
                        address, username, password,
                        fixDictionary,
                        cancelOnDisconnectOption, cancelOnDisconnectTimeoutInNs);
                }
                else
                {
                    sessionExistsHandler.onSessionExists(
                        fixLibrary,
                        sessionId,
                        localCompId,
                        localSubId,
                        localLocationId,
                        remoteCompId,
                        remoteSubId,
                        remoteLocationId,
                        logonReceivedSequenceNumber,
                        logonSequenceIndex);
                }
            }
        }

        return CONTINUE;
    }

    private void onHandoverSession(
        final int libraryId, final long connectionId, final long sessionId,
        final int lastSentSeqNum, final int lastRecvSeqNum,
        final ConnectionType connectionType,
        final SessionState sessionState,
        final int heartbeatIntervalInS,
        final boolean closedResendInterval, final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests, final boolean enableLastMsgSeqNumProcessed,
        final long correlationId,
        final int sequenceIndex,
        final boolean awaitingResend,
        final int lastResentMsgSeqNo, final int lastResendChunkMsgSeqNum, final int endOfResendRequestRange,
        final boolean awaitingHeartbeat,
        final long lastLogonTime, final long lastSequenceResetTime,
        final String localCompId, final String localSubId, final String localLocationId,
        final String remoteCompId, final String remoteSubId, final String remoteLocationId,
        final String address, final String username, final String password,
        final FixDictionary fixDictionary,
        final CancelOnDisconnectOption cancelOnDisconnectOption, final long cancelOnDisconnectTimeoutInNs)
    {
        InternalSession session;
        InitiateSessionReply reply = null;
        session = checkReconnect(sessionId, connectionId, sessionState, heartbeatIntervalInS,
            sequenceIndex, enableLastMsgSeqNumProcessed, fixDictionary, connectionType, address);
        final boolean isNewConnect = session == null;

        final OnMessageInfo messageInfo = isNewConnect ? new OnMessageInfo() : session.messageInfo();

        // From manageConnection - ie set up the session in this library.
        if (connectionType == INITIATOR)
        {
            DebugLogger.log(FIX_CONNECTION, initiatorConnectFormatter, connectionId, libraryId);
            final LibraryReply<?> task = correlationIdToReply.get(correlationId);
            final boolean isReply = task instanceof InitiateSessionReply;
            if (isReply)
            {
                reply = (InitiateSessionReply)task;
                reply.onTcpConnected(connectionId);
            }
            final SessionConfiguration sessionConfiguration = isReply ? reply.configuration() : null;
            final int initialReceivedSequenceNumber = initiatorNewSequenceNumber(
                sessionConfiguration, SessionConfiguration::initialReceivedSequenceNumber, lastRecvSeqNum);
            final int initialSentSequenceNumber = initiatorNewSequenceNumber(
                sessionConfiguration, SessionConfiguration::initialSentSequenceNumber, lastSentSeqNum);
            final boolean resetSeqNum = sessionConfiguration != null && sessionConfiguration.resetSeqNum();
            final boolean disconnectOnFirstMessageNotLogon = sessionConfiguration == null ||
                sessionConfiguration.disconnectOnFirstMessageNotLogon();

            if (isNewConnect)
            {
                session = newInitiatorSession(
                    connectionId, initialSentSequenceNumber, initialReceivedSequenceNumber,
                    sessionState, sequenceIndex, enableLastMsgSeqNumProcessed,
                    fixDictionary, resetSeqNum, messageInfo, sessionId, disconnectOnFirstMessageNotLogon);
            }
            else
            {
                session.lastSentMsgSeqNum(initialSentSequenceNumber - 1);
                session.lastReceivedMsgSeqNumOnly(initialReceivedSequenceNumber - 1);
                session.initiatorResetSeqNum(resetSeqNum);
                session.disconnectOnFirstMessageNotLogon(disconnectOnFirstMessageNotLogon);
            }
        }
        else
        {
            DebugLogger.log(FIX_CONNECTION, acceptorConnectFormatter, connectionId, libraryId);
            if (isNewConnect)
            {
                session = acceptSession(
                    connectionId, address, sessionState, heartbeatIntervalInS, sequenceIndex,
                    enableLastMsgSeqNumProcessed, fixDictionary, messageInfo, sessionId);
                session.initialLastReceivedMsgSeqNum(lastRecvSeqNum);
            }
            else
            {
                session.lastReceivedMsgSeqNumOnly(lastRecvSeqNum);
            }
            setupAcceptorSentSequenceNumber(lastSentSeqNum, sequenceIndex, session);
        }

        final CompositeKey compositeKey = sessionIdStrategy.onInitiateLogon(
            localCompId, localSubId, localLocationId,
            remoteCompId, remoteSubId, remoteLocationId);

        session.username(username); session.password(password);
        session.setupSession(sessionId, compositeKey, sessionIdToFollowerSessionWriter.get(sessionId));
        session.closedResendInterval(closedResendInterval);
        session.resendRequestChunkSize(resendRequestChunkSize);
        session.sendRedundantResendRequests(sendRedundantResendRequests);
        session.awaitingResend(awaitingResend);
        session.lastResentMsgSeqNo(lastResentMsgSeqNo);
        session.lastResendChunkMsgSeqNum(lastResendChunkMsgSeqNum);
        session.endOfResendRequestRange(endOfResendRequestRange);
        session.awaitingHeartbeat(awaitingHeartbeat);
        session.cancelOnDisconnectOption(cancelOnDisconnectOption);
        session.cancelOnDisconnectTimeoutWindowInNs(cancelOnDisconnectTimeoutInNs);

        if (lastLogonTime != UNKNOWN_TIME)
        {
            session.lastLogonTimeInNs(lastLogonTime);
        }
        if (lastSequenceResetTime != UNKNOWN_TIME)
        {
            session.lastSequenceResetTimeInNs(lastSequenceResetTime);
        }

        createSessionSubscriber(connectionId, session, reply, fixDictionary, messageInfo, compositeKey);
        if (isNewConnect)
        {
            insertSession(session, connectionType, sessionState);
        }

        DebugLogger.log(GATEWAY_MESSAGE,
            sessionExistsFormatter,
            connectionId, sessionId, lastSentSeqNum, lastRecvSeqNum);
    }

    private void setupAcceptorSentSequenceNumber(
        final int lastSentSeqNum, final int sequenceIndex, final InternalSession session)
    {
        final int sessionLastSentMsgSeqNum = session.lastSentMsgSeqNum();
        if (wasOfflineReconnect && sessionLastSentMsgSeqNum > lastSentSeqNum &&
            offlineSequenceIndex == sequenceIndex)
        {
            // Offline sessions can send messages constantly, we want to avoid racing this thread's sequence
            // number update with the indexer, which could potentially be behind. We use the sequence index
            // to avoid clobbered a deliberate reset on logon
            session.lastSentMsgSeqNum(sessionLastSentMsgSeqNum);
        }
        else
        {
            session.lastSentMsgSeqNum(lastSentSeqNum);
        }
    }

    private boolean wasOfflineReconnect;
    private int offlineSequenceIndex;

    // Either a reconnect of an offline session, or the cache.
    private InternalSession checkReconnect(
        final long sessionId,
        final long connectionId,
        final SessionState sessionState,
        final int heartbeatIntervalInS,
        final int sequenceIndex,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary,
        final ConnectionType connectionType,
        final String address)
    {
        wasOfflineReconnect = false;
        offlineSequenceIndex = 0;

        final InternalSession[] sessions = this.sessions;
        for (int i = 0, sessionsLength = sessions.length; i < sessionsLength; i++)
        {
            final InternalSession session = sessions[i];
            if (session.id() == sessionId)
            {
                DebugLogger.log(FIX_CONNECTION, reconnectFormatter, connectionId, libraryId, sessionId);

                wasOfflineReconnect = true;
                offlineSequenceIndex = session.sequenceIndex();

                session.onReconnect(
                    connectionId,
                    sessionState,
                    heartbeatIntervalInS,
                    sequenceIndex,
                    enableLastMsgSeqNumProcessed,
                    fixDictionary,
                    address,
                    fixCounters,
                    connectionType);

                return session;
            }
        }

        final WeakReference<InternalSession> reference = sessionIdToCachedSession.remove(sessionId);
        if (reference != null)
        {
            final InternalSession session = reference.get();
            if (session != null)
            {
                insertSession(session, connectionType, sessionState);
                session.onReconnect(
                    connectionId,
                    sessionState,
                    heartbeatIntervalInS,
                    sequenceIndex,
                    enableLastMsgSeqNumProcessed,
                    fixDictionary,
                    address,
                    fixCounters,
                    connectionType);
            }
            return session;
        }
        return null;
    }

    private void insertSession(
        final InternalSession session, final ConnectionType connectionType, final SessionState sessionState)
    {
        if (connectionType == INITIATOR && sessionState != ACTIVE)
        {
            pendingInitiatorSessions = ArrayUtil.add(pendingInitiatorSessions, session);
        }
        else
        {
            sessions = ArrayUtil.add(sessions, session);
        }
    }

    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final long connectionId,
        final long sessionId,
        final int sequenceIndex,
        final long messageType,
        final long timestampInNs,
        final MessageStatus status,
        final int sequenceNumber,
        final Header header,
        final int metaDataLength)
    {
        if (libraryId == this.libraryId)
        {
            DebugLogger.logFixMessage(FIX_MESSAGE, messageType, receivedFormatter, libraryId, buffer, offset, length);

            onApplicationHeartbeat(libraryId, FixMessageDecoder.TEMPLATE_ID, timestampInNs);

            final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
            if (subscriber != null)
            {
                return subscriber.onMessage(
                    buffer,
                    offset,
                    length,
                    libraryId,
                    sequenceIndex,
                    messageType,
                    timestampInNs,
                    status,
                    header.position());
            }
        }

        return CONTINUE;
    }

    public Action onDisconnect(
        final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        DebugLogger.log(GATEWAY_MESSAGE, onDisconnectFormatter, libraryId, connectionId, reason.name());
        if (libraryId != this.libraryId)
        {
            return CONTINUE;
        }

        onApplicationHeartbeat(libraryId, DisconnectDecoder.TEMPLATE_ID, NO_MESSAGE_TIMESTAMP);

        final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
        if (subscriber != null)
        {
            final InternalSession session = subscriber.session();
            // Must check the state before calling subscriber.onDisconnect()
            final boolean isPendingInitiator = session.state() == SessionState.SENT_LOGON;
            final Action action = subscriber.onDisconnect(libraryId, reason);
            if (action != ABORT)
            {
                // In sole library mode the library retains ownership of sessions that are disconnected.
                final boolean isEngineOwned = initialAcceptedSessionOwner == ENGINE;
                if (isPendingInitiator)
                {
                    pendingInitiatorSessions = ArrayUtil.remove(pendingInitiatorSessions, session);

                    if (!isEngineOwned)
                    {
                        sessions = ArrayUtil.add(sessions, session);
                    }
                }

                final WeakReference<SessionWriter> sessionWriterRef =
                    sessionIdToFollowerSessionWriter.get(session.id());
                if (sessionWriterRef != null)
                {
                    final SessionWriter sessionWriter = sessionWriterRef.get();
                    if (sessionWriter != null)
                    {
                        InternalSession.disconnectWriter(sessionWriter);
                    }
                }

                connectionIdToSession.remove(connectionId);

                if (isEngineOwned)
                {
                    session.close();
                    sessions = ArrayUtil.remove(sessions, session);
                    cacheSession(session);
                }
            }

            return action;
        }
        else
        {
            onFixPDisconnect(connectionId, reason);
        }

        return CONTINUE;
    }

    private Action onFixPDisconnect(final long connectionId, final DisconnectReason reason)
    {
        final FixPSubscription subscription = connectionIdToFixPSubscription.get(connectionId);
        if (subscription != null)
        {
            final Action action = subscription.onDisconnect(reason);
            if (action == ABORT)
            {
                return ABORT;
            }
            connectionIdToFixPSubscription.remove(connectionId);
            fixPSessionOwner.remove(subscription.session());
        }

        return CONTINUE;
    }

    public Action onFixPMessage(final long connectionId, final DirectBuffer buffer, final int offset)
    {
        final FixPSubscription subscription = connectionIdToFixPSubscription.get(connectionId);
        if (subscription != null)
        {
            return subscription.onMessage(buffer, offset);
        }

        return CONTINUE;
    }

    public Action onError(
        final int libraryId,
        final GatewayError errorType,
        final long replyToId,
        final String message)
    {
        if (libraryId == this.libraryId)
        {
            final LibraryReply<?> reply = correlationIdToReply.remove(replyToId);
            if (reply != null)
            {
                reply.onError(errorType, message);
            }
            else
            {
                // case of a connect error
            }
        }

        return configuration.gatewayErrorHandler().onError(errorType, libraryId, message);
    }

    public Action onApplicationHeartbeat(final int libraryId, final int messageTemplateId)
    {
        return onApplicationHeartbeat(libraryId, messageTemplateId, NO_MESSAGE_TIMESTAMP);
    }

    public Action onApplicationHeartbeat(final int libraryId, final int messageTemplateId, final long timestampInNs)
    {
        if (libraryId == this.libraryId)
        {
            if (timestampInNs != NO_MESSAGE_TIMESTAMP)
            {
                checkReproductionTimestamp(timestampInNs);
            }

            final long timeInMs = timeInMs();

            if (DebugLogger.isEnabled(APPLICATION_HEARTBEAT) && !DebugLogger.inboundFixMessage(messageTemplateId))
            {
                if (timestampInNs != NO_MESSAGE_TIMESTAMP)
                {
                    DebugLogger.log(
                        APPLICATION_HEARTBEAT,
                        applicationHeartbeatFormatter,
                        libraryId,
                        messageTemplateId,
                        timeInMs,
                        timestampInNs);
                }
                else
                {
                    DebugLogger.log(
                        APPLICATION_HEARTBEAT,
                        applicationHeartbeatFormatterNoTimestamp,
                        libraryId,
                        messageTemplateId,
                        timeInMs);
                }
            }

            livenessDetector.onHeartbeat(timeInMs);

            if (!isConnected() && livenessDetector.isConnected())
            {
                state = CONNECTED;
                onConnect();
            }
        }

        return CONTINUE;
    }

    private void checkReproductionTimestamp(final long timestampInNs)
    {
        if (isReproductionEnabled)
        {
            reproductionClock.advanceTimeTo(timestampInNs);
        }
    }

    public Action onReleaseSessionReply(final int libraryId, final long replyToId, final SessionReplyStatus status)
    {
        final ReleaseToGatewayReply reply = (ReleaseToGatewayReply)correlationIdToReply.remove(replyToId);
        if (reply != null)
        {
            reply.onComplete(status);
        }

        return CONTINUE;
    }

    public Action onRequestSessionReply(final int libraryId, final long replyToId, final SessionReplyStatus status)
    {
        final RequestSessionReply reply = (RequestSessionReply)correlationIdToReply.remove(replyToId);
        if (reply != null)
        {
            reply.onComplete(status);
        }

        return CONTINUE;
    }

    public Action onFollowerSessionReply(final int libraryId, final long replyToId, final long sessionId)
    {
        final LibraryReply<?> reply = correlationIdToReply.remove(replyToId);
        if (reply != null)
        {
            if (reply instanceof FollowerSessionReply)
            {
                ((FollowerSessionReply)reply).onComplete(followerSession(sessionId));
            }
            else if (reply instanceof FixPFollowerSessionReply)
            {
                ((FixPFollowerSessionReply)reply).onComplete(sessionId);
            }
        }

        return CONTINUE;
    }

    SessionWriter followerSession(final long sessionId)
    {
        checkState();

        // requestFollowerSession called twice for the same session id
        final WeakReference<SessionWriter> ref = sessionIdToFollowerSessionWriter.get(sessionId);
        if (ref != null)
        {
            final SessionWriter oldRef = ref.get();
            if (oldRef != null)
            {
                return oldRef;
            }
        }

        final SessionWriter sessionWriter = sessionWriter(sessionId, GatewayProcess.NO_CONNECTION_ID, 0);
        sessionIdToFollowerSessionWriter.put(sessionId, new WeakReference<>(sessionWriter));

        linkSession(sessionId, sessionWriter, sessions);
        linkSession(sessionId, sessionWriter, pendingInitiatorSessions);

        return sessionWriter;
    }

    private void linkSession(final long sessionId, final SessionWriter sessionWriter, final InternalSession[] sessions)
    {
        for (final InternalSession session : sessions)
        {
            if (session.id() == sessionId)
            {
                session.linkTo(sessionWriter);
                break;
            }
        }
    }

    public Action onReplayMessagesReply(final int libraryId, final long replyToId, final ReplayMessagesStatus status)
    {
        final ReplayMessagesReply reply = (ReplayMessagesReply)correlationIdToReply.remove(replyToId);
        if (reply != null)
        {
            reply.onComplete(status);
        }

        return CONTINUE;
    }

    public Action onWriteMetaDataReply(final int libraryId, final long replyToId, final MetaDataStatus status)
    {
        final WriteMetaDataReply reply = (WriteMetaDataReply)correlationIdToReply.remove(replyToId);
        if (reply != null)
        {
            reply.onComplete(status);
        }

        return CONTINUE;
    }

    public Action onILinkConnect(
        final int libraryId,
        final long correlationId,
        final long connectionId,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated,
        final long lastUuid)
    {
        if (libraryId == this.libraryId)
        {
            final InitiateILink3ConnectionReply reply =
                (InitiateILink3ConnectionReply)correlationIdToReply.get(correlationId);

            if (reply != null)
            {
                DebugLogger.log(FIX_CONNECTION, initiatorConnectFormatter, connectionId, libraryId);

                reply.onTcpConnected();

                final ILink3ConnectionConfiguration configuration = reply.configuration();
                final InternalFixPConnection connection = makeILink3Connection(
                    configuration, connectionId, reply, libraryId, fixPSessionOwner,
                    uuid, lastReceivedSequenceNumber, lastSentSequenceNumber, newlyAllocated, lastUuid);
                final FixPProtocol protocol = FixPProtocolFactory.make(FixPProtocolType.ILINK_3, errorHandler);
                final FixPSubscription subscription = new FixPSubscription(
                    protocol.makeParser(connection), connection);
                connectionIdToFixPSubscription.put(connectionId, subscription);
                fixPConnections = ArrayUtil.add(fixPConnections, connection);
            }
        }

        return CONTINUE;
    }

    private InternalFixPConnection makeILink3Connection(
        final ILink3ConnectionConfiguration configuration,
        final long connectionId,
        final InitiateILink3ConnectionReply initiateReply,
        final int libraryId,
        final FixPSessionOwner owner,
        final long uuid,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final boolean newlyAllocated,
        final long lastUuid)
    {
        initFixP(FixPProtocolType.ILINK_3);

        try
        {
            final Class<?> cls = Class.forName("uk.co.real_logic.artio.library.InternalILink3Connection");
            final Constructor<?> constructor = cls.getConstructor(
                FixPProtocol.class,
                ILink3ConnectionConfiguration.class,
                long.class,
                InitiateILink3ConnectionReply.class,
                GatewayPublication.class,
                GatewayPublication.class,
                int.class,
                FixPSessionOwner.class,
                long.class,
                long.class,
                long.class,
                boolean.class,
                long.class,
                EpochNanoClock.class,
                FixPMessageDissector.class);

            return (InternalFixPConnection)constructor.newInstance(
                fixPProtocol,
                configuration,
                connectionId,
                initiateReply,
                outboundPublication,
                inboundPublication,
                libraryId,
                owner,
                uuid,
                lastReceivedSequenceNumber,
                lastSentSequenceNumber,
                newlyAllocated,
                lastUuid,
                this.configuration.epochNanoClock(),
                commonFixPDissector);
        }
        catch (final InvocationTargetException e)
        {
            LangUtil.rethrowUnchecked(e.getTargetException());
            return null;
        }
        catch (final ClassNotFoundException | NoSuchMethodException | InstantiationException |
            IllegalAccessException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    public Action onReadMetaDataReply(
        final int libraryId,
        final long replyToId,
        final MetaDataStatus status,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength)
    {
        if (this.libraryId == libraryId)
        {
            final ReadMetaDataReply reply = (ReadMetaDataReply)correlationIdToReply.remove(replyToId);
            if (reply != null)
            {
                reply.onComplete(status, srcBuffer, srcOffset, srcLength);
            }
        }

        return CONTINUE;
    }

    public Action onThrottleConfigurationReply(
        final int libraryId, final long replyToId, final ThrottleConfigurationStatus status)
    {
        if (this.libraryId == libraryId)
        {
            final ThrottleConfigurationReply reply = (ThrottleConfigurationReply)correlationIdToReply.remove(replyToId);
            if (reply != null)
            {
                reply.onComplete(status);
            }
        }

        return CONTINUE;
    }

    public Action onEngineClose(final int libraryId)
    {
        if (libraryId == this.libraryId)
        {
            DebugLogger.log(CLOSE, "Received engine close message, starting ENGINE_CLOSE operation");
            state = ENGINE_DISCONNECT;
            sessionLogoutIndex = 0;

            attemptEngineCloseBasedLogout();
        }

        return CONTINUE;
    }

    private void attemptEngineCloseBasedLogout()
    {
        final InternalSession[] sessions = this.sessions;
        final int length = sessions.length;

        final int initialSessionLogoutIndex = this.sessionLogoutIndex;

        while (this.sessionLogoutIndex < length)
        {
            final InternalSession session = sessions[this.sessionLogoutIndex];
            final long position;
            if (session.state() == ACTIVE)
            {
                position = session.logoutAndDisconnect();
            }
            else
            {
                // Just disconnect if a logon hasn't completed at this points
                position = session.requestDisconnect();
            }

            if (position < 0)
            {
                return;
            }

            this.sessionLogoutIndex++;
        }

        // Don't repeatedly log this if you get back pressured below and re-attempt
        if (sessionLogoutIndex != initialSessionLogoutIndex && length > 0)
        {
            DebugLogger.log(CLOSE, "Completed logging out FIX Sessions");
        }

        // Continue from previous position on backpressured re-attempts
        if (fixpLogoutIterator == null)
        {
            fixpLogoutIterator = connectionIdToFixPSubscription.values().iterator();
        }
        while (fixpLogoutIterator.hasNext())
        {
            final FixPSubscription fixPSubscription = fixpLogoutIterator.next();
            if (Pressure.isBackPressured(fixPSubscription.startEndOfDay()))
            {
                return;
            }
        }

        if (!connectionIdToFixPSubscription.isEmpty())
        {
            DebugLogger.log(CLOSE, "Completed logging out FIXP Sessions");
        }

        // Yes, technically the engine is closing down, so we could flip to ATTEMPT_CONNECT state here.
        // But actually we just want to follow the normal linger and timeout procedure.
        state = CONNECTED;
    }

    private void validateEndOfDay()
    {
        if (isAtEndOfDay())
        {
            throw new IllegalStateException("Cannot perform operation whilst end of day process is running");
        }
    }

    public Action onControlNotification(
        final int libraryId,
        final InitialAcceptedSessionOwner initialAcceptedSessionOwner,
        final ControlNotificationDecoder controlNotificationDecoder)
    {
        if (libraryId == this.libraryId)
        {
            final long timeInMs = timeInMs();
            livenessDetector.onHeartbeat(timeInMs);
            state = CONNECTED;
            this.initialAcceptedSessionOwner = initialAcceptedSessionOwner;
            DebugLogger.log(
                LIBRARY_CONNECT,
                controlNotificationFormatter,
                libraryId,
                timeInMs);

            controlUpdateILinkSessions();

            return controlUpdateSessions(libraryId, controlNotificationDecoder);
        }

        return CONTINUE;
    }

    private void controlUpdateILinkSessions()
    {
        // We just disconnect everything.
        if (fixPConnections.length > 0)
        {
            for (final InternalFixPConnection connection : fixPConnections)
            {
                connection.unbindState(DisconnectReason.LIBRARY_DISCONNECT);
            }
            fixPConnections = EMPTY_FIXP_CONNECTIONS;
        }

        connectionIdToFixPSubscription.clear();
    }

    private Action controlUpdateSessions(
        final int libraryId, final ControlNotificationDecoder controlNotificationDecoder)
    {
        final LongHashSet sessionIds = this.sessionIds;
        sessionIds.clear();

        final LongHashSet disconnectedSessionIds = this.disconnectedSessionIds;
        disconnectedSessionIds.clear();

        InternalSession[] sessions = this.sessions;

        // copy session ids.
        final SessionsDecoder sessionsDecoder = controlNotificationDecoder.sessions();
        while (sessionsDecoder.hasNext())
        {
            sessionsDecoder.next();
            sessionIds.add(sessionsDecoder.sessionId());
        }

        final ControlNotificationDecoder.DisconnectedSessionsDecoder disconnectedSessions =
            controlNotificationDecoder.disconnectedSessions();
        while (disconnectedSessions.hasNext())
        {
            disconnectedSessions.next();
            disconnectedSessionIds.add(disconnectedSessions.sessionId());
        }

        for (int i = 0, size = sessions.length; i < size; i++)
        {
            final InternalSession session = sessions[i];
            final long sessionId = session.id();
            final boolean acquiredSession = !sessionIds.remove(sessionId);
            final boolean disconnectedSession = disconnectedSessionIds.remove(sessionId);
            if (acquiredSession || disconnectedSession)
            {
                final SessionSubscriber subscriber = connectionIdToSession.remove(session.connectionId());
                if (subscriber != null)
                {
                    if (acquiredSession)
                    {
                        subscriber.onTimeout(libraryId);
                    }

                    if (disconnectedSession)
                    {
                        final Action action = subscriber.onDisconnect(libraryId, DisconnectReason.REMOTE_DISCONNECT);
                        if (action == ABORT)
                        {
                            this.sessions = sessions;
                            return ABORT;
                        }
                    }
                }
                session.disable();
                // TODO: Maybe we shouldn't be creating a lot of arrays and batch this up?
                sessions = ArrayUtil.remove(sessions, i);
                cacheSession(session);
                size--;
            }
            else
            {
                i++;
            }
        }
        this.sessions = sessions;

        // sessions that the gateway thinks you have, that you don't
        if (!sessionIds.isEmpty())
        {
            final String msg = String.format(
                "The gateway thinks that we own the following session ids: %s", sessionIds);
            configuration
                .gatewayErrorHandler()
                .onError(GatewayError.UNKNOWN_SESSION, libraryId, msg);
        }

        // Commit to ensure that you leave the poll loop having reconnected successfully
        return BREAK;
    }

    public Action onLibraryExtendPosition(
        final int libraryId,
        final long correlationId,
        final int newSessionId,
        final long stopPosition,
        final int initialTermId,
        final int termBufferLength,
        final int mtuLength)
    {
        if (libraryId == this.libraryId &&
            // Possible to receive resent extend position responses if we resent the library connect
            // before the extend was received.
            newSessionId != outboundPublication.sessionId())
        {
            final long timeInMs = timeInMs();
            resetNextEngineTimer(timeInMs);

            final ChannelUri channelUri = ChannelUri.parse(currentAeronChannel);
            channelUri.initialPosition(
                stopPosition,
                initialTermId,
                termBufferLength);
            RecordingCoordinator.setMtuLength(mtuLength, channelUri);
            channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(newSessionId));
            final String channel = channelUri.toString();
            DebugLogger.log(LIBRARY_CONNECT, "Extended Library Position to: ", channel);
            try
            {
                transport.newOutboundPublication(channel);
            }
            catch (final RegistrationException e)
            {
                // In this scenario we just retry again we randomly get generated a unique id
                if (!e.getMessage().contains("existing publication has clashing session id"))
                {
                    throw e;
                }
            }

            livenessDetector.onConnectStep(timeInMs);
            sendLibraryConnect(timeInMs);
        }

        return CONTINUE;
    }

    public Action onSlowStatusNotification(
        final int libraryId, final long connectionId, final boolean hasBecomeSlow)
    {
        if (libraryId == this.libraryId)
        {
            final SessionSubscriber subscriber = connectionIdToSession.get(connectionId);
            if (subscriber != null)
            {
                subscriber.onSlowStatusNotification(libraryId, hasBecomeSlow);
            }
        }

        return CONTINUE;
    }

    public Action onResetLibrarySequenceNumber(final int libraryId, final long sessionId)
    {
        if (libraryId == this.libraryId)
        {
            for (final SessionSubscriber subscriber : connectionIdToSession.values())
            {
                final Session session = subscriber.session();
                if (session.id() == sessionId)
                {
                    return Pressure.apply(session.tryResetSequenceNumbers());
                }
            }
        }

        return Action.CONTINUE;
    }

    public Action onReplayComplete(final int libraryId, final long connection, final long correlationId)
    {
        if (libraryId == this.libraryId)
        {
            final SessionSubscriber sessionSubscriber = connectionIdToSession.get(connection);
            if (sessionSubscriber != null)
            {
                sessionSubscriber.onReplayComplete(correlationId);
            }
            else
            {
                final FixPSubscription subscription = connectionIdToFixPSubscription.get(connection);
                if (subscription != null)
                {
                    subscription.onReplayComplete();
                }
            }

            return CONTINUE;
        }

        return CONTINUE;
    }

    public Action onInboundFixPConnect(
        final long connection,
        final long sessionId,
        final FixPProtocolType protocolType,
        final DirectBuffer buffer,
        final int offset,
        final int messageLength)
    {
        initFixP(protocolType);

        final FixPContext context = commonFixPParser.lookupContext(
            buffer,
            offset,
            messageLength);

        return configuration
            .fixPConnectionExistsHandler()
            .onConnectionExists(
                fixLibrary,
                sessionId,
                protocolType,
                context);
    }

    public Action onManageFixPConnection(
        final int libraryId,
        final long correlationId,
        final long connectionId,
        final long sessionId,
        final FixPProtocolType protocolType,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final boolean offline,
        final DirectBuffer buffer,
        final int offset,
        final int messageLength)
    {
        if (libraryId != this.libraryId)
        {
            return CONTINUE;
        }
        initFixP(protocolType);
        RequestSessionReply reply = null;
        try
        {
            final FixPContext context = commonFixPParser.lookupContext(
                buffer, offset, messageLength);

            if (correlationId == NO_CORRELATION_ID)
            {
                // Reconnect of an offline session
                InternalFixPConnection connection = findFixPConnection(context);
                if (connection == null)
                {
                    final FixPMessageDissector dissector = new FixPMessageDissector(fixPProtocol.messageDecoders());
                    connection = fixPProtocol.makeAcceptorConnection(
                        connectionId, outboundPublication, inboundPublication, libraryId, fixPSessionOwner,
                        lastReceivedSequenceNumber, lastSentSequenceNumber, lastConnectPayload, context, configuration,
                        dissector);
                    fixPConnections = ArrayUtil.add(fixPConnections, connection);
                }
                else
                {
                    connection.onOfflineReconnect(connectionId, context);
                }
                return handleFixPConnection(connectionId, offline, buffer, offset, connection);
            }
            else
            {
                reply = (RequestSessionReply)correlationIdToReply.get(correlationId);

                final FixPMessageDissector dissector = new FixPMessageDissector(fixPProtocol.messageDecoders());
                final InternalFixPConnection connection = fixPProtocol.makeAcceptorConnection(
                    connectionId, outboundPublication, inboundPublication, libraryId, fixPSessionOwner,
                    lastReceivedSequenceNumber, lastSentSequenceNumber, lastConnectPayload, context,
                    configuration, dissector);
                if (offline)
                {
                    connection.state(FixPConnection.State.UNBOUND);
                }
                handleFixPConnection(connectionId, offline, buffer, offset, connection);
                fixPConnections = ArrayUtil.add(fixPConnections, connection);
                if (reply != null)
                {
                    reply.onComplete(SessionReplyStatus.OK);
                }
            }
        }
        catch (final Exception e)
        {
            if (reply != null)
            {
                reply.onError(e);
            }
            errorHandler.onError(e);
        }

        return CONTINUE;
    }

    private InternalFixPConnection findFixPConnection(final FixPContext context)
    {
        final FixPKey key = context.key();
        for (final InternalFixPConnection connection : fixPConnections)
        {
            if (connection.key().equals(key))
            {
                return connection;
            }
        }
        return null;
    }

    private Action handleFixPConnection(
        final long connectionId,
        final boolean offline,
        final DirectBuffer buffer,
        final int offset,
        final InternalFixPConnection connection)
    {
        final FixPSubscription subscription = new FixPSubscription(
            fixPProtocol.makeParser(connection), connection);

        final Action action = subscription.onMessage(buffer, offset);
        if (action == ABORT)
        {
            return ABORT;
        }

        final FixPConnectionHandler handler = configuration
            .fixPConnectionAcquiredHandler()
            .onConnectionAcquired(connection);

        connection.handler(handler);

        if (!offline)
        {
            connectionIdToFixPSubscription.put(connectionId, subscription);
        }

        return action;
    }

    public Action onThrottleNotification(
        final int libraryId,
        final long connectionId,
        final long refMsgType,
        final int refSeqNum,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength,
        final long position)
    {
        if (libraryId == this.libraryId)
        {
            if (refSeqNum == Session.UNKNOWN)
            {
                // FIXP
                final FixPSubscription fixPSubscription = connectionIdToFixPSubscription.get(connectionId);
                if (fixPSubscription != null)
                {
                    final boolean replied = fixPSubscription.onThrottleNotification(
                        refMsgType,
                        businessRejectRefIDBuffer,
                        businessRejectRefIDOffset,
                        businessRejectRefIDLength
                    );

                    return replied ? CONTINUE : ABORT;
                }
            }
            else
            {
                // FIX
                final SessionSubscriber sessionSubscriber = connectionIdToSession.get(connectionId);
                if (sessionSubscriber != null)
                {
                    final boolean replied = sessionSubscriber.onThrottleNotification(
                        refMsgType,
                        refSeqNum,
                        businessRejectRefIDBuffer,
                        businessRejectRefIDOffset,
                        businessRejectRefIDLength
                    );

                    return replied ? CONTINUE : ABORT;
                }
            }
        }

        return CONTINUE;
    }

    private void validateFixP(final FixPProtocolType protocolType)
    {
        if (fixPProtocol != null)
        {
            final FixPProtocolType existingType = fixPProtocol.protocolType();
            if (existingType != protocolType)
            {
                throw new IllegalArgumentException("Provided protocol (" + protocolType +
                    ") clashes with existing configured protocol: " + fixPProtocol);
            }
        }
    }

    private void initFixP(final FixPProtocolType protocolType)
    {
        if (fixPProtocol == null)
        {
            fixPProtocol = FixPProtocolFactory.make(protocolType, errorHandler);
            commonFixPDissector = new FixPMessageDissector(fixPProtocol.messageDecoders());
            commonFixPParser = fixPProtocol.makeParser(null);
            commonFixPProxy = fixPProtocol.makeProxy(
                commonFixPDissector, outboundPublication.dataPublication(), epochNanoClock);
        }
    }

    // -----------------------------------------------------------------------
    //                     END EVENT HANDLERS
    // -----------------------------------------------------------------------

    private void createSessionSubscriber(
        final long connectionId,
        final InternalSession session,
        final InitiateSessionReply reply,
        final FixDictionary fixDictionary,
        final OnMessageInfo messageInfo,
        final CompositeKey compositeKey)
    {
        final MessageValidationStrategy validationStrategy = configuration.messageValidationStrategy();
        final SessionParser parser = new SessionParser(
            session, validationStrategy,
            errorHandler, configuration.validateCompIdsOnEveryMessage(), configuration.validateTimeStrictly(),
            messageInfo, sessionIdStrategy);
        parser.sessionKey(compositeKey);
        parser.fixDictionary(fixDictionary);
        final SessionSubscriber subscriber = new SessionSubscriber(
            messageInfo,
            parser,
            session,
            receiveTimer,
            sessionTimer,
            this,
            configuration.replyTimeoutInMs(),
            errorHandler);
        session.isSlowConsumer(sessionAcquiredInfo.isSlow());
        subscriber.reply(reply);
        subscriber.handler(configuration.sessionAcquireHandler().onSessionAcquired(session, sessionAcquiredInfo));

        connectionIdToSession.put(connectionId, subscriber);
    }

    private InternalSession newInitiatorSession(
        final long connectionId,
        final int initialSentSequenceNumber,
        final int initialReceivedSequenceNumber,
        final SessionState state,
        final int sequenceIndex,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary,
        final boolean resetSeqNum,
        final OnMessageInfo messageInfo,
        final long sessionId,
        final boolean disconnectOnFirstMessageNotLogon)
    {
        final int defaultInterval = configuration.defaultHeartbeatIntervalInS();

        final MutableAsciiBuffer asciiBuffer = sessionBuffer();
        final SessionProxy sessionProxy = sessionProxy(connectionId);

        final InternalSession session = new InternalSession(
            defaultInterval,
            connectionId,
            configuration.epochNanoClock(),
            state,
            resetSeqNum,
            sessionProxy,
            inboundPublication,
            outboundPublication,
            sessionIdStrategy,
            configuration.sendingTimeWindowInMs(),
            fixCounters.receivedMsgSeqNo(connectionId, sessionId),
            fixCounters.sentMsgSeqNo(connectionId, sessionId),
            libraryId,
            initialSentSequenceNumber,
            sequenceIndex,
            configuration.reasonableTransmissionTimeInMs(),
            asciiBuffer,
            enableLastMsgSeqNumProcessed,
            configuration.sessionCustomisationStrategy(),
            messageInfo,
            epochFractionClock,
            ConnectionType.INITIATOR,
            configuration.resendRequestController(),
            configuration.forcedHeartbeatIntervalInS(),
            configuration.disableHeartbeatRepliesToTestRequests(),
            disconnectOnFirstMessageNotLogon,
            formatters);
        session.fixDictionary(fixDictionary);
        session.initialLastReceivedMsgSeqNum(initialReceivedSequenceNumber - 1);

        return session;
    }

    private MutableAsciiBuffer sessionBuffer()
    {
        return new MutableAsciiBuffer(new byte[configuration.sessionBufferSize()]);
    }

    private int initiatorNewSequenceNumber(
        final SessionConfiguration sessionConfiguration,
        final ToIntFunction<SessionConfiguration> initialSequenceNumberGetter,
        final int lastSequenceNumber)
    {
        final int newSequenceNumber = lastSequenceNumber + 1;
        if (sessionConfiguration == null)
        {
            return newSequenceNumber;
        }

        final int initialSequenceNumber = initialSequenceNumberGetter.applyAsInt(sessionConfiguration);
        if (initialSequenceNumber != AUTOMATIC_INITIAL_SEQUENCE_NUMBER)
        {
            return initialSequenceNumber;
        }

        if (sessionConfiguration.sequenceNumbersPersistent() && lastSequenceNumber != ConnectedSessionInfo.UNK_SESSION)
        {
            return newSequenceNumber;
        }

        return 1;
    }

    private InternalSession acceptSession(
        final long connectionId,
        final String address,
        final SessionState state,
        final int heartbeatIntervalInS,
        final int sequenceIndex,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary,
        final OnMessageInfo messageInfo,
        final long sessionId)
    {
        final long sendingTimeWindow = configuration.sendingTimeWindowInMs();
        final AtomicCounter receivedMsgSeqNo = fixCounters.receivedMsgSeqNo(connectionId, sessionId);
        final AtomicCounter sentMsgSeqNo = fixCounters.sentMsgSeqNo(connectionId, sessionId);
        final MutableAsciiBuffer asciiBuffer = sessionBuffer();

        final InternalSession session = new InternalSession(
            heartbeatIntervalInS,
            connectionId,
            configuration.epochNanoClock(),
            state,
            false,
            sessionProxy(connectionId),
            inboundPublication,
            outboundPublication,
            sessionIdStrategy,
            sendingTimeWindow,
            receivedMsgSeqNo,
            sentMsgSeqNo,
            libraryId,
            1,
            sequenceIndex,
            configuration.reasonableTransmissionTimeInMs(),
            asciiBuffer,
            enableLastMsgSeqNumProcessed,
            configuration.sessionCustomisationStrategy(),
            messageInfo,
            epochFractionClock,
            ConnectionType.ACCEPTOR,
            configuration.resendRequestController(),
            configuration.forcedHeartbeatIntervalInS(),
            configuration.disableHeartbeatRepliesToTestRequests(),
            true,
            formatters);
        session.fixDictionary(fixDictionary);
        session.address(address);
        return session;
    }

    private SessionProxy sessionProxy(final long connectionId)
    {
        return configuration.sessionProxyFactory().make(
            configuration.sessionBufferSize(),
            transport.outboundPublication(),
            sessionIdStrategy,
            configuration.sessionCustomisationStrategy(),
            configuration.epochNanoClock(),
            connectionId,
            libraryId,
            LangUtil::rethrowUnchecked,
            configuration.sessionEpochFractionFormat());
    }

    private void checkState()
    {
        if (state != CONNECTED)
        {
            throw new IllegalStateException(
                "Library has been closed or is performing end of day operation, state = " + state);
        }
    }

    private void closeWithParent()
    {
        try
        {
            fixLibrary.internalClose();
        }
        finally
        {
            close();
        }
    }

    public void close()
    {
        if (state != CLOSED)
        {
            for (final WeakReference<InternalSession> ref : sessionIdToCachedSession.values())
            {
                final InternalSession session = ref.get();
                if (session != null)
                {
                    session.close();
                }
            }

            for (final WeakReference<SessionWriter> ref : sessionIdToFollowerSessionWriter.values())
            {
                final SessionWriter sessionWriter = ref.get();
                if (sessionWriter != null)
                {
                    InternalSession.closeWriter(sessionWriter);
                }
            }

            connectionIdToSession.values().forEach(subscriber -> subscriber.session().disable());
            state = CLOSED;
        }
    }

    public long saveInitiateILink(final long correlationId, final ILink3ConnectionConfiguration configuration)
    {
        return outboundPublication.saveInitiateILinkConnection(
            libraryId, configuration.port(), correlationId, configuration.reEstablishLastConnection(),
            configuration.host(), configuration.accessKeyId(), configuration.useBackupHost(),
            configuration.backupHost());
    }

    void enqueueTask(final BooleanSupplier task)
    {
        tasks.add(task);
    }

    @SuppressWarnings("unchecked")
    public List<ILink3Connection> iLink3Sessions()
    {
        return (List<ILink3Connection>)(List<?>)unmodifiableFixPConnections;
    }

    @SuppressWarnings("unchecked")
    public List<FixPConnection> fixPConnections()
    {
        return (List<FixPConnection>)(List<?>)unmodifiableFixPConnections;
    }

    LibraryStreamInfo libraryStreamInfo()
    {
        return new LibraryStreamInfo(
            inboundPublication.sessionId(),
            inboundPublication.position(),
            outboundPublication.sessionId(),
            outboundPublication.position());
    }
}

class UnmodifiableWrapper<T> extends AbstractList<T>
{
    private final Supplier<T[]> sessions;

    UnmodifiableWrapper(final Supplier<T[]> sessions)
    {
        this.sessions = sessions;
    }

    public T get(final int index)
    {
        return sessions.get()[index];
    }

    public int size()
    {
        return sessions.get().length;
    }
}
