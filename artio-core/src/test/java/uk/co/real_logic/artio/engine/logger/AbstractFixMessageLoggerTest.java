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
import io.aeron.ExclusivePublication;
import io.aeron.driver.MediaDriver;
import org.agrona.collections.IntArrayList;
import org.agrona.collections.LongArrayList;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.OffsetEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.TestFixtures;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.messages.ReplayerTimestampDecoder;
import uk.co.real_logic.artio.messages.ReplayerTimestampEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_OUTBOUND_REPLAY_STREAM;
import static uk.co.real_logic.artio.engine.logger.FixMessageConsumerValidator.validateFixMessageConsumer;
import static uk.co.real_logic.artio.messages.MessageHeaderDecoder.ENCODED_LENGTH;

public abstract class AbstractFixMessageLoggerTest
{
    static final int LIBRARY_ID = 1;
    static final long SESSION_ID = 2;
    static final int SEQUENCE_INDEX = 3;
    static final long CONNECTION_ID = 4;

    int compactionSize;

    final EpochNanoClock clock = new OffsetEpochNanoClock();
    final LongArrayList timestamps = new LongArrayList();
    final IntArrayList streamIds = new IntArrayList();
    final IntArrayList sequenceNumbers = new IntArrayList();

    final FixMessageConsumer fixConsumer = (message, buffer, offset, length, header) ->
    {
        final long timestamp = message.timestamp();

        timestamps.add(timestamp);
        sequenceNumbers.add(message.sequenceNumber());

        final String body = validateFixMessageConsumer(message, buffer, offset, length).trim();
        final long messageNumber = Long.parseLong(body);
        assertEquals(timestamp, messageNumber);
        streamIds.add(header.streamId());
    };

    String libraryChannel;
    MediaDriver mediaDriver;
    Aeron aeron;
    FixMessageLogger logger;

    GatewayPublication inboundPublication;
    GatewayPublication outboundPublication;
    ExclusivePublication replayPublication;

    void setup(final FixPMessageConsumer fixPMessageConsumer)
    {
        libraryChannel = "aeron:udp?endpoint=localhost:" + TestFixtures.unusedPort();
        mediaDriver = TestFixtures.launchJustMediaDriver();
        aeron = Aeron.connect();

        final FixMessageLogger.Configuration config = new FixMessageLogger.Configuration()
            .fixMessageConsumer(fixConsumer)
            .fixPMessageConsumer(fixPMessageConsumer)
            .compactionSize(compactionSize)
            .maximumBufferSize(10_000)
            .libraryAeronChannel(libraryChannel);
        logger = new FixMessageLogger(config);

        inboundPublication = newPublication(DEFAULT_INBOUND_LIBRARY_STREAM);
        outboundPublication = newPublication(DEFAULT_OUTBOUND_LIBRARY_STREAM);
        replayPublication = aeron.addExclusivePublication(
            IPC_CHANNEL,
            DEFAULT_OUTBOUND_REPLAY_STREAM);
    }

    @AfterEach
    public void teardown()
    {
        Exceptions.closeAll(logger);
        Exceptions.closeAll(aeron, mediaDriver);
    }

    @Test
    public void shouldReOrderMessagesByTimestamp()
    {
        final int out = DEFAULT_OUTBOUND_LIBRARY_STREAM;
        final int in = DEFAULT_INBOUND_LIBRARY_STREAM;

        onMessage(inboundPublication, 2);
        onMessage(inboundPublication, 3);
        onMessage(inboundPublication, 4);
        onMessage(outboundPublication, 1);
        onMessage(outboundPublication, 5);
        onMessage(outboundPublication, 7);
        onMessage(inboundPublication, 6);
        onReplayerTimestamp(replayPublication, 10);

        assertEventuallyReceives(6);
        assertTimestampCountEventually(6);
        assertThat(timestamps, contains(1L, 2L, 3L, 4L, 5L, 6L));
        assertThat(streamIds.toString(), streamIds, contains(out, in, in, in, out, in));
        timestamps.clear();
        streamIds.clear();

        // A message arriving later
        onMessage(inboundPublication, 8);
        assertEventuallyReceives(1);
        assertThat(timestamps, contains(7L));
        timestamps.clear();

        assertThat("failed to reshuffle", logger.bufferPosition(), lessThanOrEqualTo(compactionSize));

        onMessage(inboundPublication, 9);
        onMessage(outboundPublication, 10);
        assertEventuallyReceives(2);
        assertTimestampCountEventually(2);
        assertThat(timestamps, contains(8L, 9L));
        timestamps.clear();

        onMessage(outboundPublication, 11);
        assertEventuallyReads(1);

        assertNoTimestamps();
        logger.onClose();
        assertThat(timestamps, contains(10L, 11L));
        assertEquals(0, logger.bufferPosition(), "failed to reshuffle");
    }

