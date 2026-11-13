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
package uk.co.real_logic.artio.timing;

import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.EpochClock;

import java.util.List;

public class HistogramLogAgent implements Agent
{
    private final List<Timer> timers;
    private final HistogramHandler histogramHandler;
    private final long intervalInMs;
    private final EpochClock milliClock;
    private final String agentNamePrefix;

    private long nextWriteTimeInMs = 0;

    @SuppressWarnings("FinalParameters")
    public HistogramLogAgent(
        final List<Timer> timers,
        final String logFile,
        final long intervalInMs,
        final ErrorHandler errorHandler,
        final EpochClock milliClock,
        HistogramHandler histogramHandler,
        final String agentNamePrefix)
    {
        this.timers = timers;
        this.intervalInMs = intervalInMs;
        this.milliClock = milliClock;
        this.agentNamePrefix = agentNamePrefix;

        if (histogramHandler == null)
        {
            histogramHandler = new HistogramLogWriter(timers.size(), logFile, errorHandler);
        }

        this.histogramHandler = histogramHandler;
        timers.forEach(timer -> this.histogramHandler.identifyTimer(timer.id(), timer.name()));
        histogramHandler.onEndTimerIdentification();
    }

    @Override
    public int doWork()
    {
        final long currentTimeInMs = milliClock.time();

        if (currentTimeInMs > nextWriteTimeInMs)
        {
            logHistograms(currentTimeInMs);

            nextWriteTimeInMs = currentTimeInMs + intervalInMs;
            return 1;
        }

        return 0;
    }

    private void logHistograms(final long currentTimeInMs)
    {
        final List<Timer> timers = this.timers;
        final HistogramHandler histogramHandler = this.histogramHandler;

        histogramHandler.onBeginTimerUpdate(currentTimeInMs);
        for (int i = 0, size = timers.size(); i < size; i++)
        {
            final Timer timer = timers.get(i);
            histogramHandler.onTimerUpdate(timer.id(), timer.getTimings());
        }
        histogramHandler.onEndTimerUpdate();
    }

    @Override
    public String roleName()
    {
        return agentNamePrefix + "HistogramLogger";
    }

    @Override
    public void onClose()
    {
        CloseHelper.close(histogramHandler);
    }
}
