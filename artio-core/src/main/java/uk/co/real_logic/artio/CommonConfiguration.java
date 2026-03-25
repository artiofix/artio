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
package uk.co.real_logic.artio;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.session.ResendRequestController;
import uk.co.real_logic.artio.session.SessionCustomisationStrategy;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.timing.HistogramHandler;
import uk.co.real_logic.artio.util.MessageTypeEncoding;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.Verify;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.agrona.concurrent.errors.DistinctErrorLog;
import org.agrona.concurrent.errors.ErrorConsumer;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Integer.getInteger;
import static java.lang.System.getProperty;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;
import static uk.co.real_logic.artio.LivenessDetector.SEND_INTERVAL_FRACTION;

/**
 * Common configuration for both the Fix Engine and Library. Some options are configurable via
 * commandline properties. Setters override commandline properties, not the other way around.
 * <p>
 * See setters or properties for documentation of what specific configuration options do.
 *
 * @see uk.co.real_logic.artio.engine.EngineConfiguration
 * @see uk.co.real_logic.artio.library.LibraryConfiguration
 */
public class CommonConfiguration
{
    // ------------------------------------------------
    //          Configuration Properties
    // ------------------------------------------------

    /**
     * Property name for length of the memory mapped buffers for the counters file
     */
    public static final String MONITORING_BUFFERS_LENGTH_PROPERTY = "fix.monitoring.length";
    /**
     * Property name for directory of the conductor buffers
     */
    public static final String MONITORING_FILE_PROPERTY = "fix.monitoring.file";
    /**
     * Property name for the flag to enable or disable debug logging
     */
    public static final String DEBUG_PRINT_MESSAGES_PROPERTY = "fix.core.debug";
    /**
     * Property name for the flag to enable or disable printing of Aeron stream identifiers and registration
     * identifiers.
     */
    public static final String DEBUG_PRINT_AERON_STREAM_IDENTIFIERS_PROPERTY = "fix.core.debug.print_aeron_stream_ids";
    /**
     * Property name for the flag to specify a subset of message types to debug print, this can be used in
     * conjunction with the FIX_MESSAGE logtag. Note: this message type filter only applies to valid fix messages.
     */
    public static final String DEBUG_PRINT_MESSAGE_TYPES_PROPERTY = "fix.core.debug.msg_types";
    /**
     * Property name for the flag to specify a thread to print
     */
    public static final String DEBUG_PRINT_THREAD_PROPERTY = "fix.core.debug.thread";
    /**
     * Property name for the flag to enable or disable flushing of writes
     */
    public static final String FORCE_WRITES_MESSAGES_PROPERTY = "fix.core.flush";
    /**
     * Property name for the flag to set the maximum number of attempts to claim a message
     * slot on the inbound stream.
     */
    public static final String INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY = "fix.core.inbound_max_claims";
    /**
     * Property name for the flag to set the maximum number of attempts to claim a message
     * slot on the outbound stream.
     */
    public static final String OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY = "fix.core.outbound_max_claims";
    /**
     * Property name for the flag to enable or disable message timing
     */
    public static final String TIME_MESSAGES_PROPERTY = "fix.core.timing";
    /**
     * Property name for the file to log debug messages to, default is standard output
     */
    public static final String DEBUG_FILE_PROPERTY = "fix.core.debug.file";
    /**
     * Property name for the period at which histogram intervals are polled and logged
     */
    public static final String HISTOGRAM_POLL_PERIOD_IN_MS_PROPERTY = "fix.benchmark.histogram_poll_period";
    /**
     * Property name for the file to which histogram intervals are logged
     */
    public static final String HISTOGRAM_LOGGING_FILE_PROPERTY = "fix.benchmark.histogram_file";

    /**
     * Property name for character to separate debug logging of FIX messages
     */
    public static final String LOGGING_SEPARATOR_PROPERTY = "fix.core.debug.separator";
    public static final int NO_FIXP_MAX_RETRANSMISSION_RANGE = 0;
    public static final ResendRequestController DEFAULT_RESEND_REQUEST_CONTROLLER =
        (session, resendRequest, correctedEndSeqNo, response) -> response.resend();

    protected ThreadFactory threadFactory;
    private int fixPAcceptedSessionMaxRetransmissionRange = NO_FIXP_MAX_RETRANSMISSION_RANGE;

    public static boolean getBoolean(final String propertyName, final boolean defaultValue)
    {
        final String propertyValue = getProperty(propertyName);
        if (propertyValue == null)
        {
            return defaultValue;
        }

        return Boolean.parseBoolean(propertyValue);
    }

    public static void validateTimeout(final long timeoutInMs)
    {
        if (timeoutInMs <= 0)
        {
            throw new IllegalArgumentException(String.format(
                "Timeout must be > 0, but was configured as %d",
                timeoutInMs));
        }
    }

