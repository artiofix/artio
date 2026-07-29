/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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

import org.agrona.ErrorHandler;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.errors.DistinctErrorLog;

import static io.aeron.driver.Configuration.ERROR_BUFFER_LENGTH_PROP_NAME;

public interface ErrorHandlerFactory
{
    static ErrorHandlerFactory saveDistinctErrors()
    {
        return errorBuffer ->
        {
            final EpochClock clock = new SystemEpochClock();
            final DistinctErrorLog distinctErrorLog = new DistinctErrorLog(errorBuffer, clock);
            return (throwable) ->
            {
                if (!distinctErrorLog.record(throwable))
                {
                    System.err.println("Error Log is full, consider increasing " + ERROR_BUFFER_LENGTH_PROP_NAME);
                    throwable.printStackTrace();
                }
            };
        };
    }

    ErrorHandler make(AtomicBuffer errorBuffer);
}
