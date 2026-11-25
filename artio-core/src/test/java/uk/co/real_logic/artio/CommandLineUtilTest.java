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
package uk.co.real_logic.artio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CommandLineUtilTest
{
    @Test
    public void shouldParseAnInt()
    {
        assertEquals(12, CommandLineUtil.parseInt("12", "stream id"));
    }

    @Test
    public void shouldParseALong()
    {
        assertEquals(12_345_678_901L, CommandLineUtil.parseLong("12345678901", "session id"));
    }

    @Test
    public void shouldNameTheArgumentWhenAnIntIsInvalid()
    {
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> CommandLineUtil.parseInt("abc", "stream id"));

        assertEquals("stream id must be a number, but was 'abc'", ex.getMessage());
        assertInstanceOf(NumberFormatException.class, ex.getCause());
    }

    @Test
    public void shouldNameTheArgumentWhenAnIntIsTooBig()
    {
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> CommandLineUtil.parseInt("12345678901", "stream id"));

        assertEquals("stream id must be a number, but was '12345678901'", ex.getMessage());
    }

    @Test
    public void shouldNameTheArgumentWhenALongIsInvalid()
    {
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> CommandLineUtil.parseLong("12x", "session id"));

        assertEquals("session id must be a number, but was '12x'", ex.getMessage());
        assertInstanceOf(NumberFormatException.class, ex.getCause());
    }
}
