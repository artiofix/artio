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
package uk.co.real_logic.artio.dictionary.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.isJustDisconnect;

public class ExceptionsTest
{
    @Test
    public void testIsJustDisconnect()
    {
        assertTrue(isJustDisconnect(new IOException("Connection reset by peer")));
        assertTrue(isJustDisconnect(new SocketException("Connection reset"))); // Java >= 13
        assertFalse(isJustDisconnect(new IOException()));
        assertFalse(isJustDisconnect(new IOException("Error")));
    }

    @Test
    public void shouldAttachCloseFailureToAnError()
    {
        final AssertionError original = new AssertionError("original");
        final IOException closeFailure = new IOException("close failed");

        Exceptions.suppressingClose(() ->
        {
            throw closeFailure;
        }, original);

        assertArrayEquals(new Throwable[]{ closeFailure }, original.getSuppressed());
    }

    @Test
    public void shouldIgnoreNullWhenSuppressingClose()
    {
        final AssertionError original = new AssertionError("original");

        Exceptions.suppressingClose(null, original);

        assertEquals(0, original.getSuppressed().length);
    }

    @Test
    public void shouldCloseEveryCloseableAndSkipNullsWhenSuppressingCloseAll()
    {
        final AssertionError original = new AssertionError("original");
        final IOException firstFailure = new IOException("first close failed");
        final IOException secondFailure = new IOException("second close failed");
        final boolean[] closed = new boolean[1];

        Exceptions.suppressingCloseAll(
            original,
            () ->
            {
                throw firstFailure;
            },
            null,
            () -> closed[0] = true,
            () ->
            {
                throw secondFailure;
            });

        assertTrue(closed[0], "should keep closing after an earlier close fails");
        assertArrayEquals(new Throwable[]{ firstFailure, secondFailure }, original.getSuppressed());
    }

    @Test
    public void shouldIgnoreCloseThrowingTheOriginalThrowable()
    {
        // e.g. a stream that remembers its first failure and throws it again on close
        final IOException original = new IOException("original");
        final boolean[] closed = new boolean[1];

        Exceptions.suppressingCloseAll(
            original,
            () ->
            {
                throw original;
            },
            () -> closed[0] = true);

        assertTrue(closed[0], "should keep closing after a close throws the original throwable");
        assertEquals(0, original.getSuppressed().length);
    }
}
