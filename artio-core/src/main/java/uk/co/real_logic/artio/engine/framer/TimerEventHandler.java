/*
 * Copyright 2022 Adaptive Financial Consulting Ltd.
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

import org.agrona.DeadlineTimerWheel;
import org.agrona.ErrorHandler;
import org.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.artio.engine.framer.GatewaySessions.PendingAcceptorLogon;

import java.util.concurrent.TimeUnit;

final class TimerEventHandler implements DeadlineTimerWheel.TimerHandler
{
    private final Long2ObjectHashMap<PendingAcceptorLogon> timerIdToPendingAcceptorLogons = new Long2ObjectHashMap<>();

    private final ErrorHandler errorHandler;

    TimerEventHandler(final ErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
    }

    public boolean onTimerExpiry(final TimeUnit timeUnit, final long now, final long timerId)
    {
        final PendingAcceptorLogon pendingAcceptorLogon = timerIdToPendingAcceptorLogons.get(timerId);
        if (pendingAcceptorLogon == null)
        {
            errorHandler.onError(new IllegalStateException("Unknown timer id: " + timerId));
            return true;
        }
        else
        {
            final boolean handled = pendingAcceptorLogon.onLingerTimeout();
            if (handled)
            {
                timerIdToPendingAcceptorLogons.remove(timerId);
            }
            return handled;
        }
    }

    public void startLingering(final long timerId, final PendingAcceptorLogon pendingAcceptorLogon)
    {
        timerIdToPendingAcceptorLogons.put(timerId, pendingAcceptorLogon);
    }
}
