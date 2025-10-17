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
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.*;
import uk.co.real_logic.artio.engine.framer.GatewaySessions.PendingAcceptorLogon;
import uk.co.real_logic.artio.engine.framer.TcpChannelSupplier.NewChannelHandler;
import uk.co.real_logic.artio.engine.logger.ReplayQuery;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.messages.AllFixSessionsReplyEncoder.SessionsEncoder;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.protocol.*;
import uk.co.real_logic.artio.session.*;
import uk.co.real_logic.artio.timing.Timer;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.CharFormatter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import org.agrona.DeadlineTimerWheel;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2LongHashMap.KeyIterator;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersReader;
import org.agrona.concurrent.status.UnsafeBufferPosition;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.agrona.collections.CollectionUtil.removeIf;
import static org.agrona.concurrent.status.CountersReader.NULL_COUNTER_ID;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.GatewayProcess.NO_CORRELATION_ID;
import static uk.co.real_logic.artio.LogTag.APPLICATION_HEARTBEAT;
import static uk.co.real_logic.artio.LogTag.FIX_CONNECTION;
import static uk.co.real_logic.artio.LogTag.LIBRARY_MANAGEMENT;
import static uk.co.real_logic.artio.Pressure.isBackPressured;
import static uk.co.real_logic.artio.dictionary.SessionConstants.LOGON_MESSAGE_TYPE;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.artio.engine.ConnectedSessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.framer.Continuation.COMPLETE;
import static uk.co.real_logic.artio.engine.framer.FixContexts.UNKNOWN_SESSION;
import static uk.co.real_logic.artio.engine.framer.FixGatewaySession.adjustLastSequenceNumber;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.FOR_NEXT_SESSION_VERSION;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.NOT_FOR_NEXT_SESSION_VERSION;
import static uk.co.real_logic.artio.fixp.FixPFirstMessageResponse.NEGOTIATE_DUPLICATE_ID;
import static uk.co.real_logic.artio.fixp.FixPFirstMessageResponse.NEGOTIATE_DUPLICATE_ID_BAD_VER;
import static uk.co.real_logic.artio.library.FixLibrary.CURRENT_SEQUENCE;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.messages.ConnectionType.ACCEPTOR;
import static uk.co.real_logic.artio.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.artio.messages.GatewayError.*;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;
import static uk.co.real_logic.artio.messages.SequenceNumberType.PERSISTENT;
import static uk.co.real_logic.artio.messages.SequenceNumberType.TRANSIENT;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.*;
import static uk.co.real_logic.artio.messages.SessionState.ACTIVE;
import static uk.co.real_logic.artio.messages.SessionState.CONNECTED;
import static uk.co.real_logic.artio.messages.SessionState.DISCONNECTED;
import static uk.co.real_logic.artio.messages.SessionStatus.SESSION_HANDOVER;

/**
 * Handles incoming connections from clients and outgoing connections to exchanges.
 */
class Framer implements Agent, EngineEndPointHandler, ProtocolHandler
{
    private static final DirectBuffer NULL_METADATA = new UnsafeBuffer(new byte[0]);

    private final CharFormatter timingOutFormatter = new CharFormatter("Timing out connection to library %s");
    private final CharFormatter libraryConnectedFormatter = new CharFormatter("Library %s - %s connected");
    private final CharFormatter handingToLibraryFormatter = new CharFormatter(
        "Handing control for session %s to library %s");
    private final CharFormatter initiatingSessionFormatter = new CharFormatter(
        "Initiating session %s from library %s");
    private final CharFormatter applicationHeartbeatFormatter = new CharFormatter(
        "Received Heartbeat (msg=%s) from library %s at %sms, sent at %sns");
    private final CharFormatter acquiringSessionFormatter = new CharFormatter(
        "Acquiring session %s from library %s");
    private final CharFormatter disconnectingSessionFormatter = new CharFormatter(
        "Disconnecting session %s from library %s");
    private final CharFormatter releasingSessionFormatter = new CharFormatter(
        "Releasing session %s with connectionId %s from library %s");
    private final CharFormatter connectingFormatter = new CharFormatter(
        "Connecting to %s:%s from library %s");

    private final RetryManager retryManager = new RetryManager();
    private final List<ResetSequenceNumberCommand> replies = new ArrayList<>();
    private final Int2ObjectHashMap<LiveLibraryInfo> idToLibrary = new Int2ObjectHashMap<>();
    private final List<LiveLibraryInfo> librariesBeingAcquired = new ArrayList<>();
    private final Consumer<AdminCommand> onAdminCommand = command -> command.execute(this);
    private final NewChannelHandler onNewConnectionFunc = this::onNewConnection;
    private final Predicate<LiveLibraryInfo> retryAcquireLibrarySessionsFunc = this::retryAcquireLibrarySessions;
    private final Consumer<FixGatewaySession> onSessionLogon = this::onSessionLogon;
    private final CatchupReplayer.Formatters catchupReplayFormatters = new CatchupReplayer.Formatters();
    // Both connection id to library id maps
    private final Long2LongHashMap resendSlowStatus = new Long2LongHashMap(-1);
    private final Long2LongHashMap resendNotSlowStatus = new Long2LongHashMap(-1);
    private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    private final ReproductionPoller reproductionPoller;
    private final TcpChannelSupplier channelSupplier;
    private final EpochClock epochClock;
    private final EpochNanoClock clock;
    private final Timer outboundTimer;
    private final Timer sendTimer;
    private final DutyCycleTracker dutyCycleTracker;

    private final ControlledFragmentHandler librarySubscriber;
    private final ControlledFragmentHandler replaySubscriber;
    private final AdminEngineProtocolSubscription adminEngineProtocolSubscription;
    private final Subscription adminEngineSubscription;
    private final ReceiverEndPoints receiverEndPoints;
    private final FixSenderEndPoints fixSenderEndPoints;
    private final CountersReader countersReader;
    private final long inboundIndexRegistrationId;
    private final long outboundIndexRegistrationId;
    private final SenderSequenceNumbers senderSequenceNumbers;
    private final ReproductionLogWriter reproductionLogWriter;
    private final FixCounters fixCounters;
    private final FixPSenderEndPoints fixPSenderEndPoints;
    private final LongConsumer removeILink3SenderEndPoints;
    private final EngineConfiguration configuration;
    private final AdminReplyPublication adminReplyPublication;
    private final FixEndPointFactory endPointFactory;
    private final Subscription librarySubscription;
    private final Image replayImage;
    private final GatewayPublication inboundPublication;
    private final String agentNamePrefix;
    private final CompletionPosition inboundCompletionPosition;
    private final CompletionPosition outboundLibraryCompletionPosition;
    private final FinalImagePositions finalImagePositions;
    private final SessionIdStrategy sessionIdStrategy;
    private final FixContexts fixContexts;
    private final QueuedPipe<AdminCommand> adminCommands;
    private final SequenceNumberIndexReader sentSequenceNumberIndex;
    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final int inboundBytesReceivedLimit;
    private final int outboundLibraryFragmentLimit;
    private final int replayFragmentLimit;
    private final GatewaySessions gatewaySessions;
    /**
     * Null if inbound messages are not logged and if we're not in reproduction mode.
     */
    private final ReplayQuery inboundMessages;
    private final ErrorHandler errorHandler;
    private final GatewayPublication outboundPublication;
    private final RecordingCoordinator recordingCoordinator;
    private final boolean soleLibraryMode;
    private final InitialAcceptedSessionOwner initialAcceptedSessionOwner;
    private final AcceptorFixDictionaryLookup acceptorFixDictionaryLookup;
    private final LongHashSet requestAllSessionSeenSessions = new LongHashSet();
    private final CancelOnDisconnectFinder cancelOnDisconnectFinder = new CancelOnDisconnectFinder();
    private final Image outboundEngineImage;
    private final boolean acceptsFixP;
    private final FixPContexts fixPContexts;
    private final long replyTimeoutInNs;
    private final DeadlineTimerWheel timerWheel;
    private final TimerEventHandler timerEventHandler;

    private long nextConnectionId = (long)(Math.random() * Long.MAX_VALUE);
    private FixPProtocol fixPProtocol;
    private AbstractFixPParser fixPParser;
    private AbstractFixPProxy fixPProxy;
    private FixPRejectRefIdExtractor fixPRejectRefIdExtractor;

    private boolean performingDisconnectOperation = false;
    private UnbindCommand pendingUnbind = null;

    // true if we should be bound, false otherwise
    // If we're in sole library mode and no library is connected we will be unbound.
    private boolean shouldBind;

    private long nextApplicationHeartbeatTimeInNs = 0;

    Framer(
        final EpochClock epochClock,
        final Timer outboundTimer,
        final Timer sendTimer,
        final EngineConfiguration configuration,
        final Subscription adminEngineSubscription,
        final AdminReplyPublication adminReplyPublication,
        final FixEndPointFactory endPointFactory,
        final Subscription librarySubscription,
        final Image replayImage,
        final ReplayQuery inboundMessages,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final QueuedPipe<AdminCommand> adminCommands,
        final SessionIdStrategy sessionIdStrategy,
        final FixContexts fixContexts,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final GatewaySessions gatewaySessions,
        final ErrorHandler errorHandler,
        final String agentNamePrefix,
        final CompletionPosition inboundCompletionPosition,
        final CompletionPosition outboundLibraryCompletionPosition,
        final FinalImagePositions finalImagePositions,
        final RecordingCoordinator recordingCoordinator,
        final FixPContexts fixPContexts,
        final CountersReader countersReader,
        final long inboundIndexRegistrationId,
        final long outboundIndexRegistrationId,
        final FixCounters fixCounters,
        final SenderSequenceNumbers senderSequenceNumbers,
        final AgentInvoker conductorAgentInvoker,
        final ReproductionLogWriter reproductionLogWriter)
    {
        this.epochClock = epochClock;
        this.clock = configuration.epochNanoClock();
        this.outboundTimer = outboundTimer;
        this.sendTimer = sendTimer;
        this.configuration = configuration;
        this.adminEngineSubscription = adminEngineSubscription;
        this.adminReplyPublication = adminReplyPublication;
        this.endPointFactory = endPointFactory;
        this.librarySubscription = librarySubscription;
        this.replayImage = replayImage;
        this.gatewaySessions = gatewaySessions;
        this.inboundMessages = inboundMessages;
        this.errorHandler = errorHandler;
        this.outboundPublication = outboundPublication;
        this.inboundPublication = inboundPublication;
        this.agentNamePrefix = agentNamePrefix;
        this.inboundCompletionPosition = inboundCompletionPosition;
        this.outboundLibraryCompletionPosition = outboundLibraryCompletionPosition;
        this.fixSenderEndPoints = new FixSenderEndPoints();
        this.countersReader = countersReader;
        this.inboundIndexRegistrationId = inboundIndexRegistrationId;
        this.outboundIndexRegistrationId = outboundIndexRegistrationId;
        this.senderSequenceNumbers = senderSequenceNumbers;
        this.reproductionLogWriter = reproductionLogWriter;
        this.fixPSenderEndPoints = new FixPSenderEndPoints();
        this.removeILink3SenderEndPoints = fixPSenderEndPoints::removeConnection;
        this.recordingCoordinator = recordingCoordinator;
        this.sessionIdStrategy = sessionIdStrategy;
        this.fixContexts = fixContexts;
        this.adminCommands = adminCommands;
        this.sentSequenceNumberIndex = sentSequenceNumberIndex;
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
        this.finalImagePositions = finalImagePositions;
        this.initialAcceptedSessionOwner = configuration.initialAcceptedSessionOwner();
        this.soleLibraryMode = initialAcceptedSessionOwner == SOLE_LIBRARY;
        this.acceptsFixP = configuration.acceptsFixP();
        this.fixPContexts = fixPContexts;
        this.fixCounters = fixCounters;
        this.dutyCycleTracker = fixCounters.getFramerDutyCycleTracker(configuration.framerCycleThresholdNs());

        replyTimeoutInNs = TimeUnit.MILLISECONDS.toNanos(configuration.replyTimeoutInMs());
        timerEventHandler = new TimerEventHandler(errorHandler);

        acceptorFixDictionaryLookup = new AcceptorFixDictionaryLookup(
            configuration.acceptorfixDictionary(),
            configuration.acceptorFixDictionaryOverrides());

        receiverEndPoints = new ReceiverEndPoints(errorHandler);

        this.outboundLibraryFragmentLimit = configuration.outboundLibraryFragmentLimit();
        this.replayFragmentLimit = configuration.replayFragmentLimit();
        this.inboundBytesReceivedLimit = configuration.inboundBytesReceivedLimit();

        librarySubscriber = new ControlledFragmentAssembler(
            ProtocolSubscription.of(this, new EngineProtocolSubscription(this)),
            0,
            true);

        // We lookup replayed message by session id, since the connection id may have changed
        // if it's a persistent session.
        replaySubscriber = new ImageControlledFragmentAssembler(ProtocolSubscription.of(
            new ProtocolHandler()
            {
                public Action onMessage(
                    final DirectBuffer buffer,
                    final int offset,
                    final int length,
                    final int libraryId,
                    final long connectionId,
                    final long sessionId,
                    final int sequenceIndex,
                    final long messageType,
                    final long timestamp,
                    final MessageStatus status,
                    final int sequenceNumber,
                    final Header header,
                    final int metaDataLength)
                {
                    return fixSenderEndPoints.onReplayMessage(connectionId, buffer, offset, length, sequenceNumber);
                }

                public Action onDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
                {
                    // Should never be replayed.
                    return Action.CONTINUE;
                }

                public Action onFixPMessage(final long connectionId, final DirectBuffer buffer, final int offset)
                {
                    return fixPSenderEndPoints.onMessage(connectionId, buffer, offset, true);
                }
            },
            new ReplayProtocolSubscription(new FramerReplayProtocolHandler(false))),
            0,
            true);

        adminEngineProtocolSubscription = new AdminEngineProtocolSubscription(this);

        final EngineReproductionConfiguration reproductionConfiguration = configuration.reproductionConfiguration();
        final boolean isReproducing = reproductionConfiguration != null;

        if (isReproducing)
        {
            final ReproductionLog reproductionLog = ReproductionLogReader.read(
                recordingCoordinator.reproductionSubscription());
            channelSupplier = new ReproductionTcpChannelSupplier(
                configuration.reproductionMessageHandler(), reproductionLog);
            reproductionPoller = new ReproductionPoller(
                reproductionConfiguration, channelSupplier, configuration.framerIdleStrategy(),
                configuration.logFileDir(), recordingCoordinator,
                configuration.libraryAeronChannel(), configuration.inboundLibraryStream(),
                configuration.reproductionReplayStream());
            shouldBind = false;
        }
        else
        {
            channelSupplier = configuration.channelSupplier();
            reproductionPoller = null;
            shouldBind = configuration.bindAtStartup();
        }

        Image image = null;
        while (image == null)
        {
            image = librarySubscription.imageBySessionId(outboundPublication.sessionId());
            if (conductorAgentInvoker != null)
            {
                conductorAgentInvoker.invoke();
            }
            Thread.yield();
        }

        this.outboundEngineImage = image;
        timerWheel = new DeadlineTimerWheel(
            MILLISECONDS, epochClock.time(), 128, 512);
    }

    public void onStart()
    {
        dutyCycleTracker.update(clock.nanoTime());
    }

