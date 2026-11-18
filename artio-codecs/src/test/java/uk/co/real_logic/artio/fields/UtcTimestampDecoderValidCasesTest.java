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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.time.temporal.ChronoField.MILLI_OF_SECOND;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;
import static uk.co.real_logic.artio.fields.CalendricalUtil.*;
import static uk.co.real_logic.artio.fields.UtcTimestampDecoder.*;

public class UtcTimestampDecoderValidCasesTest
{
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss[.SSS]");

    public static long toEpochMillis(final String timestamp)
    {
        final LocalDateTime parsedDate = LocalDateTime.parse(timestamp, FORMATTER);
        final ZonedDateTime utc = ZonedDateTime.of(parsedDate, ZoneId.of("UTC"));
        return SECONDS.toMillis(utc.toEpochSecond()) + utc.getLong(MILLI_OF_SECOND);
    }

    private int length;
    private long expectedEpochMillis;
    private long expectedEpochMicros;
    private long expectedEpochNanos;
    private MutableAsciiBuffer buffer;
    private String timestamp;

    public static Stream<String> timestamps()
    {
        return Stream.of(
            "20150225-17:51:32.000",
            "20150225-17:51:32.123",
            "20600225-17:51:32.123",
            "19700101-00:00:00.000",
            "00010101-00:00:00.000",
            "00010101-00:00:00.001",
            "99991231-23:59:59.999"
        );
    }

    public static Stream<String> nanosecondTimestamps()
    {
        return timestamps().filter(UtcTimestampDecoderValidCasesTest::fitsInEpochNanos);
    }

    // Epoch nanoseconds in a long only cover roughly 1677 to 2262, so e.g. years 0001 and 9999 can't be encoded.
    public static boolean fitsInEpochNanos(final String timestamp)
    {
        final long epochMillis = toEpochMillis(timestamp);
        return epochMillis <= Long.MAX_VALUE / NANOS_IN_MILLIS && epochMillis >= Long.MIN_VALUE / NANOS_IN_MILLIS;
    }

    public static Stream<Arguments> data()
    {
        return timestamps().map(timestamp -> of(timestamp, fitsInEpochNanos(timestamp)));
    }

    private void encodeBuffer(final String timestamp)
    {
        this.timestamp = timestamp;

        expectedEpochMillis = toEpochMillis(timestamp);
        length = timestamp.length();

        expectedEpochNanos = expectedEpochMillis * NANOS_IN_MILLIS;

        final byte[] bytes = timestamp.getBytes(US_ASCII);
        buffer = new MutableAsciiBuffer(new byte[LENGTH_WITH_NANOSECONDS + 2]);
        buffer.putBytes(1, bytes);
        expectedEpochMicros = expectedEpochMillis * MICROS_IN_MILLIS;
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void shouldParseTimestampMillis(final String timestamp)
    {
        encodeBuffer(timestamp);
        assertDecodeMillis(length);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void shouldParseTimestampMillisLong(final String timestamp)
    {
        encodeBuffer(timestamp);
        putMicros();

        assertDecodeMillis(LENGTH_WITH_MICROSECONDS);
    }

    private void assertDecodeMillis(final int lengthWithMicroseconds)
    {
        final long epochMillis = UtcTimestampDecoder.decode(buffer, 1, lengthWithMicroseconds, true);
        assertEquals(expectedEpochMillis, epochMillis, "Failed Millis testcase for: " + timestamp);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void shouldParseTimestampMicros(final String timestamp)
    {
        encodeBuffer(timestamp);
        expectedEpochMicros++;
        putMicros();

        assertDecodesMicros(LENGTH_WITH_MICROSECONDS);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void shouldParseTimestampMicrosShort(final String timestamp)
    {
        encodeBuffer(timestamp);
        assertDecodesMicros(length);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void shouldParseTimestampMicrosLong(final String timestamp)
    {
        encodeBuffer(timestamp);
        putNanos();

        assertDecodesMicros(LENGTH_WITH_NANOSECONDS);
    }

    private void assertDecodesMicros(final int length)
    {
        final long epochMicros = UtcTimestampDecoder.decodeMicros(buffer, 1, length, true);
        assertEquals(expectedEpochMicros, epochMicros,
            "Failed Micros testcase for: " + buffer.getAscii(1, length));
    }

    @ParameterizedTest
    @MethodSource(value = "nanosecondTimestamps")
    public void shouldParseTimestampNanos(final String timestamp)
    {
        encodeBuffer(timestamp);
        // If they've got the suffix field, then test microseconds, add 1 to the value
        expectedEpochNanos++;
        putNanos();

        assertDecodesNanos(LENGTH_WITH_NANOSECONDS);
    }

    @ParameterizedTest
    @MethodSource(value = "nanosecondTimestamps")
    public void shouldParseTimestampNanosShort(final String timestamp)
    {
        encodeBuffer(timestamp);
        expectedEpochNanos += NANOS_IN_MICROS;

        putMicros();

        assertDecodesNanos(LENGTH_WITH_MICROSECONDS);
    }

    private void assertDecodesNanos(final int length)
    {
        final long epochNanos = UtcTimestampDecoder.decodeNanos(buffer, 1, length, true);
        assertEquals(expectedEpochNanos, epochNanos,
            "Failed Nanos testcase for: " + timestamp);
    }

    private void putNanos()
    {
        putSuffix("000001");
    }

    private void putMicros()
    {
        putSuffix("001");
    }

    private void putSuffix(final String suffix)
    {
        buffer.putAscii(length + 1, suffix);
    }

}
