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

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.errors.DistinctErrorLog;
import org.agrona.concurrent.errors.ErrorConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.co.real_logic.artio.engine.framer.FakeEpochClock;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ErrorPrinterTest
{
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String EXCEPTION_MSG = "Exception 1";

    @Test
    public void shouldPrintErrorOnce()
    {
        final ErrorConsumer consumer = mock(ErrorConsumer.class);
        final UnsafeBuffer errorBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        final FakeEpochClock clock = new FakeEpochClock();
        final DistinctErrorLog distinctErrorLog = new DistinctErrorLog(errorBuffer, clock);
        final ErrorPrinter printer = new ErrorPrinter(
            errorBuffer, "", 0, null, consumer, clock);

        clock.advanceMilliSeconds(10);
        assertTrue(distinctErrorLog.record(new Exception(EXCEPTION_MSG)));

        doWork(1, printer);

        final ArgumentCaptor<String> exceptionCaptor = ArgumentCaptor.forClass(String.class);
        verify(consumer).accept(eq(1), anyLong(), anyLong(), exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue(), containsString(EXCEPTION_MSG));

        verifyNoMoreInteractions(consumer);

        clock.advanceMilliSeconds(1);
        doWork(0, printer);
        verifyNoMoreInteractions(consumer);

        clock.advanceMilliSeconds(1);
        doWork(0, printer);
        verifyNoMoreInteractions(consumer);
    }

    private void doWork(final int expectedWork, final ErrorPrinter printer)
    {
        final int work = printer.doWork();
        assertEquals(expectedWork, work);
    }
}