    // ------------------------------------------------
    //          Static Configuration
    // ------------------------------------------------

    /**
     * These are static final fields in order to give the optimiser more scope
     */
    public static final boolean DEBUG_PRINT_MESSAGES;
    public static final LongHashSet DEBUG_PRINT_MESSAGE_TYPES;
    public static final Set<LogTag> DEBUG_TAGS;
    public static final String DEBUG_PRINT_THREAD;
    public static final byte DEFAULT_DEBUG_LOGGING_SEPARATOR = '\001';
    public static final byte DEBUG_LOGGING_SEPARATOR;

    static
    {
        final String debugPrintMessagesValue = getProperty(DEBUG_PRINT_MESSAGES_PROPERTY);
        boolean debugPrintMessages = false;
        Set<LogTag> debugTags = Collections.emptySet();
        if (debugPrintMessagesValue != null)
        {
            if ("all".equalsIgnoreCase(debugPrintMessagesValue) || "true".equalsIgnoreCase(debugPrintMessagesValue))
            {
                debugPrintMessages = true;
                debugTags = EnumSet.allOf(LogTag.class);
            }
            else
            {
                try
                {
                    debugTags = Stream
                        .of(debugPrintMessagesValue.split(","))
                        .map(CommonConfiguration::lookupLogTag)
                        .collect(toCollection(() -> EnumSet.noneOf(LogTag.class)));

                    debugPrintMessages = !debugTags.isEmpty();
                }
                catch (final IllegalArgumentException ignore)
                {
                    // parse error in valueOf();
                }
            }
        }

        final String debugPrintThreadValue = getProperty(DEBUG_PRINT_THREAD_PROPERTY);
        DEBUG_PRINT_THREAD = debugPrintThreadValue == null ? null : debugPrintThreadValue + " : ";
        DEBUG_PRINT_MESSAGES = debugPrintMessages;
        DEBUG_TAGS = debugTags;

        final String loggingSeparator = getProperty(LOGGING_SEPARATOR_PROPERTY);
        DEBUG_LOGGING_SEPARATOR =
            loggingSeparator == null ? DEFAULT_DEBUG_LOGGING_SEPARATOR : (byte)loggingSeparator.charAt(0);

        final String debugPrintMessageTypes = getProperty(DEBUG_PRINT_MESSAGE_TYPES_PROPERTY);
        if (debugPrintMessageTypes == null)
        {
            DEBUG_PRINT_MESSAGE_TYPES = null;
        }
        else
        {
            DEBUG_PRINT_MESSAGE_TYPES = Stream
                .of(debugPrintMessageTypes.split(","))
                .map(MessageTypeEncoding::packMessageType)
                .collect(Collectors.toCollection(LongHashSet::new));
        }
    }

    private static LogTag lookupLogTag(final String name)
    {
        switch (name)
        {
            case "ILINK_SESSION": return LogTag.FIXP_SESSION;
            case "ILINK_BUSINESS": return LogTag.FIXP_BUSINESS;
            default: return LogTag.valueOf(name);
        }
    }

    public static final String DEBUG_FILE = System.getProperty(DEBUG_FILE_PROPERTY);
    public static final boolean TIME_MESSAGES = Boolean.getBoolean(TIME_MESSAGES_PROPERTY);
    public static final boolean FORCE_WRITES = Boolean.getBoolean(FORCE_WRITES_MESSAGES_PROPERTY);

    public static final int BACKOFF_SPINS = Integer.getInteger("fix.core.spins", 100);
    public static final int BACKOFF_YIELDS = Integer.getInteger("fix.core.yields", 100);

    // ------------------------------------------------
    //          Configuration Defaults
    // ------------------------------------------------

    public static final int DEFAULT_MONITORING_BUFFER_LENGTH = 4 * 1024 * 1024;
    public static final String DEFAULT_DIRECTORY = optimalTmpDirName() + File.separator + "fix-%s";
    public static final String DEFAULT_MONITORING_FILE = DEFAULT_DIRECTORY + File.separator + "monitoring";

    public static final String DEFAULT_HISTOGRAM_LOGGING_FILE = DEFAULT_DIRECTORY + File.separator + "histograms";
    public static final String DEFAULT_NAME_PREFIX = "";
    public static final int DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S = 3;
    public static final long DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS =
        SECONDS.toMillis(DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_S);

    public static final int DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS = BACKOFF_SPINS + BACKOFF_YIELDS + 1000;
    public static final int DEFAULT_OUTBOUND_MAX_CLAIM_ATTEMPTS = DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS;

