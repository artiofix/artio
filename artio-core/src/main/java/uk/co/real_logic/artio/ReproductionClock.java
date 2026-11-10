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
package uk.co.real_logic.artio;

import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.util.CharFormatter;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static uk.co.real_logic.artio.engine.framer.ReproductionProtocolHandler.REPRO_DEBUG_ENABLED;

public class ReproductionClock implements EpochNanoClock
{
    private final CharFormatter formatter = new CharFormatter("Clock.advance:old=%s,new=%s,back=%s");

    private static final long NS_IN_MS = MILLISECONDS.toNanos(1);

    private long timeInNs;

    public ReproductionClock(final long timeInNs)
    {
        this.timeInNs = timeInNs;
    }

    @Override
    public long nanoTime()
    {
        return timeInNs;
    }

    public void advanceTimeTo(final long timeInNs)
    {
        final long oldTimeInNs = this.timeInNs;

        if (REPRO_DEBUG_ENABLED)
        {
            DebugLogger.log(LogTag.REPRODUCTION,
                formatter,
                oldTimeInNs,
                timeInNs,
                oldTimeInNs > timeInNs ? "yes" : "no");
        }

        // It's possible for a new timestamp to be lower because heartbeats use cached timestamps
        this.timeInNs = Math.max(oldTimeInNs, timeInNs);
    }

    public EpochClock toMillis()
    {
        return () -> timeInNs / NS_IN_MS;
    }
}
