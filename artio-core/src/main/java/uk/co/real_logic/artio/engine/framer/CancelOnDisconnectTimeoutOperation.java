/*
 * Copyright 2021 Monotonic Ltd.
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

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.FixGatewayException;

import static io.aeron.Publication.BACK_PRESSURED;

abstract class CancelOnDisconnectTimeoutOperation implements Continuation
{
    protected final long sessionId;

    private final long timeInNs;
    private final EpochNanoClock clock;
    private final ErrorHandler errorHandler;

    protected CancelOnDisconnectTimeoutOperation(
        final long sessionId, final long timeInNs, final EpochNanoClock clock, final ErrorHandler errorHandler)
    {
        this.sessionId = sessionId;
        this.timeInNs = timeInNs;
        this.clock = clock;
        this.errorHandler = errorHandler;
    }

    @Override
    public long attempt()
    {
        if (clock.nanoTime() > timeInNs)
        {
            try
            {
                onCancelOnDisconnectTimeout();
            }
            catch (final Throwable t)
            {
                cancelOnDisconnectException(t);
            }
            return COMPLETE;
        }
        else
        {
            return BACK_PRESSURED;
        }
    }

    protected abstract void onCancelOnDisconnectTimeout();

    private void cancelOnDisconnectException(final Throwable t)
    {
        errorHandler.onError(new FixGatewayException(
            "Error executing cancel on disconnect timeout handler", t));
    }

    long sessionId()
    {
        return sessionId;
    }
}