    public static final int DEFAULT_SESSION_BUFFER_SIZE = 16 * 1024;
    public static final long DEFAULT_SENDING_TIME_WINDOW = MINUTES.toMillis(2);
    public static final int DEFAULT_HEARTBEAT_INTERVAL_IN_S = 10;
    public static final int NO_FORCED_HEARTBEAT_INTERVAL = -1;

    public static final long DEFAULT_REPLY_TIMEOUT_IN_MS = 10_000L;
    public static final long DEFAULT_HISTOGRAM_POLL_PERIOD_IN_MS = MINUTES.toMillis(1);

    public static final int DEFAULT_INBOUND_LIBRARY_STREAM = 1;
    public static final int DEFAULT_OUTBOUND_LIBRARY_STREAM = 2;

    public static final long DEFAULT_MAX_FIXP_KEEPALIVE_TIMEOUT_IN_MS = MINUTES.toMillis(1);
    public static final long DEFAULT_MIN_FIXP_KEEPALIVE_TIMEOUT_IN_MS = 1;
    public static final long DEFAULT_ACCEPTOR_FIXP_KEEPALIVE_TIMEOUT_IN_MS = SECONDS.toMillis(30);

    public static final long DEFAULT_CYCLE_THRESHOLD_NS = SECONDS.toNanos(1);

    public static final boolean RUNNING_ON_WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private long reasonableTransmissionTimeInMs = DEFAULT_REASONABLE_TRANSMISSION_TIME_IN_MS;
    private boolean printAeronStreamIdentifiers = Boolean.getBoolean(DEBUG_PRINT_AERON_STREAM_IDENTIFIERS_PROPERTY);
    private EpochNanoClock epochNanoClock = new OffsetEpochNanoClock();
    private ErrorHandlerFactory errorHandlerFactory = ErrorHandlerFactory.saveDistinctErrors();
    private MonitoringAgentFactory monitoringAgentFactory = MonitoringAgentFactory.printDistinctErrors();
    private IdleStrategy monitoringThreadIdleStrategy = backoffIdleStrategy();
    private long sendingTimeWindowInMs = DEFAULT_SENDING_TIME_WINDOW;
    private SessionIdStrategy sessionIdStrategy = SessionIdStrategy.senderAndTarget();
    private MessageValidationStrategy messageValidationStrategy = MessageValidationStrategy.none();
    private SessionCustomisationStrategy sessionCustomisationStrategy = SessionCustomisationStrategy.none();
    private int monitoringBuffersLength = getInteger(
        MONITORING_BUFFERS_LENGTH_PROPERTY, DEFAULT_MONITORING_BUFFER_LENGTH);
    private String monitoringFile = null;
    private long replyTimeoutInMs = DEFAULT_REPLY_TIMEOUT_IN_MS;
    private final Aeron.Context aeronContext = new Aeron.Context();
    private int sessionBufferSize = DEFAULT_SESSION_BUFFER_SIZE;
    private int inboundMaxClaimAttempts =
        getInteger(INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY, DEFAULT_INBOUND_MAX_CLAIM_ATTEMPTS);
    private int outboundMaxClaimAttempts =
        getInteger(OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY, DEFAULT_OUTBOUND_MAX_CLAIM_ATTEMPTS);
    private int defaultHeartbeatIntervalInS = DEFAULT_HEARTBEAT_INTERVAL_IN_S;
    private long histogramPollPeriodInMs =
        Long.getLong(HISTOGRAM_POLL_PERIOD_IN_MS_PROPERTY, DEFAULT_HISTOGRAM_POLL_PERIOD_IN_MS);
    private String histogramLoggingFile = null;
    private HistogramHandler histogramHandler;
    private String agentNamePrefix = DEFAULT_NAME_PREFIX;
    private int inboundLibraryStream = DEFAULT_INBOUND_LIBRARY_STREAM;
    private int outboundLibraryStream = DEFAULT_OUTBOUND_LIBRARY_STREAM;
    private boolean validateCompIdsOnEveryMessage = true;
    private boolean validateTimeStrictly = true;
    private EpochFractionFormat sessionEpochFractionFormat = EpochFractionFormat.MILLISECONDS;
    private long maxFixPKeepaliveTimeoutInMs = DEFAULT_MAX_FIXP_KEEPALIVE_TIMEOUT_IN_MS;
    private long minFixPKeepaliveTimeoutInMs = DEFAULT_MIN_FIXP_KEEPALIVE_TIMEOUT_IN_MS;
    private long acceptorFixPKeepaliveTimeoutInMs = DEFAULT_ACCEPTOR_FIXP_KEEPALIVE_TIMEOUT_IN_MS;
    private long noEstablishFixPTimeoutInMs = EngineConfiguration.DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS;
    private ResendRequestController resendRequestController = DEFAULT_RESEND_REQUEST_CONTROLLER;
    private int forcedHeartbeatIntervalInS = NO_FORCED_HEARTBEAT_INTERVAL;
    private boolean disableHeartbeatRepliesToTestRequests = false;

