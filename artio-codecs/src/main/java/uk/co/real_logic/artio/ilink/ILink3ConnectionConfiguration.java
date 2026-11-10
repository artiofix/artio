/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.ilink;

import org.agrona.Verify;
import uk.co.real_logic.artio.fixp.FixPConnection;

import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;

/**
 * Configuration object for connecting to an iLink3 connection.
 *
 * NB: This is an experimental API and is subject to change or potentially removal.
 */
public final class ILink3ConnectionConfiguration
{
    public static final int DEFAULT_MAX_RETRANSMIT_QUEUE_SIZE = 1024 * 1024 * 128;
    public static final int DEFAULT_REQUESTED_KEEP_ALIVE_INTERVAL = 10_000;
    public static final int DEFAULT_RETRANSMIT_TIMEOUT_IN_MS = 30_000;
    public static final int KEEP_ALIVE_INTERVAL_MAX_VALUE = 65534;
    public static final long AUTOMATIC_INITIAL_SEQUENCE_NUMBER = -1L;

    public static final String HOST_PROP_NAME = "host";
    public static final String PORT_PROP_NAME = "port";
    public static final String SESSION_ID_PROP_NAME = "session_id";
    public static final String FIRM_ID_PROP_NAME = "firm_id";
    public static final String USER_KEY_PROP_NAME = "user_key";
    public static final String ACCESS_KEY_ID_PROP_NAME = "access_key_id";
    public static final String HOST_2 = "host_2";

    public static final String REQUESTED_KEEP_ALIVE_INTERVAL_IN_MS_PROP_NAME = "requestedKeepAliveIntervalInMs";
    public static final String INITIAL_SENT_SEQUENCE_NUMBER_PROP_NAME = "initialSentSequenceNumber";
    public static final String INITIAL_RECEIVED_SEQUENCE_NUMBER_PROP_NAME = "initialReceivedSequenceNumber";
    public static final String RE_ESTABLISH_LAST_SESSION_PROP_NAME = "re_establish_last_session";

    private final String host;
    private final int port;
    private final String sessionId;
    private final String firmId;
    private final String tradingSystemName;
    private final String tradingSystemVersion;
    private final String tradingSystemVendor;
    private final int requestedKeepAliveIntervalInMs;
    private final String userKey;
    private final long initialSentSequenceNumber;
    private final long initialReceivedSequenceNumber;
    private final String accessKeyId;
    private final boolean reEstablishLastConnection;
    private final ILink3ConnectionHandler handler;
    private final boolean useBackupHost;
    private final String backupHost;
    private final int maxRetransmitQueueSize;
    private final int retransmitNotificationTimeoutInMs;

