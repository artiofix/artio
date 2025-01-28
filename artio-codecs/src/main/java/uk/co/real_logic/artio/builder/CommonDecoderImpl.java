/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
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

import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler;

import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;

/**
 * Class provides common implementation methods used by decoders, external systems shouldn't assume API stability.
 */
public abstract class CommonDecoderImpl
{
    public static final int INCORRECT_DATA_FORMAT_FOR_VALUE = 6;

    protected int invalidTagId = Decoder.NO_ERROR;
    protected int rejectReason = Decoder.NO_ERROR;
    protected AsciiBuffer buffer;
    protected DecimalFloatOverflowHandler decimalFloatOverflowHandler;

    public int invalidTagId()
    {
        return invalidTagId;
    }

    public int rejectReason()
    {
        return rejectReason;
    }

    public int getInt(
        final AsciiBuffer buffer,
        final int startInclusive, final int endExclusive, final int tag, final boolean validation)
    {
        try
        {
            return buffer.getInt(startInclusive, endExclusive);
        }
        catch (final NumberFormatException e)
        {
            if (validation)
            {
                invalidTagId = tag;
                rejectReason = INCORRECT_DATA_FORMAT_FOR_VALUE;
            }
            return MISSING_INT;
        }
    }

    public long getLong(
        final AsciiBuffer buffer,
        final int startInclusive, final int endExclusive, final int tag, final boolean validation)
    {
        try
        {
            return buffer.parseLongAscii(startInclusive, endExclusive - startInclusive);
        }
        catch (final NumberFormatException e)
        {
            if (validation)
            {
                invalidTagId = tag;
                rejectReason = INCORRECT_DATA_FORMAT_FOR_VALUE;
            }
            return MISSING_LONG;
        }
    }

    public DecimalFloat getFloat(
        final AsciiBuffer buffer,
        final DecimalFloat number,
        final int offset,
        final int length,
        final int tag,
        final boolean validation,
        final DecimalFloatOverflowHandler decimalFloatOverflowHandler)
    {
        try
        {
            return buffer.getFloat(number, offset, length, tag, decimalFloatOverflowHandler);
        }
        catch (final NumberFormatException | ArithmeticException e)
        {
            if (validation)
            {
                invalidTagId = tag;
                rejectReason = INCORRECT_DATA_FORMAT_FOR_VALUE;
                return number;
            }
            else
            {
                number.set(DecimalFloat.MISSING_FLOAT);
                return number;
            }
        }
    }

    public DecimalFloat getFloat(
        final AsciiBuffer buffer,
        final DecimalFloat number, final int offset, final int length, final int tag, final boolean validation)
    {
        return getFloat(buffer, number, offset, length, tag, validation, null);
    }


    public int getIntFlyweight(
        final AsciiBuffer buffer, final int offset, final int length, final int tag, final boolean validation)
    {
        try
        {
            return buffer.parseIntAscii(offset, length);
        }
        catch (final NumberFormatException e)
        {
            if (validation)
            {
                throw new NumberFormatException(e.getMessage() + " tag=" + tag);
            }
            else
            {
                return MISSING_INT;
            }
        }
    }

    public long getLongFlyweight(
        final AsciiBuffer buffer, final int offset, final int length, final int tag, final boolean validation)
    {
        try
        {
            return buffer.parseLongAscii(offset, length);
        }
        catch (final NumberFormatException e)
        {
            if (validation)
            {
                throw new NumberFormatException(e.getMessage() + " tag=" + tag);
            }
            else
            {
                return MISSING_LONG;
            }
        }
    }

    public int groupNoField(
        final AsciiBuffer buffer, final int oldValue,
        final boolean hasField, final int offset, final int length, final int tag,
        final boolean validation)
    {
        if (!hasField || buffer == null)
        {
            return 0;
        }

        if (oldValue != MISSING_INT)
        {
            return oldValue;
        }

        return getIntFlyweight(buffer, offset, length, tag, validation);
    }

    public DecimalFloat getFloatFlyweight(
        final AsciiBuffer buffer,
        final DecimalFloat number,
        final int offset,
        final int length,
        final int tag,
        final boolean codecValidationEnabled,
        final DecimalFloatOverflowHandler decimalFloatOverflowHandler)
    {
        try
        {
            return buffer.getFloat(number, offset, length, tag, decimalFloatOverflowHandler);
        }
        catch (final NumberFormatException e)
        {
            if (codecValidationEnabled)
            {
                throw new NumberFormatException(e.getMessage() + " tag=" + tag);
            }
            else
            {
                number.set(DecimalFloat.MISSING_FLOAT);
                return number;
            }
        }
    }

    public DecimalFloat getFloatFlyweight(
        final AsciiBuffer buffer,
        final DecimalFloat number,
        final int offset,
        final int length,
        final int tag,
        final boolean codecValidationEnabled)
    {
        return getFloatFlyweight(buffer, number, offset, length, tag, codecValidationEnabled, null);
    }
}
