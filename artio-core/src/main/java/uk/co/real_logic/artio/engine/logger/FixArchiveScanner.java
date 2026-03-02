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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.CommonContext;
import io.aeron.archive.client.AeronArchive;
import org.agrona.Strings;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;

import static uk.co.real_logic.artio.LogTag.ARCHIVE_SCAN;
import static uk.co.real_logic.artio.engine.logger.FixMessageLogger.Configuration.*;

/**
 * Scan the archive for fix messages. Can be combined with predicates to create rich queries.
 *
 * @see FixMessageConsumer
 * @see FixMessagePredicate
 * @see FixMessagePredicates
 */
public class FixArchiveScanner implements AutoCloseable
{
    public static final int DEFAULT_FRAGMENT_LIMIT = 10000;

    static final boolean DEBUG_LOG_ARCHIVE_SCAN = DebugLogger.isEnabled(ARCHIVE_SCAN);

    public static class Configuration
    {
        private int fragmentLimit = DEFAULT_FRAGMENT_LIMIT;
        private String aeronDirectoryName;
        private IdleStrategy idleStrategy;
        private int compactionSize = DEFAULT_COMPACTION_SIZE;
        public int maximumBufferSize = DEFAULT_MAXIMUM_BUFFER_SIZE;
        private String logFileDir;
        private boolean enableIndexScan;
        private AeronArchive.Context archiveContext;

        public Configuration()
        {
        }

        public Configuration aeronDirectoryName(final String aeronDirectoryName)
        {
            this.aeronDirectoryName = aeronDirectoryName;
            return this;
        }

        public String aeronDirectoryName()
        {
            return aeronDirectoryName;
        }

        public Configuration idleStrategy(final IdleStrategy idleStrategy)
        {
            this.idleStrategy = idleStrategy;
            return this;
        }

        public IdleStrategy idleStrategy()
        {
            return idleStrategy;
        }

        /**
         * See {@link uk.co.real_logic.artio.engine.logger.FixMessageLogger.Configuration#compactionSize(int)}.
         *
         * @param compactionSize the compaction size of the buffer in bytes.
         * @return this
         * @throws IllegalArgumentException if compaction size is invalid.
         */
        public Configuration compactionSize(final int compactionSize)
        {
            validateCompactionSize(compactionSize);

            this.compactionSize = compactionSize;
            return this;
        }

        public int compactionSize()
        {
            return compactionSize;
        }

        /**
         * See {@link uk.co.real_logic.artio.engine.logger.FixMessageLogger.Configuration#maximumBufferSize(int)}.
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

        public int maximumBufferSize()
        {
            return maximumBufferSize;
        }

        /**
         * Sets the fragment limit for polling different images when archive scanning.
         *
         * @param fragmentLimit the fragment limit
         * @return this
         */
        public Configuration fragmentLimit(final int fragmentLimit)
        {
            this.fragmentLimit = fragmentLimit;
            return this;
        }

        public int fragmentLimit()
        {
            return fragmentLimit;
        }

        /**
         * Sets the logFileDir used by your {@link EngineConfiguration}. This configuration option isn't required, it
         * allows faster FixArchiveScanner operations for predicates where you're searching by time by using the
         * {@link FixMessagePredicates#to(long)} or {@link FixMessagePredicates#from(long)} predicates.
         * Setting this configuration option automatically enables index scanning.
         *
         * @param logFileDir the logFileDir configured in your {@link EngineConfiguration}.
         * @return this
         */
        public Configuration logFileDir(final String logFileDir)
        {
            this.logFileDir = logFileDir;
            this.enableIndexScan = true;
            return this;
        }

        public String logFileDir()
        {
            return logFileDir;
        }

        /**
         * Enables or disables index scanning. If set to true, a {@link #logFileDir(String)} is required.
         *
         * @param enableIndexScan true to enable time based index scanning, false otherwise.
         * @return this
         */
        public Configuration enableIndexScan(final boolean enableIndexScan)
        {
            this.enableIndexScan = enableIndexScan;
            return this;
        }

