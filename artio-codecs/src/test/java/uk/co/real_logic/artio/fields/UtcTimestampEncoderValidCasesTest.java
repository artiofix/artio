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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.fields.CalendricalUtil.*;
import static uk.co.real_logic.artio.fields.UtcTimestampDecoderValidCasesTest.toEpochMillis;
import static uk.co.real_logic.artio.fields.EpochFractionFormat.*;
import static uk.co.real_logic.artio.util.CustomMatchers.sequenceEqualsAscii;

public class UtcTimestampEncoderValidCasesTest
{
    private String expectedTimestamp;
    private String expectedTimestampMicros;
    private String expectedTimestampNanos;
    private long epochMillis;
    private long epochMicros;
    private long epochNanos;
    private int expectedLength;
    private int expectedLengthMicros;
    private int expectedLengthNanos;

    public static Stream<Arguments> data()
    {
        return UtcTimestampDecoderValidCasesTest.data();
    }

    public static Stream<String> timestamps()
    {
        return UtcTimestampDecoderValidCasesTest.timestamps();
    }

    public void prepareExpected(final String timestamp)
    {
        epochMillis = toEpochMillis(timestamp);
        expectedLength = UtcTimestampEncoder.LENGTH_WITH_MILLISECONDS;
        expectedTimestamp = timestamp;
        expectedTimestampMicros = timestamp + "001";
        expectedTimestampNanos = timestamp + "000001";
        expectedLengthMicros = expectedLength + 3;
        expectedLengthNanos = expectedLength + 6;
        epochMicros = epochMillis * MICROS_IN_MILLIS + 1;
        epochNanos = epochMillis * NANOS_IN_MILLIS + 1;
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void canStaticEncodeTimestampWithOffset(final String timestamp)
    {
        prepareExpected(timestamp);
        assertInstanceEncodesTimestampMillisWithOffset(epochMillis, expectedTimestamp, expectedLength);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void canInstanceEncodeTimestamp(final String timestamp)
    {
        prepareExpected(timestamp);
        assertInstanceEncodesTimestampMillis(epochMillis, expectedTimestamp, expectedLength);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void canStaticEncodeTimestampWithOffsetMicros(final String timestamp)
    {
        prepareExpected(timestamp);
        assertInstanceEncodesTimestampMicrosWithOffset(epochMicros, expectedTimestampMicros, expectedLengthMicros);
    }

    @ParameterizedTest
    @MethodSource(value = "timestamps")
    public void canInstanceEncodeTimestampMicros(final String timestamp)
    {
        prepareExpected(timestamp);
        assertInstanceEncodesTimestampMicros(epochMicros, expectedTimestampMicros, expectedLengthMicros);
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canStaticEncodeTimestampWithOffsetNanos(final String timestamp, final boolean validNanoSecondTestCase)
    {
        prepareExpected(timestamp);
        if (validNanoSecondTestCase)
        {
            assertInstanceEncodesTimestampNanosWithOffset(epochNanos, expectedTimestampNanos, expectedLengthNanos);
        }
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canInstanceEncodeTimestampNanos(final String timestamp, final boolean validNanoSecondTestCase)
    {
        prepareExpected(timestamp);
        if (validNanoSecondTestCase)
        {
            assertInstanceEncodesTimestampNanos(epochNanos, expectedTimestampNanos, expectedLengthNanos);
        }
    }

    static void assertInstanceEncodesTimestampMillisWithOffset(
        final long epochMillis, final String expectedTimestamp, final int expectedLength)
    {
        assertInstanceEncodesTimestamp(epochMillis, expectedTimestamp, expectedLength, MILLISECONDS);
    }

    static void assertInstanceEncodesTimestampMillis(
        final long epochMillis, final String expectedTimestamp, final int expectedLength)
    {
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder();
        final int length = encoder.encode(epochMillis);

        assertEquals(expectedTimestamp, new String(encoder.buffer(), 0, length, US_ASCII));
        assertEquals(expectedLength, length, "encoded wrong length");
    }

    static void assertInstanceEncodesTimestampMicrosWithOffset(
        final long epochMicros, final String expectedTimestampMicros, final int expectedLength)
    {
        final MutableAsciiBuffer string = new MutableAsciiBuffer(new byte[expectedLength + 2]);

        final int length = UtcTimestampEncoder.encodeMicros(epochMicros, string, 1);

        assertThat(string, sequenceEqualsAscii(expectedTimestampMicros, 1, length));
        assertEquals(expectedLength, length, "encoded wrong length");
    }

    static void assertInstanceEncodesTimestampMicros(
        final long epochMicros, final String expectedTimestampMicros, final int expectedLength)
    {
        assertInstanceEncodesTimestamp(epochMicros, expectedTimestampMicros, expectedLength, MICROSECONDS);
    }

    static void assertInstanceEncodesTimestampNanosWithOffset(
        final long epochNanos, final String expectedTimestampNanos, final int expectedLength)
    {
        final MutableAsciiBuffer string = new MutableAsciiBuffer(new byte[expectedLength + 2]);

        final int length = UtcTimestampEncoder.encodeNanos(epochNanos, string, 1);

        assertThat(string, sequenceEqualsAscii(expectedTimestampNanos, 1, length));
        assertEquals(expectedLength, length, "encoded wrong length");
    }

    static void assertInstanceEncodesTimestampNanos(
        final long epochNanos, final String expectedTimestampNanos, final int expectedLength)
    {
        assertInstanceEncodesTimestamp(epochNanos, expectedTimestampNanos, expectedLength, NANOSECONDS);
    }

    private static void assertInstanceEncodesTimestamp(
        final long epochFraction,
        final String expectedTimestamp,
        final int expectedLength,
        final EpochFractionFormat epochFractionFormat)
    {
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder(epochFractionFormat);
        final int length = encoder.encode(epochFraction);

        assertEquals(expectedTimestamp, new String(encoder.buffer(), 0, length, US_ASCII));
        assertEquals(expectedLength, length, "encoded wrong length");
    }

}
