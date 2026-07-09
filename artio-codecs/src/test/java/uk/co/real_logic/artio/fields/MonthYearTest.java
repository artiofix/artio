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

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static java.time.Month.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.of;

public class MonthYearTest
{
    private final MonthYear monthYear = new MonthYear();

    public static Stream<Arguments> validMonthYears()
    {
        return Stream.of(
            of("000101", MonthYear.of(1, JANUARY)),
            of("201502", MonthYear.of(2015, FEBRUARY)),
            of("999912", MonthYear.of(9999, DECEMBER))
        );
    }

    public static Stream<Arguments> validMonthYearsWithDay()
    {
        return Stream.of(
            of("00010101", MonthYear.withDayOfMonth(1, JANUARY, 1)),
            of("20150225", MonthYear.withDayOfMonth(2015, FEBRUARY, 25)),
            of("99991231", MonthYear.withDayOfMonth(9999, DECEMBER, 31))
        );
    }

    public static Stream<Arguments> validMonthYearsWithWeek()
    {
        return Stream.of(
            of("000101w1", MonthYear.withWeekOfMonth(1, JANUARY, 1)),
            of("201502w5", MonthYear.withWeekOfMonth(2015, FEBRUARY, 5)),
            of("999912w3", MonthYear.withWeekOfMonth(9999, DECEMBER, 3))
        );
    }

    public static Stream<Arguments> invalidMonthYearsWithDay()
    {
        return Stream.of(
            of("00010101"),
            of("20150225"),
            of("99991231")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYears")
    public void shouldToStringValidDates(final String input, final MonthYear expectedMonthYear)
    {
        assertThat(expectedMonthYear, hasToString(input));
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithDay")
    public void shouldToStringValidDatesWithDay(final String input, final MonthYear expectedMonthYear)
    {
        assertThat(expectedMonthYear, hasToString(input));
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithWeek")
    public void shouldToStringValidDatesWithWeek(final String input, final MonthYear expectedMonthYear)
    {
        assertThat(expectedMonthYear, hasToString(input));
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYears")
    public void shouldDecodeValidDates(final String input, final MonthYear expectedMonthYear)
    {
        assertDecodesMonthYear(input, expectedMonthYear);
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithDay")
    public void shouldDecodeValidDatesWithDay(final String input, final MonthYear expectedMonthYear)
    {
        assertDecodesMonthYear(input, expectedMonthYear);
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithWeek")
    public void shouldDecodeValidDatesWithWeek(final String input, final MonthYear expectedMonthYear)
    {
        assertDecodesMonthYear(input, expectedMonthYear);
    }

    public static Stream<Arguments> invalidMonthYearsWithWeek()
    {
        return Stream.of(of("201502w0"), of("201502w6"), of("999912w9"));
    }

    @ParameterizedTest
    @MethodSource(value = "invalidMonthYearsWithWeek")
    public void shouldRejectInvalidWeekOfMonth(final String input)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[input.length()]);
        final MutableAsciiBuffer asciiFlyweight = new MutableAsciiBuffer(buffer);
        asciiFlyweight.putAscii(0, input);

        assertFalse(monthYear.decode(asciiFlyweight, 0, input.length()));
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYears")
    public void shouldEncodeValidDates(final String input, final MonthYear expectedMonthYear)
    {
        assertEncodesMonthYear(input, expectedMonthYear);
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithDay")
    public void shouldEncodeValidDatesWithDay(final String input, final MonthYear expectedMonthYear)
    {
        assertEncodesMonthYear(input, expectedMonthYear);
    }

    @ParameterizedTest
    @MethodSource(value = "validMonthYearsWithWeek")
    public void shouldEncodeValidDatesWithWeek(final String input, final MonthYear expectedMonthYear)
    {
        assertEncodesMonthYear(input, expectedMonthYear);
    }

    private void assertDecodesMonthYear(final String input, final MonthYear expectedMonthYear)
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[input.length()]);
        final MutableAsciiBuffer asciiFlyweight = new MutableAsciiBuffer(buffer);
        asciiFlyweight.putAscii(0, input);

        final boolean decode = monthYear.decode(asciiFlyweight, 0, input.length());

        assertTrue(decode, String.format("Failed to decode %s correctly", input));
        assertEquals(expectedMonthYear, monthYear);
    }

    private void assertEncodesMonthYear(final String input, final MonthYear monthYear)
    {
        final int expectedLength = input.length();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[expectedLength]);
        final MutableAsciiBuffer asciiFlyweight = new MutableAsciiBuffer(buffer);

        final int length = monthYear.encode(asciiFlyweight, 0);

        assertEquals(expectedLength, length, String.format("Failed to encode %s correctly", input));
    }
}
