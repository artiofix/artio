/*
 * Copyright 2015-2019 Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.LangUtil;
import org.agrona.Strings;
import org.agrona.Verify;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;
import uk.co.real_logic.artio.messages.FixMessageDecoder;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_OUTBOUND_REPLAY_STREAM;

/**
 * Prints out FIX messages from an Aeron Stream - designed for integration into logging tools like
 * Splunk.
 *
 * Main method is provided as an example of usage - when integrating into your specific system you should pass in the
 * library aeron channel and stream ids used by your {@link uk.co.real_logic.artio.engine.EngineConfiguration}.
 *
 * Since this class generates Java objects for every message that passes through the system you're recommended to run
 * it in a different process to the normal Artio Engine if you're operating in a latency sensitive environment.
 */
public class FixMessageLogger implements Agent
{
    public static class Configuration
    {
        public static final int DEFAULT_COMPACTION_SIZE = 256 * 1024 * 1024;
        public static final int MAXIMUM_MAXIMUM_BUFFER_SIZE = ExpandableArrayBuffer.MAX_ARRAY_LENGTH - 1;
        public static final int DEFAULT_MAXIMUM_BUFFER_SIZE = ExpandableArrayBuffer.MAX_ARRAY_LENGTH / 2;

        private FixMessageConsumer fixMessageConsumer;
        private Aeron.Context context;
        private boolean ownsAeronClient;
        private Aeron aeron;
        private String libraryAeronChannel = IPC_CHANNEL;
        private int inboundStreamId = DEFAULT_INBOUND_LIBRARY_STREAM;
        private int outboundStreamId = DEFAULT_OUTBOUND_LIBRARY_STREAM;
        private int outboundReplayStreamId = DEFAULT_OUTBOUND_REPLAY_STREAM;
        private int compactionSize = DEFAULT_COMPACTION_SIZE;
        public int maximumBufferSize = DEFAULT_MAXIMUM_BUFFER_SIZE;
        private FixPMessageConsumer fixPMessageConsumer;

        /**
         * Provide a consumer for FIX messages that are logger by the stream.
         *
         * @param fixMessageConsumer the consumer for FIX Messages.
         * @return this
         */
        public Configuration fixMessageConsumer(final FixMessageConsumer fixMessageConsumer)
        {
            this.fixMessageConsumer = fixMessageConsumer;
            return this;
        }

        /**
         * Deprecated: please use {@link #fixPMessageConsumer(FixPMessageConsumer)}.
         *
         * @param iLinkMessageConsumer the handler to provide to receive ILink messages
         * @return this
         */
        @Deprecated
        public Configuration iLinkMessageConsumer(
            final uk.co.real_logic.artio.ilink.ILinkMessageConsumer iLinkMessageConsumer)
        {
            return fixPMessageConsumer(iLinkMessageConsumer);
        }

        /**
         * Set the callback handler to receive FIXP messages.
         *
         * @param fixPMessageConsumer the handler to provide to receive FIXP messages
         * @return this
         */
        public Configuration fixPMessageConsumer(final FixPMessageConsumer fixPMessageConsumer)
        {
            this.fixPMessageConsumer = fixPMessageConsumer;
            return this;
        }

        /**
         * Provide an Aeron context object that is used by default to construct the Aeron client instance used by
         * this FixMessageLogger. If the {@link #aeron(Aeron)} configuration option is used to provide an Aeron
         * object then this configuration option will be ignored. This sets <code>ownsAeronClient(true)</code>
         * as the created Aeron instance will be owned by the FixMessageLogger.
         *
         * @param context the Aeron context object
         * @return this
         */
        public Configuration context(final Aeron.Context context)
        {
            Verify.notNull(context, "context");
            ownsAeronClient(true);
            this.context = context;
            return this;
        }

        /**
         * Aeron client for communicating with the local Media Driver. This overrides any context object provided
         * to {@link #context(Aeron.Context)}. This client will be closed when the FixMessageLogger is closed if
         * {@link #ownsAeronClient(boolean)} is set to true.
         *
         * @param aeron client for communicating with the local Media Driver.
         * @return this
         */
        public Configuration aeron(final Aeron aeron)
        {
            Verify.notNull(aeron, "aeron");
            this.aeron = aeron;
            return this;
        }

        /**
         * Does this FixMessageLogger own the Aeron client and take responsibility for closing it?
         *
         * @param ownsAeronClient does this own the Aeron client and take responsibility for closing it?
         * @return this
         */
        public Configuration ownsAeronClient(final boolean ownsAeronClient)
        {
            this.ownsAeronClient = ownsAeronClient;
            return this;
        }

