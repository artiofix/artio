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
package uk.co.real_logic.artio.system_tests;

import org.agrona.DirectBuffer;
import org.agrona.collections.LongArrayList;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.engine.MessageTimingHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MessageTimingCaptor implements MessageTimingHandler
{

    public static final UnsafeBuffer NO_META_DATA = new UnsafeBuffer(new byte[0]);

    private final List<DirectBuffer> metadata = new ArrayList<>();
    private final LongArrayList sequenceNumbers = new LongArrayList();

    @Override
    public synchronized void onMessage(
        final long sequenceNumber,
        final long connectionId,
        final DirectBuffer metaDataBuffer,
        final int metaDataOffset,
        final int metaDataLength)
    {
        sequenceNumbers.add(sequenceNumber);

        if (metaDataLength == 0)
        {
            metadata.add(NO_META_DATA);
        }
        else
        {
            final UnsafeBuffer buffer = new UnsafeBuffer(new byte[metaDataLength]);
            buffer.putBytes(0, metaDataBuffer, metaDataOffset, metaDataLength);

            metadata.add(buffer);
        }
    }

    public synchronized void verifyConsecutiveSequenceNumbers(final int lastSentMsgSeqNum)
    {
        assertThat(sequenceNumbers.toString(), sequenceNumbers, hasSize(lastSentMsgSeqNum));
        for (int i = 0; i < lastSentMsgSeqNum; i++)
        {
            final long sequenceNumber = sequenceNumbers.getLong(i);
            final long expectedSequenceNumber = i + 1L;
            if (expectedSequenceNumber != sequenceNumber)
            {
                assertEquals(expectedSequenceNumber, sequenceNumber, sequenceNumbers.toString());
            }
        }
    }

    public synchronized void verifyConsecutiveMetaData(final int lastSentMsgSeqNum)
    {
        assertThat(metadata, hasSize(lastSentMsgSeqNum));
        for (int i = 0; i < lastSentMsgSeqNum; i++)
        {
            final DirectBuffer buffer = metadata.get(i);
            if (buffer.capacity() > 0)
            {
                final long sequenceNumber = buffer.getInt(0);
                assertEquals(i + 1L, sequenceNumber, Arrays.toString(buffer.byteArray()));
            }
        }
    }

    public synchronized DirectBuffer getMetaData(final int messageIndex)
    {
        return metadata.get(messageIndex);
    }

    public synchronized int count()
    {
        return sequenceNumbers.size();
    }
}
