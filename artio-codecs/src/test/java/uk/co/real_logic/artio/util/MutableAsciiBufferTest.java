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
package uk.co.real_logic.artio.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.of;

public class MutableAsciiBufferTest
{

    public static int[][] valuesAndLengths()
    {
        return new int[][]
        {
            {1, 1},
            {10, 2},
            {100, 3},
            {1000, 4},
            {12, 2},
            {123, 3},
            {2345, 4},
            {9, 1},
            {99, 2},
            {999, 3},
            {9999, 4},
        };
    }

    @ParameterizedTest
    @MethodSource(value = "valuesAndLengths")
    public void shouldCalculateCorrectAsciiLength(final int[] valueAndLength)
    {
        final int value = valueAndLength[0];
        final int length = valueAndLength[1];
        assertEquals(length, MutableAsciiBuffer.lengthInAscii(value), "Wrong length for " + value);
    }

    public static Stream<Arguments> bytesAndChecksums()
    {
        final byte[] allByteValues = new byte[256];
        for (int i = 0; i < allByteValues.length; i++)
        {
            allByteValues[i] = (byte)i;
        }

        return Stream.of(
            of("8=FIX.4.4\u0001".getBytes(US_ASCII), 33),
            of(new byte[]{ (byte)0xFF }, 255),
            of("\u6ce8\u6587".getBytes(UTF_8), 68),
            of(allByteValues, 128)
        );
    }

    @ParameterizedTest
    @MethodSource(value = "bytesAndChecksums")
    public void shouldComputeChecksumAsSumOfUnsignedBytesModulo256(final byte[] bytes, final int checksum)
    {
        final MutableAsciiBuffer buffer = new MutableAsciiBuffer(bytes);
        assertEquals(checksum, buffer.computeChecksum(0, bytes.length));
    }
}
