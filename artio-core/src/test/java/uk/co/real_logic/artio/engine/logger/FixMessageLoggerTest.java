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

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.decoder.LogonDecoder;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.stream.Collectors.joining;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class FixMessageLoggerTest extends AbstractFixMessageLoggerTest
{
    final byte[] fakeFixMessage = IntStream
        .range(0, String.valueOf(Long.MIN_VALUE).length())
        .mapToObj(i -> " ").collect(joining()).getBytes(US_ASCII);

    final UnsafeBuffer fakeMessageBuffer = new UnsafeBuffer(fakeFixMessage);

    {
        compactionSize = 500;
    }

    @BeforeEach
    public void setup()
    {
        setup(null);
    }

    long onMessage(final GatewayPublication publication, final long timestamp)
    {
        return onMessage(publication, timestamp, (int)timestamp);
    }

    private long onMessage(
        final GatewayPublication publication, final long timestamp, final int sequenceNumber)
    {
        fakeMessageBuffer.putLongAscii(0, timestamp);
        return untilComplete(() -> publication.saveMessage(
            fakeMessageBuffer,
            0,
            fakeMessageBuffer.capacity(),
            LIBRARY_ID,
            LogonDecoder.MESSAGE_TYPE,
            SESSION_ID,
            SEQUENCE_INDEX,
            CONNECTION_ID,
            MessageStatus.OK,
            sequenceNumber,
            timestamp));
    }

    @Test
    public void shouldForciblyDumpMessagesRatherThanOom()
    {
        // If we hit the maximum buffer size then we should just dump the messages to the handler rather than OOM.

        final int dumpCount = 109;
        for (int i = 0; i <= dumpCount + 1; i++)
        {
            onMessage(inboundPublication, i);
            logger.doWork();
        }

        assertEventuallyReceives(dumpCount);
        timestamps.clear();

        // A messages arriving later are still received
        onMessage(inboundPublication, dumpCount + 2);
        onMessage(outboundPublication, dumpCount + 3);
        onReplayerTimestamp(replayPublication, dumpCount + 4);
        assertEventuallyReceives(2);
    }

    @Test
    public void shouldNotReorderMessagesWithinABufferWithEqualTimestamps()
    {
        onMessage(inboundPublication, 1, 1);
        onMessage(inboundPublication, 1, 2);

        logger.doWork();

        onMessage(outboundPublication, 3, 1);

        onReplayerTimestamp(replayPublication, 4);

        assertEventuallyReceives(2);
        assertThat(timestamps, contains(1L, 1L));
        assertThat(sequenceNumbers, contains(1, 2));
    }

    @Test
    public void shouldDumpingBufferMaintainOriginalOwner()
    {
        for (int i = 1; i <= 55; ++i)
        {
            onMessage(inboundPublication, i);
            onMessage(outboundPublication, i);
        }

        onReplayerTimestamp(replayPublication, 100);

        assertEventuallyReceives(110);
    }

    @Test
    public void shouldDumpingUpdateMinAndMaxTimestamps()
    {
        for (int i = 1; i <= 50; ++i)
        {
            onMessage(inboundPublication, i);
            onMessage(outboundPublication, i);
        }

        for (int i = 51; i <= 60; ++i)
        {
            onMessage(outboundPublication, i);
        }

        onReplayerTimestamp(replayPublication, 100);

        assertEventuallyReceives(109);

        onMessage(inboundPublication, 100);
        assertEventuallyReceives(110);
    }
}
