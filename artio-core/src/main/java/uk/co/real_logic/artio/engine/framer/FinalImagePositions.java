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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.Image;
import io.aeron.UnavailableImageHandler;

import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

// This is written to on the client conductor thread and read from the Framer thread.
// Lookup can be called multiple times for a session id, remove and onUnavailableImage once each.
class FinalImagePositions implements UnavailableImageHandler
{
    static final long UNKNOWN_POSITION = -1;

    // If we ask for the position before the unavailable handler is called
    // then this value is put in the map so that we can check for its presence
    // and avoid leaking memory, reference comparison against this is deliberate
    private static final Long LEAK_WITNESS = UNKNOWN_POSITION;

    // Hashtable picked over CHM due to low contention factor + expected small size
    private final Map<Integer, Long> sessionIdToPosition = new Hashtable<>();

    @Override
    public void onUnavailableImage(final Image image)
    {
        final Integer sessionId = image.sessionId();
        final long position = image.position();

        final Long old = sessionIdToPosition.remove(sessionId);
        if (!Objects.equals(old, LEAK_WITNESS))
        {
            sessionIdToPosition.put(sessionId, position);
        }
    }

    long lookupPosition(final int aeronSessionId)
    {
        final Long position = sessionIdToPosition.get(aeronSessionId);
        return position == null ? UNKNOWN_POSITION : position;
    }

    void removePosition(final int aeronSessionId)
    {
        final Integer boxedAeronSessionId = aeronSessionId;
        sessionIdToPosition.compute(boxedAeronSessionId, removeOrSetWitness);
    }

    private final BiFunction<Integer, Long, Long> removeOrSetWitness =
        (key, oldValue) -> oldValue == null ? LEAK_WITNESS : null;
}
