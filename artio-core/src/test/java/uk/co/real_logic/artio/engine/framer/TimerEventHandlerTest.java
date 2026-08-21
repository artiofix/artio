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

import org.agrona.ErrorHandler;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.engine.framer.GatewaySessions.PendingAcceptorLogon;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class TimerEventHandlerTest
{
    private static final long TIMER_ID = 42;
    private static final long NOW_IN_MS = 1_000;

    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private final PendingAcceptorLogon pendingAcceptorLogon = mock(PendingAcceptorLogon.class);
    private final TimerEventHandler timerEventHandler = new TimerEventHandler(errorHandler);

    @Test
    public void shouldStopTrackingPendingLogonOnceLingerCompletes()
    {
        when(pendingAcceptorLogon.onLingerTimeout()).thenReturn(true);
        timerEventHandler.startLingering(TIMER_ID, pendingAcceptorLogon);

        assertTrue(timerEventHandler.onTimerExpiry(MILLISECONDS, NOW_IN_MS, TIMER_ID));

        verify(pendingAcceptorLogon).onLingerTimeout();
        verifyNoInteractions(errorHandler);

        assertTrue(timerEventHandler.onTimerExpiry(MILLISECONDS, NOW_IN_MS, TIMER_ID));

        verify(pendingAcceptorLogon, times(1)).onLingerTimeout();
        verify(errorHandler).onError(any(IllegalStateException.class));
    }

    @Test
    public void shouldKeepTrackingPendingLogonUntilLingerCompletes()
    {
        when(pendingAcceptorLogon.onLingerTimeout()).thenReturn(false, true);
        timerEventHandler.startLingering(TIMER_ID, pendingAcceptorLogon);

        assertFalse(timerEventHandler.onTimerExpiry(MILLISECONDS, NOW_IN_MS, TIMER_ID));
        assertTrue(timerEventHandler.onTimerExpiry(MILLISECONDS, NOW_IN_MS, TIMER_ID));

        verify(pendingAcceptorLogon, times(2)).onLingerTimeout();
        verifyNoInteractions(errorHandler);
    }

    @Test
    public void shouldReportAnUnknownTimerId()
    {
        assertTrue(timerEventHandler.onTimerExpiry(MILLISECONDS, NOW_IN_MS, TIMER_ID));

        verify(errorHandler).onError(any(IllegalStateException.class));
    }
}
