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
package uk.co.real_logic.artio.dictionary;

import uk.co.real_logic.artio.dictionary.generation.CodecUtil;

public final class CharArrayWrapper
{
    private char[] values;
    private int length;
    private int offset;
    private int hashcode;

    public CharArrayWrapper()
    {
    }

    public CharArrayWrapper(final CharArrayWrapper other)
    {
        this.values = other.values;
        this.offset = other.offset;
        this.length = other.length;
        this.hashcode = other.hashcode;
    }

    public CharArrayWrapper(final String string)
    {
        final char[] values = string.toCharArray();
        wrap(values, values.length);
    }

    public void wrap(final char[] value, final int length)
    {
        wrap(value, 0, length);
    }

    public void wrap(final char[] value, final int offset, final int length)
    {
        this.values = value;
        this.offset = offset;
        this.length = length;
        hashcode = CodecUtil.hashCode(values, offset, length);
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final CharArrayWrapper that = (CharArrayWrapper)o;

        return this.length == that.length && CodecUtil.equals(values, that.values, offset, that.offset, this.length);
    }

    @Override
    public String toString()
    {
        return new String(values, offset, length);
    }

    @Override
    public int hashCode()
    {
        return hashcode;
    }
}