    public int doWork() throws Exception
    {
        final long timeInNs = clock.nanoTime();
        final long timeInMs = epochClock.time();

        dutyCycleTracker.measureAndUpdate(timeInNs);
        fixSenderEndPoints.timeInMs(timeInMs);

        checkOutboundTimestampSender(timeInNs);

        return retryManager.attemptSteps() +
            sendOutboundMessages() +
            sendReplayMessages() +
            pollEndPoints() +
            pollNewConnections(timeInMs) +
            pollLibraries(timeInMs) +
            gatewaySessions.pollSessions(timeInMs, timeInNs) +
            fixSenderEndPoints.poll(timeInMs) +
            adminCommands.drain(onAdminCommand) +
            checkDutyCycle(timeInMs);
    }

    private void checkOutboundTimestampSender(final long timeInNs)
    {
        // We send this outbound timestamp so that a FixArchiveScanner, in follow mode,
        // can reproduce a totally ordered sequence of messages.

        final long nextApplicationHeartbeatTimeInNs = this.nextApplicationHeartbeatTimeInNs;
        if (nextApplicationHeartbeatTimeInNs < timeInNs)
        {
            if (outboundPublication.saveApplicationHeartbeat(ENGINE_LIBRARY_ID, timeInNs) > 0)
            {
                this.nextApplicationHeartbeatTimeInNs = timeInNs + replyTimeoutInNs;
            }
        }
    }

    private int checkDutyCycle(final long timeInMs)
    {
        return removeIf(replies, ResetSequenceNumberCommand::poll) +
            resendSaveNotifications(resendSlowStatus, SlowStatus.SLOW) +
            resendSaveNotifications(resendNotSlowStatus, SlowStatus.NOT_SLOW) +
            timerWheel.poll(timeInMs, timerEventHandler, 10);
    }

    private int resendSaveNotifications(final Long2LongHashMap resend, final SlowStatus status)
    {
        int actions = 0;
        if (!resend.isEmpty())
        {
            final KeyIterator keyIterator = resend.keySet().iterator();
            while (keyIterator.hasNext())
            {
                final long connectionId = keyIterator.nextValue();
                final int libraryId = (int)resend.get(connectionId);
                final long position = inboundPublication.saveSlowStatusNotification(
                    libraryId, connectionId, status);
                if (position > 0)
                {
                    keyIterator.remove();
                }
                actions++;
            }
        }

        return actions;
    }

    private int sendReplayMessages()
    {
        return replayImage.controlledPoll(replaySubscriber, replayFragmentLimit);
    }

    private int sendOutboundMessages()
    {
        return fixPSenderEndPoints.reattempt() +
            librarySubscription.controlledPoll(librarySubscriber, outboundLibraryFragmentLimit) +
            adminEngineSubscription.poll(adminEngineProtocolSubscription, outboundLibraryFragmentLimit);
    }

    private int pollLibraries(final long timeInMs)
    {
        int total = 0;
        final Iterator<LiveLibraryInfo> iterator = idToLibrary.values().iterator();
        while (iterator.hasNext())
        {
            final LiveLibraryInfo library = iterator.next();
            total += library.poll(timeInMs);
            if (!library.isConnected())
            {
                iterator.remove();
                onLibraryDisconnect(library);

                soleLibraryModeUnbind();
            }
        }

        total += removeIf(librariesBeingAcquired, retryAcquireLibrarySessionsFunc);

        return total;
    }

    private void onLibraryDisconnect(final LiveLibraryInfo library)
    {
        if (DebugLogger.isEnabled(LIBRARY_MANAGEMENT))
        {
            DebugLogger.log(LIBRARY_MANAGEMENT, timingOutFormatter.clear().with(library.libraryId()));
        }

        tryAcquireOrDisconnectLibrarySessions(library);
        saveLibraryTimeout(library);
        disconnectILinkConnections(library);
    }

    private void disconnectILinkConnections(final LiveLibraryInfo library)
    {
        final int libraryId = library.libraryId();
        receiverEndPoints.disconnectILinkConnections(libraryId, removeILink3SenderEndPoints);
    }

    private void soleLibraryModeUnbind()
    {
        if (soleLibraryMode && idToLibrary.isEmpty())
        {
            try
            {
                channelSupplier.unbind();
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }
    }

    private void tryAcquireOrDisconnectLibrarySessions(final LiveLibraryInfo library)
    {
        final int librarySessionId = library.aeronSessionId();
        final Image image = librarySubscription.imageBySessionId(librarySessionId);
        long libraryPosition = finalImagePositions.lookupPosition(librarySessionId);
        if (image != null)
        {
            libraryPosition = image.position();
        }

        // Don't check the sent indexed position if we're not logging / indexing,
        // just acquire
        if (!configuration.logOutboundMessages() || sentIndexedPosition(librarySessionId, libraryPosition))
        {
            acquireOrDisconnectLibrarySessions(library);
        }
        else
        {
            library.acquireAtPosition(libraryPosition);
            librariesBeingAcquired.add(library);
        }
    }

    private boolean retryAcquireLibrarySessions(final LiveLibraryInfo library)
    {
        if (!configuration.logOutboundMessages() ||
            sentIndexedPosition(library.aeronSessionId(), library.acquireAtPosition()))
        {
            acquireOrDisconnectLibrarySessions(library);
            return true;
        }

        return false;
    }

    private boolean sentIndexedPosition(final int aeronSessionId, final long position)
    {
        final long indexedPosition = sentSequenceNumberIndex.indexedPosition(aeronSessionId);
        return indexedPosition >= position;
    }

    private boolean receivedIndexedPosition(final int aeronSessionId, final long position)
    {
        final long indexedPosition = receivedSequenceNumberIndex.indexedPosition(aeronSessionId);
        return indexedPosition >= position;
    }

    private void saveLibraryTimeout(final LibraryInfo library)
    {
        schedule(() -> inboundPublication.saveLibraryTimeout(library, 0));
        schedule(() -> outboundPublication.saveLibraryTimeout(library, 0));
    }

    private void acquireOrDisconnectLibrarySessions(final LiveLibraryInfo library)
    {
        final List<GatewaySession> sessions = library.gatewaySessions();
        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            if (!acceptsFixP)
            {
                final FixGatewaySession session = (FixGatewaySession)sessions.get(i);
                if (session.isOffline())
                {
                    continue;
                }

                if (soleLibraryMode)
                {
                    DebugLogger.log(
                        LIBRARY_MANAGEMENT,
                        disconnectingSessionFormatter, session.sessionId(), library.libraryId());

                    final long connectionId = session.connectionId();
                    receiverEndPoints.removeConnection(connectionId, DisconnectReason.LIBRARY_DISCONNECT);
                    fixSenderEndPoints.removeConnection(connectionId);
                    gatewaySessions.releaseByConnectionId(connectionId);
                }

                else
                {
                    final long sessionId = session.sessionId();
                    final int sentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
                    final int receivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
                    final boolean hasLoggedIn = receivedSequenceNumber != UNK_SESSION;
                    final SessionState state = hasLoggedIn ? ACTIVE : CONNECTED;

                    DebugLogger.log(
                        LIBRARY_MANAGEMENT,
                        acquiringSessionFormatter, session.sessionId(), library.libraryId());

                    ((FixGatewaySessions)gatewaySessions).acquire(
                        session,
                        state,
                        false,
                        session.heartbeatIntervalInS(),
                        sentSequenceNumber,
                        receivedSequenceNumber,
                        session.username(),
                        session.password());

                    schedule(saveManageSession(ENGINE_LIBRARY_ID, session));

                    if (performingDisconnectOperation)
                    {
                        session.session().logoutAndDisconnect();
                    }
                }
            }
        }

        finalImagePositions.removePosition(library.aeronSessionId());
    }

    private int pollEndPoints()
    {
        final int inboundBytesReceivedLimit = this.inboundBytesReceivedLimit;

        int totalBytesReceived = 0;
        int bytesReceived;
        do
        {
            bytesReceived = receiverEndPoints.pollEndPoints();
            totalBytesReceived += bytesReceived;
        }
        while (bytesReceived > 0 && totalBytesReceived < inboundBytesReceivedLimit);

        return totalBytesReceived;
    }

    private int pollNewConnections(final long timeInMs) throws IOException
    {
        return channelSupplier.pollSelector(timeInMs, onNewConnectionFunc);
    }

    private void onNewConnection(final long timeInMs, final TcpChannel channel)
    {
        if (performingDisconnectOperation)
        {
            channel.close();
            return;
        }

        if (acceptsFixP)
        {
            onNewFixPConnection(timeInMs, channel);
        }
        else
        {
            onNewFixConnection(timeInMs, channel);
        }
    }

    private void onNewFixPConnection(final long timeInMs, final TcpChannel channel)
    {
        final FixPProtocolType protocolType = initFixPProtocol();

        final long connectionId = newConnectionId();

        final AtomicCounter bytesInBuffer = fixCounters.bytesInBuffer(connectionId, channel.remoteAddr());
        final SenderSequenceNumber senderSequenceNumber =
            senderSequenceNumbers.onNewSender(true, connectionId, bytesInBuffer);

        final AcceptorFixPReceiverEndPoint receiverEndPoint = new AcceptorFixPReceiverEndPoint(
            connectionId,
            channel,
            configuration.receiverBufferSize(),
            errorHandler,
            this,
            inboundPublication,
            ENGINE_LIBRARY_ID, configuration.epochNanoClock(), connectionId, fixPProtocol,
            configuration.throttleWindowInMs(), configuration.throttleLimitOfMessages(),
            fixPRejectRefIdExtractor);
        receiverEndPoints.add(receiverEndPoint);

        final FixPSenderEndPoint senderEndPoint = FixPSenderEndPoint.of(
            connectionId, channel, errorHandler, inboundPublication.dataPublication(),
            reproductionLogWriter, ENGINE_LIBRARY_ID,
            configuration.messageTimingHandler(), fixPProtocol.explicitSequenceNumbers(),
            fixPParser.templateIdOffset(), fixPParser.retransmissionTemplateId(), fixPSenderEndPoints,
            bytesInBuffer, configuration.senderMaxBytesInBuffer(), this, receiverEndPoint, senderSequenceNumber);
        fixPSenderEndPoints.add(senderEndPoint);

        final FixPGatewaySession gatewaySession = new FixPGatewaySession(
            connectionId,
            UNK_SESSION,
            channel.remoteAddr(),
            ACCEPTOR,
            configuration.authenticationTimeoutInMs(),
            protocolType,
            fixPParser,
            fixPProxy,
            receiverEndPoint,
            senderEndPoint,
            (FixPGatewaySessions)gatewaySessions);
        gatewaySession.disconnectAt(timeInMs + configuration.noLogonDisconnectTimeoutInMs());
        gatewaySessions.track(gatewaySession);
        receiverEndPoint.gatewaySession(gatewaySession);

        saveConnect(channel, connectionId);
    }

    private FixPProtocolType initFixPProtocol()
    {
        final FixPProtocolType protocolType = configuration.supportedFixPProtocolType();
        if (fixPProtocol == null)
        {
            fixPProtocol = FixPProtocolFactory.make(protocolType, errorHandler);
            final FixPMessageDissector fixPDissector = new FixPMessageDissector(fixPProtocol.messageDecoders());
            fixPParser = fixPProtocol.makeParser(null);
            try
            {
                fixPProxy = fixPProtocol.makeProxy(fixPDissector, null, null);
                fixPRejectRefIdExtractor = fixPProtocol.makeRefIdExtractor();
            }
            catch (final Throwable e)
            {
                errorHandler.onError(e);
            }
        }
        return protocolType;
    }

    private void onNewFixConnection(final long timeInMs, final TcpChannel channel)
    {
        final long connectionId = newConnectionId();
        final FixGatewaySession gatewaySession = setupFixConnection(
            channel,
            connectionId,
            UNKNOWN_SESSION,
            null,
            ENGINE_LIBRARY_ID,
            ACCEPTOR,
            configuration.acceptedSessionClosedResendInterval(),
            configuration.acceptedSessionResendRequestChunkSize(),
            configuration.acceptedSessionSendRedundantResendRequests(),
            configuration.acceptedEnableLastMsgSeqNumProcessed(),
            null);

        gatewaySession.disconnectAt(timeInMs + configuration.noLogonDisconnectTimeoutInMs());
        gatewaySessions.track(gatewaySession);

        saveConnect(channel, connectionId);
    }

    private void saveConnect(final TcpChannel channel, final long connectionId)
    {
        final String address = channel.remoteAddr();
        final long timeInNs = clock.nanoTime();
        final long position = inboundPublication.saveConnect(connectionId, timeInNs, address);
        if (isBackPressured(position))
        {
            schedule(() -> inboundPublication.saveConnect(connectionId, timeInNs, address));
        }
    }

    private long newConnectionId()
    {
        if (reproductionPoller != null)
        {
            return reproductionPoller.newConnectionId();
        }
        else
        {
            long connectionId;
            do
            {
                connectionId = this.nextConnectionId++;
            }
            while (connectionId == NO_CONNECTION_ID);

            return connectionId;
        }
    }

    public Action onInitiateILinkConnection(
        final int libraryId, final int port, final long correlationId,
        final boolean reestablishConnection, final boolean useBackupHost,
        final String primaryHost, final String accessKeyId, final String backupHost)
    {
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library == null)
        {
            saveUnknownLibrary(libraryId, correlationId);

            return CONTINUE;
        }

        final String host = useBackupHost ? backupHost : primaryHost;
        final InetSocketAddress address = new InetSocketAddress(host, port);
        final FixPContexts fixPContexts = this.fixPContexts;

        final FixPProtocolType protocolType = configuration.supportedFixPProtocolType();
        if (protocolType != FixPProtocolType.ILINK_3)
        {
            return invalidFixPProtocol(libraryId, correlationId, protocolType);
        }
        else if (fixPProtocol == null)
        {
            initFixPProtocol();
        }

        final ILink3Key key = new ILink3Key(port, primaryHost, accessKeyId);
        final ILink3Context context = (ILink3Context)fixPContexts.calculateInitiatorContext(key, reestablishConnection);
        if (checkDuplicateILinkConnection(libraryId, correlationId, useBackupHost, accessKeyId, address, context))
        {
            return CONTINUE;
        }

        final int aeronSessionId = library.aeronSessionId();
        final Image image = librarySubscription.imageBySessionId(aeronSessionId);
        final long position = image.position();
        final ILink3LookupConnectOperation lookupInformation = new ILink3LookupConnectOperation(
            libraryId, correlationId, context, aeronSessionId, position, address);
        schedule(lookupInformation);

