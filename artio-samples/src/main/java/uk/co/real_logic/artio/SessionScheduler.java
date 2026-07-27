package uk.co.real_logic.artio;

import org.agrona.ErrorHandler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.SessionConfiguration;
import uk.co.real_logic.artio.messages.SessionState;
import uk.co.real_logic.artio.session.Session;

import java.time.Clock;
import java.time.*;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Utility class for defining a Scheduled session. A scheduled session
 * is an initiated session that automatically logs itself in and out.
 */
public class SessionScheduler
{
    static ScheduledExecutorService timeEventPool = Executors.newScheduledThreadPool(1);

    private final ErrorHandler errorHandler;
    private final java.time.Clock clock;
    private final FixLibrary library;
    private final SessionConfiguration sessionConfiguration;
    private final Set<DayOfWeek> daysOfWeek;
    private final LocalTime startTime;
    private final LocalTime endTime;

    private enum State
    {
        DISCONNECTED,
        CONNECTED,
        CONNECTING,
        DISCONNECTING
    }

    private volatile boolean duringDay;
    private State state = State.DISCONNECTED;
    private Reply<Session> reply;
    private Session session;

    /**
     * Define a session that connects non-stop on a given number of days.
     *
     * @param library the <code>{@link FixLibrary}</code> instance to use to connect.
     * @param sessionConfiguration the configuration for the session to use to connect.
     * @param timezoneName the name of the timezone that days start and end in.
     * @param startDayName the name of the first day of the week to connect on.
     * @param endDayName the name of the last day of the week to connect on.
     * @param errorHandler a callback for connection errors.
     * @return the SessionScheduler object.
     */
    public static SessionScheduler nonStopSession(
        final FixLibrary library,
        final SessionConfiguration sessionConfiguration,
        final String timezoneName,
        final String startDayName,
        final String endDayName,
        final ErrorHandler errorHandler)
    {
        return new SessionScheduler(
            java.time.Clock.systemDefaultZone(),
            library,
            sessionConfiguration,
            ZoneId.of(timezoneName),
            DayOfWeek.valueOf(startDayName),
            DayOfWeek.valueOf(endDayName),
            LocalTime.of(0, 0),
            LocalTime.of(0, 0),
            errorHandler);
    }

    /**
     * Define a {@link SessionScheduler} that connects between specified times on specified days.
     *
     * @param library the <code>{@link FixLibrary}</code> instance to use to connect.
     * @param sessionConfiguration the configuration for the session to use to connect.
     * @param timezoneName the name of the timezone that days start and end in.
     * @param startDayName the name of the first day of the week to connect on.
     * @param endDayName the name of the last day of the week to connect on.
     * @param startTimeConfiguration the time to start a connection to the exchange.
     * @param endTimeConfiguration the time to end a connection to the exchange.
     * @param errorHandler a callback for connection errors.
     * @return the SessionScheduler object.
     */
    public static SessionScheduler fromRawConfig(
        final FixLibrary library,
        final SessionConfiguration sessionConfiguration,
        final String timezoneName,
        final String startDayName,
        final String endDayName,
        final String startTimeConfiguration,
        final String endTimeConfiguration,
        final ErrorHandler errorHandler)
    {
        return new SessionScheduler(
            java.time.Clock.systemDefaultZone(),
            library,
            sessionConfiguration,
            ZoneId.of(timezoneName),
            DayOfWeek.valueOf(startDayName),
            DayOfWeek.valueOf(endDayName),
            LocalTime.parse(startTimeConfiguration),
            LocalTime.parse(endTimeConfiguration),
            errorHandler);
    }

