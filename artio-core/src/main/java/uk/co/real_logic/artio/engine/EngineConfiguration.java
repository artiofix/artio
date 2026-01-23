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

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.Verify;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Long2ObjectCache;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.errors.ErrorConsumer;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.ErrorHandlerFactory;
import uk.co.real_logic.artio.MonitoringAgentFactory;
import uk.co.real_logic.artio.ReproductionClock;
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.engine.framer.DefaultTcpChannelSupplier;
import uk.co.real_logic.artio.engine.framer.IncrementalSessionIdGenerator;
import uk.co.real_logic.artio.engine.framer.SessionIdGenerator;
import uk.co.real_logic.artio.engine.framer.TcpChannelSupplier;
import uk.co.real_logic.artio.engine.logger.FixArchiveScanner;
import uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fixp.FixPCancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.fixp.FixPProtocolFactory;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.session.CancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.session.ResendRequestController;
import uk.co.real_logic.artio.session.SessionCustomisationStrategy;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.timing.HistogramHandler;
import uk.co.real_logic.artio.validation.*;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.agrona.BitUtil.findNextPositivePowerOfTwo;
import static uk.co.real_logic.artio.admin.ArtioAdminConfiguration.DEFAULT_INBOUND_ADMIN_STREAM_ID;
import static uk.co.real_logic.artio.admin.ArtioAdminConfiguration.DEFAULT_OUTBOUND_ADMIN_STREAM_ID;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.HEADER_FILE_SIZE;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.MAX_FILE_SEGMENT_CAPACITY;
import static uk.co.real_logic.artio.library.SessionConfiguration.*;
import static uk.co.real_logic.artio.messages.CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT;
import static uk.co.real_logic.artio.messages.FixPProtocolType.ILINK_3;
import static uk.co.real_logic.artio.validation.SessionPersistenceStrategy.alwaysTransient;

/**
 * Configuration that exists for the entire duration of a fix gateway. Some options are configurable via
 * commandline properties. Setters override commandline properties, not the other way around.
 * <p>
 * See setters or properties for documentation of what specific configuration options do.
 * <p>
 * NB: DO NOT REUSE this object over multiple {@code FixEngine.launch(EngineConfiguration)} calls.
 *
 * @see FixEngine
 */
public final class EngineConfiguration extends CommonConfiguration implements AutoCloseable
{
    // ------------------------------------------------
    //          Configuration Properties
    // ------------------------------------------------

    /**
     * Property name for the directory to log archive data into
     */
    public static final String LOG_FILE_DIR_PROP = "logging.dir";

    /**
     * Property name for maximum number of records in logging index files
     */
    public static final String REPLAY_INDEX_RECORD_CAPACITY_PROP = "logging.index.records";

    /**
     * Property name for enabling or disabling checksum calculation for index files
     */
    public static final String INDEX_CHECKSUM_ENABLED_PROP = "logging.checksum.enabled";

    /**
     * Deprecated property name for size of logging index files. Do not use this, set
     * {@link #REPLAY_INDEX_RECORD_CAPACITY_PROP} instead.
     */
    @Deprecated
    public static final String REPLAY_INDEX_FILE_SIZE_PROP = "logging.index.size";

    static
    {
        if (System.getProperty(REPLAY_INDEX_FILE_SIZE_PROP) != null)
        {
            System.err.println(REPLAY_INDEX_FILE_SIZE_PROP + " is deprecated, please use " +
                REPLAY_INDEX_RECORD_CAPACITY_PROP + " instead.");
        }
    }

    // Care needs to be taken when setting the fragment limits, and buffer sizes
    // The inbound bytes received and buffer sizes should always be set low enough
    // that fragments will be read off of the TCP connections slower
    // that they can be written onto them.

    /**
     * Property name for the max number of messages to read from libraries.
     */
    public static final String OUTBOUND_LIBRARY_FRAGMENT_LIMIT_PROP = "fix.core.outbound_fragment_limit";
    /**
     * Property name for the max number of messages to read from replayer.
     */
    public static final String REPLAY_FRAGMENT_LIMIT_PROP = "fix.core.replay_fragment_limit";
    /**
     * Property name for the max number of bytes to read from all TCP Connections.
     */
    public static final String INBOUND_BYTES_RECEIVED_LIMIT_PROP = "fix.core.inbound_bytes_limit";
    /**
     * Property name for the size in bytes of the receiver end point's framing buffer.
     */
    public static final String RECEIVER_BUFFER_SIZE_PROP = "fix.core.receiver_buffer_size";
    /**
     * Property name for the size in bytes of the TCP socket's receive buffer.
     */
    public static final String RECEIVER_SOCKET_BUFFER_SIZE_PROP = "fix.core.receiver_socket_buffer_size";
    /**
     * Property name for the size in bytes of the TCP socket's send buffer.
     */
    public static final String SENDER_SOCKET_BUFFER_SIZE_PROP = "fix.core.sender_socket_buffer_size";
    /**
     * Property name for the size in bytes of the sequence number cache file
     */
    public static final String SEQUENCE_NUMBER_INDEX_SIZE_PROP = "fix.core.sequence_number_cache_size";
    /**
     * Property name for the size in bytes of the session id file
     */
    public static final String SESSION_ID_BUFFER_SIZE_PROP = "fix.core.session_id_file_size";
    /**
     * Property name for the maximum number of bytes to allow in the quarantine buffer before disconnection
     */
    public static final String SENDER_MAX_BYTES_IN_BUFFER_PROP = "fix.core.sender_max_bytes_in_buffer";
    /**
     * Property name for the timeout before a connection that hasn't sent a logon is disconnected
     */
    public static final String NO_LOGON_DISCONNECT_TIMEOUT_PROP = "fix.core.no_logon_disconnect";

    // ------------------------------------------------
    //          Configuration Defaults
    // ------------------------------------------------

    public static final String DEFAULT_LOG_FILE_DIR = "logs";
    public static final int DEFAULT_REPLAY_INDEX_RECORD_CAPACITY = 262144;
    public static final int DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY = 65536;
    public static final int DEFAULT_LOGGER_CACHE_NUM_SETS = 8;
    public static final int DEFAULT_LOGGER_CACHE_SET_SIZE = 4;

    public static final int DEFAULT_OUTBOUND_LIBRARY_FRAGMENT_LIMIT = 20;
    public static final int DEFAULT_REPLAY_FRAGMENT_LIMIT = 5;
    public static final int DEFAULT_INBOUND_BYTES_RECEIVED_LIMIT = 8 * 1024;
    public static final int DEFAULT_RECEIVER_BUFFER_SIZE = 16 * 1024;
    public static final int DEFAULT_RECEIVER_SOCKET_BUFFER_SIZE = 1024 * 1024;
    public static final int DEFAULT_SENDER_SOCKET_BUFFER_SIZE = 1024 * 1024;
    public static final int DEFAULT_SEQUENCE_NUMBER_INDEX_SIZE = 8 * 1024 * 1024;
    public static final int DEFAULT_SESSION_ID_BUFFER_SIZE = 4 * 1024 * 1024;
    public static final int DEFAULT_SENDER_MAX_BYTES_IN_BUFFER = 4 * 1024 * 1024;
    public static final int DEFAULT_REPLAY_POSITION_BUFFER_SIZE = 4 * 1024;
    public static final int DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS = (int)SECONDS.toMillis(5);
    public static final String DEFAULT_SESSION_ID_FILE = "session_id_buffer";
    public static final String DEFAULT_FIXP_ID_FILE = "fixp_id_buffer";
    public static final String DEFAULT_SEQUENCE_NUMBERS_SENT_FILE = "sequence_numbers_sent";
    public static final String DEFAULT_SEQUENCE_NUMBERS_RECEIVED_FILE = "sequence_numbers_received";
    public static final long DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS = 10_000;
    public static final ReplayHandler DEFAULT_REPLAY_HANDLER =
        (buffer, offset, length, libraryId, sessionId, sequenceIndex, messageType) ->
        {
        };
    public static final FixPRetransmitHandler DEFAULT_BINARY_FIXP_RETRANSMIT_HANDLER =
        (templateId, buffer, offset, blockLength, version) ->
        {
        };

    /** Unmodifiable set of defaults, please make a copy if you wish to modify them. */
    public static final Set<String> DEFAULT_GAPFILL_ON_REPLAY_MESSAGE_TYPES;
    public static final long DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS = 10_000;
    public static final long DEFAULT_AUTHENTICATION_TIMEOUT_IN_MS = 60_000;
    public static final int DEFAULT_MAX_CONCURRENT_SESSION_REPLAYS = 5;
    public static final long DEFAULT_DUPLICATE_ENGINE_TIMEOUT_IN_MS = SECONDS.toMillis(10);
    public static final int NO_THROTTLE_WINDOW = MISSING_INT;
    public static final boolean DEFAULT_INDEX_CHECKSUM_ENABLED = true;

    public static final long MAX_COD_TIMEOUT_IN_NS = 60_000_000_000L;
    public static final long MAX_COD_TIMEOUT_IN_MS = 60_000L;

    public static final long DEFAULT_TIME_INDEX_FLUSH_INTERVAL_IN_NS = TimeUnit.SECONDS.toNanos(1);