        public boolean enableIndexScan()
        {
            return enableIndexScan;
        }

        /**
         * Sets the context to be used to create the Aeron Archiver that this backs onto.
         *
         * NB: this archiver will be given ownership of an Aeron instance, so a custom Aeron instance
         * shouldn't be set on the archive context.
         *
         * @param archiveContext the context to use to create the aeron archiver.
         * @return this
         */
        public Configuration archiveContext(final AeronArchive.Context archiveContext)
        {
            this.archiveContext = archiveContext;
            return this;
        }

        private void conclude()
        {
            if (enableIndexScan && logFileDir == null)
            {
                throw new IllegalArgumentException("Please configure a logFileDir if you want to enable index scan");
            }

            validateMaxAndCompactionSize(maximumBufferSize, compactionSize);
        }
    }

    private final IdleStrategy idleStrategy;
    private final FixArchiveScanningAgent agent;

    public FixArchiveScanner(final Configuration configuration)
    {
        configuration.conclude();

        final Aeron.Context aeronContext = new Aeron.Context()
            .aeronDirectoryName(configuration.aeronDirectoryName())
            .clientName("fix-archive-scanner");
        final Aeron aeron = Aeron.connect(aeronContext);

        AeronArchive.Context archiveContext = configuration.archiveContext;
        if (archiveContext == null)
        {
            archiveContext = new AeronArchive.Context();
            if (archiveContext.controlRequestChannel() == null)
            {
                archiveContext.controlRequestChannel(AeronArchive.Configuration.localControlChannel())
                    .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId());
            }
            if (archiveContext.controlResponseChannel() == null)
            {
                archiveContext.controlResponseChannel(CommonContext.IPC_CHANNEL);
            }
        }

        if (Strings.isEmpty(archiveContext.clientName()))
        {
            archiveContext.clientName("fix-archive-scanner");
        }

        // Context closes Aeron instance if this fails to connect.
        final AeronArchive aeronArchive = AeronArchive.connect(archiveContext.aeron(aeron).ownsAeronClient(true));

        String logFileDir = configuration.logFileDir();
        if (!configuration.enableIndexScan())
        {
            logFileDir = null;
        }

        idleStrategy = configuration.idleStrategy();
        agent = new FixArchiveScanningAgent(
            idleStrategy,
            configuration.compactionSize,
            configuration.maximumBufferSize,
            configuration.fragmentLimit,
            logFileDir,
            aeron,
            aeronArchive);
    }

    public void scan(
        final String aeronChannel,
        final int queryStreamId,
        final FixMessageConsumer handler,
        final boolean follow,
        final int archiveScannerStreamId)
    {
        scan(aeronChannel, queryStreamId, handler, null, follow, archiveScannerStreamId);
    }

    public void scan(
        final String aeronChannel,
        final int queryStreamId,
        final FixMessageConsumer fixHandler,
        final FixPMessageConsumer fixPHandler,
        final boolean follow,
        final int archiveScannerStreamId)
    {
        final IntHashSet queryStreamIds = new IntHashSet();
        queryStreamIds.add(queryStreamId);
        scan(aeronChannel, queryStreamIds, fixHandler, fixPHandler, follow, archiveScannerStreamId);
    }

    public void scan(
        final String aeronChannel,
        final IntHashSet queryStreamIds,
        final FixMessageConsumer fixHandler,
        final FixPMessageConsumer fixPHandler,
        final boolean follow,
        final int archiveScannerStreamId)
    {
        agent.setup(aeronChannel, queryStreamIds, fixHandler, fixPHandler, follow, archiveScannerStreamId);

        while (true)
        {
            if (agent.poll(Integer.MAX_VALUE))
            {
                idleStrategy.reset();
                return;
            }

            idleStrategy.idle();
        }
    }

    public void close()
    {
        agent.close();
    }
}
