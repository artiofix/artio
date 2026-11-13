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

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * .
 */
public class AsciiFieldFlyweight
{
    private final AsciiBuffer buffer = new MutableAsciiBuffer();
    private int offset;
    private int length;

    public void wrap(final DirectBuffer buffer, final int offset, final int length)
    {
        this.buffer.wrap(buffer);
        this.offset = offset;
        this.length = length;
    }

    public final int offset()
    {
        return offset;
    }

    public final int length()
    {
        return length;
    }

    protected final AsciiBuffer asciiFlyweight()
    {
        return buffer;
    }

    @Override
    public String toString()
    {
        return new String(toByteArray(), US_ASCII);
    }

    public char[] toCharArray()
    {
        final char[] characters = new char[length];
        for (int i = 0; i < length; i++)
        {
            characters[i] = buffer.getChar(i + offset);
        }

        return characters;
    }

    public byte[] toByteArray()
    {
        final byte[] bytes = new byte[length];
        buffer.getBytes(offset, bytes);

        return bytes;
    }

    public void getBytes(final MutableDirectBuffer dstBuffer, final int dstOffset)
    {
        dstBuffer.putBytes(dstOffset, buffer, offset, length);
    }
}
