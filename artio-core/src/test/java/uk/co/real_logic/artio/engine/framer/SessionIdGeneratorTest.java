/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdGeneratorTest
{
    @Test
    void shouldGenerateIncrementalSessionIds()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator();

        assertEquals(1, generator.nextId());
        assertEquals(2, generator.nextId());
        assertEquals(3, generator.nextId());
    }

    @Test
    void shouldGenerateIncrementalSessionIdsFromCustomStart()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator(1000);

        assertEquals(1000, generator.nextId());
        assertEquals(1001, generator.nextId());
        assertEquals(1002, generator.nextId());
    }

    @Test
    void shouldUpdateCounterOnExistingSessionId()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator();

        generator.onExistingSessionId(100);
        assertEquals(101, generator.nextId());
        assertEquals(102, generator.nextId());
    }

    @Test
    void shouldNotDecrementCounterOnLowerExistingSessionId()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator(100);

        generator.onExistingSessionId(50); // Lower than current counter
        assertEquals(100, generator.nextId()); // Should continue from 100
    }

    @Test
    void shouldResetToInitialValue()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator();

        generator.nextId();
        generator.nextId();
        generator.reset();

        assertEquals(1, generator.nextId());
    }
}