    /**
     * Load the ILink3SessionConfiguration from a properties file.
     *
     * @param properties the properties object to load from.
     * @return the builder initialised with the provided properties.
     */
    public static ILink3ConnectionConfiguration.Builder fromProperties(final Properties properties)
    {
        final Builder builder = builder()
            .host(properties.getProperty(HOST_PROP_NAME))
            .port(parseInt(properties.getProperty(PORT_PROP_NAME)))
            .sessionId(properties.getProperty(SESSION_ID_PROP_NAME))
            .firmId(properties.getProperty(FIRM_ID_PROP_NAME))
            .userKey(properties.getProperty(USER_KEY_PROP_NAME))
            .accessKeyId(properties.getProperty(ACCESS_KEY_ID_PROP_NAME))
            .backupHost(properties.getProperty(HOST_2));

        getIfPresent(properties, v -> builder.requestedKeepAliveIntervalInMs(parseInt(v)),
            REQUESTED_KEEP_ALIVE_INTERVAL_IN_MS_PROP_NAME);
        getLongIfPresent(properties, builder::initialSentSequenceNumber,
            INITIAL_SENT_SEQUENCE_NUMBER_PROP_NAME);
        getLongIfPresent(properties, builder::initialReceivedSequenceNumber,
            INITIAL_RECEIVED_SEQUENCE_NUMBER_PROP_NAME);
        getIfPresent(properties, v -> builder.reEstablishLastConnection(parseBoolean(v)),
            RE_ESTABLISH_LAST_SESSION_PROP_NAME);

        return builder;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    private static void getIfPresent(final Properties properties, final Consumer<String> setter, final String propName)
    {
        final String value = properties.getProperty(propName);
        if (value != null)
        {
            setter.accept(value);
        }
    }

    private static void getLongIfPresent(final Properties properties, final LongConsumer setter, final String propName)
    {
        final String value = properties.getProperty(propName);
        if (value != null)
        {
            setter.accept(parseLong(value));
        }
    }

    public String host()
    {
        return host;
    }

    public int port()
    {
        return port;
    }

    public String sessionId()
    {
        return sessionId;
    }

    public String firmId()
    {
        return firmId;
    }

    public String tradingSystemName()
    {
        return tradingSystemName;
    }

    public String tradingSystemVersion()
    {
        return tradingSystemVersion;
    }

    public String tradingSystemVendor()
    {
        return tradingSystemVendor;
    }

    public int requestedKeepAliveIntervalInMs()
    {
        return requestedKeepAliveIntervalInMs;
    }

    public String userKey()
    {
        return userKey;
    }

    public long initialSentSequenceNumber()
    {
        return initialSentSequenceNumber;
    }

    public long initialReceivedSequenceNumber()
    {
        return initialReceivedSequenceNumber;
    }

    public boolean reEstablishLastConnection()
    {
        return reEstablishLastConnection;
    }

    public ILink3ConnectionHandler handler()
    {
        return handler;
    }

    public String accessKeyId()
    {
        return accessKeyId;
    }

    public String backupHost()
    {
        return backupHost;
    }

    public boolean useBackupHost()
    {
        return useBackupHost;
    }

    public int maxRetransmitQueueSize()
    {
        return maxRetransmitQueueSize;
    }

    public int retransmitNotificationTimeoutInMs()
    {
        return retransmitNotificationTimeoutInMs;
    }

    public int retransmitRequestMessageLimit()
    {
        return 2500;
    }

    private void validate()
    {
        Verify.notNull(host, "host");
        Verify.notNull(sessionId, "sessionId");
        Verify.notNull(firmId, "firmId");
        Verify.notNull(userKey, "userKey");
        Verify.notNull(accessKeyId, "accessKeyId");
        Verify.notNull(handler, "handler");

        if (useBackupHost)
        {
            Verify.notNull(backupHost, "backupHost");
        }

        if (requestedKeepAliveIntervalInMs <= 0)
        {
            throw new IllegalArgumentException("requestedKeepAliveInterval must be positive, but is: " +
                requestedKeepAliveIntervalInMs);
        }

        if (port <= 0)
        {
            throw new IllegalArgumentException("port must be positive, but is: " + port);
        }

        if (maxRetransmitQueueSize <= 0)
        {
            throw new IllegalArgumentException(
                "maxRetransmitQueueSize must be positive, but is: " + maxRetransmitQueueSize);
        }

        if (retransmitNotificationTimeoutInMs <= 0)
        {
            throw new IllegalArgumentException(
                "retransmitNotificationTimeoutInMs must be positive, but is: " + retransmitNotificationTimeoutInMs);
        }
    }

    @Override
    public String toString()
    {
        return "ILink3SessionConfiguration{" +
            "host='" + host + '\'' +
            ", port=" + port +
            ", sessionId='" + sessionId + '\'' +
            ", firmId='" + firmId + '\'' +
            ", tradingSystemName='" + tradingSystemName + '\'' +
            ", tradingSystemVersion='" + tradingSystemVersion + '\'' +
            ", tradingSystemVendor='" + tradingSystemVendor + '\'' +
            ", keepAliveInterval=" + requestedKeepAliveIntervalInMs +
            ", initialSentSequenceNumber=" + initialSentSequenceNumber +
            ", accessKeyId=" + accessKeyId +
            ", handler=" + handler +
            ", maxRetransmitQueueSize=" + maxRetransmitQueueSize +
            ", retransmitNotificationTimeoutInMs=" + retransmitNotificationTimeoutInMs +
            '}';
    }

    private ILink3ConnectionConfiguration(
        final String host,
        final int port,
        final String sessionId,
        final String firmId,
        final String tradingSystemName,
        final String tradingSystemVersion,
        final String tradingSystemVendor,
        final int requestedKeepAliveIntervalInMs,
        final String userKey,
        final long initialSentSequenceNumber,
        final long initialReceivedSequenceNumber,
        final String accessKeyId,
        final boolean reEstablishLastConnection,
        final ILink3ConnectionHandler handler,
        final boolean useBackupHost,
        final String backupHost,
        final int maxRetransmitQueueSize,
        final int retransmitNotificationTimeoutInMs)
    {
        this.host = host;
        this.port = port;
        this.sessionId = sessionId;
        this.firmId = firmId;
        this.tradingSystemName = tradingSystemName;
        this.tradingSystemVersion = tradingSystemVersion;
        this.tradingSystemVendor = tradingSystemVendor;
        this.requestedKeepAliveIntervalInMs = requestedKeepAliveIntervalInMs;
        this.userKey = userKey;
        this.initialSentSequenceNumber = initialSentSequenceNumber;
        this.initialReceivedSequenceNumber = initialReceivedSequenceNumber;
        this.accessKeyId = accessKeyId;

        this.reEstablishLastConnection = reEstablishLastConnection;
        this.handler = handler;
        this.useBackupHost = useBackupHost;
        this.backupHost = backupHost;
        this.maxRetransmitQueueSize = maxRetransmitQueueSize;
        this.retransmitNotificationTimeoutInMs = retransmitNotificationTimeoutInMs;

        validate();
    }

    public static final class Builder
    {
        private String host;
        private int port;
        private String sessionId;
        private String firmId;
        private String tradingSystemName = "Artio";
        private String tradingSystemVersion = "1.0";
        private String tradingSystemVendor = "Monotonic";
        private int requestedKeepAliveIntervalInMs = DEFAULT_REQUESTED_KEEP_ALIVE_INTERVAL;
        private String userKey;
        private long initialSentSequenceNumber = AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
        private long initialReceivedSequenceNumber = AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
        private String accessKeyId;
        private boolean reEstablishLastConnection = false;
        private ILink3ConnectionHandler handler;
        private boolean useBackupHost;
        private String backupHost;
        private int maxRetransmitQueueSize = DEFAULT_MAX_RETRANSMIT_QUEUE_SIZE;
        private int retransmitNotificationTimeoutInMs = DEFAULT_RETRANSMIT_TIMEOUT_IN_MS;

        public ILink3ConnectionConfiguration build()
        {
            return new ILink3ConnectionConfiguration(
                host,
                port,
                sessionId,
                firmId,
                tradingSystemName,
                tradingSystemVersion,
                tradingSystemVendor,
                requestedKeepAliveIntervalInMs,
                userKey,
                initialSentSequenceNumber,
                initialReceivedSequenceNumber,
                accessKeyId,
                reEstablishLastConnection,
                handler,
                useBackupHost,
                backupHost,
                maxRetransmitQueueSize,
                retransmitNotificationTimeoutInMs);
        }

        /**
         * Sets the host IP to connect to.
         *
         * @param host the host IP to connect to.
         * @return this
         */
        public Builder host(final String host)
        {
            this.host = host;
            return this;
        }

        /**
         * Sets the host port to connect to.
         *
         * @param port the host port to connect to.
         * @return this
         */
        public Builder port(final int port)
        {
            this.port = port;
            return this;
        }

        /**
         * Sets the sessionId used in the Negotiate and Establish messages.
         *
         * @param sessionId the sessionId used in the Negotiate and Establish messages.
         * @return this
         */
        public Builder sessionId(final String sessionId)
        {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the firmId used in the Negotiate and Establish messages.
         *
         * @param firmId the firmId used in the Negotiate and Establish messages.
         * @return this
         */
        public Builder firmId(final String firmId)
        {
            this.firmId = firmId;
            return this;
        }

        /**
         * Sets the tradingSystemName used in the Establish message.
         *
         * @param tradingSystemName the tradingSystemName used in the Establish message.
         * @return this
         */
        public Builder tradingSystemName(final String tradingSystemName)
        {
            this.tradingSystemName = tradingSystemName;
            return this;
        }

        /**
         * Sets the tradingSystemVersion used in the Establish message.
         *
         * @param tradingSystemVersion the tradingSystemVersion used in the Establish message.
         * @return this
         */
        public Builder tradingSystemVersion(final String tradingSystemVersion)
        {
            this.tradingSystemVersion = tradingSystemVersion;
            return this;
        }

        /**
         * Sets the tradingSystemVendor used in the Establish message.
         *
         * @param tradingSystemVendor the tradingSystemVendor used in the Establish message.
         * @return this
         */
        public Builder tradingSystemVendor(final String tradingSystemVendor)
        {
            this.tradingSystemVendor = tradingSystemVendor;
            return this;
        }

        /**
         * Sets the keepAliveInterval used in the Establish message. This is specified in milliseconds and
         * used as a timeout period by the iLink3 session. Sequence messages will be used to keep the session alive
         * after this interval passes and sessions will be terminated if twice this length passes without a reply
         * from the exchange.
         *
         * @param requestedKeepAliveIntervalInMs the keepAliveInterval used in the Establish message.
         * @return this
         */
        public Builder requestedKeepAliveIntervalInMs(final int requestedKeepAliveIntervalInMs)
        {
            if (requestedKeepAliveIntervalInMs > KEEP_ALIVE_INTERVAL_MAX_VALUE)
            {
                throw new IllegalArgumentException(
                    "Invalid requestedKeepAliveIntervalInMs: " + requestedKeepAliveIntervalInMs +
                    " cannot be larger than " + KEEP_ALIVE_INTERVAL_MAX_VALUE);
            }
            this.requestedKeepAliveIntervalInMs = requestedKeepAliveIntervalInMs;
            return this;
        }

        /**
         * Sets the key id used by the HMAC encryption. This is the ID for the {@link #userKey(String)} parameter.
         * This key id will be generated by the CME and can be downloaded from their Request Center along with the
         * user key.
         *
         * @param accessKeyId the key id used by the HMAC encryption.
         * @return this
         * @see #userKey(String)
         */
        public Builder accessKeyId(final String accessKeyId)
        {
            this.accessKeyId = accessKeyId;
            return this;
        }

        /**
         * Sets the key used to by the HMAC encryption in the Negotiate and Establish messages. This key will be
         * generated by the CME and can be downloaded from their Request Center. It may also be referred as
         * "the secret key".
         *
         * @param userKey the key used to by the HMAC encryption in the Negotiate and Establish messages.
         * @return this
         */
        public Builder userKey(final String userKey)
        {
            this.userKey = userKey;
            return this;
        }

        /**
         * Sets the sequence number that is sent by your session upon session establishment. The default is
         * AUTOMATIC_INITIAL_SEQUENCE_NUMBER which will start from 1 if this is a new UUID or continuing from the
         * last known sequence number if {@link #reEstablishLastConnection(boolean)} is set to true.
         *
         * @param initialSentSequenceNumber the sequence that is sent by your session upon session establishment
         * @return this
         */
        public Builder initialSentSequenceNumber(final long initialSentSequenceNumber)
        {
            this.initialSentSequenceNumber = initialSentSequenceNumber;
            return this;
        }

        /**
         * Sets the sequence number that is expected by your session upon session establishment from the exchange.
         * The default is AUTOMATIC_INITIAL_SEQUENCE_NUMBER which will start from 1 if this is a new UUID or continuing
         * from the last known sequence number if {@link #reEstablishLastConnection(boolean)} is set to true.
         *
         * @param initialReceivedSequenceNumber the sequence number that is expected by your session upon session
         *                                      establishment from the exchange
         * @return this
         */
        public Builder initialReceivedSequenceNumber(final long initialReceivedSequenceNumber)
        {
            this.initialReceivedSequenceNumber = initialReceivedSequenceNumber;
            return this;
        }

        /**
         * Enable a re-establishment of the same session with the same UUID, rather than generating a new UUID.
         * If there is an existing UUID associated with this session identifier then that will be used. The
         * session identifier here is a triple of (port, host and accessKeyId).
         * <p>
         * Note: if this session has never connected before then a new UUID will be generated.
         *
         * @param reEstablishLastConnection true to re-establish the connection, false otherwise.
         * @return this.
         */
        public Builder reEstablishLastConnection(final boolean reEstablishLastConnection)
        {
            this.reEstablishLastConnection = reEstablishLastConnection;
            return this;
        }

        /**
         * Sets your callback handler in order to receive business / application level messages from the exchange.
         *
         * @param handler your callback handler in order to receive business / application level messages from the
         *                exchange.
         * @return this
         */
        public Builder handler(final ILink3ConnectionHandler handler)
        {
            this.handler = handler;
            return this;
        }

        /**
         * Sets whether you're connecting to the normal primary or the backup IP address. Used in failover scenarios.
         *
         * @param useBackupHost true to connect to the backup host
         * @return this
         */
        public Builder useBackupHost(final boolean useBackupHost)
        {
            this.useBackupHost = useBackupHost;
            return this;
        }

        /**
         * Sets the backup host IP to connect to.
         *
         * @param backupHost the backup host IP to connect to.
         * @return this
         */
        public Builder backupHost(final String backupHost)
        {
            this.backupHost = backupHost;
            return this;
        }

        /**
         * Sets the maximum size for the retransmit queue. This is an in-memory on heap buffer that is used to queue
         * received messages from a server that were sent out of order whilst a retransmit is occurring. The maximum
         * allows users to stop OOME from occuring in the case of large retransmits.
         *
         * @param maxRetransmitQueueSize the maximum size for the retransmit queue.
         * @return this
         */
        public Builder maxRetransmitQueueSizeInBytes(final int maxRetransmitQueueSize)
        {
            this.maxRetransmitQueueSize = maxRetransmitQueueSize;
            return this;
        }

        /**
         * Sets a timeout used in retransmit operations. The timeout is started when a retransmit request is sent. If
         * this timeout is breached then the {@link ILink3ConnectionHandler#onRetransmitTimeout(FixPConnection)}
         * method will be invoked. This notification could be used to cancel the retransmit or inform operators,
         * traders or algorithms that it's taking a while to get sequence numbers back into sync. The timeout
         * doesn't not in and of itself cancel the retransmit request - just call the callback.
         *
         * @param retransmitNotificationTimeoutInMs timeout for receiving a notification when retransmit operations
         *                                          take too long to be filled.
         * @return this
         */
        public Builder retransmitNotificationTimeoutInMs(final int retransmitNotificationTimeoutInMs)
        {
            this.retransmitNotificationTimeoutInMs = retransmitNotificationTimeoutInMs;
            return this;
        }
    }
}
