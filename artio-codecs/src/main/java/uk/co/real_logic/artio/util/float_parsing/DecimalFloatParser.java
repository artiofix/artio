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
package uk.co.real_logic.artio.util.float_parsing;


import uk.co.real_logic.artio.fields.DecimalFloat;

import static uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat.VALUE_MAX_VAL;
import static uk.co.real_logic.artio.util.PowerOf10.pow10;

public final class DecimalFloatParser
{
    private static final char LOWER_CASE_E = 'e';
    private static final char UPPER_CASE_E = 'E';
    private static final char PLUS = '+';
    private static final char MINUS = '-';
    private static final byte DOT = '.';

    public static <Data> DecimalFloat extract(
        final DecimalFloat number,
        final CharReader<Data> charReader,
        final Data data,
        final int offset,
        final int length)
    {
        return extract(number, charReader, data, offset, length, -1, null);
    }

    public static <Data> DecimalFloat extract(
        final DecimalFloat number,
        final CharReader<Data> charReader,
        final Data data,
        final int offset,
        final int length,
        final int tagId,
        final DecimalFloatOverflowHandler overflowHandler)
    {
        // Throw away trailing spaces
        int workingOffset = offset;
        int end = workingOffset + length;
        for (int index = end - 1; charReader.isSpace(data, index) && index > workingOffset; index--)
        {
            end--;
        }

        int endOfSignificand = findEndOfSignificand(charReader, workingOffset, end, data);
        final int startOfExponent = endOfSignificand + 1;

        if (isFloatingPoint(charReader, workingOffset, endOfSignificand, data))
        {
            // Throw away trailing zeros
            for (int index = endOfSignificand - 1; charReader.isZero(data, index) && index > workingOffset; index--)
            {
                endOfSignificand--;
            }
        }

        // Throw away leading spaces
        for (int index = workingOffset; index < endOfSignificand && charReader.isSpace(data, index); index++)
        {
            workingOffset++;
        }

        // Is it negative?
        final boolean negative = charReader.charAt(data, workingOffset) == MINUS;
        if (negative)
        {
            workingOffset++;
        }

        // Throw away leading zeros
        for (int index = workingOffset; index < endOfSignificand && charReader.isZero(data, index); index++)
        {
            workingOffset++;
        }

        int workingScale = 0;
        long value = 0;
        final int timesNeg = 0;
        int dotIndex = 0;
        for (int index = workingOffset; index < endOfSignificand; index++)
        {
            final char charValue = charReader.charAt(data, index);
            if (charValue == DOT)
            {
                // number of digits after the dot
                workingScale = endOfSignificand - (index + 1);
                dotIndex = index;
            }
            else
            {
                final int digit = charReader.getDigit(data, index, charValue);
                value = value * 10 + digit;
                if (value < 0 || value > VALUE_MAX_VAL)
                {
                    if (overflowHandler != null)
                    {
                        overflowHandler.handleOverflow(
                            number,
                            charReader,
                            data,
                            offset,
                            length,
                            index - offset,
                            dotIndex - offset,
                            tagId);
                        return number;
                    }
                    else
                    {
                        throw new ArithmeticException(
                          "Out of range: when parsing " + charReader.asString(data, offset, length));
                    }
                }
            }
        }

        int exponent = 0;
        final int exponentLength = end - startOfExponent;
        if (exponentLength > 0)
        {
            // scientific notation
            exponent = parseExponent(charReader, data, offset, length, startOfExponent, end);
        }
        else if (exponentLength == 0)
        {
            throw new NumberFormatException(charReader.asString(data, offset, length).toString());
        }

        return updateValue(number, workingScale, exponent, timesNeg, negative, value);
    }

    private static DecimalFloat updateValue(
        final DecimalFloat number,
        final int workingScale,
        final int exponent,
        final int timesNeg,
        final boolean negative,
        final long value)
    {
        final int scale = workingScale - exponent - timesNeg;
        final long signedValue = negative ? -1 * value : value;
        return number.set(
          (scale >= 0) ? signedValue : signedValue * pow10(-scale),
          Math.max(scale, 0)
        );
    }

    private static <Data> int parseExponent(
        final CharReader<Data> charReader,
        final Data data,
        final int offset,
        final int length,
        final int startOfExponent,
        final int end)
    {
        int exponent = 0;
        boolean negative = false;
        int position = startOfExponent;

        final char firstChar = charReader.charAt(data, position);
        if (firstChar == MINUS)
        {
            position++;
            negative = true;
        }
        else if (firstChar == PLUS)
        {
            position++;
        }

        while (position < end)
        {
            final char charValue = charReader.charAt(data, position);
            final int digit = charReader.getDigit(data, position, charValue);
            position++;
            exponent = exponent * 10 + digit;
            if (exponent > 1000) // overflow and arbitrary limit check
            {
                throw new NumberFormatException(charReader.asString(data, offset, length).toString());
            }
        }

        return negative ? -exponent : exponent;
    }

    private static <Data> int findEndOfSignificand(
        final CharReader<Data> dataExtractor,
        final int offset,
        final int end,
        final Data data
    )
    {
        for (int index = end - 1; index > offset; index--)
        {
            final char charValue = dataExtractor.charAt(data, index);
            if (charValue == LOWER_CASE_E || charValue == UPPER_CASE_E)
            {
                return index;
            }
        }
        return end;
    }

    private static <Data> boolean isFloatingPoint(
        final CharReader<Data> dataExtractor,
        final int offset,
        final int end,
        final Data data
    )
    {
        for (int index = end - 1; index >= offset; index--)
        {
            if (dataExtractor.charAt(data, index) == '.')
            {
                return true;
            }
        }
        return false;
    }
}
