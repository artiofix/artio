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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.fields.CalendricalUtil.MICROS_IN_MILLIS;
import static uk.co.real_logic.artio.fields.CalendricalUtil.NANOS_IN_MILLIS;
import static uk.co.real_logic.artio.fields.UtcTimestampDecoderValidCasesTest.toEpochMillis;
import static uk.co.real_logic.artio.fields.EpochFractionFormat.MICROSECONDS;
import static uk.co.real_logic.artio.fields.EpochFractionFormat.NANOSECONDS;

public class UtcTimestampEncoderUpdateValidCasesTest
{

    private String expectedTimestamp;
    private String expectedTimestampMicros;
    private long epochMillis;
    private long epochMicros;
    private long otherEpochMicros;
    private int expectedLength;
    private int expectedLengthMicros;
    private boolean validNanoSecondTestCase;
    private long epochNanos;
    private long otherEpochNanos;
    private int expectedLengthNanos;
    private String expectedTimestampNanos;

    public static Stream<Arguments> data()
    {
        return UtcTimestampDecoderValidCasesTest
            .data()
            .flatMap(x -> UtcTimestampDecoderValidCasesTest
                .data()
                .map(y -> Arguments.of(x.get()[0], toEpochMillis(y.get()[0].toString()), x.get()[1], y.get()[1])));
    }

    private void prepare(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstvalidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        this.expectedTimestamp = timestamp;
        validNanoSecondTestCase = firstvalidNanoSecondTestCase && secondValidNanoSecondTestCase;
        epochMillis = toEpochMillis(expectedTimestamp);
        expectedLength = expectedTimestamp.length();
        expectedLengthMicros = expectedLength + 3;
        expectedLengthNanos = expectedLength + 6;
        expectedTimestampMicros = expectedTimestamp + "001";
        expectedTimestampNanos = expectedTimestamp + "000001";
        epochMicros = epochMillis * MICROS_IN_MILLIS + 1;
        epochNanos = epochMillis * NANOS_IN_MILLIS + 1;
        otherEpochMicros = otherEpochMillis * MICROS_IN_MILLIS + 1;
        otherEpochNanos = otherEpochMillis * NANOS_IN_MILLIS + 1;
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestamp(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder();
        encoder.initialise(otherEpochMillis);

        final int length = encoder.update(epochMillis);

        assertEquals(expectedLength, length, "encoded wrong length");
        assertEquals(expectedTimestamp, new String(encoder.buffer(), 0, length, US_ASCII));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestampWithoutInitialize(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder();

        final int length = encoder.update(epochMillis);

        assertEquals(expectedLength, length, "encoded wrong length");
        assertEquals(expectedTimestamp, new String(encoder.buffer(), 0, length, US_ASCII));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestampMicros(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder(MICROSECONDS);
        encoder.initialise(otherEpochMicros);

        final int length = encoder.update(epochMicros);

        assertEquals(expectedLengthMicros, length, "encoded wrong length");
        assertEquals(expectedTimestampMicros, new String(encoder.buffer(), 0, length, US_ASCII));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestampMicrosWithoutInitialise(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        final UtcTimestampEncoder encoder = new UtcTimestampEncoder(MICROSECONDS);

        final int length = encoder.update(epochMicros);

        assertEquals(expectedLengthMicros, length, "encoded wrong length");
        assertEquals(expectedTimestampMicros, new String(encoder.buffer(), 0, length, US_ASCII));
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestampNanos(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        if (validNanoSecondTestCase)
        {
            final UtcTimestampEncoder encoder = new UtcTimestampEncoder(NANOSECONDS);
            encoder.initialise(otherEpochNanos);

            final int length = encoder.update(epochNanos);

            assertEquals(expectedLengthNanos, length, "encoded wrong length");
            assertEquals(expectedTimestampNanos, new String(encoder.buffer(), 0, length, US_ASCII));
        }
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void canUpdateTimestampNanosWithoutInitialise(
        final String timestamp,
        final long otherEpochMillis,
        final boolean firstValidNanoSecondTestCase,
        final boolean secondValidNanoSecondTestCase)
    {
        prepare(timestamp, otherEpochMillis, firstValidNanoSecondTestCase, secondValidNanoSecondTestCase);
        if (validNanoSecondTestCase)
        {
            final UtcTimestampEncoder encoder = new UtcTimestampEncoder(NANOSECONDS);

            final int length = encoder.update(epochNanos);

            assertEquals(expectedLengthNanos, length, "encoded wrong length");
            assertEquals(expectedTimestampNanos, new String(encoder.buffer(), 0, length, US_ASCII));
        }
    }

}
