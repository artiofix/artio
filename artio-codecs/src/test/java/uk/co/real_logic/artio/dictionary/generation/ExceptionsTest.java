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
}