    private final AtomicBoolean isConcluded = new AtomicBoolean(false);

    // ------------------------
    // BEGIN SETTERS
    // ------------------------

    /**
     * Sets the sending time window. The sending time window is the period of acceptance
     * delta between the current time on the Fix Library thread and the sending time
     * received in messages. Sessions are disconnected if the sending time diverges by
     * more than this window and if validation is enabled.
     *
     * This is also used for validating acceptor FIXP message timestamps.
     *
     * @param sendingTimeWindowInMs the current sending time in milliseconds
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration sendingTimeWindowInMs(final long sendingTimeWindowInMs)
    {
        this.sendingTimeWindowInMs = sendingTimeWindowInMs;
        return this;
    }

    /**
     * Set the default interval for heartbeats if not exchanged upon logon. Specified in seconds.
     *
     * @param value the default interval for heartbeats if not exchanged upon logon. Specified in seconds.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration defaultHeartbeatIntervalInS(final int value)
    {
        defaultHeartbeatIntervalInS = value;
        return this;
    }

    /**
     * Set an interval for heartbeats, forcing an override of the timeout agreement at the exchange of logon messages.
     * This configuration option is useful
     * for testing the behaviour of other client's heartbeat behaviour, or for working around buggy counter-parties.
     * If you want to mostly disable heartbeats in order to test counter-parties then this can be set to a very long
     * time period, for example a week (604800 seconds). This configuration option doesn't alter the HeartBtInt (108)
     * field value of the Logon message.
     *
     * See also the related {@link #disableHeartbeatRepliesToTestRequests(boolean)} option.
     *
     * Note this option should be set to the same value for connecting {@link EngineConfiguration} and
     *  {@link uk.co.real_logic.artio.library.LibraryConfiguration} configurations.
     *
     * @param forcedHeartbeatIntervalInS the interval for heartbeats, forcing an override of the logon message.
     *                                  Specified in seconds.
     * @return this
     */
    public CommonConfiguration forcedHeartbeatIntervalInS(final int forcedHeartbeatIntervalInS)
    {
        this.forcedHeartbeatIntervalInS = forcedHeartbeatIntervalInS;
        return this;
    }

    /**
     * Disables heartbeats in response to test requests. This configuration option is useful
     * for testing the behaviour of other client's heartbeat behaviour or simulating disconnects.
     *
     * See also the related {@link #forcedHeartbeatIntervalInS(int)} option.
     *
     * Note this option should be set to the same value for connecting {@link EngineConfiguration} and
     *  {@link uk.co.real_logic.artio.library.LibraryConfiguration} configurations.
     *
     * @param disableHeartbeatRepliesToTestRequests true to disable, false (the default) to respond to test requests.
     * @return this
     */
    public CommonConfiguration disableHeartbeatRepliesToTestRequests(
        final boolean disableHeartbeatRepliesToTestRequests)
    {
        this.disableHeartbeatRepliesToTestRequests = disableHeartbeatRepliesToTestRequests;
        return this;
    }

    /**
     * Sets the session id strategy.
     *
     * @param sessionIdStrategy the session id strategy.
     * @return this
     * @see SessionIdStrategy
     */
    public CommonConfiguration sessionIdStrategy(final SessionIdStrategy sessionIdStrategy)
    {
        this.sessionIdStrategy = sessionIdStrategy;
        return this;
    }

    /**
     * Sets the session customisation strategy of the FIX Library,
     * see {@link SessionCustomisationStrategy} for details.
     * <p>
     * This only needs to be set if this FIX Library is the acceptor library.
     *
     * @param sessionCustomisationStrategy the session customisation strategy to use.
     * @return this
     */
    public CommonConfiguration sessionCustomisationStrategy(
        final SessionCustomisationStrategy sessionCustomisationStrategy)
    {
        this.sessionCustomisationStrategy = sessionCustomisationStrategy;
        return this;
    }

    /**
     * Sets the message validation strategy of the FIX Library,
     * see {@link MessageValidationStrategy} for details.
     *
     * @param messageValidationStrategy the message validation strategy to use.
     * @return this
     */
    public CommonConfiguration messageValidationStrategy(final MessageValidationStrategy messageValidationStrategy)
    {
        this.messageValidationStrategy = messageValidationStrategy;
        return this;
    }

    public CommonConfiguration reasonableTransmissionTimeInMs(final long reasonableTransmissionTimeInMs)
    {
        this.reasonableTransmissionTimeInMs = reasonableTransmissionTimeInMs;
        return this;
    }

