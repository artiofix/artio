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
import uk.co.real_logic.artio.decoder.HeartbeatDecoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFixturesTest
{
    @Test
    void shouldGenerateMessageOfGivenLength()
    {
        final int messageLength = 256;
        final byte[] bytes = TestFixtures.largeMessage(messageLength);
        assertEquals(messageLength, bytes.length);

        final HeartbeatDecoder decoder = new HeartbeatDecoder();
        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(bytes);
        decoder.decode(buffer, 0, bytes.length);
        assertTrue(decoder.validate());
        assertEquals(buffer.computeChecksum(0, messageLength - 7),
            Integer.parseInt(decoder.trailer().checkSumAsString(), 10));
    }
}