        /**
         * Provide the Aeron channel used to communicate with library instances by your
         * {@link uk.co.real_logic.artio.engine.FixEngine}. This should be the same value as provided to
         * {@link uk.co.real_logic.artio.engine.EngineConfiguration#libraryAeronChannel(String)}.
         *
         * Defaults to the IPC channel if not configured.
         *
         * @param libraryAeronChannel The Aeron channel used to communicate between engine and library instances
         * @return this
         */
        public Configuration libraryAeronChannel(final String libraryAeronChannel)
        {
            Verify.notNull(libraryAeronChannel, "libraryAeronChannel");
            this.libraryAeronChannel = libraryAeronChannel;
            return this;
        }

        /**
         * Provide the inbound streamId used to communicate between engine and library instances.
         * if you override {@link uk.co.real_logic.artio.engine.EngineConfiguration#inboundLibraryStream(int)} then you
         * should set this to the same value.
         *
         * @param inboundStreamId The inbound streamId used to communicate between engine and library instances
         * @return this
         */
        public Configuration inboundStreamId(final int inboundStreamId)
        {
            this.inboundStreamId = inboundStreamId;
            return this;
        }

        /**
         * Provide the outbound streamId used to communicate between engine and library instances.
         * if you override {@link uk.co.real_logic.artio.engine.EngineConfiguration#outboundLibraryStream(int)} then
         * you should set this to the same value.
         *
         * @param outboundStreamId The outbound streamId used to communicate between engine and library instances
         * @return this
         */
        public Configuration outboundStreamId(final int outboundStreamId)
        {
            this.outboundStreamId = outboundStreamId;
            return this;
        }

        /**
         * Provide the outbound replay streamId used to communicate between engine and library instances.
         * if you override {@link uk.co.real_logic.artio.engine.EngineConfiguration#outboundReplayStream(int)} then
         * you should set this to the same value.
         *
         * @param outboundReplayStreamId The outbound replay streamId used to communicate between engine and library
         *                               instances
         * @return this
         */
        public Configuration outboundReplayStreamId(final int outboundReplayStreamId)
        {
            this.outboundReplayStreamId = outboundReplayStreamId;
            return this;
        }

        /**
         * Provide the compaction size to within the reorder buffer. The FixMessageLogger re-orders its messages
         * internally in order to hand them off the consumer in timestamp order. A larger compaction size allows it's
         * internal reorder buffer to grow larger before compaction is attempted. A larger compaction size results in
         * less compaction and thus less CPU usage at the cost of more memory being consumed.
         *
         * @param compactionSize the compaction size to within the reorder buffer.
         * @return this
         */
        public Configuration compactionSize(final int compactionSize)
        {
            validateCompactionSize(compactionSize);

            this.compactionSize = compactionSize;
            return this;
        }

        /**
         * Sets the maximum size that the internal reorder buffer can grow to. If this is exceeded then all the
         * messages within the reorder buffer are simply dumped out in their current order.
         *
         * @param maximumBufferSize the maximum reorder buffer size in bytes
         * @return this
         */
        public Configuration maximumBufferSize(final int maximumBufferSize)
        {
            validateMaximumBufferSize(maximumBufferSize);

            this.maximumBufferSize = maximumBufferSize;
            return this;
        }

        void conclude()
        {
            Verify.notNull(fixMessageConsumer, "fixMessageConsumer");

            validateMaxAndCompactionSize(maximumBufferSize, compactionSize);

            if (aeron == null)
            {
                if (context == null)
                {
                    context(new Aeron.Context());
                }
                if (Strings.isEmpty(context.clientName()))
                {
                    context.clientName("fix-message-logger");
                }

                aeron = Aeron.connect(context);
            }
        }

        public static void validateMaxAndCompactionSize(final int maximumBufferSize, final int compactionSize)
        {
            if (maximumBufferSize < compactionSize)
            {
                throw new IllegalArgumentException(
                    "maximumBufferSize (" + maximumBufferSize + ") cannot be less than the compactionSize (" +
                        compactionSize + ")");
            }
        }

        public static void validateMaximumBufferSize(final int maximumBufferSize)
        {
            if (maximumBufferSize <= 0)
            {
                throw new IllegalArgumentException("maximumBufferSize must be positive, but is: " + maximumBufferSize);
            }

            if (maximumBufferSize > MAXIMUM_MAXIMUM_BUFFER_SIZE)
            {
                throw new IllegalArgumentException("maximumBufferSize must be <= " + MAXIMUM_MAXIMUM_BUFFER_SIZE +
                    ", but is: " + maximumBufferSize);
            }
        }

