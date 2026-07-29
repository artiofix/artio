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

public final class CharSequenceCharReader implements CharReader<CharSequence>
{

    private static final char ZERO = '0';

    public static final CharSequenceCharReader INSTANCE = new CharSequenceCharReader();

    private CharSequenceCharReader()
    {

    }

    @Override
    public boolean isSpace(final CharSequence data, final int index)
    {
        return Character.isSpaceChar(data.charAt(index));
    }

    @Override
    public char charAt(final CharSequence data, final int index)
    {
        return data.charAt(index);
    }

    @Override
    public CharSequence asString(final CharSequence data, final int offset, final int length)
    {
        return data.subSequence(offset, offset + length);
    }

    @Override
    public boolean isZero(final CharSequence data, final int index)
    {
        return data.charAt(index) == ZERO;
    }

    @Override
    public int getDigit(final CharSequence data, final int index, final char charValue)
    {
        if (charValue < '0' || charValue > '9')
        {
            throw new NumberFormatException("'" + charValue + "' isn't a valid digit @ " + index);
        }

        return charValue - '0';
    }
}