    /**
     * Define a {@link SessionScheduler} that connects between specified times on specified days.
     *
     * @param clock the clock to use as a source of time.
     * @param library the <code>{@link FixLibrary}</code> instance to use to connect.
     * @param sessionConfiguration the configuration for the session to use to connect.
     * @param timezone the timezone that days start and end in.
     * @param startDay the first day of the week to connect on.
     * @param endDay the name of the last day of the week to connect on.
     * @param startTime the time to start a connection to the exchange.
     * @param endTime the time to end a connection to the exchange.
     * @param errorHandler a callback for connection errors.
     */
    public SessionScheduler(
        final Clock clock,
        final FixLibrary library,
        final SessionConfiguration sessionConfiguration,
        final ZoneId timezone,
        final DayOfWeek startDay,
        final DayOfWeek endDay,
        final LocalTime startTime,
        final LocalTime endTime,
        final ErrorHandler errorHandler)
    {
        this.clock = clock.withZone(timezone);
        this.library = library;
        this.sessionConfiguration = sessionConfiguration;
        this.startTime = startTime;
        this.endTime = endTime;
        this.errorHandler = errorHandler;

        daysOfWeek = Arrays
            .stream(DayOfWeek.values())
            .filter(dayOfWeek -> dayOfWeek.compareTo(startDay) >= 0 && dayOfWeek.compareTo(endDay) <= 0)
            .collect(Collectors.toSet());

        scheduleStart();
    }

    /**
     * Getter for whether you are currently during your normally connected working day.
     *
     * @return whether you are currently during your normally connected working day.
     */
    public boolean duringDay()
    {
        return duringDay;
    }

    /**
     * Duty cycle - to be polled on the same thread as the <code>{@link FixLibrary}</code>.
     *
     * @return the number of ites of work done.
     */
    public int doWork()
    {
        switch (state)
        {
            case DISCONNECTED:
            {
                if (duringDay)
                {
                    reply = library.initiate(sessionConfiguration);
                    state = State.CONNECTING;
                    return 1;
                }

                return 0;
            }

            case CONNECTING:
            {
                switch (reply.state())
                {
                    case COMPLETED:
                    {
                        session = reply.resultIfPresent();
                        state = State.CONNECTED;
                        scheduleEnd();
                        return 1;
                    }

                    case ERRORED:
                    {
                        errorHandler.onError(reply.error());
                        reply = null;
                        state = State.DISCONNECTED;
                        return 1;
                    }

                    case TIMED_OUT:
                    {
                        errorHandler.onError(new TimeoutException("Timeout connected to Session"));
                        reply = null;
                        state = State.DISCONNECTED;
                        return 1;
                    }

                    default:
                        break;
                }

                return 0;
            }

            case CONNECTED:
            {
                if (!duringDay)
                {
                    final long position = session.logoutAndDisconnect();
                    if (position > 0)
                    {
                        state = State.DISCONNECTING;
                    }

                    return 1;
                }
                else if (!session.isConnected())
                {
                    session = null;
                    state = State.DISCONNECTED;
                    return 1;
                }

                return 0;
            }

            case DISCONNECTING:
            {
                if (session.state() == SessionState.DISCONNECTED)
                {
                    session = null;
                    state = State.DISCONNECTED;
                    scheduleStart();
                    return 1;
                }

                return 0;
            }
        }

        return 0;
    }

    private void scheduleStart()
    {
        final ZonedDateTime now = ZonedDateTime.now(clock);
        if (daysOfWeek.contains(now.getDayOfWeek()))
        {
            final LocalTime currentTime = now.toLocalTime();
            final long startGapMs = Duration.between(currentTime, startTime).toMillis();
            if (startGapMs <= 0)
            {
                final long endGapMs = Duration.between(currentTime, endTime).toMillis();
                if (endGapMs > 0)
                {
                    onDayStart();
                }
            }
            else
            {
                scheduleEvent(startGapMs, this::onDayStart);
            }
        }
    }

    private void scheduleEnd()
    {
        final ZonedDateTime now = ZonedDateTime.now(clock);
        if (daysOfWeek.contains(now.getDayOfWeek()))
        {
            final LocalTime currentTime = now.toLocalTime();
            final long endGapMs = Duration.between(currentTime, endTime).toMillis();
            if (endGapMs <= 0)
            {
                onDayEnd();
            }
            else
            {
                scheduleEvent(endGapMs, this::onDayEnd);
            }
        }
    }

    private void scheduleEvent(final long gapMs, final Runnable command)
    {
        timeEventPool.schedule(command, gapMs, TimeUnit.MILLISECONDS);
    }

    void onDayStart()
    {
        duringDay = true;
    }

    void onDayEnd()
    {
        duringDay = false;
    }
}
