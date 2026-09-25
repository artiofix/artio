/*
 * Copyright 2015-2026 Real Logic Limited.
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
import io.aeron.Counter;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class FixCountersTest
{
    private static final int LIBRARY_ID = 7;
    private static final long GATEWAY_ID = 88L;
    private static final long CONNECTION_ID = 42;
    private static final long SESSION_ID = 89;
    private static final String ADDRESS = "localhost:9001";

    private final Aeron aeron = mock(Aeron.class);
    private final Counter counter = mock(Counter.class);
    private FixCounters fixCounters;

    @BeforeEach
    void setUp()
    {
        when(aeron.addCounter(anyInt(), any(), anyInt(), anyInt(), any(), anyInt(), anyInt()))
            .thenReturn(counter);
        fixCounters = new FixCounters(aeron, false, LIBRARY_ID, GATEWAY_ID);
        clearInvocations(aeron);
    }

    @Test
    void shouldHaveLibraryIdAndGatewayIdInKeyBuffer()
    {
        final int expectedKeyLength = SIZE_OF_INT + SIZE_OF_LONG;
        fixCounters.messagesRead(CONNECTION_ID, ADDRESS);

        final ArgumentCaptor<DirectBuffer> bufferCapture = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<Integer> keyLengthCapture = ArgumentCaptor.forClass(Integer.class);

        verify(aeron).addCounter(anyInt(), bufferCapture.capture(), eq(0), keyLengthCapture.capture(), any(),
            anyInt(), anyInt());

        final DirectBuffer buffer = bufferCapture.getValue();
        final int keyLength = keyLengthCapture.getValue();

        assertEquals(expectedKeyLength, keyLength);
        assertEquals(LIBRARY_ID, buffer.getInt(0));
        assertEquals(GATEWAY_ID, buffer.getLong(SIZE_OF_INT));
    }

    @Test
    void shouldAppendLibraryIdAndGatewayIdToCounterLabel()
    {
        fixCounters.receivedMsgSeqNo(CONNECTION_ID, SESSION_ID);

        final ArgumentCaptor<DirectBuffer> labelCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(aeron).addCounter(anyInt(), any(), anyInt(), anyInt(), labelCaptor.capture(),
            offsetCaptor.capture(), lengthCaptor.capture());

        final String label = labelCaptor.getValue()
                                        .getStringWithoutLengthAscii(offsetCaptor.getValue(), lengthCaptor.getValue());

        final String expectedLabel = "Last Received MsgSeqNo connId=" + CONNECTION_ID + ",sessId=" + SESSION_ID +
            " libraryId=" + LIBRARY_ID + " gatewayId=" + GATEWAY_ID;

        assertEquals(expectedLabel, label);
    }

    @Test
    void shouldReturnCounterCreatedByAeron()
    {
        final AtomicCounter result = fixCounters.messagesRead(CONNECTION_ID, ADDRESS);

        assertSame(counter, result);
    }
}
