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
package uk.co.real_logic.artio;

import org.agrona.ErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.session.Session;

import java.time.Clock;
import java.time.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.aeron.Publication.BACK_PRESSURED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class SessionSchedulerTest
{
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final FixLibrary library = mock(FixLibrary.class);
    private final SessionConfiguration sessionConfiguration = mock(SessionConfiguration.class);
    private final ScheduledExecutorService timeEventPool = mock(ScheduledExecutorService.class);
    private final Session session = mock(Session.class);
    @SuppressWarnings("unchecked")
    private final Reply<Session> reply = mock(Reply.class);
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);

    private SessionScheduler sessionScheduler;

    @BeforeEach
    public void setup()
    {
        SessionScheduler.timeEventPool = timeEventPool;

        sessionIsConnected(true);

        when(library.initiate(sessionConfiguration)).thenReturn(reply);
        replyIs(Reply.State.EXECUTING);

        sessionScheduler = newSessionScheduler(LocalTime.of(8, 30));
    }

    private void replyIs(final Reply.State executing)
    {
        when(reply.state()).thenReturn(executing);
    }

    @Test
    public void shouldScheduleStartEvent()
    {
        verifyTimeEvent(1_800_000L);

        sessionScheduler = newSessionScheduler(LocalTime.of(9, 30));
        verifyNoTimeEvent();
        assertTrue(sessionScheduler.duringDay(), "not during day");

        sessionScheduler = newSessionScheduler(LocalTime.of(17, 30));
        verifyNoTimeEvent();
        assertNotDuringDay();
    }

    @Test
    public void shouldConnectAfterStartTimePoint()
    {
        initiateConnection();

        replyCompletes();

        sessionScheduler.doWork();

        verify(reply).resultIfPresent();

        reset(library, reply);
    }

    @Test
    public void shouldScheduleEndTimePointAtStart()
    {
        reset(timeEventPool);

        shouldConnectAfterStartTimePoint();
        verifyTimeEvent(30_600_000L);
    }

    @Test
    public void shouldNotInitiateConnectionBeforeStartTimePoint()
    {
        sessionScheduler.doWork();

        verify(library, never()).initiate(sessionConfiguration);
    }

    @Test
    public void shouldDisconnectConnectionAfterEndTimePoint()
    {
        shouldConnectAfterStartTimePoint();

        sessionScheduler.onDayEnd();
        sessionScheduler.doWork();

        verify(session).logoutAndDisconnect();
    }

    @Test
    public void shouldDisconnectConnectionAfterEndTimePointWhenBackPressured()
    {
        when(session.logoutAndDisconnect()).thenReturn(BACK_PRESSURED, 100L);

        shouldConnectAfterStartTimePoint();

        sessionScheduler.onDayEnd();
        sessionScheduler.doWork();
        sessionScheduler.doWork();
        sessionScheduler.doWork();

        verifyLogout(times(2));
    }

    @Test
    public void shouldNotDisconnectConnectionBeforeEndTimePoint()
    {
        shouldConnectAfterStartTimePoint();

        sessionScheduler.doWork();

        verifyLogout(never());
    }

    @Test
    public void shouldRetryOnConnectionErrors()
    {
        final Exception error = new Exception("error");

        initiateConnection();

        replyIs(Reply.State.ERRORED);
        when(reply.error()).thenReturn(error);

        sessionScheduler.doWork();

        verify(errorHandler).onError(error);

        reset(library, reply);

        sessionScheduler.doWork();

        verify(library).initiate(sessionConfiguration);
    }

    @Test
    public void shouldReconnectSessionIfDisconnectedDuringDay()
    {
        shouldConnectAfterStartTimePoint();

        sessionIsConnected(false);

        sessionScheduler.doWork();
        sessionScheduler.doWork();

        verify(library).initiate(sessionConfiguration);
    }

    private void sessionIsConnected(final boolean value)
    {
        when(session.isConnected()).thenReturn(value);
    }

    private void initiateConnection()
    {
        sessionScheduler.onDayStart();
        sessionScheduler.doWork();

        verify(library).initiate(sessionConfiguration);

        sessionScheduler.doWork();

        verify(reply, never()).resultIfPresent();
    }

    private SessionScheduler newSessionScheduler(final LocalTime time)
    {
        final Instant currentTime = ZonedDateTime.of(
            LocalDate.of(2019, Month.FEBRUARY, 8),
            time,
            LONDON).toInstant();

        final Clock clock = Clock.fixed(currentTime, LONDON);

        return new SessionScheduler(
            clock,
            library,
            sessionConfiguration,
            LONDON,
            DayOfWeek.MONDAY,
            DayOfWeek.FRIDAY,
            LocalTime.of(9, 0),
            LocalTime.of(17, 0),
            errorHandler);
    }

    private void verifyLogout(final VerificationMode times)
    {
        verify(session, times).logoutAndDisconnect();
    }

    private void replyCompletes()
    {
        replyIs(Reply.State.COMPLETED);
        when(reply.resultIfPresent()).thenReturn(session);
    }

    private void assertNotDuringDay()
    {
        assertFalse(sessionScheduler.duringDay(), "during day");
    }

    private void verifyTimeEvent(final long delayInMilliseconds)
    {
        verify(timeEventPool, times(1))
            .schedule(any(Runnable.class), eq(delayInMilliseconds), eq(TimeUnit.MILLISECONDS));
        reset(timeEventPool);
    }

    private void verifyNoTimeEvent()
    {
        verify(timeEventPool, never()).schedule(any(Runnable.class), anyLong(), any());
        reset(timeEventPool);
    }
}
