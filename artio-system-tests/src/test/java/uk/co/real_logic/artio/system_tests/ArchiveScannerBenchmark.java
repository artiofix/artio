/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.system_tests;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.IntHashSet;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.SequenceNumberExtractor;
import uk.co.real_logic.artio.engine.logger.FixArchiveScanner;
import uk.co.real_logic.artio.engine.logger.FixMessageConsumer;
import uk.co.real_logic.artio.engine.logger.FixMessagePredicate;
import uk.co.real_logic.artio.engine.logger.FixMessagePredicates;
import uk.co.real_logic.artio.messages.FixMessageDecoder;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_ARCHIVE_SCANNER_STREAM;
import static uk.co.real_logic.artio.engine.SequenceNumberExtractor.NO_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.engine.logger.FixMessagePredicates.*;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.ACCEPTOR_LOGS;

public class ArchiveScannerBenchmark
{
    private static int messageCount;
    private static final Int2IntHashMap STREAM_ID_TO_LAST_SEQ_NUM = new Int2IntHashMap(MISSING_INT);

    public static void main(final String[] args)
    {
        System.out.println("Running in: " + new File(".").getAbsolutePath());

        final long start = Long.parseLong(args[0]);
        final long end = Long.parseLong(args[1]);
        final boolean enableIndexScan = Boolean.parseBoolean(args[2]);
        final boolean includePredicate = Boolean.parseBoolean(args[3]);
        final int totalRuns = Integer.parseInt(args[4]);
        final boolean logProgress = args.length >= 6 && Boolean.parseBoolean(args[5]);
        final String acceptorLogs = args.length >= 7 ? args[6] : ACCEPTOR_LOGS;
        final String acceptorArchive = args.length >= 8 ? args[7] : null;
        final String session = args.length >= 9 ? args[8] : "INITIATOR";

        System.out.println("start = " + start + ", end = " + end + ", enableIndexScan = " + enableIndexScan +
            ", includePredicate = " + includePredicate + ", totalRuns = " + totalRuns + ", logProgress = " +
            logProgress + ",acceptorLogs=" + acceptorLogs + ",acceptorArchive=" + acceptorArchive);

        final FixArchiveScanner.Configuration context = new FixArchiveScanner.Configuration()
            .aeronDirectoryName(CommonContext.getAeronDirectoryName())
            .idleStrategy(CommonConfiguration.backoffIdleStrategy())
            .logFileDir(acceptorLogs)
            .enableIndexScan(enableIndexScan);

        final MediaDriver.Context mdContext = TestFixtures.mediaDriverContext(
            TestFixtures.TERM_BUFFER_LENGTH, false);
        final Archive.Context archiveCtx = new Archive.Context()
            .deleteArchiveOnStart(false)
            .segmentFileLength(mdContext.ipcTermBufferLength());

        if (acceptorArchive != null)
        {
            archiveCtx.archiveDirectoryName(acceptorArchive);
        }

        try (ArchivingMediaDriver mediaDriver = ArchivingMediaDriver.launch(mdContext, archiveCtx);
            FixArchiveScanner scanner = new FixArchiveScanner(context))
        {
            for (int i = 0; i < totalRuns; i++)
            {
                final IntHashSet queryStreamIds = new IntHashSet();
                queryStreamIds.add(CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM);
                queryStreamIds.add(CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM);

                final FixDictionary fixDictionary = FixDictionary.of(FixDictionary.findDefault());
                final Predicate<SessionHeaderDecoder> sessionFilter = targetCompIdOf(session)
                    .or(senderCompIdOf(session));
                FixMessagePredicate predicate = whereHeader(fixDictionary, sessionFilter);

                if (end != 0)
                {
                    predicate = predicate.and(FixMessagePredicates.between(start, end + 1));
                }

                final FixMessageConsumer fixMessageConsumer = new BenchmarkMessageConsumer(
                    logProgress);

                final long scanStart = System.nanoTime();
                final FixMessageConsumer consumer = includePredicate ?
                    filterBy(fixMessageConsumer, predicate) : fixMessageConsumer;
                scanner.scan(
                    IPC_CHANNEL,
                    queryStreamIds,
                    consumer,
                    null,
                    false,
                    DEFAULT_ARCHIVE_SCANNER_STREAM);

                final long scanEnd = System.nanoTime();
                System.out.println("message scan time = " + TimeUnit.NANOSECONDS.toMillis(scanEnd - scanStart));
                System.out.println("messages = " + messageCount);
            }
        }
    }

    private static class BenchmarkMessageConsumer implements FixMessageConsumer
    {
        private final SequenceNumberExtractor sequenceNumber = new SequenceNumberExtractor();

        private final boolean logProgress;

        private boolean seenMessage = false;

        BenchmarkMessageConsumer(final boolean logProgress)
        {
            this.logProgress = logProgress;
        }

        @Override
        public void onMessage(
            final FixMessageDecoder message,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final ArtioLogHeader header)
        {
            messageCount++;

            if (logProgress)
            {
                final int messageOffset = message.limit() + FixMessageDecoder.bodyHeaderLength();
                final int bodyLength = message.bodyLength();

                final int streamId = header.streamId();

                if (!seenMessage)
                {
                    System.out.println("First message: " + message.body());
                    seenMessage = true;
                }

                final int msgSeqNum = sequenceNumber.extract(buffer, messageOffset, bodyLength);
                if (msgSeqNum != NO_SEQUENCE_NUMBER)
                {
                    final int lastSeqNum = STREAM_ID_TO_LAST_SEQ_NUM.get(streamId);
                    if (lastSeqNum != MISSING_INT && lastSeqNum != msgSeqNum - 1)
                    {
                        System.out.println("Out of order sequence number: lastSeqNum=" + lastSeqNum +
                            ",msgSeqNum=" + msgSeqNum + ",streamId=" + streamId);
                    }
                }

                STREAM_ID_TO_LAST_SEQ_NUM.put(streamId, msgSeqNum);

                final int size = messageCount;
                if ((size % 1000) == 0)
                {
                    System.out.println("messageCount = " + size);
                }
            }
        }
    }
}