        public static void validateCompactionSize(final int compactionSize)
        {
            if (compactionSize <= 0)
            {
                throw new IllegalArgumentException("Compaction size must be positive, but is: " + compactionSize);
            }
        }
    }

    public static void main(final String[] args)
    {
        final AgentRunner runner = start();

        Runtime.getRuntime().addShutdownHook(new Thread(runner::close));
    }

    public static AgentRunner start()
    {
        final Configuration configuration = new Configuration()
            .fixMessageConsumer(FixMessageLogger::print);
        final FixMessageLogger logger = new FixMessageLogger(configuration);

        final AgentRunner runner = new AgentRunner(
            CommonConfiguration.backoffIdleStrategy(),
            Throwable::printStackTrace,
            null,
            logger);

        AgentRunner.startOnThread(runner);

        return runner;
    }

    private static void print(
        final FixMessageDecoder fixMessageDecoder,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final ArtioLogHeader header)
    {
        System.out.printf("%s:stream=%s %s%n", fixMessageDecoder.status(), header.streamId(), fixMessageDecoder.body());
    }

    private final StreamTimestampZipper zipper;
    private final Configuration configuration;
    private volatile boolean closed = false;

    @Deprecated
    public FixMessageLogger(
        final FixMessageConsumer fixMessageConsumer,
        final Aeron.Context context,
        final String libraryAeronChannel,
        final int inboundStreamId,
        final int outboundStreamId,
        final int outboundReplayStreamId)
    {
        this(new Configuration()
            .fixMessageConsumer(fixMessageConsumer)
            .context(context)
            .libraryAeronChannel(libraryAeronChannel)
            .inboundStreamId(inboundStreamId)
            .outboundStreamId(outboundStreamId)
            .outboundReplayStreamId(outboundReplayStreamId));
    }

    public FixMessageLogger(
        final Configuration configuration)
    {
        configuration.conclude();
        this.configuration = configuration;
        final Aeron aeron = configuration.aeron;
        SubscriptionPoller[] pollers = null;

        try
        {
            final String libraryAeronChannel = configuration.libraryAeronChannel;
            pollers = new SubscriptionPoller[]
            {
                newSubscriptionPoller(aeron, libraryAeronChannel, configuration.inboundStreamId),
                newSubscriptionPoller(aeron, libraryAeronChannel, configuration.outboundStreamId),
                newSubscriptionPoller(aeron, IPC_CHANNEL, configuration.outboundReplayStreamId),
            };
        }
        catch (final Throwable t)
        {
            if (configuration.ownsAeronClient)
            {
                aeron.close();
            }
            LangUtil.rethrowUnchecked(t);
        }

        zipper = new StreamTimestampZipper(
            configuration.fixMessageConsumer,
            configuration.fixPMessageConsumer,
            configuration.compactionSize,
            configuration.maximumBufferSize, false,
            pollers);
    }

    private SubscriptionPoller newSubscriptionPoller(
        final Aeron aeron, final String libraryAeronChannel, final int streamId)
    {
        return new SubscriptionPoller(aeron.addSubscription(libraryAeronChannel, streamId));
    }

    public int doWork()
    {
        return zipper.poll(Integer.MAX_VALUE);
    }

    public void onClose()
    {
        if (!closed)
        {
            closed = true;

            if (zipper != null)
            {
                zipper.close();
            }

            if (configuration.ownsAeronClient)
            {
                configuration.aeron.close();
            }
        }
    }

    public String roleName()
    {
        return "FixMessageLogger";
    }

    int bufferPosition()
    {
        return zipper.bufferPosition();
    }

    int bufferCapacity()
    {
        return zipper.bufferCapacity();
    }

    private static final class SubscriptionPoller implements StreamTimestampZipper.Poller
    {
        private final Subscription subscription;

        private SubscriptionPoller(final Subscription subscription)
        {
            this.subscription = subscription;
        }

        public int poll(final FragmentAssembler fragmentAssembler, final int fragmentLimit)
        {
            return subscription.poll(fragmentAssembler, fragmentLimit);
        }

        public int streamId()
        {
            return subscription.streamId();
        }

        public void close()
        {
            subscription.close();
        }

        public boolean isComplete()
        {
            return false;
        }
    }
}