        try
        {
            DebugLogger.log(FIX_CONNECTION, connectingFormatter, host, port, libraryId);

            channelSupplier.open(
                address,
                (channel, ex) ->
                {
                    if (ex != null)
                    {
                        cancelILink3LookupConnectOperation(correlationId, false);
                        saveError(UNABLE_TO_CONNECT, libraryId, correlationId, ex);
                        return;
                    }

                    DebugLogger.log(FIX_CONNECTION, initiatingSessionFormatter, context.connectUuid(), libraryId);
                    final long connectionId = newConnectionId();

                    lookupInformation.connected(connectionId);
                    if (useBackupHost)
                    {
                        context.backupConnected(true);
                    }
                    else
                    {
                        context.primaryConnected(true);
                    }

                    final AtomicCounter bytesInBuffer = fixCounters.bytesInBuffer(connectionId, channel.remoteAddr());
                    final SenderSequenceNumber senderSequenceNumber =
                        senderSequenceNumbers.onNewSender(true, connectionId, bytesInBuffer);
                    final InitiatorFixPReceiverEndPoint receiverEndPoint = new InitiatorFixPReceiverEndPoint(
                        connectionId, channel, configuration.receiverBufferSize(),
                        errorHandler, this, inboundPublication, libraryId, context,
                        configuration.epochNanoClock(), correlationId, fixPContexts, fixPProtocol,
                        configuration.throttleWindowInMs(), configuration.throttleLimitOfMessages(),
                        fixPRejectRefIdExtractor);
                    receiverEndPoints.add(receiverEndPoint);
                    fixPSenderEndPoints.add(FixPSenderEndPoint.of(
                        connectionId, channel, errorHandler, inboundPublication.dataPublication(),
                        reproductionLogWriter,
                        libraryId, configuration.messageTimingHandler(), fixPProtocol.explicitSequenceNumbers(),
                        fixPParser.templateIdOffset(), fixPParser.retransmissionTemplateId(), fixPSenderEndPoints,
                        bytesInBuffer,
                        configuration.senderMaxBytesInBuffer(), this, receiverEndPoint, senderSequenceNumber));
                });
        }
        catch (final Exception ex)
        {
            cancelILink3LookupConnectOperation(correlationId, false);
            saveError(UNABLE_TO_CONNECT, libraryId, correlationId, ex);
        }
        return CONTINUE;
    }

    private Action invalidFixPProtocol(
        final int libraryId, final long correlationId, final FixPProtocolType protocolType)
    {
        saveError(INVALID_CONFIGURATION, libraryId, correlationId, new IllegalStateException(
            "Invalid configured protocol type: " + protocolType));
        return CONTINUE;
    }

    public void onCancelOnDisconnectTrigger(final long sessionId, final long timeInNs)
    {
        if (acceptsFixP)
        {
            onFixPCancelOnDisconnectTrigger(sessionId, timeInNs);
        }
        else
        {
            onFixCancelOnDisconnectTrigger(sessionId, timeInNs);
        }
    }

    private void onFixPCancelOnDisconnectTrigger(final long sessionId, final long timeInNs)
    {
        final FixPCancelOnDisconnectTimeoutHandler handler = configuration.fixPCancelOnDisconnectTimeoutHandler();
        if (handler != null)
        {
            final FixPContext context = fixPContexts.lookupContext(sessionId);
            if (context == null)
            {
                cancelOnDisconnectError(sessionId);
                return;
            }

            schedule(new CancelOnDisconnectTimeoutOperation(sessionId, timeInNs, clock, errorHandler)
            {
                protected void onCancelOnDisconnectTimeout()
                {
                    handler.onCancelOnDisconnectTimeout(sessionId, context);
                }
            });
        }
    }

    private void onFixCancelOnDisconnectTrigger(final long sessionId, final long timeInNs)
    {
        final CancelOnDisconnectTimeoutHandler handler = configuration.cancelOnDisconnectTimeoutHandler();
        if (handler != null)
        {
            final Map.Entry<CompositeKey, SessionContext> entry = fixContexts.lookupById(sessionId);
            if (entry == null)
            {
                cancelOnDisconnectError(sessionId);
                return;
            }

            final CompositeKey sessionKey = entry.getKey();
            schedule(new CancelOnDisconnectTimeoutOperation(sessionId, timeInNs, clock, errorHandler)
            {
                protected void onCancelOnDisconnectTimeout()
                {
                    handler.onCancelOnDisconnectTimeout(sessionId, sessionKey);
                }
            });
        }
    }

    private void cancelOnDisconnectError(final long sessionId)
    {
        errorHandler.onError(new IllegalStateException("Unknown session id when performing cancel on" +
            " disconnect timeout: " + sessionId));
    }

    public Action onThrottleReject(
        final int libraryId,
        final long connectionId,
        final long refMsgType,
        final int refSeqNum,
        final int sequenceNumber,
        final int sequenceIndex,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength,
        final Header header)
    {
        return fixSenderEndPoints.onThrottleReject(
            libraryId,
            connectionId,
            refMsgType,
            refSeqNum,
            sequenceNumber,
            sequenceIndex,
            businessRejectRefIDBuffer,
            businessRejectRefIDOffset,
            businessRejectRefIDLength
        );
    }

    public Action onThrottleConfiguration(
        final int libraryId,
        final long correlationId,
        final long sessionId,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return saveThrottleConfReply(libraryId, correlationId, ThrottleConfigurationStatus.UNKNOWN_LIBRARY);
        }

        final GatewaySession gatewaySession = libraryInfo.lookupSessionById(sessionId);
        if (gatewaySession == null)
        {
            return saveThrottleConfReply(libraryId, correlationId, ThrottleConfigurationStatus.SESSION_NOT_OWNED);
        }

        if (gatewaySession.isOffline())
        {
            return saveThrottleConfReply(libraryId, correlationId, ThrottleConfigurationStatus.SESSION_NOT_LOGGED_IN);
        }

        final ThrottleConfigurationStatus status =
            gatewaySession.configureThrottle(throttleWindowInMs, throttleLimitOfMessages) ?
            ThrottleConfigurationStatus.OK : ThrottleConfigurationStatus.INVALID_DICTIONARY;
        return saveThrottleConfReply(libraryId, correlationId, status);
    }

    private Action saveThrottleConfReply(
        final int libraryId, final long correlationId, final ThrottleConfigurationStatus ok)
    {
        final long position = inboundPublication.saveThrottleConfigurationReply(libraryId, correlationId, ok);
        if (Pressure.isBackPressured(position))
        {
            schedule(() -> inboundPublication.saveThrottleConfigurationReply(libraryId, correlationId, ok));
        }

        return CONTINUE;
    }

    private boolean checkDuplicateILinkConnection(
        final int libraryId,
        final long correlationId,
        final boolean useBackupHost,
        final String accessKeyId,
        final InetSocketAddress address,
        final ILink3Context context)
    {
        // Check if we're ok
        if ((useBackupHost && !context.backupConnected()) || (!useBackupHost && !context.primaryConnected()))
        {
            return false;
        }

        saveError(DUPLICATE_SESSION, libraryId, correlationId, String.format(
            "Duplicate iLink3 Connection for (addr=%s,accessKeyId=%s,%s)",
            address,
            accessKeyId,
            useBackupHost ? "backup" : "primary"));

        return true;
    }

    public void onAllFixSessions(final long correlationId)
    {
        schedule(() -> allFixSessionsRequest(correlationId));
    }

    private long allFixSessionsRequest(final long correlationId)
    {
        final LongHashSet seenSessions = this.requestAllSessionSeenSessions;
        try
        {
            final List<SessionInfo> allSessions = fixContexts.allSessions();
            final int sessionsCount = allSessions.size();
            final SessionsEncoder sessionsEncoder = adminReplyPublication.startRequestAllFixSessions(
                correlationId,
                sessionsCount);

            replyConnectedSessions(seenSessions, sessionsEncoder, gatewaySessions.sessions());

            for (final LiveLibraryInfo libraryInfo : idToLibrary.values())
            {
                replyConnectedSessions(seenSessions, sessionsEncoder, libraryInfo.gatewaySessions());
            }

            for (final SessionInfo sessionInfo : allSessions)
            {
                if (!seenSessions.contains(sessionInfo.sessionId()))
                {
                    final SessionContext context = (SessionContext)sessionInfo;
                    final long lastLogonTime = context.lastLogonTimeInNs();
                    replySession(sessionsEncoder, NO_CONNECTION_ID, "", sessionInfo, lastLogonTime, false);
                }
            }

            return adminReplyPublication.saveRequestAllFixSessions();
        }
        finally
        {
            seenSessions.clear();
        }
    }

    public void onDisconnectSession(final long correlationId, final long sessionId)
    {
        if (!fixContexts.isKnownSessionId(sessionId))
        {
            schedule(() -> saveUnknownSessionAdminReply(correlationId, sessionId));
            return;
        }

        if (!fixContexts.isAuthenticated(sessionId))
        {
            schedule(() -> saveNotAuthenticatedAdminReply(correlationId, sessionId));
            return;
        }

        GatewaySession gatewaySession = gatewaySessions.sessionById(sessionId);
        if (gatewaySession == null)
        {
            gatewaySession = findLibrarySession(sessionId);
        }

        if (gatewaySession == null)
        {
            schedule(() -> saveNotAuthenticatedAdminReply(correlationId, sessionId));
            return;
        }

        final int libraryId = gatewaySession.libraryId();
        final long connectionId = gatewaySession.connectionId();

        onDisconnect(libraryId, connectionId, DisconnectReason.ADMIN_API_DISCONNECT);

        schedule(() -> saveOkAdminReply(correlationId));
    }

    private long saveOkAdminReply(final long correlationId)
    {
        return adminReplyPublication.saveGenericAdminReply(correlationId, GatewayError.NULL_VAL, "");
    }

    private long saveUnknownSessionAdminReply(final long correlationId, final long sessionId)
    {
        return adminReplyPublication.saveGenericAdminReply(
            correlationId, GatewayError.UNKNOWN_SESSION, sessionId + " is an unknown session");
    }

    private long saveNotAuthenticatedAdminReply(final long correlationId, final long sessionId)
    {
        return adminReplyPublication.saveGenericAdminReply(
            correlationId, GatewayError.EXCEPTION, sessionId + " is not currently authenticated");
    }

    private void replyConnectedSessions(
        final LongHashSet seenSessions,
        final SessionsEncoder sessionsEncoder,
        final List<GatewaySession> gatewaySessions)
    {
        final int gatewaySessionsSize = gatewaySessions.size();
        for (int i = 0; i < gatewaySessionsSize; i++)
        {
            final FixGatewaySession gatewaySession = (FixGatewaySession)gatewaySessions.get(i);
            final long connectionId = gatewaySession.connectionId();
            final String address = gatewaySession.address();

            final boolean isSlowConsumer = fixSenderEndPoints.isSlowConsumer(connectionId);

            replySession(
                sessionsEncoder, connectionId, address, gatewaySession, gatewaySession.lastLogonTime(), isSlowConsumer);

            seenSessions.add(gatewaySession.sessionId());
        }
    }

    private void replySession(
        final SessionsEncoder sessionsEncoder,
        final long connectionId,
        final String address,
        final SessionInfo sessionInfo,
        final long lastLogonTime,
        final boolean isSlowConsumer)
    {
        final long sessionId = sessionInfo.sessionId();
        final CompositeKey sessionKey = sessionInfo.sessionKey();

        if (sessionKey != null)
        {
            final int lastReceivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
            final int lastSentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);

            sessionsEncoder.next()
                    .sessionId(sessionId)
                    .connectionId(connectionId)
                    .lastReceivedSequenceNumber(lastReceivedSequenceNumber)
                    .lastSentSequenceNumber(lastSentSequenceNumber)
                    .lastLogonTime(lastLogonTime)
                    .sequenceIndex(sessionInfo.sequenceIndex())
                    .slowStatus(isSlowConsumer ? SlowStatus.SLOW : SlowStatus.NOT_SLOW)
                    .address(address)
                    .localCompId(sessionKey.localCompId())
                    .localSubId(sessionKey.localSubId())
                    .localLocationId(sessionKey.localLocationId())
                    .remoteCompId(sessionKey.remoteCompId())
                    .remoteSubId(sessionKey.remoteSubId())
                    .remoteLocationId(sessionKey.remoteLocationId());
        }
    }

    public void onAdminResetSequenceNumbersRequest(final long correlationId, final long sessionId)
    {
        if (!fixContexts.isKnownSessionId(sessionId))
        {
            schedule(() -> saveUnknownSessionAdminReply(correlationId, sessionId));
            return;
        }

        // Delegate to the existing ResetSequenceNumberCommand with an additional step at the end in order
        // to notify the admin API
        final ResetSequenceNumberCommand resetSequenceNumberCommand = new ResetSequenceNumberCommand(
            sessionId,
            gatewaySessions,
            fixContexts,
            receivedSequenceNumberIndex,
            sentSequenceNumberIndex,
            inboundPublication,
            outboundPublication,
            clock.nanoTime());

        resetSequenceNumberCommand.setupAdminReset(correlationId, adminReplyPublication);

        onResetSequenceNumber(resetSequenceNumberCommand);
    }

    public void startLingering(
        final PendingAcceptorLogon pendingAcceptorLogon, final long lingerExpiryTimeInMs)
    {
        final long timerId = timerWheel.scheduleTimer(lingerExpiryTimeInMs);
        timerEventHandler.startLingering(timerId, pendingAcceptorLogon);

        // NB: at this point we don't register the REP on the selector, TCP and NIO are the villains of this story
        // if we register then a counter-party disconnecting the connection before the end of the linger would result
        // selector events and in repeat pending logon polling and latency. If we get a selector event in this
        // case it would either be a read or a disconnect - but Nio doesn't distinguish between these two things,
        // So either we allow a counter-party that spams on logon to cause a latency blip by reading these events
        // or a counter-party that disconnects to cause a latency blip by reading the logout.
        receiverEndPoints.receiverEndPointPollingOptional(
            pendingAcceptorLogon.connectionId(), false);
    }

    void onResetReplayQuery(final long fixSessionId)
    {
        inboundMessages.onReset(fixSessionId);
    }

    void onStartReproduction(final StartReproduction startReproduction)
    {
        reproductionPoller.start(startReproduction, idToLibrary);
        schedule(reproductionPoller);
    }

    private final class ILink3LookupConnectOperation implements Continuation
    {
        private final int libraryId;
        private final long correlationId;
        private final ILink3Context context;
        private final int aeronSessionId;
        private final long position;
        private final InetSocketAddress address;

        private boolean hasConnected = false;
        private boolean hasScannedIndex = false;

        private long connectionId;
        private long lastReceivedSequenceNumber;
        private long lastSentSequenceNumber;

        private ILink3LookupConnectOperation(
            final int libraryId,
            final long correlationId,
            final ILink3Context context,
            final int aeronSessionId,
            final long position,
            final InetSocketAddress address)
        {
            this.libraryId = libraryId;
            this.correlationId = correlationId;
            this.context = context;
            this.aeronSessionId = aeronSessionId;
            this.position = position;
            this.address = address;
        }

        public long attempt()
        {
            if (!hasScannedIndex)
            {
                scanIndex();

                return BACK_PRESSURED;
            }

            if (!hasConnected)
            {
                return BACK_PRESSURED;
            }

            return inboundPublication.saveILinkConnect(
                libraryId, correlationId, connectionId, context.connectUuid(), lastReceivedSequenceNumber,
                lastSentSequenceNumber, context.newlyAllocated(), context.connectLastUuid());
        }

        private void scanIndex()
        {
            if (sentSequenceNumberIndex.indexedPosition(aeronSessionId) > position)
            {
                final long uuidToScan = context.connectLastUuid() == 0 ?
                    context.connectUuid() : context.connectLastUuid();
                lastSentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(uuidToScan);
                lastReceivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(uuidToScan);
                hasScannedIndex = true;
            }
        }

        public void connected(final long connectionId)
        {
            hasConnected = true;
            this.connectionId = connectionId;
        }
    }

    public Action onInitiateConnection(
        final int libraryId, final int port, final String host,
        final String senderCompId, final String senderSubId, final String senderLocationId,
        final String targetCompId, final String targetSubId, final String targetLocationId,
        final SequenceNumberType sequenceNumberType,
        final int requestedInitialReceivedSequenceNumber, final int requestedInitialSentSequenceNumber,
        final boolean resetSequenceNumber, final boolean closedResendInterval, final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests, final boolean enableLastMsgSeqNumProcessed,
        final String username, final String password,
        final Class<? extends FixDictionary> fixDictionaryClass,
        final int heartbeatIntervalInS,
        final long correlationId,
        final Header header)
    {
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library == null)
        {
            return saveUnknownLibrary(libraryId, correlationId);
        }

        if (acceptsFixP)
        {
            return saveError(INVALID_CONFIGURATION, libraryId, correlationId, new IllegalStateException(
                "Artio configured as a FIXP acceptor, cannot initiate FIX connection in this configuration."));
        }

        final boolean logInboundMessages = configuration.logInboundMessages();
        final boolean logOutboundMessages = configuration.logOutboundMessages();
        if (sequenceNumberType == PERSISTENT && !configuration.logAllMessages())
        {
            return badSequenceNumberConfiguration(libraryId, correlationId, logInboundMessages, logOutboundMessages);
        }

        final CompositeKey sessionKey = sessionIdStrategy.onInitiateLogon(
            senderCompId, senderSubId, senderLocationId,
            targetCompId, targetSubId, targetLocationId);
        final SessionContext sessionContext = fixContexts.onLogon(
            sessionKey, FixDictionary.of(fixDictionaryClass));

        if (isUnsafeDuplicateSession(sessionContext, library))
        {
            return sendDuplicateSessionError(libraryId, correlationId, sessionKey);
        }

        try
        {
            DebugLogger.log(FIX_CONNECTION, connectingFormatter, host, port, libraryId);

            final InetSocketAddress address = new InetSocketAddress(host, port);
            final ConnectingSession connectingSession = new ConnectingSession(address, sessionContext.sessionId());
            library.connectionStartsConnecting(correlationId, connectingSession);
            channelSupplier.open(address,
                (channel, ex) ->
                {
                    if (ex != null)
                    {
                        fixContexts.onDisconnect(sessionContext.sessionId());
                        library.connectionFinishesConnecting(correlationId);
                        saveError(UNABLE_TO_CONNECT, libraryId, correlationId, ex);
                        return;
                    }

                    onFixConnectionOpen(
                        libraryId,
                        senderCompId, senderSubId, senderLocationId,
                        targetCompId, targetSubId, targetLocationId,
                        sequenceNumberType,
                        resetSequenceNumber, closedResendInterval, resendRequestChunkSize, sendRedundantResendRequests,
                        enableLastMsgSeqNumProcessed,
                        username, password,
                        fixDictionaryClass,
                        heartbeatIntervalInS,
                        correlationId,
                        header,
                        library,
                        address,
                        channel,
                        sessionContext,
                        sessionKey);
                });
        }
        catch (final Exception ex)
        {
            fixContexts.onDisconnect(sessionContext.sessionId());
            return saveError(UNABLE_TO_CONNECT, libraryId, correlationId, ex);
        }

        return CONTINUE;
    }

    private Action sendDuplicateSessionError(
        final int libraryId, final long correlationId, final CompositeKey sessionKey)
    {
        final long sessionId = fixContexts.lookupSessionId(sessionKey);
        final int owningLibraryId = fixSenderEndPoints.libraryLookup().applyAsInt(sessionId);
        final String msg = "Duplicate Session for: " + sessionKey + " Surrogate Key: " + sessionId +
            " Currently owned by " + owningLibraryId;
        return saveError(DUPLICATE_SESSION, libraryId, correlationId, msg);
    }

    private boolean isUnsafeDuplicateSession(final SessionContext sessionContext, final LiveLibraryInfo library)
    {
        // Obvious
        if (sessionContext == FixContexts.DUPLICATE_SESSION)
        {
            return true;
        }

        // If the library in question owns the session and its offline then it's safe to initiate the connection.
        // If another library owns the session then it's not safe.
        final long sessionId = sessionContext.sessionId();
        final GatewaySession gatewaySession = library.lookupSessionById(sessionId);
        if (gatewaySession != null && !acceptsFixP)
        {
            return !gatewaySession.isOffline();
        }

        return isOwnedSession(sessionId);
    }

    private Action saveUnknownLibrary(final int libraryId, final long correlationId)
    {
        return saveError(GatewayError.UNKNOWN_LIBRARY, libraryId, correlationId, "Unknown Library");
    }

    public Action onMidConnectionDisconnect(final int libraryId, final long correlationId)
    {
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library == null)
        {
            saveError(GatewayError.UNKNOWN_LIBRARY, libraryId, correlationId, "");

            return CONTINUE;
        }

        final ConnectingSession connectingSession = library.connectionFinishesConnecting(correlationId);
        if (connectingSession == null)
        {
            if (cancelILink3LookupConnectOperation(correlationId, true))
            {
                return CONTINUE;
            }

            saveError(GatewayError.UNKNOWN_SESSION, libraryId, correlationId,
                "Engine doesn't think library is connecting this session");

            return CONTINUE;
        }

        fixContexts.onDisconnect(connectingSession.sessionId());
        final InetSocketAddress address = connectingSession.address();
        stopConnecting(address);

        return CONTINUE;
    }

    private boolean cancelILink3LookupConnectOperation(final long correlationId, final boolean requiresStopping)
    {
        return retryManager.removeIf(continuation ->
        {
            if (continuation instanceof ILink3LookupConnectOperation)
            {
                final ILink3LookupConnectOperation connectOperation = (ILink3LookupConnectOperation)continuation;
                if (connectOperation.correlationId == correlationId)
                {
                    if (requiresStopping)
                    {
                        stopConnecting(connectOperation.address);
                    }

                    return true;
                }
            }

            return false;
        }) > 0;
    }

    private void stopConnecting(final InetSocketAddress address)
    {
        try
        {
            channelSupplier.stopConnecting(address);
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
        }
    }

    private Action badSequenceNumberConfiguration(
        final int libraryId,
        final long correlationId,
        final boolean logInboundMessages,
        final boolean logOutboundMessages)
    {
        final String msg =
            "You need to enable the logging of inbound and outbound messages on your EngineConfiguration" +
            " in order to initiate a connection with persistent sequence numbers." +
            " logInboundMessages = " + logInboundMessages +
            " logOutboundMessages = " + logOutboundMessages;

        saveError(INVALID_CONFIGURATION, libraryId, correlationId, msg);

        return CONTINUE;
    }

    private void onFixConnectionOpen(
        final int libraryId,
        final String senderCompId,
        final String senderSubId,
        final String senderLocationId,
        final String targetCompId,
        final String targetSubId,
        final String targetLocationId,
        final SequenceNumberType sequenceNumberType,
        final boolean resetSequenceNumber,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final String username,
        final String password,
        final Class<? extends FixDictionary> fixDictionaryClass,
        final int heartbeatIntervalInS,
        final long correlationId,
        final Header outBoundheader,
        final LiveLibraryInfo library,
        final InetSocketAddress address,
        final TcpChannel channel,
        final SessionContext sessionContext,
        final CompositeKey sessionKey)
    {
        try
        {
            DebugLogger.log(FIX_CONNECTION,
                initiatingSessionFormatter, sessionContext.sessionId(), library.libraryId());
            final long connectionId = newConnectionId();
            final FixDictionary fixDictionary = FixDictionary.of(fixDictionaryClass);
            sessionContext.onLogon(
                resetSequenceNumber || sequenceNumberType == TRANSIENT, clock.nanoTime(), fixDictionary);
            final long sessionId = sessionContext.sessionId();
            final FixGatewaySession gatewaySession = setupFixConnection(
                channel,
                connectionId,
                sessionContext,
                sessionKey,
                libraryId,
                INITIATOR,
                closedResendInterval,
                resendRequestChunkSize,
                sendRedundantResendRequests,
                enableLastMsgSeqNumProcessed,
                fixDictionary);
            gatewaySession.libraryId(libraryId);
            gatewaySession.lastSequenceResetTime(sessionContext.lastSequenceResetTime());
            gatewaySession.lastLogonTime(sessionContext.lastLogonTimeInNs());
            library.addSession(gatewaySession);

            handoverNewConnectionToLibrary(
                libraryId,
                senderCompId,
                senderSubId,
                senderLocationId,
                targetCompId,
                targetSubId,
                targetLocationId,
                closedResendInterval,
                resendRequestChunkSize,
                sendRedundantResendRequests,
                enableLastMsgSeqNumProcessed,
                username,
                password,
                // Will be echo'd or validated by counter-party in Logon message
                CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT,
                0,
                fixDictionaryClass,
                heartbeatIntervalInS,
                correlationId,
                library,
                sessionContext,
                sessionKey,
                connectionId,
                sessionId,
                gatewaySession,
                outBoundheader.sessionId(),
                outBoundheader.position(),
                address.toString(),
                INITIATOR);
        }
        catch (final Exception e)
        {
            saveError(EXCEPTION, libraryId, correlationId, e);
        }
    }

    // Used when handing over a new connection that has never been gateway managed.
    // Eg: accepted sessions in initialAcceptedSessionOwner=SOLE_LIBRARY and also initiated sessions
    private void handoverNewConnectionToLibrary(
        final int libraryId,
        final String senderCompId,
        final String senderSubId,
        final String senderLocationId,
        final String targetCompId,
        final String targetSubId,
        final String targetLocationId,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final String username,
        final String password,
        final CancelOnDisconnectOption cancelOnDisconnectOption,
        final long cancelOnDisconnectTimeoutWindowInNs,
        final Class<? extends FixDictionary> fixDictionary,
        final int heartbeatIntervalInS,
        final long correlationId,
        final LiveLibraryInfo library,
        final SessionContext sessionContext,
        final CompositeKey sessionKey,
        final long connectionId,
        final long sessionId,
        final FixGatewaySession gatewaySession,
        final int outBoundAeronSessionId,
        final long outBoundRequiredPosition,
        final String address,
        final ConnectionType connectionType)
    {
        schedule(new HandoverNewFixConnectionToLibrary(
            gatewaySession,
            outBoundAeronSessionId,
            outBoundRequiredPosition,
            sessionId,
            connectionType,
            sessionContext,
            sessionKey,
            username,
            password,
            cancelOnDisconnectOption,
            cancelOnDisconnectTimeoutWindowInNs,
            heartbeatIntervalInS,
            libraryId,
            connectionId,
            closedResendInterval,
            resendRequestChunkSize,
            sendRedundantResendRequests,
            enableLastMsgSeqNumProcessed,
            correlationId,
            senderCompId,
            senderSubId,
            senderLocationId,
            targetCompId,
            targetSubId,
            targetLocationId,
            address,
            fixDictionary,
            library));
    }

    Action saveError(final GatewayError error, final int libraryId, final long replyToId, final String message)
    {
        schedule(() -> inboundPublication.saveError(error, libraryId, replyToId, message));
        return CONTINUE;
    }

    private Action saveError(final GatewayError error, final int libraryId, final long replyToId, final Exception e)
    {
        final String message = e.getMessage();
        errorHandler.onError(e);
        return saveError(error, libraryId, replyToId, message == null ? e.getClass().getName() : message);
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
        final long timestamp,
        final MessageStatus status,
        final int sequenceNumber,
        final Header header,
        final int metaDataLength)
    {
        final long now = outboundTimer.recordSince(timestamp);

        onApplicationHeartbeat(libraryId, header.sessionId(), FixMessageDecoder.TEMPLATE_ID, timestamp);

        final boolean online = fixSenderEndPoints.onMessage(
            libraryId,
            connectionId,
            buffer,
            offset,
            length,
            sequenceNumber,
            sequenceIndex,
            messageType,
            metaDataLength);

        if (!online)
        {
            checkOfflineSequenceReset(sessionId, messageType, sequenceIndex);
        }

        sendTimer.recordSince(now);

        return CONTINUE;
    }

    public Action onValidResendRequest(
        final long session, final long connection, final long correlationId, final Header header)
    {
        fixSenderEndPoints.onValidResendRequest(connection, correlationId);

        fixPSenderEndPoints.onValidResendRequest(connection, correlationId);

        return CONTINUE;
    }

    private void checkOfflineSequenceReset(final long sessionId, final long messageType, final int sequenceIndex)
    {
        if (messageType == LOGON_MESSAGE_TYPE || messageType == SEQUENCE_RESET_MESSAGE_TYPE)
        {
            final Map.Entry<CompositeKey, SessionContext> entry = fixContexts.lookupById(sessionId);
            if (entry != null)
            {
                final SessionContext context = entry.getValue();
                final int currentSequenceIndex = context.sequenceIndex();
                if (sequenceIndex > currentSequenceIndex)
                {
                    context.onSequenceIndex(clock.nanoTime(), sequenceIndex);
                }
            }
        }
    }

    public Action onFixPMessage(final long connectionId, final DirectBuffer buffer, final int offset)
    {
        return fixPSenderEndPoints.onMessage(connectionId, buffer, offset, false);
    }

    private FixGatewaySession setupFixConnection(
        final TcpChannel channel,
        final long connectionId,
        final SessionContext context,
        final CompositeKey sessionKey,
        final int libraryId,
        final ConnectionType connectionType,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary)
    {
        final FixReceiverEndPoint receiverEndPoint = endPointFactory.receiverEndPoint(
            channel,
            connectionId,
            context.sessionId(),
            context.sequenceIndex(),
            libraryId,
            this);
        receiverEndPoints.add(receiverEndPoint);

        final FixSenderEndPoint senderEndPoint = endPointFactory.senderEndPoint(
            channel, connectionId, libraryId, this, receiverEndPoint);
        fixSenderEndPoints.add(senderEndPoint);

        final FixGatewaySession gatewaySession = new FixGatewaySession(
            connectionId,
            context,
            channel.remoteAddr(),
            connectionType,
            sessionKey,
            receiverEndPoint,
            senderEndPoint,
            this.onSessionLogon,
            closedResendInterval,
            resendRequestChunkSize,
            sendRedundantResendRequests,
            enableLastMsgSeqNumProcessed,
            fixDictionary,
            configuration);

        receiverEndPoint.gatewaySession(gatewaySession);

        return gatewaySession;
    }

    public Action onRequestDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        return onDisconnect(libraryId, connectionId, reason);
    }

    public Action onDisconnect(final int libraryId, final long connectionId, final DisconnectReason reason)
    {
        receiverEndPoints.removeConnection(connectionId, reason);
        fixSenderEndPoints.removeConnection(connectionId);
        fixPSenderEndPoints.removeConnection(connectionId);
        gatewaySessions.releaseByConnectionId(connectionId);
        fixPContexts.onDisconnect(connectionId);

        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library != null)
        {
            if (soleLibraryMode)
            {
                library.offlineSession(connectionId);
            }
            else
            {
                library.removeSessionByConnectionId(connectionId);
            }
        }

        return CONTINUE;
    }

    public Action onSeqIndexSync(final int libraryId, final long sessionId, final int sequenceIndex)
    {
        final long timeInNs = clock.nanoTime();
        fixContexts.onSequenceIndex(sessionId, timeInNs, sequenceIndex);
        return CONTINUE;
    }

    public Action onLibraryConnect(
        final int libraryId,
        final String libraryName,
        final long correlationId,
        final int aeronSessionId)
    {
        final Action action = retryManager.retry(correlationId);
        if (action != null)
        {
            return action;
        }

        if (performingDisconnectOperation)
        {
            // Do not do allow a new library to connect whilst performing end of day operations.
            return CONTINUE;
        }

        final RecordingCoordinator.LibraryExtendPosition extend = recordingCoordinator.trackLibrary(
            aeronSessionId, libraryId);
        if (extend != null)
        {
            return Pressure.apply(inboundPublication.saveLibraryExtendPosition(libraryId, correlationId, extend));
        }

        final LiveLibraryInfo existingLibrary = idToLibrary.get(libraryId);
        if (existingLibrary != null)
        {
            existingLibrary.onHeartbeat(epochClock.time());

            return saveControlNotification(libraryId, existingLibrary.sessions()) ? CONTINUE : ABORT;
        }

        if (soleLibraryMode)
        {
            if (idToLibrary.size() >= 1)
            {
                logSoleLibraryError();
                return CONTINUE;
            }
            else
            {
                soleLibraryModeBind();
            }
        }

        // Send an empty control notification if you've never seen this library before
        if (!saveControlNotification(libraryId, Collections.emptyList()))
        {
            return ABORT;
        }

        final List<Continuation> unitsOfWork = new ArrayList<>();
        unitsOfWork.add(() ->
        {
            final LivenessDetector livenessDetector = LivenessDetector.forEngine(
                inboundPublication,
                libraryId,
                configuration.replyTimeoutInMs(),
                epochClock.time(),
                clock);

            final LiveLibraryInfo library = new LiveLibraryInfo(
                errorHandler,
                libraryId, libraryName, livenessDetector, aeronSessionId,
                gatewaySessions instanceof FixPGatewaySessions);
            idToLibrary.put(libraryId, library);

            DebugLogger.log(LIBRARY_MANAGEMENT, libraryConnectedFormatter, libraryId, libraryName);

            return COMPLETE;
        });

        for (final GatewaySession gatewaySession : gatewaySessions.sessions())
        {
            if (!acceptsFixP)
            {
                final FixGatewaySession fixGatewaySession = (FixGatewaySession)gatewaySession;
                final InternalSession session = fixGatewaySession.session();
                // session could be null if this gatewaySession is still in the process of
                // logging on at this point in time.
                if (session != null)
                {
                    unitsOfWork.add(saveManageSession(
                        libraryId,
                        fixGatewaySession));
                }
            }
        }

        return retryManager.firstAttempt(correlationId, new UnitOfWork(unitsOfWork));
    }

    private boolean saveControlNotification(final int libraryId, final List<?> sessions)
    {
        final LongHashSet disconnectedSessions = gatewaySessions.findDisconnectedSessions(libraryId);
        if (Pressure.isBackPressured(
            inboundPublication.saveControlNotification(
            libraryId, initialAcceptedSessionOwner, sessions, disconnectedSessions)))
        {
            return false;
        }
        else
        {
            gatewaySessions.removeDisconnectedSessions(disconnectedSessions);
            return true;
        }
    }

    private void soleLibraryModeBind()
    {
        if (shouldBind)
        {
            try
            {
                channelSupplier.bind();
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }
    }

    public Action onApplicationHeartbeat(
        final int libraryId, final int aeronSessionId, final int messageTemplateId, final long timestampInNs)
    {
        final LiveLibraryInfo library = idToLibrary.get(libraryId);
        if (library != null)
        {
            final long timeInMs = epochClock.time();
            if (DebugLogger.isEnabled(APPLICATION_HEARTBEAT) && !DebugLogger.outboundFixMessage(messageTemplateId))
            {
                DebugLogger.log(
                    APPLICATION_HEARTBEAT,
                    applicationHeartbeatFormatter,
                    messageTemplateId,
                    libraryId,
                    timeInMs,
                    timestampInNs);
            }
            library.onHeartbeat(timeInMs);

            return null;
        }

        // Don't skip messages from the engine managed library.
        if (libraryId == ENGINE_LIBRARY_ID)
        {
            return null;
        }

        return CONTINUE;
    }

    public Action onReleaseSession(
        final int libraryId,
        final long connectionId,
        final long sessionId,
        final long correlationId,
        final SessionState state,
        final boolean awaitingResend,
        final long heartbeatIntervalInMs,
        final int lastSentSequenceNumber,
        final int lastReceivedSequenceNumber,
        final String username,
        final String password,
        final Header header)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return saveReleaseSessionReply(correlationId, SessionReplyStatus.UNKNOWN_LIBRARY);
        }

        DebugLogger.log(
            LIBRARY_MANAGEMENT,
            releasingSessionFormatter,
            sessionId,
            connectionId,
            libraryId);

        final FixGatewaySession session = (FixGatewaySession)libraryInfo.removeSessionBySessionId(sessionId);

        if (session == null)
        {
            return saveReleaseSessionReply(correlationId, SessionReplyStatus.UNKNOWN_SESSION);
        }

        final Action action = Pressure.apply(inboundPublication.saveReleaseSessionReply(OK, correlationId));
        if (action == ABORT)
        {
            libraryInfo.addSession(session);
        }
        else
        {
            // Gateway doesn't need to acquire offline session.
            if (state != DISCONNECTED)
            {
                ((FixGatewaySessions)gatewaySessions).acquire(
                    session,
                    state,
                    awaitingResend,
                    (int)MILLISECONDS.toSeconds(heartbeatIntervalInMs),
                    lastSentSequenceNumber,
                    lastReceivedSequenceNumber,
                    username,
                    password);

                schedule(saveManageSession(ENGINE_LIBRARY_ID, session));
            }
        }

        return action;
    }

    private Action saveReleaseSessionReply(final long correlationId, final SessionReplyStatus unknownLibrary)
    {
        final long position = inboundPublication.saveReleaseSessionReply(unknownLibrary, correlationId);
        if (Pressure.isBackPressured(position))
        {
            schedule(() -> inboundPublication.saveReleaseSessionReply(unknownLibrary, correlationId));
        }

        return CONTINUE;
    }

    public Action onRequestSession(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return saveRequestSessionReply(libraryId, SessionReplyStatus.UNKNOWN_LIBRARY, correlationId);
        }

        if (sessionId == Session.UNKNOWN)
        {
            return saveRequestSessionReply(libraryId, SessionReplyStatus.UNKNOWN_SESSION, correlationId);
        }

        if (acceptsFixP)
        {
            return onRequestFixPSession(libraryId, libraryInfo, sessionId, correlationId);
        }
        else
        {
            return onRequestFixSession(
                libraryId, libraryInfo, sessionId, correlationId, replayFromSequenceNumber, replayFromSequenceIndex);
        }
    }

    private Action saveRequestSessionReply(
        final int libraryId, final SessionReplyStatus status, final long correlationId)
    {
        final GatewayPublication inboundPublication = this.inboundPublication;
        final long position = inboundPublication.saveRequestSessionReply(libraryId, status, correlationId);
        if (Pressure.isBackPressured(position))
        {
            schedule(() -> inboundPublication.saveRequestSessionReply(libraryId, status, correlationId));
        }

        return CONTINUE;
    }

    private Action onRequestFixPSession(
        final int libraryId,
        final LiveLibraryInfo libraryInfo,
        final long sessionId,
        final long correlationId)
    {
        return Pressure.apply(onRequestFixPSessionInternal(libraryId, libraryInfo, sessionId, correlationId));
    }

    private long onRequestFixPSessionInternal(
        final int libraryId, final LiveLibraryInfo libraryInfo, final long sessionId, final long correlationId)
    {
        final FixPGatewaySession gatewaySession = (FixPGatewaySession)gatewaySessions.releaseBySessionId(sessionId);
        if (gatewaySession == null)
        {
            if (requestOfflineFixPSession(libraryInfo, sessionId, correlationId))
            {
                return 1;
            }
            else
            {
                return inboundPublication.saveRequestSessionReply(
                    libraryId, SessionReplyStatus.UNKNOWN_SESSION, correlationId);
            }
        }

        final int currentLibraryId = gatewaySession.libraryId();
        if (currentLibraryId != ENGINE_LIBRARY_ID && currentLibraryId != libraryId)
        {
            return inboundPublication.saveRequestSessionReply(
                libraryId, OTHER_SESSION_OWNER, correlationId);
        }

        gatewaySession.setManagementTo(libraryId);
        libraryInfo.addSession(gatewaySession);

        schedule(new OnRequestFixPSessionHandover(
            correlationId,
            gatewaySession,
            libraryInfo,
            false));

        return 1;
    }

    private boolean requestOfflineFixPSession(
        final LiveLibraryInfo libraryInfo,
        final long sessionId,
        final long correlationId)
    {
        final InternalFixPContext context = fixPContexts.lookupContext(sessionId);
        if (context == null)
        {
            return false;
        }

        if (isOwnedSession(sessionId))
        {
            saveOtherSessionOwner(libraryInfo, correlationId);
        }
        else
        {
            // Create GatewaySession for offline session and associate it with the library
            final FixPProtocolType protocolType = initFixPProtocol();

            final int libraryId = libraryInfo.libraryId();
            final FixPGatewaySession gatewaySession = new FixPGatewaySession(
                NO_CONNECTION_ID,
                sessionId,
                ":" + NO_CONNECTION_ID,
                ACCEPTOR,
                configuration.authenticationTimeoutInMs(),
                protocolType,
                fixPParser,
                fixPProxy,
                null,
                null,
                (FixPGatewaySessions)gatewaySessions);

            final byte[] firstMessage = fixPProxy.encodeFirstMessage(context);
            gatewaySession.setupOfflineSession(context, firstMessage, libraryId);
            libraryInfo.addSession(gatewaySession);

            schedule(new OnRequestFixPSessionHandover(
                correlationId,
                gatewaySession,
                libraryInfo,
                true));
        }

        return true;
    }

    class SessionHandover extends UnitOfWork
    {
        private final int aeronSessionId;
        long requiredPosition;

        SessionHandover()
        {
            super(new ArrayList<>());

            aeronSessionId = outboundPublication.sessionId();
            requiredPosition = outboundPublication.position();
        }

        long awaitIndexer()
        {
            // Ensure that we've indexed up to this point in time.
            // If we don't do this then the indexer thread could receive a message sent from the Framer after
            // the library has sent its first message and get the wrong sent sequence number.
            // Only applies if there's a position to wait for and if the indexer is actually running on those messages.
            if (requiredPosition > 0 && configuration.logOutboundMessages())
            {
                return sentIndexedPosition(aeronSessionId, requiredPosition) ? COMPLETE : BACK_PRESSURED;
            }
            else
            {
                return COMPLETE;
            }
        }
    }

    final class OnRequestFixPSessionHandover extends SessionHandover
    {
        private final long correlationId;
        private final FixPGatewaySession gatewaySession;
        private final LiveLibraryInfo libraryInfo;
        private final boolean offline;

        OnRequestFixPSessionHandover(
            final long correlationId,
            final FixPGatewaySession gatewaySession,
            final LiveLibraryInfo libraryInfo,
            final boolean offline)
        {
            this.correlationId = correlationId;
            this.gatewaySession = gatewaySession;
            this.libraryInfo = libraryInfo;
            this.offline = offline;

            // NB: simpler handover than with FIX between we don't allow the engine to own session management
            // for FixP connections so we don't need to wait for gateway owned messages to be handed over
            workList.add(this::awaitIndexer);
            workList.add(this::sendManageConnection);
        }

        private long sendManageConnection()
        {
            final long sessionId = gatewaySession.sessionId();

            final long lastConnectPayload = gatewaySession.hasUnsentMessagesAtNegotiate() ?
                FOR_NEXT_SESSION_VERSION : NOT_FOR_NEXT_SESSION_VERSION;

            return inboundPublication.saveManageFixPConnection(
                libraryInfo.libraryId(),
                correlationId,
                gatewaySession.connectionId(),
                sessionId,
                gatewaySession.protocolType(),
                receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId),
                sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId),
                lastConnectPayload,
                gatewaySession.firstMessage(),
                offline);
        }
    }

    private Action onRequestFixSession(
        final int libraryId,
        final LiveLibraryInfo libraryInfo,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex)
    {
        final FixGatewaySession gatewaySession = (FixGatewaySession)gatewaySessions.releaseBySessionId(sessionId);
        if (gatewaySession == null)
        {
            // Session maybe offline.
            if (requestOfflineFixSession(
                libraryInfo, sessionId, correlationId, replayFromSequenceIndex, replayFromSequenceNumber))
            {
                return CONTINUE;
            }
            else
            {
                return saveRequestSessionReply(libraryId, SessionReplyStatus.UNKNOWN_SESSION, correlationId);
            }
        }

        final InternalSession session = gatewaySession.session();
        if (session == null || !session.isActive())
        {
            return saveRequestSessionReply(libraryId, SESSION_NOT_LOGGED_IN, correlationId);
        }

        schedule(new OnRequestFixSessionHandover(
            correlationId, replayFromSequenceNumber, replayFromSequenceIndex, libraryInfo, gatewaySession));

        return CONTINUE;
    }

    final class OnRequestFixSessionHandover extends SessionHandover
    {
        private final int libraryId;
        private final LiveLibraryInfo libraryInfo;
        private final FixGatewaySession gatewaySession;
        private final InternalSession session;
        private final long sessionId;
        private final long connectionId;

        // captured in awaitGatewaySessionMessagesSent
        private int lastSentSeqNum;
        private int lastRecvSeqNum;

        OnRequestFixSessionHandover(
            final long correlationId,
            final int replayFromSequenceNumber,
            final int replayFromSequenceIndex,
            final LiveLibraryInfo libraryInfo,
            final FixGatewaySession gatewaySession)
        {
            this.libraryInfo = libraryInfo;
            this.gatewaySession = gatewaySession;

            libraryId = libraryInfo.libraryId();
            session = gatewaySession.session();
            sessionId = session.id();

            connectionId = gatewaySession.connectionId();
            final DirectBuffer buffer = new UnsafeBuffer();
            final MetaDataStatus status = sentSequenceNumberIndex.readMetaData(session.id(), buffer);

            // Add the gateway session into the library's owned set at this point, ie
            // before the handover has happened in order to avoid another racing
            // requestSession from handing over an offline session
            libraryInfo.addSession(gatewaySession);

            add(this::awaitGatewaySessionMessagesSent);
            add(() -> saveManageSessionTo(correlationId, status, buffer, outboundPublication));
            add(this::awaitIndexer);

            // Notify the library
            add(() -> saveManageSessionTo(correlationId, status, buffer, inboundPublication));
            scheduleCatchupSession(
                workList,
                libraryId,
                connectionId,
                correlationId,
                replayFromSequenceNumber,
                replayFromSequenceIndex,
                gatewaySession,
                () -> lastRecvSeqNum);
        }

        private long awaitGatewaySessionMessagesSent()
        {
            // If there's still a message that was sent by the session when it was managed by the FixEngine
            // then wait for it to be sent.
            if (outboundEngineImage.position() < gatewaySession.lastSentPosition())
            {
                return BACK_PRESSURED;
            }

            gatewaySession.handoverManagementTo(libraryId);
            // only capture sequences after receiver has been stopped
            lastRecvSeqNum = session.lastReceivedMsgSeqNum();
            lastSentSeqNum = session.lastSentMsgSeqNum();

            DebugLogger.log(LIBRARY_MANAGEMENT, handingToLibraryFormatter, sessionId, libraryId);

            return COMPLETE;
        }

        private long saveManageSessionTo(
            final long correlationId,
            final MetaDataStatus metaDataStatus,
            final DirectBuffer metaData,
            final GatewayPublication publication)
        {
            final CompositeKey compositeKey = session.compositeKey();
            final long position = publication.saveManageSession(
                libraryId,
                connectionId,
                gatewaySession.sessionId(),
                lastSentSeqNum,
                lastRecvSeqNum,
                SESSION_HANDOVER,
                gatewaySession.slowStatus(),
                gatewaySession.connectionType(),
                session.state(),
                session.awaitingResend(),
                gatewaySession.heartbeatIntervalInS(),
                gatewaySession.closedResendInterval(),
                gatewaySession.resendRequestChunkSize(),
                gatewaySession.sendRedundantResendRequests(),
                gatewaySession.enableLastMsgSeqNumProcessed(),
                correlationId,
                gatewaySession.sequenceIndex(),
                session.lastResentMsgSeqNo(),
                session.lastResendChunkMsgSeqNum(),
                session.endOfResendRequestRange(),
                session.awaitingHeartbeat(),
                gatewaySession.logonReceivedSequenceNumber(),
                gatewaySession.logonSequenceIndex(),
                session.lastLogonTimeInNs(),
                session.lastSequenceResetTimeInNs(),
                compositeKey.localCompId(),
                compositeKey.localSubId(),
                compositeKey.localLocationId(),
                compositeKey.remoteCompId(),
                compositeKey.remoteSubId(),
                compositeKey.remoteLocationId(),
                gatewaySession.address(),
                gatewaySession.username(),
                gatewaySession.password(),
                gatewaySession.fixDictionary().getClass(),
                metaDataStatus,
                metaData,
                gatewaySession.cancelOnDisconnectOption(),
                gatewaySession.cancelOnDisconnectTimeoutWindowInNs());

            if (position > 0)
            {
                // technically the wrong position is written on the inbound publication, but its always used
                // in awaitIndexer() before then
                this.requiredPosition = position;
            }

            return position;
        }
    }

    private boolean requestOfflineFixSession(
        final LiveLibraryInfo libraryInfo,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceIndex,
        final int replayFromSequenceNumber)
    {
        final Map.Entry<CompositeKey, SessionContext> entry = fixContexts.lookupById(sessionId);
        if (entry == null)
        {
            return false;
        }

        if (isOwnedSession(sessionId))
        {
            saveOtherSessionOwner(libraryInfo, correlationId);
        }
        else
        {
            schedule(new HandoverOfflineFixSession(
                libraryInfo,
                sessionId,
                correlationId,
                replayFromSequenceIndex,
                replayFromSequenceNumber,
                entry.getKey(),
                entry.getValue()));
        }

        return true;
    }

    private void saveOtherSessionOwner(final LiveLibraryInfo libraryInfo, final long correlationId)
    {
        schedule(() -> inboundPublication.saveRequestSessionReply(
            libraryInfo.libraryId(), OTHER_SESSION_OWNER, correlationId));
    }

    private boolean isOwnedSession(final long sessionId)
    {
        return findLibrarySession(sessionId) != null;
    }

    private GatewaySession findLibrarySession(final long sessionId)
    {
        for (final LiveLibraryInfo library : idToLibrary.values())
        {
            final GatewaySession gatewaySession = library.lookupSessionById(sessionId);
            if (gatewaySession != null)
            {
                return gatewaySession;
            }
        }
        return null;
    }

    private final class HandoverOfflineFixSession extends UnitOfWork
    {
        private final DirectBuffer metaData = new UnsafeBuffer();

        private final LiveLibraryInfo libraryInfo;
        private final long sessionId;
        private final long correlationId;
        private final CompositeKey compositeKey;
        private final FixGatewaySession gatewaySession;
        private final int aeronSessionId;
        private final long requiredPosition;

        private MetaDataStatus metaDataStatus;
        private int lastSentSequenceNumber;
        private int lastReceivedSequenceNumber;

        private HandoverOfflineFixSession(
            final LiveLibraryInfo libraryInfo,
            final long sessionId,
            final long correlationId,
            final int replayFromSequenceIndex,
            final int replayFromSequenceNumber,
            final CompositeKey compositeKey,
            final SessionContext sessionContext)
        {
            super(new ArrayList<>());
            this.libraryInfo = libraryInfo;
            this.sessionId = sessionId;
            this.correlationId = correlationId;
            this.compositeKey = compositeKey;
            this.aeronSessionId = outboundPublication.sessionId();
            this.requiredPosition = outboundPublication.position();

            if (configuration.canReplayInbound())
            {
                // Create GatewaySession for offline session and associate it with the library
                final int libraryId = libraryInfo.libraryId();
                gatewaySession = new FixGatewaySession(
                    NO_CONNECTION_ID,
                    sessionContext,
                    ":" + NO_CONNECTION_ID,
                    ACCEPTOR,
                    compositeKey,
                    null,
                    null,
                    null,
                    configuration.acceptedSessionClosedResendInterval(),
                    configuration.acceptedSessionResendRequestChunkSize(),
                    configuration.acceptedSessionSendRedundantResendRequests(),
                    configuration.acceptedEnableLastMsgSeqNumProcessed(),
                    sessionContext.lastFixDictionary(),
                    configuration);
                gatewaySession.lastSequenceResetTime(sessionContext.lastSequenceResetTime());
                gatewaySession.lastLogonTime(sessionContext.lastLogonTimeInNs());
                gatewaySession.libraryId(libraryId);
                libraryInfo.addSession(gatewaySession);

                workList.add(this::checkLoggerUpToDate);
                workList.add(() -> saveManageSession(outboundPublication));
                workList.add(() -> saveManageSession(inboundPublication));
                scheduleCatchupSession(
                    workList,
                    libraryId,
                    NO_CONNECTION_ID,
                    correlationId,
                    replayFromSequenceNumber,
                    replayFromSequenceIndex,
                    gatewaySession,
                    () -> lastReceivedSequenceNumber);
            }
            else
            {
                gatewaySession = null;
                errorHandler.onError(new IllegalStateException(
                    "Cannot return an offline session when logging disabled"));
                workList.add(() -> inboundPublication.saveRequestSessionReply(
                    libraryInfo.libraryId(), INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES, correlationId));
            }
        }

        private long checkLoggerUpToDate()
        {
            if (requiredPosition != 0 && !sentIndexedPosition(aeronSessionId, requiredPosition))
            {
                return BACK_PRESSURED;
            }

            lastSentSequenceNumber = adjustLastSequenceNumber(
                sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId));
            lastReceivedSequenceNumber = adjustLastSequenceNumber(
                receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId));
            metaDataStatus = sentSequenceNumberIndex.readMetaData(sessionId, metaData);

            return COMPLETE;
        }

        private long saveManageSession(final GatewayPublication inboundPublication)
        {
            return inboundPublication.saveManageSession(
                libraryInfo.libraryId(),
                NO_CONNECTION_ID,
                gatewaySession.sessionId(),
                lastSentSequenceNumber,
                lastReceivedSequenceNumber,
                SESSION_HANDOVER,
                SlowStatus.NOT_SLOW,
                gatewaySession.connectionType(),
                SessionState.DISCONNECTED,
                InternalSession.INITIAL_AWAITING_RESEND,
                gatewaySession.heartbeatIntervalInS(),
                gatewaySession.closedResendInterval(),
                gatewaySession.resendRequestChunkSize(),
                gatewaySession.sendRedundantResendRequests(),
                gatewaySession.enableLastMsgSeqNumProcessed(),
                correlationId,
                gatewaySession.sequenceIndex(),
                InternalSession.INITIAL_LAST_RESENT_MSG_SEQ_NO,
                InternalSession.INITIAL_LAST_RESEND_CHUNK_MSG_SEQ_NUM,
                InternalSession.INITIAL_END_OF_RESEND_REQUEST_RANGE,
                InternalSession.INITIAL_AWAITING_HEARTBEAT,
                gatewaySession.logonReceivedSequenceNumber(),
                gatewaySession.logonSequenceIndex(),
                gatewaySession.lastLogonTime(),
                gatewaySession.lastSequenceResetTime(),
                compositeKey.localCompId(),
                compositeKey.localSubId(),
                compositeKey.localLocationId(),
                compositeKey.remoteCompId(),
                compositeKey.remoteSubId(),
                compositeKey.remoteLocationId(),
                gatewaySession.address(),
                gatewaySession.username(),
                gatewaySession.password(),
                gatewaySession.fixDictionary().getClass(),
                metaDataStatus,
                metaData,
                gatewaySession.cancelOnDisconnectOption(),
                gatewaySession.cancelOnDisconnectTimeoutWindowInNs());
        }
    }

    public Action onReplayMessages(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long latestReplyArrivalTimeInMs)
    {
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            return saveReplayMessagesReply(libraryId, correlationId, ReplayMessagesStatus.UNKNOWN_LIBRARY);
        }

        final FixGatewaySession gatewaySession = (FixGatewaySession)libraryInfo.lookupSessionById(sessionId);
        if (gatewaySession == null)
        {
            return saveReplayMessagesReply(libraryId, correlationId, ReplayMessagesStatus.SESSION_NOT_OWNED);
        }

        if (!configuration.canReplayInbound())
        {
            return saveReplayMessagesReply(libraryId, correlationId,
                ReplayMessagesStatus.INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES);
        }

        schedule(new CatchupReplayer(
            receivedSequenceNumberIndex,
            inboundMessages,
            inboundPublication,
            errorHandler,
            correlationId,
            gatewaySession.connectionId(),
            libraryId,
            replayToSequenceNumber,
            replayToSequenceIndex,
            replayFromSequenceNumber,
            replayFromSequenceIndex,
            gatewaySession,
            latestReplyArrivalTimeInMs,
            CatchupReplayer.ReplayFor.REPLAY_MESSAGES,
            catchupReplayFormatters,
            configuration.sessionEpochFractionFormat(),
            clock));

        return CONTINUE;
    }

    private Action saveReplayMessagesReply(
        final int libraryId, final long correlationId, final ReplayMessagesStatus unknownLibrary)
    {
        final long position = inboundPublication.saveReplayMessagesReply(libraryId, correlationId, unknownLibrary);
        if (Pressure.isBackPressured(position))
        {
            schedule(() -> inboundPublication.saveReplayMessagesReply(libraryId, correlationId, unknownLibrary));
        }
        return CONTINUE;
    }

    public Action onFollowerSessionRequest(
        final int libraryId,
        final long correlationId,
        final FixPProtocolType fixPProtocolType,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final Header header)
    {
        if (acceptsFixP)
        {
            if (fixPProtocolType == FixPProtocolType.NULL_VAL ||
                fixPProtocolType != configuration.supportedFixPProtocolType())
            {
                saveError(INVALID_CONFIGURATION, libraryId, correlationId,
                    "Engine is not configured to accept FIXP protocol: " + fixPProtocolType);
            }
            else
            {
                initFixPProtocol();

                final long sessionId = fixPParser.sessionId(srcBuffer, srcOffset);
                final InternalFixPContext context = fixPParser.lookupContext(srcBuffer, srcOffset, srcLength);

                // In the case of creating a follower session: we want to be a bit more lenient with this validation
                // If the application uses a context that is pretending to be an Establish but it's the first time
                // We don't care, so just pretend it's a Negotiate.
                final FixPFirstMessageResponse resp = fixPContexts.onAcceptorLogon(
                    sessionId, context, NO_CONNECTION_ID, true);

                // Duplicate negotiate here means we've already got this session so we just return it.
                if (resp == FixPFirstMessageResponse.OK || resp == NEGOTIATE_DUPLICATE_ID)
                {
                    saveFollowerSessionReply(libraryId, correlationId, sessionId);
                }
                else if (resp == NEGOTIATE_DUPLICATE_ID_BAD_VER)
                {
                    saveError(INVALID_CONFIGURATION, libraryId, correlationId,
                        "The session already exists and is currently connected with a different session version" +
                        ", cannot modify session version whilst connected");
                }
                else
                {
                    saveError(UNABLE_TO_LOGON, libraryId, correlationId, "Failed with error: " + resp);
                }
            }
        }
        else
        {
            if (fixPProtocolType != FixPProtocolType.NULL_VAL)
            {
                saveError(INVALID_CONFIGURATION, libraryId, correlationId,
                    "Engine is not configured to accept FIXP, but FIXP (" + fixPProtocolType +
                    ") request made");
            }
            else
            {
                onFixFollowerSessionRequest(libraryId, correlationId, srcBuffer, srcOffset, srcLength);
            }
        }

        return CONTINUE;
    }

    private void onFixFollowerSessionRequest(
        final int libraryId,
        final long correlationId,
        final DirectBuffer srcBuffer, final int srcOffset, final int srcLength)
    {
        asciiBuffer.wrap(srcBuffer);
        final FixDictionary fixDictionary = acceptorFixDictionaryLookup.lookup(asciiBuffer, srcOffset, srcLength);
        final SessionHeaderDecoder acceptorHeaderDecoder =
            acceptorFixDictionaryLookup.lookupHeaderDecoder(fixDictionary);
        acceptorHeaderDecoder.reset();
        acceptorHeaderDecoder.decode(asciiBuffer, srcOffset, srcLength);

        final CompositeKey compositeKey;
        try
        {
            compositeKey = sessionIdStrategy.onAcceptLogon(acceptorHeaderDecoder);
        }
        catch (final IllegalArgumentException e)
        {
            saveError(EXCEPTION, libraryId, correlationId, e.getMessage());
            return;
        }

        final SessionContext sessionContext = fixContexts.newSessionContext(compositeKey, fixDictionary);
        final long sessionId = sessionContext.sessionId();

        saveFollowerSessionReply(libraryId, correlationId, sessionId);
    }

    private void saveFollowerSessionReply(
        final int libraryId, final long correlationId, final long sessionId)
    {
        schedule(new UnitOfWork(
            () -> inboundPublication.saveFollowerSessionReply(
            libraryId,
            correlationId,
            sessionId),
            () -> outboundPublication.saveFollowerSessionReply(
            libraryId,
            correlationId,
            sessionId)));
    }

    private Continuation saveManageSession(
        final int libraryId,
        final FixGatewaySession gatewaySession)
    {
        final CompositeKey compositeKey = gatewaySession.sessionKey();
        if (compositeKey != null)
        {
            final InternalSession session = gatewaySession.session();
            return saveManageSession(
                libraryId,
                gatewaySession,
                SessionStatus.LIBRARY_NOTIFICATION,
                compositeKey,
                session,
                NO_CORRELATION_ID,
                MetaDataStatus.NULL_VAL,
                NULL_METADATA);
        }

        return () -> COMPLETE;
    }

    private UnitOfWork saveManageSession(
        final int libraryId,
        final FixGatewaySession gatewaySession,
        final SessionStatus sessionstatus,
        final CompositeKey compositeKey,
        final InternalSession session,
        final long correlationId,
        final MetaDataStatus metaDataStatus,
        final DirectBuffer metaData)
    {
        return new UnitOfWork(
            () -> saveManageSessionTo(libraryId, gatewaySession, sessionstatus,
                compositeKey, session, correlationId, metaDataStatus, metaData, inboundPublication),
            () -> saveManageSessionTo(libraryId, gatewaySession, sessionstatus,
                compositeKey, session, correlationId, metaDataStatus, metaData, outboundPublication));
    }

    private long saveManageSessionTo(
        final int libraryId, final FixGatewaySession gatewaySession, final SessionStatus sessionstatus,
        final CompositeKey compositeKey, final InternalSession session, final long correlationId,
        final MetaDataStatus metaDataStatus, final DirectBuffer metaData, final GatewayPublication publication)
    {
        final long connectionId = gatewaySession.connectionId();
        final int lastSentSeqNum = session.lastSentMsgSeqNum();
        final int lastReceivedSeqNum = session.lastReceivedMsgSeqNum();

        return publication.saveManageSession(
            libraryId,
            connectionId,
            gatewaySession.sessionId(),
            lastSentSeqNum,
            lastReceivedSeqNum,
            sessionstatus,
            gatewaySession.slowStatus(),
            gatewaySession.connectionType(),
            session.state(),
            session.awaitingResend(),
            gatewaySession.heartbeatIntervalInS(),
            gatewaySession.closedResendInterval(),
            gatewaySession.resendRequestChunkSize(),
            gatewaySession.sendRedundantResendRequests(),
            gatewaySession.enableLastMsgSeqNumProcessed(),
            correlationId,
            gatewaySession.sequenceIndex(),
            session.lastResentMsgSeqNo(),
            session.lastResendChunkMsgSeqNum(),
            session.endOfResendRequestRange(),
            session.awaitingHeartbeat(),
            gatewaySession.logonReceivedSequenceNumber(),
            gatewaySession.logonSequenceIndex(),
            session.lastLogonTimeInNs(),
            session.lastSequenceResetTimeInNs(),
            compositeKey.localCompId(),
            compositeKey.localSubId(),
            compositeKey.localLocationId(),
            compositeKey.remoteCompId(),
            compositeKey.remoteSubId(),
            compositeKey.remoteLocationId(),
            gatewaySession.address(),
            gatewaySession.username(),
            gatewaySession.password(),
            gatewaySession.fixDictionary().getClass(),
            metaDataStatus,
            metaData,
            gatewaySession.cancelOnDisconnectOption(),
            gatewaySession.cancelOnDisconnectTimeoutWindowInNs());
    }

    private void scheduleCatchupSession(
        final List<Continuation> continuations,
        final int libraryId,
        final long connectionId,
        final long correlationId,
        final int replayFromSequenceNumber,
        final int requestedReplayFromSequenceIndex,
        final FixGatewaySession session,
        final IntSupplier lastReceivedSeqNumSupplier)
    {
        if (replayFromSequenceNumber != NO_MESSAGE_REPLAY)
        {
            if (!configuration.canReplayInbound())
            {
                continuations.add(() ->
                {
                    final long position = inboundPublication.saveRequestSessionReply(
                        libraryId, INVALID_CONFIGURATION_NOT_LOGGING_MESSAGES, correlationId);
                    if (position > 0)
                    {
                        session.play();
                    }
                    return position;
                });
                return;
            }

            continuations.add(() ->
            {
                final int replayFromSequenceIndex;
                final int sequenceIndex = session.sequenceIndex();
                final int lastReceivedSeqNum = lastReceivedSeqNumSupplier.getAsInt();
                if (requestedReplayFromSequenceIndex == CURRENT_SEQUENCE)
                {
                    replayFromSequenceIndex = sequenceIndex;
                }
                else
                {
                    if (requestedReplayFromSequenceIndex > sequenceIndex ||
                        (requestedReplayFromSequenceIndex == sequenceIndex &&
                        replayFromSequenceNumber > lastReceivedSeqNum))
                    {
                        return sequenceNumberTooHigh(libraryId, correlationId, session);
                    }
                    replayFromSequenceIndex = requestedReplayFromSequenceIndex;
                }

                schedule(new CatchupReplayer(
                    receivedSequenceNumberIndex,
                    inboundMessages,
                    inboundPublication,
                    errorHandler,
                    correlationId,
                    connectionId,
                    libraryId,
                    lastReceivedSeqNum,
                    sequenceIndex,
                    replayFromSequenceNumber,
                    replayFromSequenceIndex,
                    session,
                    catchupEndTimeInMs(),
                    CatchupReplayer.ReplayFor.REQUEST_SESSION,
                    catchupReplayFormatters,
                    configuration.sessionEpochFractionFormat(),
                    clock
                ));
                return COMPLETE;
            });
        }
        else
        {
            continuations.add(() -> CatchupReplayer.sendOk(inboundPublication, correlationId, session, libraryId,
                catchupReplayFormatters));
        }
    }

    private long catchupEndTimeInMs()
    {
        return epochClock.time() + (configuration.replyTimeoutInMs() / 2);
    }

    private long sequenceNumberTooHigh(final int libraryId, final long correlationId, final FixGatewaySession session)
    {
        final long position = inboundPublication.saveRequestSessionReply(
            libraryId, SEQUENCE_NUMBER_TOO_HIGH, correlationId);
        if (!Pressure.isBackPressured(position))
        {
            session.play();
        }
        return position;
    }

    private void onSessionLogon(final FixGatewaySession gatewaySession)
    {
        if (!soleLibraryMode)
        {
            // Notify libraries of the existence of this logged on session.
            schedule(new Continuation()
            {
                private final CompositeKey key = gatewaySession.sessionKey();
                private InternalSession session = gatewaySession.session();

                private final Continuation saveManageSession = session == null ? null : saveManageSession(
                    ENGINE_LIBRARY_ID,
                    gatewaySession,
                    SESSION_HANDOVER,
                    key,
                    session,
                    NO_CORRELATION_ID,
                    MetaDataStatus.NULL_VAL,
                    NULL_METADATA);

                @Override
                public long attempt()
                {
                    session = gatewaySession.session();
                    if (session == null)
                    {
                        // Another library is now handling the session, don't publish availability.
                        return COMPLETE;
                    }

                    return saveManageSession.attempt();
                }
            });
        }
    }

    boolean onFixLogonMessageReceived(final FixGatewaySession gatewaySession, final long sessionId)
    {
        stopCancelOnDisconnect(sessionId);

        if (checkOfflineSession(gatewaySession, sessionId))
        {
            return true;
        }

        if (!soleLibraryMode)
        {
            ((FixGatewaySessions)gatewaySessions).acquire(
                gatewaySession,
                CONNECTED,
                false,
                configuration.defaultHeartbeatIntervalInS(),
                UNK_SESSION,
                UNK_SESSION,
                null,
                null);
        }

        return false;
    }

    private void stopCancelOnDisconnect(final long sessionId)
    {
        cancelOnDisconnectFinder.sessionId = sessionId;
        retryManager.removeIf(cancelOnDisconnectFinder);
    }

    public boolean onFixPLogonMessageReceived(final FixPGatewaySession session, final long sessionId)
    {
        stopCancelOnDisconnect(sessionId);

        final LiveLibraryInfo library = lookupOfflineLibrary(session, sessionId);
        if (library == null)
        {
            return false;
        }

        // Handover reconnected FIXP session to existing library that owns the offline version of the session.
        final int libraryId = library.libraryId();
        final Action action = onRequestFixPSession(libraryId, library, sessionId, NO_CORRELATION_ID);
        if (action != CONTINUE)
        {
            schedule(() -> onRequestFixPSessionInternal(libraryId, library, sessionId, NO_CORRELATION_ID));
        }

        return true;
    }

    private boolean checkOfflineSession(final GatewaySession gatewaySession, final long sessionId)
    {
        return lookupOfflineLibrary(gatewaySession, sessionId) != null;
    }

    private LiveLibraryInfo lookupOfflineLibrary(final GatewaySession gatewaySession, final long sessionId)
    {
        for (final LiveLibraryInfo library : idToLibrary.values())
        {
            final GatewaySession oldGatewaySession = library.lookupSessionById(sessionId);
            if (oldGatewaySession != null)
            {
                gatewaySession.consumeOfflineSession(oldGatewaySession);
                // the handover process will associate the new gateway session with the library
                library.removeSession(gatewaySession);
                return library;
            }
        }
        return null;
    }

    void onGatewaySessionSetup(final FixGatewaySession gatewaySession, final boolean isOfflineReconnect)
    {
        if (gatewaySession.connectionType() == ACCEPTOR)
        {
            // Hand over management of this new session to the sole library or it's existing offline owner.

            LiveLibraryInfo libraryInfo = null;

            if (soleLibraryMode)
            {
                if (idToLibrary.size() != 1)
                {
                    logSoleLibraryError();
                }

                libraryInfo = idToLibrary.values().iterator().next();
            }

            if (isOfflineReconnect)
            {
                final int libraryId = gatewaySession.libraryId();
                libraryInfo = idToLibrary.get(libraryId);
                if (libraryInfo == null)
                {
                    logOfflineSessionLibrary(libraryId);
                }
            }

            if (libraryInfo != null)
            {
                final CompositeKey sessionKey = gatewaySession.sessionKey();
                final int libraryAeronSessionId = libraryInfo.aeronSessionId();
                final long requiredPosition = librarySubscription.imageBySessionId(libraryAeronSessionId).position();

                final int libraryId = libraryInfo.libraryId();
                gatewaySession.setManagementTo(libraryId);
                libraryInfo.addSession(gatewaySession);

                handoverNewConnectionToLibrary(
                    libraryId,
                    sessionKey.localCompId(),
                    sessionKey.localSubId(),
                    sessionKey.localLocationId(),
                    sessionKey.remoteCompId(),
                    sessionKey.remoteSubId(),
                    sessionKey.remoteLocationId(),
                    gatewaySession.closedResendInterval(),
                    gatewaySession.resendRequestChunkSize(),
                    gatewaySession.sendRedundantResendRequests(),
                    gatewaySession.enableLastMsgSeqNumProcessed(),
                    gatewaySession.username(),
                    gatewaySession.password(),
                    gatewaySession.cancelOnDisconnectOption(),
                    gatewaySession.cancelOnDisconnectTimeoutWindowInNs(),
                    gatewaySession.fixDictionary().getClass(),
                    gatewaySession.heartbeatIntervalInS(),
                    NO_CORRELATION_ID,
                    libraryInfo,
                    gatewaySession.context(),
                    sessionKey,
                    gatewaySession.connectionId(),
                    gatewaySession.sessionId(),
                    gatewaySession,
                    libraryAeronSessionId,
                    requiredPosition,
                    gatewaySession.address(),
                    ACCEPTOR);
            }
        }
    }

    private void logOfflineSessionLibrary(final int libraryId)
    {
        errorHandler.onError(new IllegalStateException(
            "Error, offline session owned by non-existent library: " + libraryId));
    }

    private void logSoleLibraryError()
    {
        errorHandler.onError(new IllegalStateException(
            "Error, invalid numbers of libraries: " + idToLibrary.size() + " whilst in sole library mode"));
    }

    void onQueryLibraries(final QueryLibrariesCommand command)
    {
        final List<LibraryInfo> libraries = new ArrayList<>(idToLibrary.values());
        libraries.add(new EngineLibraryInfo(gatewaySessions));
        command.success(libraries);
    }

    void onResetSessionIds(final File backupLocation, final ResetSessionIdsCommand command)
    {
        schedule(new UnitOfWork(
            inboundPublication::saveResetSessionIds,
            outboundPublication::saveResetSessionIds,
            () ->
            {
                try
                {
                    fixContexts.reset(backupLocation);
                }
                catch (final Exception ex)
                {
                    command.onError(ex);
                }

                return COMPLETE;
            },
            () ->
            {
                if (command.hasCompleted())
                {
                    return COMPLETE;
                }

                if (sequenceNumbersNotReset())
                {
                    return BACK_PRESSURED;
                }
                else
                {
                    command.success();
                    return COMPLETE;
                }
            }));
    }

    void onStartClose(final DisconnectAllCommand disconnectAllCommand)
    {
        DebugLogger.log(LogTag.CLOSE, "Framer has started close operation");

        performingDisconnectOperation = true;

        schedule(disconnectAllOperation(disconnectAllCommand::success));
    }

    private DisconnectAllOperation disconnectAllOperation(final Runnable onSuccess)
    {
        return new DisconnectAllOperation(
            inboundPublication,
            new ArrayList<>(idToLibrary.values()),
            // Take a copy to avoid library sessions being acquired causing issues
            new ArrayList<>(gatewaySessions.sessions()),
            receiverEndPoints,
            onSuccess);
    }

    void onResetSequenceNumber(final ResetSequenceNumberCommand reply)
    {
        reply.libraryLookup(fixSenderEndPoints.libraryLookup());

        if (!reply.poll())
        {
            replies.add(reply);
        }
    }

    void onLookupSessionId(final LookupSessionIdCommand command)
    {
        final CompositeKey compositeKey = sessionIdStrategy.onInitiateLogon(
            command.localCompId,
            command.localSubId,
            command.localLocationId,
            command.remoteCompId,
            command.remoteSubId,
            command.remoteLocationId);

        final long sessionId = fixContexts.lookupSessionId(compositeKey);

        if (sessionId == Session.UNKNOWN)
        {
            command.error(new IllegalArgumentException("Unknown Session: " + compositeKey));
        }
        else
        {
            command.complete(sessionId);
        }
    }

    private boolean sequenceNumbersNotReset()
    {
        return sentSequenceNumberIndex.lastKnownSequenceNumber(1) != UNK_SESSION ||
            receivedSequenceNumberIndex.lastKnownSequenceNumber(1) != UNK_SESSION;
    }

    public Action onWriteMetaData(
        final int libraryId,
        final long sessionId,
        final long correlationId,
        final int metaDataOffset,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength)
    {
        // Handled by the Sent SequenceNumberIndexWriter

        return CONTINUE;
    }

    public Action onReadMetaData(
        final int libraryId,
        final long sessionId,
        final long correlationId)
    {
        final DirectBuffer metaDataBuffer = new UnsafeBuffer();
        final MetaDataStatus status = sentSequenceNumberIndex.readMetaData(sessionId, metaDataBuffer);

        schedule(() -> inboundPublication.saveReadMetaDataReply(
            libraryId,
            correlationId,
            status,
            metaDataBuffer,
            0,
            metaDataBuffer.capacity()));

        return CONTINUE;
    }

    public void onClose()
    {
        closeAll(
            this::quiesce,
            retryManager,
            inboundMessages,
            receiverEndPoints,
            fixSenderEndPoints,
            fixPSenderEndPoints,
            channelSupplier,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex);
    }

    private void quiesce()
    {
        final Long2LongHashMap inboundPositions = new Long2LongHashMap(CompletionPosition.MISSING_VALUE);
        inboundPositions.put(inboundPublication.sessionId(), inboundPublication.position());
        inboundCompletionPosition.complete(inboundPositions);

        final Long2LongHashMap outboundPositions = new Long2LongHashMap(CompletionPosition.MISSING_VALUE);
        idToLibrary.values().forEach(liveLibraryInfo ->
        {
            final int aeronSessionId = liveLibraryInfo.aeronSessionId();
            final Image image = librarySubscription.imageBySessionId(aeronSessionId);
            if (image != null)
            {
                final long position = image.position();
                outboundPositions.put(aeronSessionId, position);
            }
        });

        outboundLibraryCompletionPosition.complete(outboundPositions);

        recordingCoordinator.completionPositions(inboundPositions, outboundPositions);
    }

    public String roleName()
    {
        return agentNamePrefix + "Framer";
    }

    void schedule(final Continuation continuation)
    {
        retryManager.schedule(continuation);
    }

    void slowStatus(final int libraryId, final long connectionId, final boolean hasBecomeSlow)
    {
        if (hasBecomeSlow)
        {
            resendNotSlowStatus.remove(connectionId);
            sendSlowStatus(libraryId, connectionId, resendSlowStatus, SlowStatus.SLOW);
        }
        else
        {
            resendSlowStatus.remove(connectionId);
            sendSlowStatus(libraryId, connectionId, resendNotSlowStatus, SlowStatus.NOT_SLOW);
        }
    }

    private void sendSlowStatus(
        final int libraryId,
        final long connectionId,
        final Long2LongHashMap toResend,
        final SlowStatus status)
    {
        final long position = inboundPublication.saveSlowStatusNotification(libraryId, connectionId, status);
        if (Pressure.isBackPressured(position))
        {
            toResend.put(connectionId, libraryId);
        }
    }

    void receiverEndPointPollingOptional(final long connectionId)
    {
        receiverEndPoints.receiverEndPointPollingOptional(connectionId, true);
    }

    void receiverEndPointPollingRequired(final ReceiverEndPoint receiverEndPoint)
    {
        receiverEndPoints.receiverEndPointPollingRequired(receiverEndPoint.connectionId);
    }

    void onBind(final BindCommand bindCommand)
    {
        if (soleLibraryMode && idToLibrary.isEmpty())
        {
            shouldBind = true;
            bindCommand.success();
            return;
        }

        if (pendingUnbind != null)
        {
            bindCommand.onError(new IllegalStateException("Unbind operation is in progress"));
            return;
        }

        try
        {
            performingDisconnectOperation = false;
            channelSupplier.bind();
            shouldBind = true;
            bindCommand.success();
        }
        catch (final Exception e)
        {
            bindCommand.onError(e);
        }
    }

    void onUnbind(final UnbindCommand unbindCommand)
    {
        if (pendingUnbind != null)
        {
            pendingUnbind.addConcurrentUnbind(unbindCommand);
        }

        if (soleLibraryMode && idToLibrary.isEmpty())
        {
            shouldBind = false;
            unbindCommand.success();
            return;
        }

        try
        {
            channelSupplier.unbind();
            shouldBind = false;
        }
        catch (final Exception e)
        {
            unbindCommand.onError(e);
            return;
        }

        if (unbindCommand.disconnect())
        {
            pendingUnbind = unbindCommand;
            performingDisconnectOperation = true;
            schedule(disconnectAllOperation(() ->
            {
                pendingUnbind = null;
                unbindCommand.success();
            }));
        }
        else
        {
            unbindCommand.success();
        }
    }

    class CounterIdFinder implements CountersReader.MetaData
    {
        private final String aeronSessionId;

        int counterId = NULL_COUNTER_ID;

        CounterIdFinder(final int aeronSessionId)
        {
            this.aeronSessionId = String.valueOf(aeronSessionId);
        }

        public void accept(final int counterId, final int typeId, final DirectBuffer keyBuffer, final String label)
        {
            if (typeId == AeronCounters.DRIVER_SUBSCRIBER_POSITION_TYPE_ID &&
                countersReader.getCounterRegistrationId(counterId) == outboundIndexRegistrationId &&
                label.contains(aeronSessionId))
            {
                this.counterId = counterId;
            }
        }
    }

    public void onPositionRequest(final PositionRequestCommand command)
    {
        final int libraryId = command.libraryId();
        final LiveLibraryInfo libraryInfo = idToLibrary.get(libraryId);
        if (libraryInfo == null)
        {
            command.error(new IllegalStateException("Unknown Library: " + libraryId));
            return;
        }

        final CounterIdFinder finder = new CounterIdFinder(libraryInfo.aeronSessionId());
        countersReader.forEach(finder);
        final int counterId = finder.counterId;

        if (counterId == NULL_COUNTER_ID)
        {
            command.error(new IllegalStateException("Unable to find counter for: " + libraryId));
            return;
        }

        command.position(new UnsafeBufferPosition((UnsafeBuffer)countersReader.valuesBuffer(), counterId));
    }

    public void onEngineStreamInfoRequest(final EngineStreamInfoRequestCommand command)
    {
        command.complete(new EngineStreamInfo(
            inboundIndexRegistrationId,
            outboundIndexRegistrationId,
            librarySubscription.registrationId(),
            inboundPublication.sessionId(),
            inboundPublication.position(),
            outboundPublication.sessionId(),
            outboundPublication.position()));
    }

    public void onWriteMetaDataResponse(final WriteMetaDataResponse response)
    {
        schedule(() -> inboundPublication.saveWriteMetaDataReply(
            response.libraryId(),
            response.correlationId(),
            response.status()));
    }

    class HandoverNewFixConnectionToLibrary extends UnitOfWork
    {
        private final FixGatewaySession gatewaySession;
        private final int outBoundAeronSessionId;
        private final long outBoundRequiredPosition;
        private final int inboundAeronSessionId;
        private final long inBoundRequiredPosition;
        private final long sessionId;
        private final ConnectionType connectionType;
        private final SessionContext sessionContext;
        private final CompositeKey sessionKey;
        private final String username;
        private final String password;
        private final CancelOnDisconnectOption cancelOnDisconnectOption;
        private final long cancelOnDisconnectTimeoutWindowInNs;
        private final int heartbeatIntervalInS;
        private final int libraryId;
        private final long connectionId;
        private final boolean closedResendInterval;
        private final int resendRequestChunkSize;
        private final boolean sendRedundantResendRequests;
        private final boolean enableLastMsgSeqNumProcessed;
        private final long correlationId;
        private final String senderCompId;
        private final String senderSubId;
        private final String senderLocationId;
        private final String targetCompId;
        private final String targetSubId;
        private final String targetLocationId;
        private final String address;
        private final Class<? extends FixDictionary> fixDictionary;
        private final LiveLibraryInfo library;

        private MetaDataStatus metaDataStatus;
        private DirectBuffer metaDataBuffer;
        private int lastSentSequenceNumber;
        private int lastReceivedSequenceNumber;
        private boolean hasDisconnected = false;

        HandoverNewFixConnectionToLibrary(
            final FixGatewaySession gatewaySession,
            final int outBoundAeronSessionId,
            final long outBoundRequiredPosition,
            final long sessionId,
            final ConnectionType connectionType,
            final SessionContext sessionContext,
            final CompositeKey sessionKey,
            final String username,
            final String password,
            final CancelOnDisconnectOption cancelOnDisconnectOption,
            final long cancelOnDisconnectTimeoutWindowInNs,
            final int heartbeatIntervalInS,
            final int libraryId,
            final long connectionId,
            final boolean closedResendInterval,
            final int resendRequestChunkSize,
            final boolean sendRedundantResendRequests,
            final boolean enableLastMsgSeqNumProcessed,
            final long correlationId,
            final String senderCompId,
            final String senderSubId,
            final String senderLocationId,
            final String targetCompId,
            final String targetSubId,
            final String targetLocationId,
            final String address,
            final Class<? extends FixDictionary> fixDictionary,
            final LiveLibraryInfo library)
        {
            this.gatewaySession = gatewaySession;
            this.outBoundAeronSessionId = outBoundAeronSessionId;
            this.outBoundRequiredPosition = outBoundRequiredPosition;
            this.sessionId = sessionId;
            this.connectionType = connectionType;
            this.sessionContext = sessionContext;
            this.sessionKey = sessionKey;
            this.username = username;
            this.password = password;
            this.cancelOnDisconnectOption = cancelOnDisconnectOption;
            this.cancelOnDisconnectTimeoutWindowInNs = cancelOnDisconnectTimeoutWindowInNs;
            this.heartbeatIntervalInS = heartbeatIntervalInS;
            this.libraryId = libraryId;
            this.connectionId = connectionId;
            this.closedResendInterval = closedResendInterval;
            this.resendRequestChunkSize = resendRequestChunkSize;
            this.sendRedundantResendRequests = sendRedundantResendRequests;
            this.enableLastMsgSeqNumProcessed = enableLastMsgSeqNumProcessed;
            this.correlationId = correlationId;
            this.senderCompId = senderCompId;
            this.senderSubId = senderSubId;
            this.senderLocationId = senderLocationId;
            this.targetCompId = targetCompId;
            this.targetSubId = targetSubId;
            this.targetLocationId = targetLocationId;
            this.address = address;
            this.fixDictionary = fixDictionary;
            this.library = library;

            inboundAeronSessionId = inboundPublication.sessionId();
            inBoundRequiredPosition = inboundPublication.position();

            if (configuration.logAllMessages())
            {
                work(this::checkLoggerUpToDate, this::saveManageSessionOutbound, this::saveManageSessionInbound);
            }
            else
            {
                noMetaData();
                gatewaySession.lastLogonWasSequenceReset();

                work(this::onLogon, this::saveManageSessionOutbound, this::saveManageSessionInbound);
            }
        }

        private long checkLoggerUpToDate()
        {
            if (checkDisconnectDuringHandover())
            {
                return COMPLETE;
            }

            if (gatewaySession.initialResetSeqNum())
            {
                lastSentSequenceNumber = 0;
                lastReceivedSequenceNumber = 0;
                noMetaData();
                gatewaySession.lastLogonWasSequenceReset();
                return COMPLETE;
            }

            if (sentIndexedPosition(outBoundAeronSessionId, outBoundRequiredPosition) &&
                receivedIndexedPosition(inboundAeronSessionId, inBoundRequiredPosition))
            {
                lastSentSequenceNumber = sentSequenceNumberIndex.lastKnownSequenceNumber(sessionId);
                lastReceivedSequenceNumber = receivedSequenceNumberIndex.lastKnownSequenceNumber(sessionId);

                // Acceptors are adjusted here - symmetrically with the non initialAcceptedSessionOwner=SOLE_LIBRARY
                // case, whilst Initiator configuration is always adjusted on the library side.
                if (connectionType == ACCEPTOR)
                {
                    lastSentSequenceNumber = adjustLastSequenceNumber(lastSentSequenceNumber);
                    lastReceivedSequenceNumber = adjustLastSequenceNumber(lastReceivedSequenceNumber);
                    if (lastReceivedSequenceNumber == 0)
                    {
                        gatewaySession.lastLogonWasSequenceReset();
                    }
                    else
                    {
                        gatewaySession.lastSequenceResetTime(sessionContext.lastSequenceResetTime());
                    }
                }

                metaDataBuffer = new UnsafeBuffer();
                metaDataStatus = sentSequenceNumberIndex.readMetaData(sessionId, metaDataBuffer);

                return onLogon();
            }

            return BACK_PRESSURED;
        }

        private boolean checkDisconnectDuringHandover()
        {
            if (!hasDisconnected)
            {
                hasDisconnected = gatewaySession.connectionId() == NO_CONNECTION_ID;

                // Only do this the first time we notice a disconnect
                if (hasDisconnected)
                {
                    // Need to notify the library because it doesn't know about the session yet and it will
                    // have a reply object corresponding to the initiate() method call.
                    library.connectionFinishesConnecting(correlationId);
                    saveError(UNABLE_TO_CONNECT, libraryId, correlationId,
                        "Disconnected before session active");
                }
            }

            return hasDisconnected;
        }

        private void noMetaData()
        {
            metaDataStatus = MetaDataStatus.NO_META_DATA;
            metaDataBuffer = NULL_METADATA;
        }

        private long onLogon()
        {
            if (checkDisconnectDuringHandover())
            {
                return COMPLETE;
            }

            gatewaySession.onLogon(
                sessionId,
                sessionContext,
                sessionKey,
                username,
                password,
                heartbeatIntervalInS,
                lastReceivedSequenceNumber,
                cancelOnDisconnectOption,
                cancelOnDisconnectTimeoutWindowInNs);

            return COMPLETE;
        }

        private long saveManageSessionOutbound()
        {
            if (checkDisconnectDuringHandover())
            {
                return COMPLETE;
            }

            return saveManageSession(outboundPublication);
        }

        private long saveManageSessionInbound()
        {
            if (checkDisconnectDuringHandover())
            {
                return COMPLETE;
            }

            final long position = saveManageSession(inboundPublication);
            if (position > 0)
            {
                library.connectionFinishesConnecting(correlationId);
                gatewaySession.play();
            }

            return position;
        }

        private long saveManageSession(final GatewayPublication publication)
        {
            return publication.saveManageSession(
                libraryId,
                connectionId,
                sessionId,
                lastSentSequenceNumber,
                lastReceivedSequenceNumber,
                SESSION_HANDOVER,
                SlowStatus.NOT_SLOW,
                connectionType,
                CONNECTED,
                false,
                heartbeatIntervalInS,
                closedResendInterval,
                resendRequestChunkSize,
                sendRedundantResendRequests,
                enableLastMsgSeqNumProcessed,
                correlationId,
                sessionContext.sequenceIndex(),
                InternalSession.INITIAL_LAST_RESENT_MSG_SEQ_NO,
                InternalSession.INITIAL_LAST_RESEND_CHUNK_MSG_SEQ_NUM,
                InternalSession.INITIAL_END_OF_RESEND_REQUEST_RANGE,
                InternalSession.INITIAL_AWAITING_HEARTBEAT,
                gatewaySession.logonReceivedSequenceNumber(),
                gatewaySession.logonSequenceIndex(),
                gatewaySession.lastLogonTime(),
                gatewaySession.lastSequenceResetTime(),
                senderCompId,
                senderSubId,
                senderLocationId,
                targetCompId,
                targetSubId,
                targetLocationId,
                address,
                username,
                password,
                fixDictionary,
                metaDataStatus,
                metaDataBuffer,
                cancelOnDisconnectOption,
                cancelOnDisconnectTimeoutWindowInNs);
        }
    }

    public AcceptorFixDictionaryLookup acceptorFixDictionaryLookup()
    {
        return acceptorFixDictionaryLookup;
    }

    static class CancelOnDisconnectFinder implements Predicate<Continuation>
    {
        long sessionId;

        public boolean test(final Continuation continuation)
        {
            return continuation instanceof CancelOnDisconnectTimeoutOperation &&
                ((CancelOnDisconnectTimeoutOperation)continuation).sessionId() == sessionId;
        }
    }

    class FramerReplayProtocolHandler implements ReplayProtocolHandler
    {
        private final boolean slow;

        FramerReplayProtocolHandler(final boolean slow)
        {
            this.slow = slow;
        }

        public Action onReplayComplete(final long connectionId, final long correlationId)
        {
            final Action action = fixSenderEndPoints.onReplayComplete(connectionId, correlationId, slow);
            if (action != ABORT && !slow)
            {
                return fixPSenderEndPoints.onReplayComplete(connectionId, correlationId, slow);
            }
            return action;
        }

        public Action onStartReplay(
            final long session, final long connection, final long correlationId, final long position)
        {
            fixSenderEndPoints.onStartReplay(connection, correlationId, slow);

            return CONTINUE;
        }
    }
}
