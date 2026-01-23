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
package uk.co.real_logic.artio.engine;

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.engine.framer.IncrementalSessionIdGenerator;
import uk.co.real_logic.artio.engine.framer.SessionIdGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic configuration tests for FixEngine components.
 * Tests that SessionIdGenerator configuration works correctly.
 */
public class FixEngineInitFramerTest
{
    @Test
    public void shouldUseDefaultSessionIdGeneratorWhenNotConfigured()
    {
        final EngineConfiguration config = new EngineConfiguration();

        final SessionIdGenerator generator = config.sessionIdGenerator();

        assertNotNull(generator, "Should have default SessionIdGenerator");
        assertInstanceOf(IncrementalSessionIdGenerator.class, generator,
            "Default should be IncrementalSessionIdGenerator");
    }

    @Test
    public void shouldUseCustomSessionIdGeneratorWhenConfigured()
    {
        final EngineConfiguration config = new EngineConfiguration();
        final SessionIdGenerator customGenerator = new IncrementalSessionIdGenerator(5000);

        config.sessionIdGenerator(customGenerator);

        assertSame(customGenerator, config.sessionIdGenerator(),
            "Should return the configured SessionIdGenerator");
    }

    @Test
    public void shouldAllowChainingWhenSettingSessionIdGenerator()
    {
        final EngineConfiguration config = new EngineConfiguration();
        final SessionIdGenerator generator = new IncrementalSessionIdGenerator(1000);

        final EngineConfiguration result = config.sessionIdGenerator(generator);

        assertSame(config, result, "Should return same config instance for method chaining");
        assertSame(generator, config.sessionIdGenerator(), "Should have set the generator");
    }

    @Test
    public void shouldGenerateUniqueIdsWithIncrementalGenerator()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator();

        final long id1 = generator.nextId();
        final long id2 = generator.nextId();
        final long id3 = generator.nextId();

        assertEquals(1, id1);
        assertEquals(2, id2);
        assertEquals(3, id3);
    }

    @Test
    public void shouldGenerateUniqueIdsWithCustomStartValue()
    {
        final IncrementalSessionIdGenerator generator = new IncrementalSessionIdGenerator(1000);

        final long id1 = generator.nextId();
        final long id2 = generator.nextId();

        assertEquals(1000, id1, "Should start from custom value");
        assertEquals(1001, id2, "Should increment from custom value");
        assertNotEquals(id1, id2, "IDs should be unique");
    }

    @Test
    public void shouldConfigureMultipleEnginesWithDifferentGenerators()
    {
        final EngineConfiguration config1 = new EngineConfiguration()
            .sessionIdGenerator(new IncrementalSessionIdGenerator(1000));

        final EngineConfiguration config2 = new EngineConfiguration()
            .sessionIdGenerator(new IncrementalSessionIdGenerator(2000));

        assertNotSame(config1.sessionIdGenerator(), config2.sessionIdGenerator(),
            "Each config should have its own generator instance");

        assertInstanceOf(IncrementalSessionIdGenerator.class, config1.sessionIdGenerator());
        assertInstanceOf(IncrementalSessionIdGenerator.class, config2.sessionIdGenerator());

        // Verify they generate different ID ranges
        assertEquals(1000, ((IncrementalSessionIdGenerator)config1.sessionIdGenerator()).nextId());
        assertEquals(2000, ((IncrementalSessionIdGenerator)config2.sessionIdGenerator()).nextId());
    }

    @Test
    public void shouldPreserveGeneratorThroughMultipleCalls()
    {
        final EngineConfiguration config = new EngineConfiguration();
        final SessionIdGenerator generator = new IncrementalSessionIdGenerator(500);

        config.sessionIdGenerator(generator);

        final SessionIdGenerator retrieved1 = config.sessionIdGenerator();
        final SessionIdGenerator retrieved2 = config.sessionIdGenerator();

        assertSame(generator, retrieved1, "First retrieval should return same generator");
        assertSame(generator, retrieved2, "Second retrieval should return same generator");
        assertSame(retrieved1, retrieved2, "Multiple retrievals should return same instance");
    }
}