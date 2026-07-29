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

import uk.co.real_logic.artio.util.AsciiBuffer;

public final class AsciiBufferCharReader implements CharReader<AsciiBuffer>
{
    private static final byte SPACE = ' ';
    private static final byte ZERO = '0';

    public static final AsciiBufferCharReader INSTANCE = new AsciiBufferCharReader();

    private AsciiBufferCharReader()
    {

    }

    @Override
    public boolean isSpace(final AsciiBuffer data, final int index)
    {
        return data.getByte(index) == SPACE;
    }

    @Override
    public char charAt(final AsciiBuffer data, final int index)
    {
        return (char)data.getByte(index);
    }

    @Override
    public CharSequence asString(final AsciiBuffer data, final int offset, final int length)
    {
        return data.getAscii(offset, length);
    }

    @Override
    public boolean isZero(final AsciiBuffer data, final int index)
    {
        return data.getByte(index) == ZERO;
    }

    @Override
    public int getDigit(final AsciiBuffer data, final int index, final char charValue)
    {
        if (charValue < 0x30 || charValue > 0x39)
        {
            throw new NumberFormatException("'" + charValue + "' isn't a valid digit @ " + index);
        }

        return charValue - 0x30;
    }
}
