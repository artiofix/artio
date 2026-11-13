/*
 * Copyright 2022 Monotonic Ltd.
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

import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayList;
import java.util.List;

class ReproductionLog
{
    private final Long2ObjectHashMap<List<ConnectionBackPressureEvent>> connectionIdToEvents =
        new Long2ObjectHashMap<>();

    void put(final long connectionId, final ConnectionBackPressureEvent event)
    {
        final List<ConnectionBackPressureEvent> events = connectionIdToEvents.computeIfAbsent(
            connectionId, ignore -> new ArrayList<>());
        events.add(event);
    }

    List<ConnectionBackPressureEvent> lookupEvents(final long connectionId)
    {
        return connectionIdToEvents.get(connectionId);
    }

    @Override
    public String toString()
    {
        return "ReproductionLog{" +
            "connectionIdToEvents=" + connectionIdToEvents +
            '}';
    }
}

class ConnectionBackPressureEvent
{
    private final int seqNum;
    private final boolean replay;
    private final int written;

    ConnectionBackPressureEvent(final int seqNum, final boolean replay, final int written)
    {
        this.seqNum = seqNum;
        this.replay = replay;
        this.written = written;
    }

    public int seqNum()
    {
        return seqNum;
    }

    public boolean replay()
    {
        return replay;
    }

    public int written()
    {
        return written;
    }

    @Override
    public String toString()
    {
        return "ConnectionBackPressureEvent{" +
            "seqNum=" + seqNum +
            ", replay=" + replay +
            ", written=" + written +
            '}';
    }
}
