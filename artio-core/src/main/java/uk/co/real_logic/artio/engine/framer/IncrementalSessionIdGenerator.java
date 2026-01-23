/*
 * Copyright 2015-2025 Real Logic Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

/**
 * Default session ID generator that uses a simple incrementing counter.
 * This maintains backward compatibility with the existing session ID generation behavior.
 * Session IDs start at 1 and increment for each new session.
 */
public class IncrementalSessionIdGenerator implements SessionIdGenerator
{
    static final long LOWEST_VALID_SESSION_ID = 1L;

    private long counter;

    /**
     * Create a new IncrementalSessionIdGenerator starting from the lowest valid session ID.
     */
    public IncrementalSessionIdGenerator()
    {
        this(LOWEST_VALID_SESSION_ID);
    }

    /**
     * Create a new IncrementalSessionIdGenerator starting from a specific value.
     *
     * @param initialValue the initial counter value
     */
    public IncrementalSessionIdGenerator(final long initialValue)
    {
        this.counter = initialValue;
    }

    @Override
    public long nextId()
    {
        return counter++;
    }

    @Override
    public void onExistingSessionId(final long existingSessionId)
    {
        counter = Math.max(counter, existingSessionId + 1);
    }

    @Override
    public void reset()
    {
        counter = LOWEST_VALID_SESSION_ID;
    }

    /**
     * Get the current counter value.
     *
     * @return the current counter value
     */
    public long counter()
    {
        return counter;
    }
}
