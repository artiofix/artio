/*
 * Copyright 2015-2026 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParsedAddressTest
{
    @Test
    void shouldParseAHostAndPort()
    {
        final ParsedAddress parsed = ParsedAddress.parse("192.168.0.1:56324");

        assertEquals("192.168.0.1", parsed.host());
        assertEquals(56324, parsed.port());
    }

    @Test
    void shouldDropTheLeadingSlashOfASocketAddress()
    {
        final ParsedAddress parsed = ParsedAddress.parse("/127.0.0.1:5000");

        assertEquals("127.0.0.1", parsed.host());
        assertEquals(5000, parsed.port());
    }

    @Test
    void shouldUseAnUnknownPortWhenThePortIsNotANumber()
    {
        final ParsedAddress parsed = ParsedAddress.parse("192.168.0.1:abc");

        assertEquals("192.168.0.1", parsed.host());
        assertEquals(Session.UNKNOWN, parsed.port());
    }
}
