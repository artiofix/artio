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
package uk.co.real_logic.artio.util;

import org.agrona.AsciiEncoding;
import org.agrona.AsciiNumberFormatException;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.fields.*;
import uk.co.real_logic.artio.util.float_parsing.AsciiBufferCharReader;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatParser;

import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;

public final class MutableAsciiBuffer extends UnsafeBuffer implements AsciiBuffer
{
    private static final byte ZERO = '0';
    private static final byte DOT = (byte)'.';

    private static final byte Y = (byte)'Y';
    private static final byte N = (byte)'N';

    public MutableAsciiBuffer()
    {
        super(0, 0);
    }

    public MutableAsciiBuffer(final byte[] buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final byte[] buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final ByteBuffer buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final ByteBuffer buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final DirectBuffer buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final DirectBuffer buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final long address, final int length)
    {
        super(address, length);
    }

    public int getNatural(final int startInclusive, final int endExclusive)
    {
        return super.parseNaturalIntAscii(startInclusive, endExclusive - startInclusive);
    }

    public long getNaturalLong(final int startInclusive, final int endExclusive)
    {
        return super.parseNaturalLongAscii(startInclusive, endExclusive - startInclusive);
    }

    @SuppressWarnings("FinalParameters")
    public int getInt(int startInclusive, final int endExclusive)
    {
        final int length = endExclusive - startInclusive;
        if (length == 0)
        {
            return MISSING_INT;
        }

        return super.parseIntAscii(startInclusive, length);
    }

    public int getDigit(final int index)
    {
        final byte value = getByte(index);
        return getDigit(index, value);
    }

    public boolean isDigit(final int index)
    {
        final byte value = getByte(index);
        return value >= 0x30 && value <= 0x39;
    }

    private int getDigit(final int index, final byte value)
    {
        if (value < 0x30 || value > 0x39)
        {
            throw new AsciiNumberFormatException("'" + ((char)value) + "' isn't a valid digit @ " + index);
        }

        return value - 0x30;
    }

    public char getChar(final int index)
    {
        return (char)getByte(index);
    }

    public boolean getBoolean(final int index)
    {
        return YES == getByte(index);
    }

    public byte[] getBytes(final byte[] oldBuffer, final int offset, final int length)
    {
        final byte[] resultBuffer = oldBuffer.length < length ? new byte[length] : oldBuffer;
        getBytes(offset, resultBuffer, 0, length);
        return resultBuffer;
    }

    public char[] getChars(final char[] oldBuffer, final int offset, final int length)
    {
        final char[] resultBuffer = oldBuffer.length < length ? new char[length] : oldBuffer;
        for (int i = 0; i < length; i++)
        {
            resultBuffer[i] = getChar(i + offset);
        }
        return resultBuffer;
    }

    /**
     * Not at all a performant conversion: don't use this on a critical application path.
     *
     * @param offset The offset within the buffer to start at.
     * @param length the length in bytes to convert to a String
     * @return a String
     */
    public String getAscii(final int offset, final int length)
    {
        final byte[] buff = new byte[length];
        getBytes(offset, buff);
        return new String(buff, 0, length, US_ASCII);
    }

    public long getMessageType(final int offset, final int length)
    {
        return MessageTypeEncoding.packMessageType(byteArray(), addressOffset(), offset, length);
    }

    @SuppressWarnings("FinalParameters")
    public DecimalFloat getFloat(
        final DecimalFloat number,
        int offset,
        int length,
        int tagId,
        DecimalFloatOverflowHandler decimalFloatOverflowHandler)
    {
        return DecimalFloatParser.extract(
          number,
          AsciiBufferCharReader.INSTANCE,
          this,
          offset,
          length,
          tagId,
          decimalFloatOverflowHandler);
    }

    public int getLocalMktDate(final int offset, final int length)
    {
        return LocalMktDateDecoder.decode(this, offset, length);
    }

    public long getUtcTimestamp(final int offset, final int length)
    {
        return UtcTimestampDecoder.decode(this, offset, length, true);
    }

    public long getUtcTimeOnly(final int offset, final int length)
    {
        return UtcTimeOnlyDecoder.decode(this, offset, length, true);
    }

    public int getUtcDateOnly(final int offset)
    {
        return UtcDateOnlyDecoder.decode(this, offset);
    }

    public int scanBack(final int startInclusive, final int endExclusive, final char terminatingCharacter)
    {
        return scanBack(startInclusive, endExclusive, (byte)terminatingCharacter);
    }

    public int scanBack(final int startInclusive, final int endExclusive, final byte terminator)
    {
        for (int index = startInclusive; index > endExclusive; index--)
        {
            final byte value = getByte(index);
            if (value == terminator)
            {
                return index;
            }
        }

        return UNKNOWN_INDEX;
    }

