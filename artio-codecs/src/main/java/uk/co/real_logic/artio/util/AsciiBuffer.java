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

import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler;

/**
 * Mutable String class that flyweights a data buffer. This assumes a US-ASCII encoding
 * and should only be used for performance sensitive decoding/encoding tasks.
 */
public interface AsciiBuffer extends DirectBuffer
{
    int UNKNOWN_INDEX = -1;
    byte YES = 'Y';
    byte NEGATIVE = '-';
    int LONGEST_INT_LENGTH = String.valueOf(Integer.MIN_VALUE).length();
    int LONGEST_LONG_LENGTH = String.valueOf(Long.MIN_VALUE).length();
    int LONGEST_FLOAT_LENGTH = LONGEST_LONG_LENGTH + 3 + 1/*leading zero*/;
    int SEPARATOR_LENGTH = 1;
    int DOT_LENGTH = 1;
    int ZERO_LENGTH = 1;

    byte SEPARATOR = (byte)'\001';

    int getNatural(int startInclusive, int endExclusive);

    long getNaturalLong(int startInclusive, int endExclusive);

    int getInt(int startInclusive, int endExclusive);

    int getDigit(int index);

    boolean isDigit(int index);

    byte getByte(int index);

    char getChar(int index);

    boolean getBoolean(int index);

    byte[] getBytes(byte[] oldBuffer, int offset, int length);

    char[] getChars(char[] oldBuffer, int offset, int length);

    /**
     * May not  be the best performance conversion: don't use this on a critical application path.
     *
     * @param offset at which the string begins.
     * @param length of the string in bytes.
     * @return a String
     */
    String getAscii(int offset, int length);

    long getMessageType(int offset, int length);

    DecimalFloat getFloat(
        DecimalFloat number,
        int offset,
        int length,
        int tagId,
        DecimalFloatOverflowHandler decimalFloatOverflowHandler);

    default DecimalFloat getFloat(
        DecimalFloat number,
        int offset,
        int length)
    {
        return getFloat(number, offset, length, -1, null);
    }

    int getLocalMktDate(int offset, int length);

    long getUtcTimestamp(int offset, int length);

    long getUtcTimeOnly(int offset, int length);

    int getUtcDateOnly(int offset);

    int scanBack(int startInclusive, int endExclusive, char terminatingCharacter);

    int scanBack(int startInclusive, int endExclusive, byte terminator);

    int scan(int startInclusive, int endExclusive, char terminatingCharacter);

    int scan(int startInclusive, int endExclusive, byte terminator);

    int computeChecksum(int startInclusive, int endExclusive);
}
