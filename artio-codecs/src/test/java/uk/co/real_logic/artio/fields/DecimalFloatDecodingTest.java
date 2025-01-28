/*
 * Copyright 2015-2024 Real Logic Limited.
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class DecimalFloatDecodingTest
{


    @Parameters(name = "{index}: {0} => {1},{2}")
    public static Iterable<Object[]> decimalFloatCodecData()
    {
        return Arrays.asList(new Object[][]
        {
            {"9.99999999999999999", ReadOnlyDecimalFloat.VALUE_MAX_VAL, 17},
            {"99999999999999999.9", ReadOnlyDecimalFloat.VALUE_MAX_VAL, 1},
            {"55.36", 5536L, 2},
            {"5.536e0", 5536L, 3},
            {"5.536e1", 5536L, 2},
            {"5.536e2", 5536L, 1},
            {"5.536e+2", 5536L, 1},
            {"5.536e+02", 5536L, 1},
            {"5.536e-1", 5536L, 4},
            {"5.536e-01", 5536L, 4},
            {"55.3600", 5536L, 2},
            {"1e+016", 10000000000000000L, 0},
            {"1e-016", 1L, 16},
            {"5.536e-2", 5536L, 5},
            {"5.536e-02", 5536L, 5},
            {"0055.36", 5536L, 2},
            {"0055.3600", 5536L, 2},
            {".995", 995L, 3},
            {"0.9950", 995L, 3},
            {"25", 25L, 0},
            {"-55.36", -5536L, 2},
            {"-553.6e-1", -5536L, 2},
            {"-0055.3600", -5536L, 2},
            {"-0055.3600e0", -5536L, 2},
            {"-0055.3600e5", -5536000L, 0},
            {"-55.3600", -5536L, 2},
            {"-.995", -995L, 3},
            {"-0.9950", -995L, 3},
            {"-25", -25L, 0},
            {".6", 6L, 1},
            {".600", 6L, 1},
            {".6e0", 6L, 1},
            {".6e2", 60L, 0},
            {".06", 6L, 2},
            {".06e-2", 6L, 4},
            {"-.6", -6L, 1},
            {"-.6e0", -6L, 1},
            {"-.6e2", -60L, 0},
            {"-.6e-2", -6L, 3},
            {"-.6E-2", -6L, 3},
            {"-.06", -6L, 2},
            {"10", 10L, 0},
            {"-10", -10L, 0},
            {"10.", 10L, 0},
            {"-10.", -10L, 0},

            {"1.00000000", 1L, 0},
            {"-1.00000000", -1L, 0},
            {"0.92117125", 92117125L, 8},
            {"-0.92117125", -92117125L, 8},
            {"0.92125000", 92125L, 5},
            {"-0.92125000", -92125L, 5},
            {"0.00007875", 7875, 8},
            {"-0.00007875", -7875, 8},
            {"1.00109125", 100109125, 8},
            {"-1.00109125", -100109125, 8},
            {"6456.00000001", 645600000001L, 8},

            {"0.00000001", 1, 8},
            {"6456.123456789", 6456123456789L, 9},
            {"6456.000000001", 6456000000001L, 9},

            {"0", 0L, 0},
            {"00", 0L, 0},
            {"0.", 0L, 0},
            {".0", 0L, 0},
            {"0.0", 0L, 0},
            {"00.00", 0L, 0},
            {"0e0", 0L, 0},
            {"00e00", 0L, 0},
            {"00.00e00", 0L, 0},

            {"1.0e0", 1L, 0},
            {"1.0e10", 10_000_000_000L, 0},
            {"1.0e+00010", 10_000_000_000L, 0},
            {"1.0e-10", 1L, 10},
            {"1.0e-100", 1L, 100},
        });
    }

    private final String input;
    private final long value;
    private final int scale;

    public DecimalFloatDecodingTest(final String input, final long value, final int scale)
    {
        this.input = input;
        this.value = value;
        this.scale = scale;
    }

    @Test
    public void shouldDecodeFromString()
    {
        final DecimalFloat price = new DecimalFloat();

        price.fromString(input);

        assertEquals("Incorrect Value", value, price.value());
        assertEquals("Incorrect Scale", scale, price.scale());
    }

    @Test
    public void shouldDecodeFromStringWithOffset()
    {
        final DecimalFloat price = new DecimalFloat();

        final String extendedInput = ' ' + input + ' ';
        price.fromString(extendedInput, 1, input.length());

        assertEquals("Incorrect Value", value, price.value());
        assertEquals("Incorrect Scale", scale, price.scale());
    }

    @Test
    public void canDecodeDecimalFloat()
    {
        final byte[] bytes = input.getBytes(US_ASCII);
        canDecodeDecimalFloatFromBytes(bytes);
    }

    @Test
    public void canDecodeDecimalFloatWithSpacePrefixOrSuffix()
    {
        final String paddedInput = "  " + this.input + "  ";
        final byte[] bytes = paddedInput.getBytes(US_ASCII);
        canDecodeDecimalFloatFromBytes(bytes);
    }

    private void canDecodeDecimalFloatFromBytes(final byte[] bytes)
    {
        final MutableAsciiBuffer string = new MutableAsciiBuffer(new byte[bytes.length + 2]);
        string.putBytes(1, bytes);
        final DecimalFloat price = new DecimalFloat();

        string.getFloat(price, 1, bytes.length);

        assertEquals("Incorrect Value", value, price.value());
        assertEquals("Incorrect Scale", scale, price.scale());
    }

}
