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
package uk.co.real_logic.artio.util;

import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.fields.CalendricalUtil;
import uk.co.real_logic.artio.fields.EpochFractionFormat;

/**
 * A factory for {@link EpochFractionClock}
 * Based internally on an {@link EpochNanoClock}
 */
public final class EpochFractionClocks
{
    public static EpochFractionClock millisClock(final EpochNanoClock clock)
    {
        return new MillisBasedClock(clock);
    }

    public static EpochFractionClock create(final EpochClock clock, final EpochNanoClock nanoClock,
        final EpochFractionFormat format)
    {
        switch (format)
        {
            case NANOSECONDS:
                return new NanoBasedClock(nanoClock);

            case MICROSECONDS:
                return new MicrosBasedClock(nanoClock);

            case MILLISECONDS:
                return new MillisBasedClock(nanoClock);

            default:
                throw new RuntimeException("Unknown precision: " + format);
        }
    }

    static class MillisBasedClock implements EpochFractionClock
    {
        private final EpochNanoClock clock;

        MillisBasedClock(final EpochNanoClock clock)
        {
            this.clock = clock;
        }

        @Override
        public long epochFractionTime()
        {
            return clock.nanoTime() / CalendricalUtil.NANOS_IN_MILLIS;
        }

        @Override
        public EpochFractionFormat epochFractionPrecision()
        {
            return EpochFractionFormat.MILLISECONDS;
        }
    }

    static class MicrosBasedClock implements EpochFractionClock
    {
        private final EpochNanoClock epochNanoClock;

        MicrosBasedClock(final EpochNanoClock epochNanoClock)
        {
            this.epochNanoClock = epochNanoClock;
        }

        @Override
        public long epochFractionTime()
        {
            return epochNanoClock.nanoTime() / CalendricalUtil.NANOS_IN_MICROS;
        }

        @Override
        public EpochFractionFormat epochFractionPrecision()
        {
            return EpochFractionFormat.MICROSECONDS;
        }
    }

    static class NanoBasedClock implements EpochFractionClock
    {
        private final EpochNanoClock epochNanoClock;

        NanoBasedClock(final EpochNanoClock epochNanoClock)
        {
            this.epochNanoClock = epochNanoClock;
        }

        @Override
        public long epochFractionTime()
        {
            return epochNanoClock.nanoTime();
        }

        @Override
        public EpochFractionFormat epochFractionPrecision()
        {
            return EpochFractionFormat.NANOSECONDS;
        }
    }
}
