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
package uk.co.real_logic.artio.system_tests;

import org.agrona.concurrent.errors.ErrorConsumer;

public class ErrorCounter implements ErrorConsumer
{
    private String containsString;

    private volatile int lastObservationCount;

    @Override
    public void accept(
        final int observationCount,
        final long firstObservationTimestamp,
        final long lastObservationTimestamp,
        final String encodedException)
    {
        if (containsString == null || encodedException.contains(containsString))
        {
            lastObservationCount = observationCount;
        }
    }

    public int lastObservationCount()
    {
        return lastObservationCount;
    }

    public void containsString(final String containsString)
    {
        this.containsString = containsString;
    }
}
