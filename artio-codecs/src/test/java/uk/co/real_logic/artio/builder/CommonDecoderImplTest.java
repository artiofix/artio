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
package uk.co.real_logic.artio.builder;

import java.util.stream.Stream;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.util.float_parsing.CharReader;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat.VALUE_MAX_VAL;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class CommonDecoderImplTest
{

    public static Stream<Arguments> decimalFloatCodecData()
    {
        return Stream.of(
          Arguments.of(String.valueOf(VALUE_MAX_VAL), -1, -1, false),
          Arguments.of("1.999999999999999999", 19, 1, true),
          Arguments.of("0.9999999999999999991", 20, 1, true),
          Arguments.of("922337203.6854775807", 19, 9, true),
          Arguments.of("922337203.6854775808", 19, 9, true),
          Arguments.of("9.223372036854775808", 19, 1, true),
          Arguments.of("922337203685477580.8", 19, 18, true),
          Arguments.of("0.009223372036854775808", 22, 1, true)
        );
    }

    static class SimpleDecoderImpl extends CommonDecoderImpl
    {
    }




    @ParameterizedTest(name = "{index}: {0} => {1},{2},{3}")
    @MethodSource("decimalFloatCodecData")
    void testGetFloat(
        final String valueWithOverflow,
        final int positionOfOverflow,
        final int positionOfDecimalPoint,
        final boolean hasOverflow)
    {
        final SimpleDecoderImpl decoder = new SimpleDecoderImpl();
        final DecimalFloat number = new DecimalFloat();
        final DecimalFloat returnedFloat = decoder.getFloat(
            new MutableAsciiBuffer(valueWithOverflow.getBytes(), 0, valueWithOverflow.length()),
            number,
            0,
            valueWithOverflow.length(),
            21,
            true,
            new DecimalFloatOverflowHandler()
            {
                @Override
                public <Data> void handleOverflow(
                    final DecimalFloat number,
                    final CharReader<Data> charReader,
                    final Data data,
                    final int offset,
                    final int length,
                    final int posOverflow,
                    final int posDecimal,
                    final int tagId)
                {

                    if (!hasOverflow)
                    {
                        throw new IllegalArgumentException("Overflow not expected");
                    }
                    assertEquals(valueWithOverflow, charReader.asString(data, offset, length));
                    assertEquals(positionOfOverflow, posOverflow);
                    assertEquals(positionOfDecimalPoint, posDecimal);
                    assertEquals(21, tagId);
                    number.set(999, 1);
                }
            });
        if (hasOverflow)
        {
            assertEquals(1, number.scale());
            assertEquals(999, number.value());
        }
        assertEquals(number, returnedFloat);
    }
}