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
 * Strategy interface for generating unique session identifiers across FIX sessions.
 * Implementations must ensure that generated IDs are unique within a single engine instance
 * and ideally unique across multiple engine instances in distributed deployments.
 */
public interface SessionIdGenerator
{
    /**
     * Generate the next unique session identifier.
     *
     * @return a unique session ID
     */
    long nextId();

    /**
     * Update the generator's state based on an existing session ID that was loaded from persistence.
     * This is called during initialization when session IDs are loaded from disk to ensure
     * the generator doesn't produce duplicate IDs.
     *
     * @param existingSessionId a session ID that already exists
     */
    void onExistingSessionId(long existingSessionId);

    /**
     * Reset the generator to its initial state.
     * This is called when the session store is reset.
     */
    void reset();
}