    static
    {
        final Set<String> defaultGapFillOnReplayMessageTypes = new HashSet<>();
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.LOGON_MESSAGE_TYPE_STR);
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.LOGOUT_MESSAGE_TYPE_STR);
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.RESEND_REQUEST_MESSAGE_TYPE_STR);
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.HEARTBEAT_MESSAGE_TYPE_STR);
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.TEST_REQUEST_MESSAGE_TYPE_STR);
        defaultGapFillOnReplayMessageTypes.add(SessionConstants.SEQUENCE_RESET_TYPE_STR);
        DEFAULT_GAPFILL_ON_REPLAY_MESSAGE_TYPES = Collections.unmodifiableSet(defaultGapFillOnReplayMessageTypes);
    }

    public static final int DEFAULT_OUTBOUND_REPLAY_STREAM = 3;
    public static final int DEFAULT_ARCHIVE_REPLAY_STREAM = 4;
    public static final int DEFAULT_ARCHIVE_SCANNER_STREAM = 5;
    public static final int DEFAULT_REPRODUCTION_LOG_STREAM = 6;
    public static final int DEFAULT_REPRODUCTION_REPLAY_STREAM = 7;

    public static final int DEFAULT_INITIAL_SEQUENCE_INDEX = 0;
    public static final int DEFAULT_CANCEL_ON_DISCONNECT_TIMEOUT_WINDOW_IN_MS = 0;

    private String host = null;
    private int port;
    private int replayIndexFileRecordCapacity = getInteger(
        REPLAY_INDEX_RECORD_CAPACITY_PROP, DEFAULT_REPLAY_INDEX_RECORD_CAPACITY);
    private int replayIndexSegmentRecordCapacity = DEFAULT_REPLAY_INDEX_SEGMENT_CAPACITY;
    private String logFileDir = getProperty(LOG_FILE_DIR_PROP, DEFAULT_LOG_FILE_DIR);
    private int loggerCacheNumSets = DEFAULT_LOGGER_CACHE_NUM_SETS;
    private int loggerCacheSetSize = DEFAULT_LOGGER_CACHE_SET_SIZE;
    private boolean logInboundMessages = true;
    private boolean logOutboundMessages = true;
    private boolean printStartupWarnings = true;
    private IdleStrategy framerIdleStrategy = backoffIdleStrategy();
    private IdleStrategy archiverIdleStrategy = backoffIdleStrategy();
    private AtomicBuffer sentSequenceNumberBuffer;
    private AtomicBuffer receivedSequenceNumberBuffer;
    private MappedFile sentSequenceNumberIndex;
    private MappedFile receivedSequenceNumberIndex;
    private MappedFile sessionIdBuffer;
    private MappedFile fixPBuffer;
    private SessionIdGenerator sessionIdGenerator;
    private Set<String> gapfillOnReplayMessageTypes = new HashSet<>(DEFAULT_GAPFILL_ON_REPLAY_MESSAGE_TYPES);
    private IntHashSet gapfillOnRetransmitILinkTemplateIds = new IntHashSet();
    private final AeronArchive.Context archiveContext = new AeronArchive.Context();
    private AeronArchive.Context archiveContextClone;
    private Aeron.Context aeronContextClone;

    private int outboundLibraryFragmentLimit =
        getInteger(OUTBOUND_LIBRARY_FRAGMENT_LIMIT_PROP, DEFAULT_OUTBOUND_LIBRARY_FRAGMENT_LIMIT);
    private int replayFragmentLimit =
        getInteger(REPLAY_FRAGMENT_LIMIT_PROP, DEFAULT_REPLAY_FRAGMENT_LIMIT);
    private int inboundBytesReceivedLimit =
        getInteger(INBOUND_BYTES_RECEIVED_LIMIT_PROP, DEFAULT_INBOUND_BYTES_RECEIVED_LIMIT);
    private int receiverBufferSize =
        getInteger(RECEIVER_BUFFER_SIZE_PROP, DEFAULT_RECEIVER_BUFFER_SIZE);
    private int receiverSocketBufferSize =
        getInteger(RECEIVER_SOCKET_BUFFER_SIZE_PROP, DEFAULT_RECEIVER_SOCKET_BUFFER_SIZE);
    private int senderSocketBufferSize =
        getInteger(SENDER_SOCKET_BUFFER_SIZE_PROP, DEFAULT_SENDER_SOCKET_BUFFER_SIZE);
    private final int sequenceNumberIndexSize =
        getInteger(SEQUENCE_NUMBER_INDEX_SIZE_PROP, DEFAULT_SEQUENCE_NUMBER_INDEX_SIZE);
    private final int sessionIdBufferSize =
        getInteger(SESSION_ID_BUFFER_SIZE_PROP, DEFAULT_SESSION_ID_BUFFER_SIZE);
    private int senderMaxBytesInBuffer =
        getInteger(SENDER_MAX_BYTES_IN_BUFFER_PROP, DEFAULT_SENDER_MAX_BYTES_IN_BUFFER);
    private int noLogonDisconnectTimeoutInMs =
        getInteger(NO_LOGON_DISCONNECT_TIMEOUT_PROP, DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS);
    private boolean indexChecksumEnabled = getBoolean(INDEX_CHECKSUM_ENABLED_PROP, DEFAULT_INDEX_CHECKSUM_ENABLED);

    private String libraryAeronChannel = null;
    private Function<EngineConfiguration, TcpChannelSupplier> channelSupplierFactory = DefaultTcpChannelSupplier::new;
    private SessionPersistenceStrategy sessionPersistenceStrategy;
    private long slowConsumerTimeoutInMs = DEFAULT_SLOW_CONSUMER_TIMEOUT_IN_MS;
    private EngineScheduler scheduler = new DefaultEngineScheduler();
    private ReplayHandler replayHandler = DEFAULT_REPLAY_HANDLER;
    private FixPRetransmitHandler fixPRetransmitHandler = DEFAULT_BINARY_FIXP_RETRANSMIT_HANDLER;
    private int outboundReplayStream = DEFAULT_OUTBOUND_REPLAY_STREAM;
    private int archiveReplayStream = DEFAULT_ARCHIVE_REPLAY_STREAM;
    private int reproductionLogStream = DEFAULT_REPRODUCTION_LOG_STREAM;
    private int reproductionReplayStream = DEFAULT_REPRODUCTION_REPLAY_STREAM;
    private boolean acceptedSessionClosedResendInterval = DEFAULT_CLOSED_RESEND_INTERVAL;
    private int acceptedSessionResendRequestChunkSize = NO_RESEND_REQUEST_CHUNK_SIZE;
    private boolean acceptedSessionSendRedundantResendRequests = DEFAULT_SEND_REDUNDANT_RESEND_REQUESTS;
    private boolean acceptedEnableLastMsgSeqNumProcessed = DEFAULT_ENABLE_LAST_MSG_SEQ_NUM_PROCESSED;
    private InitialAcceptedSessionOwner initialAcceptedSessionOwner = InitialAcceptedSessionOwner.ENGINE;
    private AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.none();
    private FixPAuthenticationStrategy fixPAuthenticationStrategy = FixPAuthenticationStrategy.none();
    private long indexFileStateFlushTimeoutInMs = DEFAULT_INDEX_FILE_STATE_FLUSH_TIMEOUT_IN_MS;
    private FixDictionary acceptorfixDictionary;
    private boolean lookupDefaultAcceptorfixDictionary = true;
    private final Map<String, FixDictionary> acceptorFixDictionaryOverrides = new HashMap<>();
    private boolean deleteLogFileDirOnStart = false;
    private long authenticationTimeoutInMs = DEFAULT_AUTHENTICATION_TIMEOUT_IN_MS;
    private boolean bindAtStartup = false;
    private int initialSequenceIndex = DEFAULT_INITIAL_SEQUENCE_INDEX;
    private MessageTimingHandler messageTimingHandler = null;
    private int maxConcurrentSessionReplays = DEFAULT_MAX_CONCURRENT_SESSION_REPLAYS;
    private int replayPositionBufferSize = DEFAULT_REPLAY_POSITION_BUFFER_SIZE;
    private long duplicateEngineTimeoutInMs = DEFAULT_DUPLICATE_ENGINE_TIMEOUT_IN_MS;
    private boolean errorIfDuplicateEngineDetected = true;
    private int inboundAdminStream = DEFAULT_INBOUND_ADMIN_STREAM_ID;
    private int outboundAdminStream = DEFAULT_OUTBOUND_ADMIN_STREAM_ID;
    private FixPProtocolType acceptorFixPProtocol = null;
    private CancelOnDisconnectTimeoutHandler cancelOnDisconnectTimeoutHandler = null;
    private FixPCancelOnDisconnectTimeoutHandler fixPCancelOnDisconnectTimeoutHandler = null;
    private int throttleWindowInMs = NO_THROTTLE_WINDOW;
    private int throttleLimitOfMessages = NO_THROTTLE_WINDOW;
    private long timeIndexReplayFlushIntervalInNs = DEFAULT_TIME_INDEX_FLUSH_INTERVAL_IN_NS;
    private CancelOnDisconnectOption cancelOnDisconnectOption = DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT;
    private int cancelOnDisconnectTimeoutWindowInMs = DEFAULT_CANCEL_ON_DISCONNECT_TIMEOUT_WINDOW_IN_MS;
    private long framerCycleThresholdNs = DEFAULT_CYCLE_THRESHOLD_NS;
    private long indexerCycleThresholdNs = DEFAULT_CYCLE_THRESHOLD_NS;

    private EngineReproductionConfiguration reproductionConfiguration;
    private ReproductionMessageHandler reproductionMessageHandler = (connectionId, bytes) ->
    {
    };
    private boolean writeReproductionLog = false;

    // ---------------------
    // BEGIN SETTERS
    // ---------------------

    /**
     * Sets the local address to bind to when the Gateway is used to accept connections.
     * <p>
     * Optional. If set defaults {@link #bindAtStartup(boolean)} to true. Care should be taken with the
     * initialisation order of these options.
     *
     * @param host the hostname to bind to.
     * @param port the port to bind to.
     * @return this
     */
    public EngineConfiguration bindTo(final String host, final int port)
    {
        Objects.requireNonNull(host, "host");
        this.host = host;
        this.port = port;
        bindAtStartup(true);
        return this;
    }

    /**
     * Controls whether the engine should eagerly bind the network interface at startup when {@link #bindTo(String, int)}
     * is used, or whether it is delayed until {@link FixEngine#bind()} is invoked. If used in conjunction with
     * {@link InitialAcceptedSessionOwner#SOLE_LIBRARY} then the binding operation will be delayed until the library
     * is connected.
     *
     * @param bindAtStartup false to delay binding until {@link FixEngine#bind()} is invoked.
     * @return this
     */
    public EngineConfiguration bindAtStartup(final boolean bindAtStartup)
    {
        this.bindAtStartup = bindAtStartup;
        return this;
    }

    /**
     * Configures the engine to accept the provided FIXP connections. The Engine no longer accepts
     * regular FIX protocol connections and only accepts this binary protocol. Protocol must be a valid acceptor
     * protocol.
     *
     * Automatically sets
     * <code>lookupDefaultAcceptorfixDictionary(false)</code>
     *
     * @param acceptorFixPProtocol the protocol to accept
     * @return this
     * @throws IllegalArgumentException if acceptorFixPProtocol isn't a valid protocol
     */
    public EngineConfiguration acceptFixPProtocol(final FixPProtocolType acceptorFixPProtocol)
    {
        if (!FixPProtocolFactory.isAcceptorImplemented(acceptorFixPProtocol))
        {
            throw new IllegalArgumentException(acceptorFixPProtocol + " isn't a valid acceptor protocol");
        }

        this.acceptorFixPProtocol = acceptorFixPProtocol;
        lookupDefaultAcceptorfixDictionary = false;
        return this;
    }

    /**
     * Sets the receiver buffer size. This determines the maximum size of message that can be
     * received over the wire.
     *
     * @param receiverBufferSize the receiver buffer size.
     * @return this
     * @see CommonConfiguration#sessionBufferSize(int)
     * @see EngineConfiguration#RECEIVER_BUFFER_SIZE_PROP
     */
    public EngineConfiguration receiverBufferSize(final int receiverBufferSize)
    {
        this.receiverBufferSize = receiverBufferSize;
        return this;
    }

    /**
     * Sets the receiver socket buffer size.
     *
     * @param receiverSocketBufferSize the receiver socket buffer size.
     * @return this
     * @see EngineConfiguration#RECEIVER_SOCKET_BUFFER_SIZE_PROP
     */
    public EngineConfiguration receiverSocketBufferSize(final int receiverSocketBufferSize)
    {
        this.receiverSocketBufferSize = receiverSocketBufferSize;
        return this;
    }

    /**
     * Sets the sender socket buffer size.
     *
     * @param senderSocketBufferSize the receiver socket buffer size.
     * @return this
     * @see EngineConfiguration#SENDER_SOCKET_BUFFER_SIZE_PROP
     */
    public EngineConfiguration senderSocketBufferSize(final int senderSocketBufferSize)
    {
        this.senderSocketBufferSize = senderSocketBufferSize;
        return this;
    }

    /**
     * Sets the directory to store log files in.
     *
     * @param logFileDir the directory to store log files in.
     * @return this
     * @see EngineConfiguration#LOG_FILE_DIR_PROP
     */
    public EngineConfiguration logFileDir(final String logFileDir)
    {
        this.logFileDir = logFileDir;
        return this;
    }

    /**
     * Sets the maximum size of index files. as calculated by the number of records that can be stored per FIX session.
     * In business terms this is maximum number of messages back in history that Artio can respond to resend requests
     * from. Each session's index storage is divided into segments, the size of a segment is configured by
     * {@link #replayIndexSegmentRecordCapacity(int)}.
     *
     * If this isn't a power of two, then the next positive power of two will be used.
     *
     * {@link #replayIndexFileCapacityToBytes(int)} can be used in order to calculate the maximum space required per
     * FIX session.
     *
     * @param replayIndexFileRecordCapacity the number of fix messages to keep track of the replay index.
     * @return this
     */
    public EngineConfiguration replayIndexFileRecordCapacity(final int replayIndexFileRecordCapacity)
    {
        this.replayIndexFileRecordCapacity = findNextPositivePowerOfTwo(replayIndexFileRecordCapacity);
        return this;
    }

    /**
     * Sets the capacity of an individual segment file. See {@link #replayIndexFileRecordCapacity(int)} for more
     * details.
     *
     * If this isn't a power of two, then the next positive power of two will be used.
     *
     * @param indexSegmentCapacityInRecords the capacity of an individual segment file
     * @throws IllegalArgumentException if the capacity is outside of the supported range
     * @return this
     */
    public EngineConfiguration replayIndexSegmentRecordCapacity(final int indexSegmentCapacityInRecords)
    {
        this.replayIndexSegmentRecordCapacity = findNextPositivePowerOfTwo(indexSegmentCapacityInRecords);
        if (replayIndexSegmentRecordCapacity > MAX_FILE_SEGMENT_CAPACITY || replayIndexSegmentRecordCapacity < 0)
        {
            throw new IllegalArgumentException("replayIndexSegmentRecordCapacity cannot be > " +
                MAX_FILE_SEGMENT_CAPACITY + " or < 0 but is set to " + indexSegmentCapacityInRecords);
        }
        return this;
    }

    /**
     * Convert the number of records in a replay index file to a file size. Note: because replay index file sizes must
     * be a power of two this method can return a file size greater than the requested number of records but never less.
     *
     * @param requestedNumberOfRecordsToStore the number of records to store in the replay index.
     * @return the replay index file size in bytes
     */
    public static long replayIndexFileCapacityToBytes(final int requestedNumberOfRecordsToStore)
    {
        return HEADER_FILE_SIZE + ReplayIndexDescriptor.RECORD_LENGTH *
            (long)findNextPositivePowerOfTwo(requestedNumberOfRecordsToStore);
    }

    /**
     * Sets the set size of the logger's caches. This is currently only used by the ReplayQuery size. See
     * {@link Long2ObjectCache} for guidance on how to adjust the number of sets and the set size.
     * <p>
     * The ReplayQuery class has a caches of open memory mapped files which it queries
     * into. Replay queries are used by Artio in response to FIX resend requests, the memory mapped files themselves
     * are cached between resend requests since it's often the case that a few FIX sessions are often responsible for
     * most resend requests.
     * <p>
     * This and {@link #loggerCacheNumSets} controls the size of those caches.
     * The {@link #loggerCacheNumSets} should be increased if you see files being opened/closed in that area too
     * frequently.
     *
     * @param loggerCacheSetSize the set size of the logger's caches.
     * @return this
     */
    public EngineConfiguration loggerCacheSetSize(final int loggerCacheSetSize)
    {
        this.loggerCacheSetSize = loggerCacheSetSize;
        return this;
    }

    /**
     * Sets the number of sets of in the logger's caches.
     *
     * @see #loggerCacheSetSize(int)
     *
     * @param loggerCacheNumSets the number of sets of in the logger's caches.
     * @return this
     */
    public EngineConfiguration loggerCacheNumSets(final int loggerCacheNumSets)
    {
        this.loggerCacheNumSets = loggerCacheNumSets;
        return this;
    }

    /**
     * Sets logging of inbound messages.
     * <p>
     * Switch off if you don't want the logging system to store all inbound messages in the archival system.
     * <p>
     * Default: true.
     *
     * NB: if this configuration parameter is switched off then any replay requests will be gap filled.
     *
     * @param logInboundMessages logging of inbound messages.
     * @return this
     */
    public EngineConfiguration logInboundMessages(final boolean logInboundMessages)
    {
        this.logInboundMessages = logInboundMessages;
        return this;
    }

    /**
     * Sets logging of outbound messages.
     * <p>
     * Switch off if you don't want the logging system to store all outbound messages.
     * <b>NB:</b> take care if you switch this off as message replay won't work.
     * <p>
     * Default: true.
     *
     * @param logOutboundMessages logging of outbound messages.
     * @return this
     */
    public EngineConfiguration logOutboundMessages(final boolean logOutboundMessages)
    {
        this.logOutboundMessages = logOutboundMessages;
        return this;
    }

    public EngineConfiguration printStartupWarnings(final boolean printStartupWarnings)
    {
        this.printStartupWarnings = printStartupWarnings;
        return this;
    }

    /**
     * Sets the idle strategy for the Framer thread.
     *
     * @param framerIdleStrategy the idle strategy for the Framer thread.
     * @return this
     */
    public EngineConfiguration framerIdleStrategy(final IdleStrategy framerIdleStrategy)
    {
        this.framerIdleStrategy = framerIdleStrategy;
        return this;
    }

    /**
     * Sets the idle strategy for the Logger thread.
     *
     * @param archiverIdleStrategy the idle strategy for the Logger thread.
     * @return this
     */
    public EngineConfiguration archiverIdleStrategy(final IdleStrategy archiverIdleStrategy)
    {
        this.archiverIdleStrategy = archiverIdleStrategy;
        return this;
    }

    /**
     * Sets the fragment limit for the subscription to outbound messages from libraries.
     *
     * @param outboundLibraryFragmentLimit the fragment limit for the subscription to outbound messages from libraries.
     * @return this
     * @see EngineConfiguration#OUTBOUND_LIBRARY_FRAGMENT_LIMIT_PROP
     */
    public EngineConfiguration outboundLibraryFragmentLimit(final int outboundLibraryFragmentLimit)
    {
        this.outboundLibraryFragmentLimit = outboundLibraryFragmentLimit;
        return this;
    }

    /**
     * Sets the fragment limit for the subscription to messages from the replayer.
     *
     * @param outboundReplayFragmentLimit the fragment limit for the subscription to messages from the replayer.
     * @return this
     * @see EngineConfiguration#REPLAY_FRAGMENT_LIMIT_PROP
     */
    public EngineConfiguration replayFragmentLimit(final int outboundReplayFragmentLimit)
    {
        this.replayFragmentLimit = outboundReplayFragmentLimit;
        return this;
    }

    /**
     * Sets the bytes limit for receiving inbound messages.
     *
     * @param inboundBytesReceivedLimit the bytes limit for receiving inbound messages.
     * @return this
     * @see EngineConfiguration#INBOUND_BYTES_RECEIVED_LIMIT_PROP
     */
    public EngineConfiguration inboundBytesReceivedLimit(final int inboundBytesReceivedLimit)
    {
        this.inboundBytesReceivedLimit = inboundBytesReceivedLimit;
        return this;
    }

    public EngineConfiguration senderMaxBytesInBuffer(final int senderMaxBytesInBuffer)
    {
        this.senderMaxBytesInBuffer = senderMaxBytesInBuffer;
        return this;
    }

    /**
     * Set the timeout in milliseconds for TCP connections which don't send a logon message.
     *
     * @param noLogonDisconnectTimeout the timeout in milliseconds for TCP connections which don't send a logon message
     * @return this
     */
    public EngineConfiguration noLogonDisconnectTimeoutInMs(final int noLogonDisconnectTimeout)
    {
        this.noLogonDisconnectTimeoutInMs = noLogonDisconnectTimeout;
        return this;
    }

    public EngineConfiguration channelSupplierFactory(final Function<EngineConfiguration, TcpChannelSupplier> value)
    {
        this.channelSupplierFactory = value;
        return this;
    }

    public EngineConfiguration sessionPersistenceStrategy(final SessionPersistenceStrategy sessionPersistenceStrategy)
    {
        this.sessionPersistenceStrategy = sessionPersistenceStrategy;
        return this;
    }

    public EngineConfiguration slowConsumerTimeoutInMs(final long slowConsumerTimeoutInMs)
    {
        this.slowConsumerTimeoutInMs = slowConsumerTimeoutInMs;
        return this;
    }

    public EngineConfiguration scheduler(final EngineScheduler scheduler)
    {
        this.scheduler = scheduler;
        return this;
    }

    /**
     * Sets the types of message that are gapfilled instead of replayed.
     *
     * When a resend request (2) arrives the gateway can choose to gap fill certain messages
     * instead of replaying them. A gap fill is implemented by sending a sequence reset message (4)
     * with it's gap fill flag set to true. This tells the FIX counter party that we won't be resending
     * those messages.
     *
     * By default Artio only gap fills administrative messages as the FIX spec demands. This method allows
     * you to customise it according to your needs.
     *
     * @see EngineConfiguration#DEFAULT_GAPFILL_ON_REPLAY_MESSAGE_TYPES
     * @param gapfillOnReplayMessageTypes the message types to gap fill
     * @return this
     */
    public EngineConfiguration gapfillOnReplayMessageTypes(final Set<String> gapfillOnReplayMessageTypes)
    {
        this.gapfillOnReplayMessageTypes = gapfillOnReplayMessageTypes;
        return this;
    }

    /**
     * Sets the types of template ids that are gapfilled instead of replayed in an Ilink3 connection.
     *
     * When a NotApplied message is handled by retransmitting message not all messages need to be retransmitted.
     * A gap fill here is implemented by sending a sequence message.
     *
     * @param gapfillOnRetransmitILinkTemplateIds the template ids to gap fill
     * @return this
     */
    public EngineConfiguration gapfillOnRetransmitILinkTemplateIds(
        final IntHashSet gapfillOnRetransmitILinkTemplateIds)
    {
        this.gapfillOnRetransmitILinkTemplateIds = gapfillOnRetransmitILinkTemplateIds;
        return this;
    }

    /**
     * Sets a handler that will be invoked when a message is replayed.
     *
     * @param replayHandler the replay handler
     * @return this
     */
    public EngineConfiguration replayHandler(final ReplayHandler replayHandler)
    {
        this.replayHandler = replayHandler;
        return this;
    }

    /**
     * Sets a handler that will be invoked when an iLink3 message is replayed.
     *
     * This configuration option has been deprecated - please use
     * {@link #fixPRetransmitHandler(FixPRetransmitHandler)} instead.
     *
     * @param iLink3RetransmitHandler the replay handler
     * @return this
     */
    @Deprecated
    public EngineConfiguration iLink3RetransmitHandler(final ILink3RetransmitHandler iLink3RetransmitHandler)
    {
        this.fixPRetransmitHandler = iLink3RetransmitHandler;
        return this;
    }

    /**
     * Sets a handler that will be invoked when an iLink3 message is replayed.
     *
     * @param fixPRetransmitHandler the replay handler
     * @return this
     */
    public EngineConfiguration fixPRetransmitHandler(
        final FixPRetransmitHandler fixPRetransmitHandler)
    {
        this.fixPRetransmitHandler = fixPRetransmitHandler;
        return this;
    }

    public EngineConfiguration outboundReplayStream(final int outboundReplayStream)
    {
        this.outboundReplayStream = outboundReplayStream;
        return this;
    }

    public EngineConfiguration archiveReplayStream(final int archiveReplayStream)
    {
        this.archiveReplayStream = archiveReplayStream;
        return this;
    }

    /**
     * Sets the {@link SessionConfiguration#closedResendInterval()} property for accepted Sessions.
     *
     * @param acceptedSessionClosedResendInterval the {@link SessionConfiguration#closedResendInterval()} property for
     *                                            accepted Sessions.
     * @return this
     */
    public EngineConfiguration acceptedSessionClosedResendInterval(final boolean acceptedSessionClosedResendInterval)
    {
        this.acceptedSessionClosedResendInterval = acceptedSessionClosedResendInterval;
        return this;
    }

    /**
     * Sets the {@link SessionConfiguration#resendRequestChunkSize()} property for accepted Sessions.
     *
     * @param acceptedSessionResendRequestChunkSize the {@link SessionConfiguration#resendRequestChunkSize()} property
     *                                              for accepted Sessions.
     * @return this
     */
    public EngineConfiguration acceptedSessionResendRequestChunkSize(final int acceptedSessionResendRequestChunkSize)
    {
        this.acceptedSessionResendRequestChunkSize = acceptedSessionResendRequestChunkSize;
        return this;
    }

    /**
     * Sets the {@link SessionConfiguration#sendRedundantResendRequests()} property for accepted Sessions.
     *
     * @param acceptedSessionSendRedundantResendRequests the {@link SessionConfiguration#sendRedundantResendRequests()}
     *                                                   property for accepted Sessions.
     * @return this
     */
    public EngineConfiguration acceptedSessionSendRedundantResendRequests(
        final boolean acceptedSessionSendRedundantResendRequests)
    {
        this.acceptedSessionSendRedundantResendRequests = acceptedSessionSendRedundantResendRequests;
        return this;
    }

    /**
     * Sets the {@link SessionConfiguration#enableLastMsgSeqNumProcessed()} property for accepted Sessions.
     *
     * @param acceptedEnableLastMsgSeqNumProcessed the {@link SessionConfiguration#enableLastMsgSeqNumProcessed()}
     *                                             property for accepted Sessions.
     * @return this
     */
    public EngineConfiguration acceptedEnableLastMsgSeqNumProcessed(
        final boolean acceptedEnableLastMsgSeqNumProcessed)
    {
        this.acceptedEnableLastMsgSeqNumProcessed = acceptedEnableLastMsgSeqNumProcessed;
        return this;
    }

    /**
     * Set whether accepted sessions are initially owned by the Engine or a Library - the default is
     * the Engine. When a FIX initiator initially connects to an Artio acceptor then by default this session is owned
     * by the Engine and Libraries can request ownership of the Session from the Engine. If <code>SOLE_LIBRARY</code>
     * mode is chosen then only a single library instance must connect.
     *
     * In sole
     * library mode the server side TCP port will automatically be bound when the sole library connects, and unbound
     * when it disconnects.
     *
     * @param initialAcceptedSessionOwner whether accepted sessions are initially owned by the Engine or a Library
     * @return this
     */
    public EngineConfiguration initialAcceptedSessionOwner(
        final InitialAcceptedSessionOwner initialAcceptedSessionOwner)
    {
        this.initialAcceptedSessionOwner = initialAcceptedSessionOwner;
        return this;
    }

    /**
     * Sets the aeron channel that libraries will use to communicate with this FixEngine instance.
     *
     * @param libraryAeronChannel the aeron channel that libraries will use to communicate with this FixEngine instance.
     * @return this
     */
    public EngineConfiguration libraryAeronChannel(final String libraryAeronChannel)
    {
        this.libraryAeronChannel = libraryAeronChannel;
        return this;
    }

    /**
     * Sets the authentication strategy of the FIX Engine to be used for FIX sessions, see
     * {@link AuthenticationStrategy} for details.
     * <p>
     * This only needs to be set if this FIX Engine is an acceptor and has no impact on an initiator.
     *
     * @param authenticationStrategy the authentication strategy to use.
     * @return this
     * @see EngineConfiguration#authenticationTimeoutInMs(long)
     */
    public EngineConfiguration authenticationStrategy(final AuthenticationStrategy authenticationStrategy)
    {
        Verify.notNull(authenticationStrategy, "authenticationStrategy");
        this.authenticationStrategy = authenticationStrategy;
        return this;
    }

    /**
     * Sets the authentication strategy of the FIX Engine to be used for FIXP sessions, see
     * {@link AuthenticationStrategy} for details.
     * <p>
     * This only needs to be set if this FIX Engine is an acceptor and has no impact on an initiator.
     *
     * @param fixPAuthenticationStrategy the authentication strategy to use.
     * @return this
     * @see EngineConfiguration#authenticationTimeoutInMs(long)
     */
    public EngineConfiguration fixPAuthenticationStrategy(final FixPAuthenticationStrategy fixPAuthenticationStrategy)
    {
        Verify.notNull(fixPAuthenticationStrategy, "fixPAuthenticationStrategy");
        this.fixPAuthenticationStrategy = fixPAuthenticationStrategy;
        return this;
    }

    public EngineConfiguration indexFileStateFlushTimeoutInMs(final long indexFileStateFlushTimeoutInMs)
    {
        this.indexFileStateFlushTimeoutInMs = indexFileStateFlushTimeoutInMs;
        return this;
    }

    /**
     * Specify a single acceptor FIX Dictionary. If an override for a given FIX version is specified using
     * {@link #overrideAcceptorFixDictionary(Class)} then this FIX Dictionary will be used as a catch-all for any
     * connections that don't match the versions in the overrides.
     *
     * Note, that if this dictionary isn't specified then the default path that is
     * generated by the CodecGenerationTool will be used.
     *
     * @param acceptorfixDictionary the Fix Dictionary used to parse messages when accepting an inbound connection.
     * @return this
     * @see EngineConfiguration#overrideAcceptorFixDictionary(Class)
     * @see AuthenticationProxy#accept(Class)
     */
    public EngineConfiguration acceptorfixDictionary(final Class<? extends FixDictionary> acceptorfixDictionary)
    {
        this.acceptorfixDictionary = FixDictionary.of(acceptorfixDictionary);
        return this;
    }

    /**
     * Can be used to disable the automated lookup of an acceptorFixDictionary. This is useful when you're not
     * using regular FIX as your protocol but something else for example - Artio's iLink3 support.
     *
     * @param lookupDefaultAcceptorfixDictionary true if you want to lookup a default acceptor fix dictionary (the
     *                                           default), false otherwise.
     * @return this
     */
    public EngineConfiguration lookupDefaultAcceptorfixDictionary(final boolean lookupDefaultAcceptorfixDictionary)
    {
        this.lookupDefaultAcceptorfixDictionary = lookupDefaultAcceptorfixDictionary;
        return this;
    }

    /**
     * Override the acceptor FIX Dictionary for a given beginString. The beginString to use is extracted from the
     * Provided FIX Dictionary. If you wish to use multiple FIX dictionaries based upon the logon message and they
     * both have the same beginString field then you should implement a custom {@link AuthenticationStrategy}
     * and use the {@link AuthenticationProxy#accept(Class)} method in order to specify the dictionary.
     *
     * @param fixDictionaryClass the FIX Dictionary to use
     * @return this
     * @see EngineConfiguration#acceptorfixDictionary(Class)
     * @see AuthenticationProxy#accept(Class)
     */
    public EngineConfiguration overrideAcceptorFixDictionary(final Class<? extends FixDictionary> fixDictionaryClass)
    {
        final FixDictionary dictionary = FixDictionary.of(fixDictionaryClass);
        this.acceptorFixDictionaryOverrides.put(dictionary.beginString(), dictionary);
        return this;
    }

    /**
     * Enable or disable the deleting of the {@link #logFileDir(String)} on startup. This defaults to
     * false. When set to true if the log file directory exists when the engine is started up then it will
     * be deleted. This is usually an undesirable behaviour in a production environment but it can be
     * very useful when using Artio in automated tests as any previous file system state will not
     * interfere with your test's repeatability.
     *
     * @param deleteLogFileDirOnStart true to enable or false to disable
     * @return this
     */
    public EngineConfiguration deleteLogFileDirOnStart(final boolean deleteLogFileDirOnStart)
    {
        this.deleteLogFileDirOnStart = deleteLogFileDirOnStart;
        return this;
    }

    /**
     * Sets the timeout to be used for the authentication process. It is possible for either a buggy implementation
     * or a malicious Logon message to cause the
     * {@link AuthenticationStrategy#authenticateAsync(AbstractLogonDecoder, AuthenticationProxy)} method to not invoke
     * its authentication proxy. As a result Artio implements a configurable timeout that will close the session if
     * the proxy isn't used within the timeout. This configuration option defines the length of that timeout.
     *
     * @param authenticationTimeoutInMs the timeout to be used for the authentication process.
     * @return this
     * @see EngineConfiguration#authenticationStrategy(AuthenticationStrategy)
     */
    public EngineConfiguration authenticationTimeoutInMs(final long authenticationTimeoutInMs)
    {
        this.authenticationTimeoutInMs = authenticationTimeoutInMs;
        return this;
    }

    /**
     * Sets the message timing handler for this Engine instance.
     *
     * @param messageTimingHandler the message timing handler for this Engine instance.
     * @return this
     */
    public EngineConfiguration messageTimingHandler(final MessageTimingHandler messageTimingHandler)
    {
        this.messageTimingHandler = messageTimingHandler;
        return this;
    }

    /**
     * Enables Artio's message throttle. If a session starts to send more messages than the specified throttle limit
     * then Artio will drop those messages as efficiently as possible and reply to the messages with a business reject.
     * The time window is applied as a rolling manner. This can be overriden on a per session basis at runtime using
     * {@link uk.co.real_logic.artio.session.Session#throttleMessagesAt(int, int)}.
     *
     * @param throttleWindowInMs the time window to apply the throttle over.
     * @param throttleLimitOfMessages the maximum number of messages that can be received within the time window.
     * @throws IllegalArgumentException if either parameter is &lt; 1.
     * @return this
     */
    public EngineConfiguration enableMessageThrottle(final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        validateMessageThrottleOptions(throttleWindowInMs, throttleLimitOfMessages);

        this.throttleWindowInMs = throttleWindowInMs;
        this.throttleLimitOfMessages = throttleLimitOfMessages;
        return this;
    }

    public static void validateMessageThrottleOptions(final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        if (throttleWindowInMs < 1)
        {
            throw new IllegalArgumentException(
                "Unable to configure message throttle, throttleWindowInMs must be >= 1 but is " + throttleWindowInMs);
        }

        if (throttleLimitOfMessages < 1)
        {
            throw new IllegalArgumentException(
                "Unable to configure message throttle, throttleLimitOfMessages must be >= 1 but is " +
                    throttleLimitOfMessages);
        }
    }

    /**
     * Sets the maximum number of resend requests per session that Artio will process concurrently. Once the maximum is
     * hit further FIX resend requests will be ignored and an Exception will be logged noting the event. Note
     * this is a per-session parameter - ie the number of ResendRequests that will be queued for processing.
     *
     * @param maxConcurrentSessionReplays the maximum number of resend requests per session that Artio will enqueue
     *                                    before rejecting anymore.
     * @return this
     */
    public EngineConfiguration maxConcurrentSessionReplays(final int maxConcurrentSessionReplays)
    {
        this.maxConcurrentSessionReplays = maxConcurrentSessionReplays;
        return this;
    }

    /**
     * Sets the initial sequenceIndex for the new session.
     * Doesnt affects existing session.
     *
     * @param initialSequenceIndex initial sequence index
     * @return this
     */
    public EngineConfiguration initialSequenceIndex(final int initialSequenceIndex)
    {
        this.initialSequenceIndex = initialSequenceIndex;
        return this;
    }

    public EngineConfiguration replayPositionBufferSize(final int replayPositionBufferSize)
    {
        this.replayPositionBufferSize = replayPositionBufferSize;
        return this;
    }

    /**
     * Sets the timeout for detecting duplicate engines. Artio throws an exception on startup if it
     * detects another Artio process trying to use the same directory. It does this by checking whether
     * files have been updated within a certain timeout - that is configured using this method.
     *
     * @param duplicateEngineTimeoutInMs the timeout for detecting duplicate engines.
     * @return this
     * @see EngineConfiguration#errorIfDuplicateEngineDetected(boolean)
     */
    public EngineConfiguration duplicateEngineTimeoutInMs(final long duplicateEngineTimeoutInMs)
    {
        this.duplicateEngineTimeoutInMs = duplicateEngineTimeoutInMs;
        return this;
    }

    /**
     * Enables or disables the detection startup duplicate engine detection. If Artio detects a duplicate
     * Engine then it will throw an exception on startup.
     *
     * @param errorIfDuplicateEngineDetected true to enable duplicate engine detection or false to disable it.
     * @return this
     * @see EngineConfiguration#duplicateEngineTimeoutInMs(long)
     */
    public EngineConfiguration errorIfDuplicateEngineDetected(final boolean errorIfDuplicateEngineDetected)
    {
        this.errorIfDuplicateEngineDetected = errorIfDuplicateEngineDetected;
        return this;
    }

    /**
     * Sets the cancel on disconnect timeout handler for FIX sessions. This is invoked when a cancel on disconnect
     * event occurs.
     *
     * You can see <a href="https://github.com/artiofix/artio/wiki/Cancel-On-Disconnect-Notification">the wiki</a>
     * for more details around Cancel on disconnect support.
     *
     * @param cancelOnDisconnectTimeoutHandler the handler to be invoked when a cancel on disconnect event occurs.
     * @return this
     * @see #fixPCancelOnDisconnectTimeoutHandler(FixPCancelOnDisconnectTimeoutHandler)
     */
    public EngineConfiguration cancelOnDisconnectTimeoutHandler(
        final CancelOnDisconnectTimeoutHandler cancelOnDisconnectTimeoutHandler)
    {
        this.cancelOnDisconnectTimeoutHandler = cancelOnDisconnectTimeoutHandler;
        return this;
    }

    /**
     * Sets the cancel on disconnect timeout handler for FIXP connections. This is invoked when a cancel on disconnect
     * event occurs.
     *
     * You can see <a href="https://github.com/artiofix/artio/wiki/Cancel-On-Disconnect-Notification">the wiki</a>
     * for more details around Cancel on disconnect support.
     *
     * @param fixPCancelOnDisconnectTimeoutHandler the handler to be invoked when a cancel on disconnect event occurs.
     * @return this
     * @see #cancelOnDisconnectTimeoutHandler(CancelOnDisconnectTimeoutHandler)
     */
    public EngineConfiguration fixPCancelOnDisconnectTimeoutHandler(
        final FixPCancelOnDisconnectTimeoutHandler fixPCancelOnDisconnectTimeoutHandler)
    {
        this.fixPCancelOnDisconnectTimeoutHandler = fixPCancelOnDisconnectTimeoutHandler;
        return this;
    }

    /**
     * Set the stream id from an admin API to a FIX Engine.
     *
     * @param inboundAdminStream the stream id from an admin API to a FIX Engine.
     * @return this
     */
    public EngineConfiguration inboundAdminStream(final int inboundAdminStream)
    {
        this.inboundAdminStream = inboundAdminStream;
        return this;
    }

    /**
     * Set the stream id from a FIX Engine to an admin API.
     *
     * @param outboundAdminStream the stream id from a FIX Engine to an admin API.
     * @return this
     */
    public EngineConfiguration outboundAdminStream(final int outboundAdminStream)
    {
        this.outboundAdminStream = outboundAdminStream;
        return this;
    }

    /**
     * Sets the interval that the time index, used for optimizing the {@link FixArchiveScanner}.
     *
     * Larger intervals reduce time index disk space consumption, smaller intervals improve time based archive scans.
     *
     * @param timeIndexReplayFlushIntervalInNs the interval before a record is written for the time index.
     * @return this
     */
    public EngineConfiguration timeIndexReplayFlushIntervalInNs(final long timeIndexReplayFlushIntervalInNs)
    {
        this.timeIndexReplayFlushIntervalInNs = timeIndexReplayFlushIntervalInNs;
        return this;
    }

    /**
     * Allows disabling of the checksum calculation and validation of index files. Note: this does not affect the
     * checksum calculation for AeronArchiver - only artio itself.
     *
     * @param indexChecksumEnabled true to enable, false to disable
     * @return this
     */
    public EngineConfiguration indexChecksumEnabled(final boolean indexChecksumEnabled)
    {
        this.indexChecksumEnabled = indexChecksumEnabled;
        return this;
    }

    /**
     * Sets the message handler for outbound messages to be passed to when reproduction mode is enabled.
     *
     * See {@link #reproduceInbound(long, long)} in order to enable inbound reproduction.
     *
     * @param reproductionMessageHandler the message handler
     * @return this
     */
    public EngineConfiguration reproductionMessageHandler(
        final ReproductionMessageHandler reproductionMessageHandler)
    {
        this.reproductionMessageHandler = reproductionMessageHandler;
        return this;
    }

    /**
     * Enable inbound reproduction mode for the Engine.
     *
     * Inbound reproduction mode needs to be started using: {@link FixEngine#startReproduction()}. In order to use this
     * with libraries then libraries must have their
     * {@link uk.co.real_logic.artio.library.LibraryConfiguration#reproduceInbound(long, long)} mode enabled.
     *
     * @param startInNs the start time to reproduce from.
     * @param endInNs the end time to reproduce until.
     * @return this
     */
    public EngineConfiguration reproduceInbound(
        final long startInNs, final long endInNs)
    {
        final ReproductionClock clock = new ReproductionClock(startInNs);
        epochNanoClock(clock);
        this.reproductionConfiguration = new EngineReproductionConfiguration(
            startInNs, endInNs, clock);
        writeReproductionLog(false);
        return this;
    }

    /**
     * Set whether writing the reproduction log is enabled or disabled. Disabled by default.
     *
     * This logs additional events on the {@link #reproductionLogStream(int)} in order to enable a more accurate
     * reproduction of events. For example: at what point back-pressure happens. These events are not needed in order
     * to perform an inbound reproduction: they just make the inbound reproduction more accurate.
     *
     * @param writeReproductionLog true to enable the reproduction log
     * @return this
     */
    public EngineConfiguration writeReproductionLog(final boolean writeReproductionLog)
    {
        this.writeReproductionLog = writeReproductionLog;
        return this;
    }

    /**
     * Sets the Aeron stream id for the reproduction event stream. The Aeron channel used in conjunction with
     * this stream is {@link io.aeron.CommonContext#IPC_CHANNEL}.
     *
     * This is unused unless {@link #writeReproductionLog(boolean)} is set to true.
     *
     * @param reproductionLogStream the Aeron stream id for the reproduction event stream.
     * @return this
     */
    public EngineConfiguration reproductionLogStream(final int reproductionLogStream)
    {
        this.reproductionLogStream = reproductionLogStream;
        return this;
    }

    /**
     * Sets the Aeron stream id for replaying the archive log when running a reproduction. The Aeron channel used in
     * conjunction with this stream is {@link io.aeron.CommonContext#IPC_CHANNEL}.
     *
     * This is unused unless {@link #reproduceInbound(long, long)} is enabled.
     *
     * @param reproductionReplayStream the Aeron stream id for the reproduction replay stream.
     * @return this
     */
    public EngineConfiguration reproductionReplayStream(final int reproductionReplayStream)
    {
        this.reproductionReplayStream = reproductionReplayStream;
        return this;
    }

    /**
     * Sets the default option for accepted sessions in the case that no cancel on disconnect option is provided by the
     * initiator on the logon message. The default value for this field is
     * {@link CancelOnDisconnectOption#DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT}.
     *
     * @param cancelOnDisconnectOption the cancel on disconnect option to use if none is provided by the initiator.
     * @return this
     */
    public EngineConfiguration cancelOnDisconnectOption(final CancelOnDisconnectOption cancelOnDisconnectOption)
    {
        this.cancelOnDisconnectOption = cancelOnDisconnectOption;
        return this;
    }

    /**
     * Sets the default cancel on disconnection timeout window for accepted sessions in the case that none is provided
     * by the initiator on the logon message. This is only used when the resolved cancel on disconnect option is not
     * {@link CancelOnDisconnectOption#DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT}. The default value for this field is
     * {@link #DEFAULT_CANCEL_ON_DISCONNECT_TIMEOUT_WINDOW_IN_MS}.
     *
     * @param cancelOnDisconnectTimeoutWindowInMs the cancel on disconnect timeout window to use if none is provided by
     *                                            the initiator.
     * @return this
     */
    public EngineConfiguration cancelOnDisconnectTimeoutWindowInMs(final int cancelOnDisconnectTimeoutWindowInMs)
    {
        this.cancelOnDisconnectTimeoutWindowInMs = cancelOnDisconnectTimeoutWindowInMs;
        return this;
    }

    /**
     * Set a threshold for the framer work cycle time which when exceeded it will increment the
     * framer cycle time exceeded count.
     *
     * @param framerCycleThresholdNs value in nanoseconds. The value 0 will disable duty cycle tracking on the framer.
     * @return this for fluent API.
     */
    public EngineConfiguration framerCycleThresholdNs(final long framerCycleThresholdNs)
    {
        this.framerCycleThresholdNs = framerCycleThresholdNs;
        return this;
    }

    /**
     * Set a threshold for the indexer work cycle time which when exceeded it will increment the
     * indexer cycle time exceeded count.
     *
     * @param indexerCycleThresholdNs value in nanoseconds. The value 0 will disable duty cycle tracking on the indexer.
     * @return this for fluent API.
     */
    public EngineConfiguration indexerCycleThresholdNs(final long indexerCycleThresholdNs)
    {
        this.indexerCycleThresholdNs = indexerCycleThresholdNs;
        return this;
    }

    // ---------------------
    // END SETTERS
    // ---------------------

    // ------------------------
    // BEGIN INHERITED SETTERS
    // ------------------------

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration sendingTimeWindowInMs(final long sendingTimeWindowInMs)
    {
        super.sendingTimeWindowInMs(sendingTimeWindowInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration defaultHeartbeatIntervalInS(final int value)
    {
        super.defaultHeartbeatIntervalInS(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration forcedHeartbeatIntervalInS(final int value)
    {
        super.forcedHeartbeatIntervalInS(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public CommonConfiguration disableHeartbeatRepliesToTestRequests(
        final boolean disableHeartbeatRepliesToTestRequests)
    {
        return super.disableHeartbeatRepliesToTestRequests(disableHeartbeatRepliesToTestRequests);
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration sessionIdStrategy(final SessionIdStrategy sessionIdStrategy)
    {
        super.sessionIdStrategy(sessionIdStrategy);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration sessionCustomisationStrategy(
        final SessionCustomisationStrategy sessionCustomisationStrategy)
    {
        super.sessionCustomisationStrategy(sessionCustomisationStrategy);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration messageValidationStrategy(final MessageValidationStrategy messageValidationStrategy)
    {
        super.messageValidationStrategy(messageValidationStrategy);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration reasonableTransmissionTimeInMs(final long reasonableTransmissionTimeInMs)
    {
        super.reasonableTransmissionTimeInMs(reasonableTransmissionTimeInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration monitoringBuffersLength(final Integer monitoringBuffersLength)
    {
        super.monitoringBuffersLength(monitoringBuffersLength);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration monitoringFile(final String monitoringFile)
    {
        super.monitoringFile(monitoringFile);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("Deprecated")
    @Deprecated
    public EngineConfiguration printErrorMessages(final boolean printErrorMessages)
    {
        super.printErrorMessages(printErrorMessages);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("Deprecated")
    @Deprecated
    public EngineConfiguration customErrorConsumer(final ErrorConsumer customErrorConsumer)
    {
        super.customErrorConsumer(customErrorConsumer);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration errorHandlerFactory(final ErrorHandlerFactory errorHandlerFactory)
    {
        super.errorHandlerFactory(errorHandlerFactory);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration monitoringAgentFactory(final MonitoringAgentFactory monitoringAgentFactory)
    {
        super.monitoringAgentFactory(monitoringAgentFactory);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration monitoringThreadIdleStrategy(final IdleStrategy errorPrinterIdleStrategy)
    {
        super.monitoringThreadIdleStrategy(errorPrinterIdleStrategy);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration replyTimeoutInMs(final long replyTimeoutInMs)
    {
        super.replyTimeoutInMs(replyTimeoutInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration inboundMaxClaimAttempts(final int inboundMaxClaimAttempts)
    {
        super.inboundMaxClaimAttempts(inboundMaxClaimAttempts);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration outboundMaxClaimAttempts(final int outboundMaxClaimAttempts)
    {
        super.outboundMaxClaimAttempts(outboundMaxClaimAttempts);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration sessionBufferSize(final int bufferSize)
    {
        super.sessionBufferSize(bufferSize);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration histogramPollPeriodInMs(final long histogramPollPeriodInMs)
    {
        super.histogramPollPeriodInMs(histogramPollPeriodInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration histogramLoggingFile(final String histogramLoggingFile)
    {
        super.histogramLoggingFile(histogramLoggingFile);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration histogramHandler(final HistogramHandler histogramHandler)
    {
        super.histogramHandler(histogramHandler);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration agentNamePrefix(final String agentNamePrefix)
    {
        super.agentNamePrefix(agentNamePrefix);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration printAeronStreamIdentifiers(final boolean printAeronStreamIdentifiers)
    {
        super.printAeronStreamIdentifiers(printAeronStreamIdentifiers);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration epochNanoClock(final EpochNanoClock epochNanoClock)
    {
        super.epochNanoClock(epochNanoClock);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration inboundLibraryStream(final int inboundLibraryStream)
    {
        super.inboundLibraryStream(inboundLibraryStream);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration outboundLibraryStream(final int outboundLibraryStream)
    {
        super.outboundLibraryStream(outboundLibraryStream);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration threadFactory(final ThreadFactory threadFactory)
    {
        super.threadFactory(threadFactory);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration validateCompIdsOnEveryMessage(final boolean validateCompIdsOnEveryMessage)
    {
        super.validateCompIdsOnEveryMessage(validateCompIdsOnEveryMessage);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration validateTimeStrictly(final boolean validateTimeStrictly)
    {
        super.validateTimeStrictly(validateTimeStrictly);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration sessionEpochFractionFormat(final EpochFractionFormat sessionEpochFractionFormat)
    {
        super.sessionEpochFractionFormat(sessionEpochFractionFormat);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration maxFixPKeepaliveTimeoutInMs(final long maxFixpKeepaliveTimeoutInMs)
    {
        super.maxFixPKeepaliveTimeoutInMs(maxFixpKeepaliveTimeoutInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration noEstablishFixPTimeoutInMs(final long noEstablishFixPTimeoutInMs)
    {
        super.noEstablishFixPTimeoutInMs(noEstablishFixPTimeoutInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration fixPAcceptedSessionMaxRetransmissionRange(
        final int fixPAcceptedSessionMaxRetransmissionRange)
    {
        super.fixPAcceptedSessionMaxRetransmissionRange(fixPAcceptedSessionMaxRetransmissionRange);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public EngineConfiguration resendRequestController(final ResendRequestController resendRequestController)
    {
        super.resendRequestController(resendRequestController);
        return this;
    }

    // ------------------------
    // END INHERITED SETTERS
    // ------------------------

    // ---------------------
    // BEGIN GETTERS
    // ---------------------

    /**
     * See {@link #cancelOnDisconnectTimeoutHandler(CancelOnDisconnectTimeoutHandler)} for details.
     *
     * @return the cancel on disconnect timeout handler
     */
    public CancelOnDisconnectTimeoutHandler cancelOnDisconnectTimeoutHandler()
    {
        return cancelOnDisconnectTimeoutHandler;
    }

    /**
     * See {@link #fixPCancelOnDisconnectTimeoutHandler(FixPCancelOnDisconnectTimeoutHandler)} for details.
     *
     * @return the FIXP cancel on disconnect timeout handler
     */
    public FixPCancelOnDisconnectTimeoutHandler fixPCancelOnDisconnectTimeoutHandler()
    {
        return fixPCancelOnDisconnectTimeoutHandler;
    }

    /**
     * See {@link #receiverBufferSize(int)} for details.
     *
     * @return the receiver buffer size
     */
    public int receiverBufferSize()
    {
        return receiverBufferSize;
    }

    /**
     * See {@link #receiverSocketBufferSize(int)} for details.
     *
     * @return the receiver socket buffer size
     */
    public int receiverSocketBufferSize()
    {
        return receiverSocketBufferSize;
    }

    public int senderSocketBufferSize()
    {
        return senderSocketBufferSize;
    }

    public boolean hasBindAddress()
    {
        return host != null;
    }

    public InetSocketAddress bindAddress()
    {
        return new InetSocketAddress(host, port);
    }

    public boolean bindAtStartup()
    {
        return this.bindAtStartup;
    }

    public String logFileDir()
    {
        return logFileDir;
    }

    public int replayIndexFileRecordCapacity()
    {
        return replayIndexFileRecordCapacity;
    }

    public int replayIndexSegmentRecordCapacity()
    {
        return replayIndexSegmentRecordCapacity;
    }

    public int loggerCacheSetSize()
    {
        return loggerCacheSetSize;
    }

    public int loggerCacheNumSets()
    {
        return loggerCacheNumSets;
    }

    public boolean logInboundMessages()
    {
        return logInboundMessages;
    }

    public boolean logOutboundMessages()
    {
        return logOutboundMessages;
    }

    public boolean printStartupWarnings()
    {
        return printStartupWarnings;
    }

    boolean logAnyMessages()
    {
        return logInboundMessages || logOutboundMessages;
    }

    public boolean isReproductionEnabled()
    {
        return reproductionConfiguration != null;
    }

    public ReproductionMessageHandler reproductionMessageHandler()
    {
        return reproductionMessageHandler;
    }

    boolean requiresAeronArchive()
    {
        return logAnyMessages() || isReproductionEnabled();
    }

    public boolean canReplayInbound()
    {
        return logInboundMessages() || isReproductionEnabled();
    }

    public boolean logAllMessages()
    {
        return logInboundMessages && logOutboundMessages;
    }

    public IdleStrategy framerIdleStrategy()
    {
        return framerIdleStrategy;
    }

    public IdleStrategy archiverIdleStrategy()
    {
        return archiverIdleStrategy;
    }

    public int outboundLibraryFragmentLimit()
    {
        return outboundLibraryFragmentLimit;
    }

    public int replayFragmentLimit()
    {
        return replayFragmentLimit;
    }

    public int inboundBytesReceivedLimit()
    {
        return inboundBytesReceivedLimit;
    }

    public MappedFile sentSequenceNumberIndex()
    {
        return sentSequenceNumberIndex;
    }

    public AtomicBuffer sentSequenceNumberBuffer()
    {
        return sentSequenceNumberBuffer;
    }

    public MappedFile receivedSequenceNumberIndex()
    {
        return receivedSequenceNumberIndex;
    }

    public AtomicBuffer receivedSequenceNumberBuffer()
    {
        return receivedSequenceNumberBuffer;
    }

    public MappedFile sessionIdBuffer()
    {
        return sessionIdBuffer;
    }

    public MappedFile fixPIdBuffer()
    {
        if (fixPBuffer == null)
        {
            fixPBuffer = mapFile(DEFAULT_FIXP_ID_FILE, sessionIdBufferSize);
        }
        return fixPBuffer;
    }

    /**
     * Gets the SessionIdGenerator used for generating unique session identifiers.
     * If not explicitly set, returns a default IncrementalSessionIdGenerator.
     *
     * @return the session ID generator
     */
    public SessionIdGenerator sessionIdGenerator()
    {
        if (sessionIdGenerator == null)
        {
            sessionIdGenerator = new IncrementalSessionIdGenerator();
        }
        return sessionIdGenerator;
    }

    /**
     * Sets a custom SessionIdGenerator for generating session identifiers.
     * This allows for distributed session ID generation strategies like Snowflake IDs.
     *
     * @param sessionIdGenerator the session ID generator to use
     * @return this configuration for method chaining
     */
    public EngineConfiguration sessionIdGenerator(final SessionIdGenerator sessionIdGenerator)
    {
        this.sessionIdGenerator = sessionIdGenerator;
        return this;
    }

    public Set<String> gapfillOnReplayMessageTypes()
    {
        return gapfillOnReplayMessageTypes;
    }

    public IntHashSet gapfillOnRetransmitILinkTemplateIds()
    {
        return gapfillOnRetransmitILinkTemplateIds;
    }

    public int senderMaxBytesInBuffer()
    {
        return senderMaxBytesInBuffer;
    }

    public int noLogonDisconnectTimeoutInMs()
    {
        return noLogonDisconnectTimeoutInMs;
    }

    public SessionPersistenceStrategy sessionPersistenceStrategy()
    {
        return sessionPersistenceStrategy;
    }

    public EngineScheduler scheduler()
    {
        return scheduler;
    }

    public long slowConsumerTimeoutInMs()
    {
        return slowConsumerTimeoutInMs;
    }

    public ReplayHandler replayHandler()
    {
        return replayHandler;
    }

    public FixPRetransmitHandler fixPRetransmitHandler()
    {
        return fixPRetransmitHandler;
    }

    public InitialAcceptedSessionOwner initialAcceptedSessionOwner()
    {
        return initialAcceptedSessionOwner;
    }

    public AuthenticationStrategy authenticationStrategy()
    {
        return authenticationStrategy;
    }

    public FixPAuthenticationStrategy fixPAuthenticationStrategy()
    {
        return fixPAuthenticationStrategy;
    }

    public long indexFileStateFlushTimeoutInMs()
    {
        return indexFileStateFlushTimeoutInMs;
    }

    public FixDictionary acceptorfixDictionary()
    {
        return acceptorfixDictionary;
    }

    public Map<String, FixDictionary> acceptorFixDictionaryOverrides()
    {
        return acceptorFixDictionaryOverrides;
    }

    public boolean deleteLogFileDirOnStart()
    {
        return deleteLogFileDirOnStart;
    }

    public long authenticationTimeoutInMs()
    {
        return authenticationTimeoutInMs;
    }

    public int initialSequenceIndex()
    {
        return initialSequenceIndex;
    }

    public int maxConcurrentSessionReplays()
    {
        return maxConcurrentSessionReplays;
    }

    public int replayPositionBufferSize()
    {
        return replayPositionBufferSize;
    }

    public long duplicateEngineTimeoutInMs()
    {
        return duplicateEngineTimeoutInMs;
    }

    public boolean errorIfDuplicateEngineDetected()
    {
        return errorIfDuplicateEngineDetected;
    }

    public boolean acceptsFixP()
    {
        return acceptorFixPProtocol != null;
    }

    public FixPProtocolType supportedFixPProtocolType()
    {
        return acceptsFixP() ? acceptorFixPProtocol : ILINK_3;
    }

    public AeronArchive.Context aeronArchiveContext()
    {
        return archiveContext;
    }

    public Aeron.Context aeronContextClone()
    {
        return aeronContextClone;
    }

    public AeronArchive.Context archiveContextClone()
    {
        return archiveContextClone;
    }

    public int outboundReplayStream()
    {
        return outboundReplayStream;
    }

    public int archiveReplayStream()
    {
        return archiveReplayStream;
    }

    public boolean acceptedSessionClosedResendInterval()
    {
        return acceptedSessionClosedResendInterval;
    }

    public int acceptedSessionResendRequestChunkSize()
    {
        return acceptedSessionResendRequestChunkSize;
    }

    public boolean acceptedSessionSendRedundantResendRequests()
    {
        return acceptedSessionSendRedundantResendRequests;
    }

    public boolean acceptedEnableLastMsgSeqNumProcessed()
    {
        return acceptedEnableLastMsgSeqNumProcessed;
    }

    public MessageTimingHandler messageTimingHandler()
    {
        return messageTimingHandler;
    }

    public int inboundAdminStream()
    {
        return inboundAdminStream;
    }

    public int outboundAdminStream()
    {
        return outboundAdminStream;
    }

    public int throttleWindowInMs()
    {
        return throttleWindowInMs;
    }

    public int throttleLimitOfMessages()
    {
        return throttleLimitOfMessages;
    }

    public long timeIndexReplayFlushIntervalInNs()
    {
        return timeIndexReplayFlushIntervalInNs;
    }

    public CancelOnDisconnectOption cancelOnDisconnectOption()
    {
        return cancelOnDisconnectOption;
    }

    public int cancelOnDisconnectTimeoutWindowInMs()
    {
        return cancelOnDisconnectTimeoutWindowInMs;
    }

    public long framerCycleThresholdNs()
    {
        return framerCycleThresholdNs;
    }

    public long indexerCycleThresholdNs()
    {
        return indexerCycleThresholdNs;
    }

    public boolean indexChecksumEnabled()
    {
        return indexChecksumEnabled;
    }

    public EngineReproductionConfiguration reproductionConfiguration()
    {
        return reproductionConfiguration;
    }

    public boolean writeReproductionLog()
    {
        return writeReproductionLog;
    }

    public int reproductionLogStream()
    {
        return reproductionLogStream;
    }

    public int reproductionReplayStream()
    {
        return reproductionReplayStream;
    }

    // ---------------------
    // END GETTERS
    // ---------------------

    public EngineConfiguration conclude()
    {
        super.conclude("engine");

        if (isReproductionEnabled())
        {
            if (reproductionConfiguration.clock() != epochNanoClock())
            {
                throw new IllegalArgumentException("Do no set the nano clock when using reproduction mode");
            }

            if (writeReproductionLog())
            {
                throw new IllegalArgumentException(
                    "Do not set writeReproductionLog(true) when using reproduction mode");
            }

            bindAtStartup(false);
        }

        if (libraryAeronChannel() == null)
        {
            throw new IllegalArgumentException("Missing required configuration: library aeron channel");
        }

        if (bindAtStartup() && !hasBindAddress())
        {
            throw new IllegalArgumentException("If you're setting EngineConfiguration.bindAtStartup() then you must " +
                "also specify an address to bind to using EngineConfiguration.bindTo(host,port)");
        }

        if (receiverBufferSize() < sessionBufferSize())
        {
            throw new IllegalArgumentException(String.format(
                "You cannot set the receiverBufferSize(%d) < sessionBufferSize(%d)." +
                    "this would allow you to encode messages that are larger than you can read.",
                receiverBufferSize(),
                sessionBufferSize()));
        }

        if (acceptsFixP() && !logAllMessages())
        {
            throw new IllegalArgumentException("FIXP acceptor is not supported without logging messages");
        }

        if (deleteLogFileDirOnStart())
        {
            final File logFileDir = new File(logFileDir());
            if (logFileDir.exists())
            {
                IoUtil.delete(logFileDir, false);
            }
        }

        if (sentSequenceNumberIndex() == null)
        {
            sentSequenceNumberIndex = mapFile(DEFAULT_SEQUENCE_NUMBERS_SENT_FILE, sequenceNumberIndexSize);
        }

        if (sentSequenceNumberBuffer() == null)
        {
            sentSequenceNumberBuffer = new UnsafeBuffer(new byte[sequenceNumberIndexSize]);
        }

        if (receivedSequenceNumberIndex() == null)
        {
            receivedSequenceNumberIndex = mapFile(DEFAULT_SEQUENCE_NUMBERS_RECEIVED_FILE, sequenceNumberIndexSize);
        }

        if (receivedSequenceNumberBuffer() == null)
        {
            receivedSequenceNumberBuffer = new UnsafeBuffer(new byte[sequenceNumberIndexSize]);
        }

        if (sessionIdBuffer() == null)
        {
            sessionIdBuffer = mapFile(DEFAULT_SESSION_ID_FILE, sessionIdBufferSize);
        }

        if (sessionPersistenceStrategy() == null)
        {
            sessionPersistenceStrategy(alwaysTransient());
        }

        if (lookupDefaultAcceptorfixDictionary && acceptorfixDictionary() == null)
        {
            acceptorfixDictionary(FixDictionary.findDefault());
        }

        aeronContextClone = aeronContext().clone();
        archiveContextClone = archiveContext.clone();

        return this;
    }

    private MappedFile mapFile(final String file, final int size)
    {
        return MappedFile.map(logFileDir() + File.separator + file, size);
    }

    public String libraryAeronChannel()
    {
        return libraryAeronChannel;
    }

    public TcpChannelSupplier channelSupplier()
    {
        return channelSupplierFactory.apply(this);
    }

    public boolean isRelevantStreamId(final int streamId)
    {
        return (streamId == outboundLibraryStream() && logOutboundMessages()) ||
            (streamId == inboundLibraryStream() && logInboundMessages());
    }

    public void close()
    {
        CloseHelper.close(sentSequenceNumberIndex);
        CloseHelper.close(receivedSequenceNumberIndex);
        CloseHelper.close(sessionIdBuffer);
        CloseHelper.close(fixPBuffer);
    }
}
