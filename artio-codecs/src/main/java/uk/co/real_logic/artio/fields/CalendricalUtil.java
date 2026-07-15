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
package uk.co.real_logic.artio.fields;

import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static java.lang.String.format;
import static java.time.Year.isLeap;

public final class CalendricalUtil
{
    // ------------ Time Constants ------------
    static final int SECONDS_IN_MINUTE = 60;
    static final int SECONDS_IN_HOUR = SECONDS_IN_MINUTE * 60;
    static final int SECONDS_IN_DAY = SECONDS_IN_HOUR * 24;
    static final long MILLIS_IN_SECOND = 1_000L;
    static final long MICROS_IN_MILLIS = 1_000L;
    static final long MICROS_IN_SECOND = MILLIS_IN_SECOND * MICROS_IN_MILLIS;
    public static final long NANOS_IN_MICROS = 1_000L;
    public static final long NANOS_IN_MILLIS = NANOS_IN_MICROS * MICROS_IN_MILLIS;
    static final long NANOS_IN_SECOND = MICROS_IN_SECOND * NANOS_IN_MICROS;
    static final long MILLIS_IN_DAY = SECONDS_IN_DAY * MILLIS_IN_SECOND;
    static final long MICROS_IN_DAY = SECONDS_IN_DAY * MICROS_IN_SECOND;
    static final long NANOS_IN_DAY = SECONDS_IN_DAY * NANOS_IN_SECOND;

    private CalendricalUtil()
    {
    }

    // ------------ Date Constants ------------

    private static final int MAX_DAYS_IN_YEAR = 365;
    private static final int MONTHS_IN_YEAR = 12;
    private static final int DAYS_IN_400_YEAR_CYCLE = 146097;
    private static final int DAYS_UNTIL_START_OF_UNIX_EPOCH = 719528;

    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;

    private static final int MIN_DAY_OF_MONTH = 1;
    private static final int MAX_DAY_OF_MONTH = 31;

    private static final int MIN_WEEK_OF_MONTH = 1;
    private static final int MAX_WEEK_OF_MONTH = 5;

    // ------------ Decoding ------------

    public static boolean isValidMonth(final int month)
    {
        return month >= MIN_MONTH && month <= MAX_MONTH;
    }

    public static boolean isValidDayOfMonth(final int dayOfMonth)
    {
        return dayOfMonth >= MIN_DAY_OF_MONTH && dayOfMonth <= MAX_DAY_OF_MONTH;
    }

    public static boolean isValidWeekOfMonth(final int weekOfMonth)
    {
        return weekOfMonth >= MIN_WEEK_OF_MONTH && weekOfMonth <= MAX_WEEK_OF_MONTH;
    }

    static int getValidInt(
        final AsciiBuffer timestamp,
        final int startInclusive,
        final int endExclusive,
        final int min,
        final int max)
    {
        final int value = timestamp.getNatural(startInclusive, endExclusive);
        if (value < min || value > max)
        {
            throw new IllegalArgumentException(format("Invalid value: %s outside of range %d-%d", value, min, max));
        }
        return value;
    }

    /**
     * Converts a year/month/day representation of a UTC date to the number of days since the epoch.
     *
     * @param year the year component of the date value to convert
     * @param month the month component of the date value to convert
     * @param day the day component of the date value to convert
     * @return number of days since the epoch
     */
    static int toEpochDay(final int year, final int month, final int day)
    {
        return yearsToDays(year) + monthsToDays(month, year) + (day - 1) - DAYS_UNTIL_START_OF_UNIX_EPOCH;
    }

    private static int monthsToDays(final int month, final int year)
    {
        int days = (367 * month - 362) / MONTHS_IN_YEAR;
        if (month > 2)
        {
            days--;
            if (!isLeap(year))
            {
                days--;
            }
        }
        return days;
    }

    /**
     * @param years a positive number of years
     * @return the number of days in years
     */
    private static int yearsToDays(final int years)
    {
        // All divisions by a statically known constant, so are really multiplications
        return MAX_DAYS_IN_YEAR * years + (years + 3) / 4 - (years + 99) / 100 + (years + 399) / 400;
    }

    // ------------ Encoding ------------

    // Based on:
    // https://github.com/ThreeTen/threetenbp/blob/master/src/main/java/org/threeten/bp/LocalDate.java#L281
    // Simplified to unnecessary remove negative year case.
    static void encodeDate(final long epochDay, final MutableAsciiBuffer string, final int offset)
    {
        // adjust to 0000-03-01 so leap day is at end of four year cycle
        final long zeroDay = epochDay + DAYS_UNTIL_START_OF_UNIX_EPOCH - 60;
        long yearEstimate = (400 * zeroDay + 591) / DAYS_IN_400_YEAR_CYCLE;
        long dayEstimate = estimateDayOfYear(zeroDay, yearEstimate);
        if (dayEstimate < 0)
        {
            // fix estimate
            yearEstimate--;
            dayEstimate = estimateDayOfYear(zeroDay, yearEstimate);
        }
        final int marchDay0 = (int)dayEstimate;

        // convert march-based values back to january-based
        final int marchMonth0 = (marchDay0 * 5 + 2) / 153;
        final int month = (marchMonth0 + 2) % 12 + 1;
        final int day = marchDay0 - (marchMonth0 * 306 + 5) / 10 + 1;
        final int year = (int)(yearEstimate + marchMonth0 / 10);

        string.putNaturalPaddedIntAscii(offset, 4, year);
        string.putNaturalPaddedIntAscii(offset + 4, 2, month);
        string.putNaturalPaddedIntAscii(offset + 6, 2, day);
    }

    private static long estimateDayOfYear(final long zeroDay, final long yearEst)
    {
        return zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
    }
}
