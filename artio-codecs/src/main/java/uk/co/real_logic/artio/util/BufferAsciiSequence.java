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

import org.agrona.DirectBuffer;

public class BufferAsciiSequence implements CharSequence
{
    private DirectBuffer buffer;
    private int offset;
    private int length;

    public BufferAsciiSequence wrap(final DirectBuffer buffer, final int offset, final int length)
    {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        return this;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public char charAt(final int index)
    {
        return (char)buffer.getByte(index + offset);
    }

    @Override
    public CharSequence subSequence(final int start, final int end)
    {
        if (start < 0 || end < 0 || end > length || start > end)
        {
            throw new IllegalArgumentException(
                String.format("start = %d, end = %d, length = %d", start, end, length));
        }

        return new BufferAsciiSequence().wrap(buffer, offset + start, end - start);
    }

    @Override
    public String toString()
    {
        return buffer.getStringWithoutLengthUtf8(offset, length);
    }
}
