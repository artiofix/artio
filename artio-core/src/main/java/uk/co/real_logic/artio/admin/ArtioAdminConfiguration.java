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
package uk.co.real_logic.artio.admin;

import io.aeron.Aeron;
import org.agrona.Strings;
import org.agrona.SystemUtil;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.OffsetEpochNanoClock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.lang.Integer.getInteger;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.CommonConfiguration.backoffIdleStrategy;

/**
 * Configuration class used to configure an {@link ArtioAdmin} instance.
 * <p>
 * If you're using the default configuration for your {@link uk.co.real_logic.artio.engine.FixEngine} then it is only
 * necessary to ensure that the {@link #aeronChannel(String)} is configured correctly - the other defaults should
 * work out of the box.
 */
public final class ArtioAdminConfiguration
{
    /**
     * Property name for the system property to override the inbound admin stream id
     */
    public static final String INBOUND_STREAM_ID_PROP = "fix.admin.inbound_stream_id";

    /**
     * Property name for the system property to override the outbound admin stream id
     */
    public static final String OUTBOUND_STREAM_ID_PROP = "fix.admin.outbound_stream_id";

    /**
     * Property name for the system property to override the aeron channel
     */
    public static final String AERON_CHANNEL_PROP = "fix.admin.aeron_channel";

    /**
     * Property name for the system property to override the connect timeout.
     */
    public static final String CONNECT_TIMEOUT_PROP = "fix.admin.connect_timeout";

    public static final int DEFAULT_INBOUND_ADMIN_STREAM_ID = 21;
    public static final int DEFAULT_OUTBOUND_ADMIN_STREAM_ID = 22;

    private final AtomicBoolean isConcluded = new AtomicBoolean(false);
    private final Aeron.Context aeronContext = new Aeron.Context();

    private IdleStrategy idleStrategy;
    private EpochNanoClock epochNanoClock;
    private int inboundAdminStream = getInteger(INBOUND_STREAM_ID_PROP, DEFAULT_INBOUND_ADMIN_STREAM_ID);
    private int outboundAdminStream = getInteger(OUTBOUND_STREAM_ID_PROP, DEFAULT_OUTBOUND_ADMIN_STREAM_ID);
    private String aeronChannel = System.getProperty(AERON_CHANNEL_PROP, IPC_CHANNEL);
    private long replyTimeoutInNs = TimeUnit.MILLISECONDS.toNanos(DEFAULT_REPLY_TIMEOUT_IN_MS);
    private long connectTimeoutNs = SystemUtil.getDurationInNanos(CONNECT_TIMEOUT_PROP, TimeUnit.SECONDS.toNanos(5));

    /**
     * Sets the {@link IdleStrategy} used by blocking Admin operations.
     *
     * @param idleStrategy the {@link IdleStrategy}.
     * @return this
     */
    public ArtioAdminConfiguration idleStrategy(final IdleStrategy idleStrategy)
    {
        this.idleStrategy = idleStrategy;
        return this;
    }

    /**
     * Set the stream id from an admin API to a FIX Engine.  This should be the same as
     * the configured {@link uk.co.real_logic.artio.engine.EngineConfiguration#inboundAdminStream(int)} value
     * on your FIXEngine instance.
     *
     * @param inboundAdminStream the stream id from an admin API to a FIX Engine.
     * @return this
     */
    public ArtioAdminConfiguration inboundAdminStream(final int inboundAdminStream)
    {
        this.inboundAdminStream = inboundAdminStream;
        return this;
    }

    /**
     * Set the stream id from a FIX Engine to an admin API. This should be the same as
     * the configured {@link uk.co.real_logic.artio.engine.EngineConfiguration#outboundAdminStream(int)} value
     * on your FIXEngine instance.
     *
     * @param outboundAdminStream the stream id from a FIX Engine to an admin API.
     * @return this
     */
    public ArtioAdminConfiguration outboundAdminStream(final int outboundAdminStream)
    {
        this.outboundAdminStream = outboundAdminStream;
        return this;
    }

    /**
     * Sets the aeron channel that {@link ArtioAdmin} will use to communicate with the
     * {@link uk.co.real_logic.artio.engine.FixEngine} instance. This should be the same as
     * the configured {@link uk.co.real_logic.artio.engine.EngineConfiguration#libraryAeronChannel(String)} value
     * on your FIXEngine instance.
     *
     * @param aeronChannel the aeron channel to communicate with the FixEngine instance.
     * @return this
     */
    public ArtioAdminConfiguration aeronChannel(final String aeronChannel)
    {
        this.aeronChannel = aeronChannel;
        return this;
    }

    /**
     * The timeout to use for operations to the {@link uk.co.real_logic.artio.engine.FixEngine}. If a reply hasn't
     * been received within this time period then the operation will be considered a failure and return with an
     * exception being thrown.
     *
     * @param replyTimeoutInNs the timeout for operations to the Fix Engine.
     * @return this
     */
    public ArtioAdminConfiguration replyTimeoutInNs(final long replyTimeoutInNs)
    {
        this.replyTimeoutInNs = replyTimeoutInNs;
        return this;
    }

    /**
     * The timeout to for establishing connection to the {@link uk.co.real_logic.artio.engine.FixEngine}.
     *
     * @param connectTimeoutNs for connecting to the Fix Engine.
     * @return this
     */
    public ArtioAdminConfiguration connectTimeoutNs(final long connectTimeoutNs)
    {
        this.connectTimeoutNs = connectTimeoutNs;
        return this;
    }

    /**
     * Sets the clock used in order to calculate reply timeouts.
     *
     * @param epochNanoClock the clock used in order to calculate reply timeouts.
     * @return this
     */
    public ArtioAdminConfiguration epochNanoClock(final EpochNanoClock epochNanoClock)
    {
        this.epochNanoClock = epochNanoClock;
        return this;
    }

    public Aeron.Context aeronContext()
    {
        return aeronContext;
    }

    public IdleStrategy idleStrategy()
    {
        return idleStrategy;
    }

    public EpochNanoClock epochNanoClock()
    {
        return epochNanoClock;
    }

    public int inboundAdminStream()
    {
        return inboundAdminStream;
    }

    public int outboundAdminStream()
    {
        return outboundAdminStream;
    }

    public String aeronChannel()
    {
        return aeronChannel;
    }

    public long replyTimeoutInNs()
    {
        return replyTimeoutInNs;
    }

    public long connectTimeoutNs()
    {
        return connectTimeoutNs;
    }

    void conclude()
    {
        if (isConcluded.compareAndSet(false, true))
        {
            if (idleStrategy() == null)
            {
                idleStrategy(backoffIdleStrategy());
            }

            if (epochNanoClock() == null)
            {
                epochNanoClock(new OffsetEpochNanoClock());
            }

            if (connectTimeoutNs < 0)
            {
                throw new IllegalArgumentException("connectTimeoutNs cannot be negative: " + connectTimeoutNs);
            }

            if (Strings.isEmpty(aeronContext.clientName()))
            {
                aeronContext.clientName("fix-artio-admin");
            }
        }
        else
        {
            throw new IllegalStateException(
                "This configuration has already been concluded, are you trying to re-use it?");
        }
    }
}