    public int scan(final int startInclusive, final int endExclusive, final char terminatingCharacter)
    {
        return scan(startInclusive, endExclusive, (byte)terminatingCharacter);
    }

    public int scan(final int startInclusive, final int endExclusive, final byte terminator)
    {
        int indexValue = UNKNOWN_INDEX;
        for (int i = startInclusive; i < endExclusive; i++)
        {
            final byte value = getByte(i);
            if (value == terminator)
            {
                indexValue = i;
                break;
            }
        }

        return indexValue;
    }

    public int computeChecksum(final int startInclusive, final int endExclusive)
    {
        int total = 0;
        for (int index = startInclusive; index < endExclusive; index++)
        {
            total += getByte(index);
        }

        return total % 256;
    }

    public int putAscii(final int index, final String string)
    {
        final byte[] bytes = string.getBytes(US_ASCII);
        putBytes(index, bytes);

        return bytes.length;
    }

    public void putSeparator(final int index)
    {
        putByte(index, SEPARATOR);
    }

    public int putBooleanAscii(final int offset, final boolean value)
    {
        putByte(offset, value ? Y : N);
        return 1;
    }

    public static int lengthInAscii(final int value)
    {
        int characterCount = 0;
        for (int remainder = value; remainder > 0; remainder = remainder / 10)
        {
            characterCount++;
        }
        return characterCount;
    }

    public int putCharAscii(final int index, final char value)
    {
        putByte(index, (byte)value);
        return 1;
    }

    public int putFloatAscii(final int offset, final ReadOnlyDecimalFloat price)
    {
        return putFloatAscii(offset, price.value(), price.scale());
    }

    /**
     * Put's a float value in an ascii encoding. This method keeps given scale and will not trim needed trailing zeros.
     *
     * @param offset the position at which to start putting ascii encoded float.
     * @param value the value of the float to encode - see {@link DecimalFloat} for details.
     * @param scale the scale of the float to encode - see {@link DecimalFloat} for details.
     * @throws IllegalArgumentException if you try to encode NaN.
     * @return the length of the encoded value
     */
    public int putFloatAscii(final int offset, final long value, final int scale)
    {
        if (ReadOnlyDecimalFloat.isNaNValue(value, scale))
        {
            throw new IllegalArgumentException("You cannot encode NaN into a buffer - it's not a number");
        }

        if (value == 0)
        {
            return handleZero(offset, scale);
        }

        final long remainder = calculateRemainderAndPutMinus(offset, value);
        final int minusAdj = value < 0 ? 1 : 0;
        final int start = offset + minusAdj;

        final int length = remainder == Long.MIN_VALUE ? AsciiEncoding.MIN_LONG_VALUE.length - 1 :
            AsciiEncoding.digitCount(-remainder);

        if (length <= scale)
        {
            putByte(start, ZERO);
            putByte(start + 1, DOT);
            putTrailingZero(start + 2, scale - length);
            putLong(remainder, start + scale + DOT_LENGTH);
            return minusAdj + scale + 2;
        }
        else if (scale > 0)
        {
            putLong(remainder, start + length + DOT_LENGTH - 1, scale);
            return minusAdj + length + DOT_LENGTH;
        }
        else
        {
            putLong(remainder, start + length - 1);
            putTrailingZero(start + length, -scale);
            return minusAdj + length - scale;
        }
    }

    private void putTrailingZero(final int offset, final int zerosCount)
    {
        for (int ix = 0; ix < zerosCount; ix++)
        {
            putByte(offset + ix, ZERO);
        }
    }

    private int handleZero(final int offset, final int scale)
    {
        putByte(offset, ZERO);
        if (scale <= 0)
        {
            return 1;
        }
        putByte(offset + 1, DOT);
        putTrailingZero(offset + 2, scale);

        return 2 + scale;
    }

    private long calculateRemainderAndPutMinus(final int offset, final long value)
    {
        if (value < 0)
        {
            putChar(offset, '-');
            return value;
        }
        else
        {
            // Deal with negatives to avoid overflow for LONG.MAX_VALUE
            return -1L * value;
        }
    }

    @SuppressWarnings("FinalParameters")
    private int putLong(long remainder, final int end)
    {
        int index = end;
        while (remainder < 0)
        {
            final long digit = remainder % 10;
            remainder = remainder / 10;
            putByte(index, (byte)(ZERO + (-1L * digit)));
            index--;
        }

        return index;
    }

    @SuppressWarnings("FinalParameters")
    private int putLong(long remainder, final int end, int scale)
    {
        int index = end;
        while (remainder < 0)
        {
            if (scale == 0)
            {
                putByte(index, DOT);
            }
            else
            {
                final long digit = remainder % 10;
                remainder = remainder / 10;
                putByte(index, (byte)(ZERO + (-1L * digit)));
            }
            index--;
            scale--;
        }

        return index;
    }
}