    /**
     * Sets the length of the buffer used for monitoring counters.
     *
     * @param monitoringBuffersLength the length of the buffer used for monitoring counters.
     * @return this
     * @see CommonConfiguration#MONITORING_BUFFERS_LENGTH_PROPERTY
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration monitoringBuffersLength(final Integer monitoringBuffersLength)
    {
        this.monitoringBuffersLength = monitoringBuffersLength;
        return this;
    }

    /**
     * Sets the location for the monitoring file.
     *
     * @param monitoringFile the location for the monitoring file.
     * @return this
     * @see CommonConfiguration#MONITORING_FILE_PROPERTY
     */
    public CommonConfiguration monitoringFile(final String monitoringFile)
    {
        this.monitoringFile = monitoringFile;
        return this;
    }

    /**
     * The approach of setting a custom error consumer combined with this print flag has been deprecated in
     * a combination of {@link #errorHandlerFactory(ErrorHandlerFactory)}
     * and {@link #monitoringAgentFactory(MonitoringAgentFactory)}.
     * It will be removed in a future version.
     *
     * Sets the printing of error messages on or off. Error messages are always logged in an error buffer that
     * can be scanned by another diagnostic process, this simply switches on or off the printing these errors on
     * standard out.
     * <p>
     * Default: true
     *
     * @param printErrorMessages the printing of error messages.
     * @return this
     */
    @Deprecated
    public CommonConfiguration printErrorMessages(final boolean printErrorMessages)
    {
        if (!printErrorMessages)
        {
            monitoringAgentFactory(MonitoringAgentFactory.none());
        }
        return this;
    }

    /**
     * The approach of setting a custom error consumer has been deprecated in favour of using
     * a combination of {@link #errorHandlerFactory(ErrorHandlerFactory)}
     * and {@link #monitoringAgentFactory(MonitoringAgentFactory)}.
     * It will be removed in a future version.
     *
     * @param customErrorConsumer a custom error consumer to print values out
     * @return this
     */
    @Deprecated
    public CommonConfiguration customErrorConsumer(final ErrorConsumer customErrorConsumer)
    {
        monitoringAgentFactory(MonitoringAgentFactory.consumeDistinctErrors(customErrorConsumer));
        return this;
    }

    /**
     * By default errors within Artio are stored into a {@link DistinctErrorLog} and also printed out. This
     * method allows the configuration of a custom {@link ErrorHandler} implementation that
     * can be used to integrate into custom logging configuration. Often used in combination with
     * the {@link #monitoringAgentFactory(MonitoringAgentFactory)} configuration option.
     *
     * See {@link ErrorHandlerFactory#saveDistinctErrors()} for the default value and an example.
     *
     * @param errorHandlerFactory the factory for creating a custom error handler
     * @return this
     */
    public CommonConfiguration errorHandlerFactory(final ErrorHandlerFactory errorHandlerFactory)
    {
        this.errorHandlerFactory = errorHandlerFactory;
        return this;
    }

    /**
     * By default an implementation is provided that prints out errors from an {@link DistinctErrorLog}
     * and also prints out any {@link AeronArchive} errors. This method allows the configuration of alternative
     * error monitoring strategies on the monitoring thread. Often used in combination with
     * the {@link #errorHandlerFactory(ErrorHandlerFactory)} configuration option.
     *
     * For example if you want to save errors to disk but not print them on standard error then just set
     * this to {@link MonitoringAgentFactory#none()}. Other factory methods are on this interface.
     *
     * @param monitoringAgentFactory the factory for setting the
     * @return this
     */
    public CommonConfiguration monitoringAgentFactory(final MonitoringAgentFactory monitoringAgentFactory)
    {
        this.monitoringAgentFactory = monitoringAgentFactory;
        return this;
    }

    /**
     * Sets the idle strategy for the Error Printer thread.
     *
     * @param errorPrinterIdleStrategy the idle strategy for the Error Printer thread.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration monitoringThreadIdleStrategy(final IdleStrategy errorPrinterIdleStrategy)
    {
        this.monitoringThreadIdleStrategy = errorPrinterIdleStrategy;
        return this;
    }

    /**
     * Sets the reply timeout in milliseconds.
     * <p>
     * This is the timeout for control protocol messages between the FIX Gateway and FIX Library instances.
     *
     * @param replyTimeoutInMs the reply timeout in milliseconds.
     * @return this
     */
    public CommonConfiguration replyTimeoutInMs(final long replyTimeoutInMs)
    {
        this.replyTimeoutInMs = replyTimeoutInMs;
        return this;
    }