    @Test
    public void shouldReOrderMessagesByTimestampIntermediatePolling()
    {
        // poll
        onMessage(inboundPublication, 1);
        onMessage(inboundPublication, 3);
        assertEventuallyReads(2);
        assertNoTimestamps();

        logger.doWork();

        // poll
        onMessage(inboundPublication, 5);
        assertEventuallyReads(1);
        assertNoTimestamps();

        // poll
        onMessage(outboundPublication, 2);
        onMessage(outboundPublication, 4);
        onMessage(outboundPublication, 6);
        onReplayerTimestamp(replayPublication, 10);
        assertEventuallyReads(4);
        assertTimestampCountEventually(5);
        assertThat(timestamps.toString(), timestamps, contains(1L, 2L, 3L, 4L, 5L));
        timestamps.clear();
        logger.onClose();

        assertThat(timestamps.toString(), timestamps, contains(6L));
        timestamps.clear();

        assertEquals(0, logger.bufferPosition(), "failed to reshuffle");
    }

    // From issue #408
    @Test
    public void shouldHandleSubsequentReplayTimestampsCorrectly()
    {
        // outbound = 2, inbound = 1
        onMessage(outboundPublication, 1603800570460284857L);
        onMessage(inboundPublication, 1603800570513023097L);
        onReplayerTimestamp(replayPublication, 1603800571498664415L);
        assertEventuallyReads(3);
        assertTimestampCountEventually(1);
        assertThat(timestamps, contains(1603800570460284857L));
        timestamps.clear();

        onMessage(outboundPublication, 1603800578520566892L);
        assertEventuallyReads(1);
        assertTimestampCountEventually(1);
        assertThat(timestamps, contains(1603800570513023097L));
        timestamps.clear();

        onMessage(inboundPublication, 1603800581079423921L);
        assertEventuallyReads(1);
        assertNoTimestamps();

        onMessage(outboundPublication, 1603800586520278849L);
        assertEventuallyReads(1);
        assertNoTimestamps();

        onMessage(inboundPublication, 1603800591079715485L);
        assertEventuallyReads(1);
        assertNoTimestamps();

        // finally a replay timestamp flushes out the previous messages
        onReplayerTimestamp(replayPublication, 1603800591079715486L);
        assertEventuallyReads(1);
        assertTimestampCountEventually(3);
        assertThat(timestamps, contains(1603800578520566892L, 1603800581079423921L, 1603800586520278849L));
    }

    void assertEventuallyReceives(final int messageCount)
    {
        assertEventuallyTrue(
            () ->
            {
                aeron.printCounters(System.err);
                return "Failed to receive a message: " + timestamps;
            },
            () ->
            {
                logger.doWork();
                return timestamps.size() >= messageCount;
            },
            10_000,
            () ->
            {
            });
    }

    private void assertTimestampCountEventually(final int count)
    {
        assertEventuallyTrue("Failed to process all timestamps", () ->
        {
            logger.doWork();
            return timestamps.size() >= count;
        });
    }

    private void assertEventuallyReads(final int messageCount)
    {
        assertEventuallyTrue(
            () -> "Failed to receive a message: " + timestamps,
            new BooleanSupplier()
            {
                int read = 0;

                @Override
                public boolean getAsBoolean()
                {
                    read += logger.doWork();
                    return read >= messageCount;
                }
            },
            1000,
            () ->
            {
            });
    }

    abstract long onMessage(GatewayPublication inboundPublication, long timestamp);

    void onReplayerTimestamp(final ExclusivePublication replayStream, final long timestampInNs)
    {
        final UnsafeBuffer timestampBuffer = new UnsafeBuffer(new byte[
            ENCODED_LENGTH + ReplayerTimestampDecoder.BLOCK_LENGTH]);
        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final ReplayerTimestampEncoder replayerTimestampEncoder = new ReplayerTimestampEncoder();
        replayerTimestampEncoder
            .wrapAndApplyHeader(timestampBuffer, 0, headerEncoder)
            .timestamp(timestampInNs);

        untilComplete(() -> replayStream.offer(timestampBuffer));
    }

    protected long untilComplete(final LongSupplier op)
    {
        while (true)
        {
            final long position = op.getAsLong();
            if (position > 0)
            {
                return position;
            }
            Thread.yield();
        }
    }

    private GatewayPublication newPublication(final int streamId)
    {
        final ExclusivePublication publication = aeron.addExclusivePublication(
            libraryChannel, streamId);
        return new GatewayPublication(
            publication,
            mock(AtomicCounter.class),
            CommonConfiguration.backoffIdleStrategy(),
            clock,
            1);
    }

    private void assertNoTimestamps()
    {
        assertThat(timestamps, hasSize(0));
    }
}