    /**
     * Sets the inbound max claim attempts.
     *
     * @param inboundMaxClaimAttempts the inbound max claim attempts
     * @return this
     * @see CommonConfiguration#INBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration inboundMaxClaimAttempts(final int inboundMaxClaimAttempts)
    {
        this.inboundMaxClaimAttempts = inboundMaxClaimAttempts;
        return this;
    }

    /**
     * Sets the outbound max claim attempts.
     *
     * @param outboundMaxClaimAttempts the outbound max claim attempts
     * @return this
     * @see CommonConfiguration#OUTBOUND_MAX_CLAIM_ATTEMPTS_PROPERTY
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration outboundMaxClaimAttempts(final int outboundMaxClaimAttempts)
    {
        this.outboundMaxClaimAttempts = outboundMaxClaimAttempts;
        return this;
    }

    /**
     * Sets the session's encoding buffer size. The session buffer is a buffer used by each Session to encode messages
     * via
     * {@link uk.co.real_logic.artio.session.Session#trySend(uk.co.real_logic.artio.builder.Encoder)}.
     *
     * This is also used as the size of buffer for messages that are sent by the Session management system itself.
     *
     * @param bufferSize the session's encoding buffer size
     * @return this
     */
    public CommonConfiguration sessionBufferSize(final int bufferSize)
    {
        this.sessionBufferSize = bufferSize;
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration histogramPollPeriodInMs(final long histogramPollPeriodInMs)
    {
        this.histogramPollPeriodInMs = histogramPollPeriodInMs;
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration histogramLoggingFile(final String histogramLoggingFile)
    {
        this.histogramLoggingFile = histogramLoggingFile;
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration histogramHandler(final HistogramHandler histogramHandler)
    {
        this.histogramHandler = histogramHandler;
        return this;
    }

    /**
     * Sets the prefix to be used on agent names. This can be used to distinguish two different Artio instances
     * running in the same process, for example in tests.
     *
     * @param agentNamePrefix the prefix to be used on agent names.
     * @return this
     */
    public CommonConfiguration agentNamePrefix(final String agentNamePrefix)
    {
        this.agentNamePrefix = agentNamePrefix;
        return this;
    }

    /**
     * Set to true to print out the mapping between aeron stream identifiers (sessionIds) and the usage of given
     * Aeron publication and subscription objects within Artio. See {@link StreamInformation} for details.
     *
     * This can be helpful when debugging issues related to back-pressure and trying to correlate Artio internal
     * concepts to Aeron Counters. Defaults to false.
     *
     * @param printAeronStreamIdentifiers true to print, false otherwise.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration printAeronStreamIdentifiers(final boolean printAeronStreamIdentifiers)
    {
        this.printAeronStreamIdentifiers = printAeronStreamIdentifiers;
        return this;
    }

    /**
     * Sets the clock used for producing requestTimestamp fields on iLink3 messages.
     *
     * @param epochNanoClock the clock used for producing requestTimestamp fields on iLink3 messages.
     * @return this
     */
    public CommonConfiguration epochNanoClock(final EpochNanoClock epochNanoClock)
    {
        this.epochNanoClock = epochNanoClock;
        return this;
    }

    /**
     * Set the Aeron stream id to be used for the inbound stream, aka coming from counter-parties to the library.
     *
     * @param inboundLibraryStream the aeron stream id
     * @return this
     */
    public CommonConfiguration inboundLibraryStream(final int inboundLibraryStream)
    {
        this.inboundLibraryStream = inboundLibraryStream;
        return this;
    }

    /**
     * Set the Aeron stream id to be used for the outbound stream, aka coming from the library and going out to
     * counter-parties.
     *
     * @param outboundLibraryStream the aeron stream id
     * @return this
     */
    public CommonConfiguration outboundLibraryStream(final int outboundLibraryStream)
    {
        this.outboundLibraryStream = outboundLibraryStream;
        return this;
    }

    /**
     * Sets factory for threads such as framer, archivingRunner, etc in EngineScheduler
     * @param threadFactory factory for custom thread creating
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration threadFactory(final ThreadFactory threadFactory)
    {
        this.threadFactory = threadFactory;
        return this;
    }

    /**
     * Set to true in order to check that the sender, target comp ids (including sub and location) are the same on
     * every message as the logon message. This can be disabled for performance reasons.
     *
     * @param validateCompIdsOnEveryMessage true to validate comp ids
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration validateCompIdsOnEveryMessage(final boolean validateCompIdsOnEveryMessage)
    {
        this.validateCompIdsOnEveryMessage = validateCompIdsOnEveryMessage;
        return this;
    }

    /**
     * Set to true in order to validate that time from sender corresponds to FIX time format.
     * See http://fixwiki.org/fixwiki/UTCTimestampDataType for details.
     *
     * @param validateTimeStrictly true to validate time matches format
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration validateTimeStrictly(final boolean validateTimeStrictly)
    {
        this.validateTimeStrictly = validateTimeStrictly;
        return this;
    }

    /**
     * Sets the time precision that the session logic uses to encode time stamps.
     *
     * @param sessionEpochFractionFormat the format to use.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration sessionEpochFractionFormat(final EpochFractionFormat sessionEpochFractionFormat)
    {
        Verify.notNull(sessionEpochFractionFormat, "sessionEpochFractionFormat");
        this.sessionEpochFractionFormat = sessionEpochFractionFormat;
        return this;
    }

    /**
     * Sets the maximum keep alive timeout in milliseconds that can be agreed by a FIXP connection's logon exchange.
     *
     * NB: this only affects the keepalive for the counter-party, use {@link #acceptorFixPKeepaliveTimeoutInMs(long)}
     * for setting your own keepalive interval as an acceptor.
     *
     * @param maxFixpKeepaliveTimeoutInMs the maximum keep alive timeout.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration maxFixPKeepaliveTimeoutInMs(final long maxFixpKeepaliveTimeoutInMs)
    {
        this.maxFixPKeepaliveTimeoutInMs = maxFixpKeepaliveTimeoutInMs;
        return this;
    }

    /**
     * Sets the minimum keep alive timeout in milliseconds that can be agreed by a FIXP connection's logon exchange.
     *
     * NB: this only affects the keepalive for the counter-party, use {@link #acceptorFixPKeepaliveTimeoutInMs(long)}
     * for setting your own keepalive interval as an acceptor.
     *
     * @param minFixPKeepaliveTimeoutInMs the minimum keep alive timeout.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration minFixPKeepaliveTimeoutInMs(final long minFixPKeepaliveTimeoutInMs)
    {
        this.minFixPKeepaliveTimeoutInMs = minFixPKeepaliveTimeoutInMs;
        return this;
    }

    /**
     * Sets the minimum keep alive timeout in milliseconds that can be agreed by a FIXP connection's logon exchange.
     *
     * @param acceptorFixPKeepaliveTimeoutInMs the minimum keep alive timeout.
     * @return this
     */
    @SuppressWarnings("UnusedReturnValue")
    public CommonConfiguration acceptorFixPKeepaliveTimeoutInMs(final long acceptorFixPKeepaliveTimeoutInMs)
    {
        this.acceptorFixPKeepaliveTimeoutInMs = acceptorFixPKeepaliveTimeoutInMs;
        return this;
    }

    /**
     * Sets the timeout in milliseconds before a FIXP TCP connection is closed if no establish message is sent.
     * This is analogous to {@link EngineConfiguration#noLogonDisconnectTimeoutInMs(int)} for FIX.
     *
     * @param noEstablishFixPTimeoutInMs the time before a FIXP connection is closed if no establish message is sent.
     * @return this
     */
    public CommonConfiguration noEstablishFixPTimeoutInMs(final long noEstablishFixPTimeoutInMs)
    {
        this.noEstablishFixPTimeoutInMs = noEstablishFixPTimeoutInMs;
        return this;
    }

    /**
     * Sets the maximum count allowed in a FIXP retransmit request. This is only used for accepted FIXP sessions,
     * such as Binary Entrypoint.
     *
     * @param fixPAcceptedSessionMaxRetransmissionRange the maximum count allowed in a FIXP retransmit request.
     * @return this
     */
    public CommonConfiguration fixPAcceptedSessionMaxRetransmissionRange(
        final int fixPAcceptedSessionMaxRetransmissionRange)
    {
        this.fixPAcceptedSessionMaxRetransmissionRange = fixPAcceptedSessionMaxRetransmissionRange;
        return this;
    }

    /**
     * Configures how a resend request is responded to. We default to responding to any valid resend request. See
     * Javadoc on {@link ResendRequestController} for details of how to implement it.
     *
     *
     * @param resendRequestController the controller to use.
     * @return this
     */
    public CommonConfiguration resendRequestController(final ResendRequestController resendRequestController)
    {
        this.resendRequestController = resendRequestController;
        return this;
    }

    // ------------------------
    // END SETTERS
    // ------------------------

    // ------------------------
    // BEGIN GETTERS
    // ------------------------

    public long sendingTimeWindowInMs()
    {
        return sendingTimeWindowInMs;
    }

    public int defaultHeartbeatIntervalInS()
    {
        return defaultHeartbeatIntervalInS;
    }

    public int forcedHeartbeatIntervalInS()
    {
        return forcedHeartbeatIntervalInS;
    }

    public boolean disableHeartbeatRepliesToTestRequests()
    {
        return disableHeartbeatRepliesToTestRequests;
    }

    public long reasonableTransmissionTimeInMs()
    {
        return reasonableTransmissionTimeInMs;
    }

    public long noEstablishFixPTimeoutInMs()
    {
        return noEstablishFixPTimeoutInMs;
    }

    public long maxFixPKeepaliveTimeoutInMs()
    {
        return maxFixPKeepaliveTimeoutInMs;
    }

    public long minFixPKeepaliveTimeoutInMs()
    {
        return minFixPKeepaliveTimeoutInMs;
    }

    public long acceptorFixPKeepaliveTimeoutInMs()
    {
        return acceptorFixPKeepaliveTimeoutInMs;
    }

    public Aeron.Context aeronContext()
    {
        return aeronContext;
    }

    public MonitoringAgentFactory monitoringAgentFactory()
    {
        return monitoringAgentFactory;
    }

    public ErrorHandlerFactory errorHandlerFactory()
    {
        return errorHandlerFactory;
    }

    public IdleStrategy monitoringThreadIdleStrategy()
    {
        return monitoringThreadIdleStrategy;
    }

    public SessionIdStrategy sessionIdStrategy()
    {
        return sessionIdStrategy;
    }

    public SessionCustomisationStrategy sessionCustomisationStrategy()
    {
        return sessionCustomisationStrategy;
    }

    public MessageValidationStrategy messageValidationStrategy()
    {
        return messageValidationStrategy;
    }

    public int monitoringBuffersLength()
    {
        return monitoringBuffersLength;
    }

    public String monitoringFile()
    {
        return monitoringFile;
    }

    public long replyTimeoutInMs()
    {
        return replyTimeoutInMs;
    }

    public long connectAttemptTimeoutInMs()
    {
        return replyTimeoutInMs() / SEND_INTERVAL_FRACTION;
    }

    public long histogramPollPeriodInMs()
    {
        return histogramPollPeriodInMs;
    }

    public int inboundMaxClaimAttempts()
    {
        return inboundMaxClaimAttempts;
    }

    public int outboundMaxClaimAttempts()
    {
        return outboundMaxClaimAttempts;
    }

    public int sessionBufferSize()
    {
        return sessionBufferSize;
    }

    public String histogramLoggingFile()
    {
        return histogramLoggingFile;
    }

    public HistogramHandler histogramHandler()
    {
        return histogramHandler;
    }

    public String agentNamePrefix()
    {
        return agentNamePrefix;
    }

    public boolean printAeronStreamIdentifiers()
    {
        return printAeronStreamIdentifiers;
    }

    public boolean validateCompIdsOnEveryMessage()
    {
        return validateCompIdsOnEveryMessage;
    }

    public boolean validateTimeStrictly()
    {
        return validateTimeStrictly;
    }

    public EpochFractionFormat sessionEpochFractionFormat()
    {
        return sessionEpochFractionFormat;
    }

    public int fixPAcceptedSessionMaxRetransmissionRange()
    {
        return fixPAcceptedSessionMaxRetransmissionRange;
    }

    public ResendRequestController resendRequestController()
    {
        return resendRequestController;
    }

    // ------------------------
    // END GETTERS
    // ------------------------

    protected void conclude(final String fixSuffix)
    {
        if (isConcluded.compareAndSet(false, true))
        {
            if (monitoringFile() == null)
            {
                monitoringFile(getProperty(
                    MONITORING_FILE_PROPERTY, String.format(DEFAULT_MONITORING_FILE, fixSuffix)));
            }

            if (histogramLoggingFile() == null)
            {
                histogramLoggingFile(getProperty(
                    HISTOGRAM_LOGGING_FILE_PROPERTY, String.format(DEFAULT_HISTOGRAM_LOGGING_FILE, fixSuffix)));
            }
        }
        else
        {
            throw new IllegalStateException(
                "This configuration has already been concluded, are you trying to re-use it?");
        }

        if (threadFactory == null)
        {
            threadFactory = Thread::new;
        }
    }

    /**
     * If shared memory is available, use that as a temporary directory,
     * otherwise use the default temp directory
     *
     * @return the optimal temporary directory
     */
    public static String optimalTmpDirName()
    {
        if ("Linux".equalsIgnoreCase(System.getProperty("os.name")))
        {
            final File devShmDir = new File("/dev/shm");

            if (devShmDir.exists())
            {
                return devShmDir.getAbsolutePath();
            }
        }

        return IoUtil.tmpDirName();
    }

    public static IdleStrategy backoffIdleStrategy()
    {
        return new BackoffIdleStrategy(BACKOFF_SPINS, BACKOFF_YIELDS, 1, 1 << 20);
    }

    public EpochNanoClock epochNanoClock()
    {
        return epochNanoClock;
    }

    public int inboundLibraryStream()
    {
        return inboundLibraryStream;
    }

    public int outboundLibraryStream()
    {
        return outboundLibraryStream;
    }

    public ThreadFactory threadFactory()
    {
        return threadFactory;
    }
}
