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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReadOnlyDecimalFloatTest
{

    public static final ReadOnlyDecimalFloat READ_ONLY_FIVE = new ReadOnlyDecimalFloat(5, 0);
    public static final ReadOnlyDecimalFloat READ_ONLY_ZERO = ReadOnlyDecimalFloat.ZERO;

    @Test
    public void shouldEqualCorrespondingDecimalFloat()
    {
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy(), ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy());
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy(), ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy());
    }

    @Test
    public void shouldNotEqualDifferentDecimalFloat()
    {
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy(), ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy());
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy(), ReadOnlyDecimalFloat.MIN_VALUE);
        assertEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy());
    }

    @Test
    public void shouldNotEqualDifferentReadOnlyDecimalFloat()
    {
        assertNotEquals(ReadOnlyDecimalFloat.MIN_VALUE, ReadOnlyDecimalFloat.MAX_VALUE);
        assertNotEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy(), ReadOnlyDecimalFloat.MAX_VALUE);
        assertNotEquals(ReadOnlyDecimalFloat.MAX_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy());
        assertNotEquals(ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy(), ReadOnlyDecimalFloat.MAX_VALUE);
        assertNotEquals(ReadOnlyDecimalFloat.MAX_VALUE, ReadOnlyDecimalFloat.MIN_VALUE.mutableCopy().immutableCopy());
    }

    @Test
    public void shouldBeComparableWithDecimalFloat()
    {
        assertEquals(0, READ_ONLY_ZERO.compareTo(READ_ONLY_ZERO));
        assertEquals(0, READ_ONLY_ZERO.compareTo(READ_ONLY_ZERO.mutableCopy()));
        assertEquals(0, READ_ONLY_ZERO.compareTo(READ_ONLY_ZERO.mutableCopy().immutableCopy()));
        assertEquals(0, READ_ONLY_ZERO.mutableCopy().compareTo(READ_ONLY_ZERO));
        assertEquals(0, READ_ONLY_ZERO.mutableCopy().immutableCopy().compareTo(READ_ONLY_ZERO));

        assertEquals(1, READ_ONLY_FIVE.compareTo(READ_ONLY_ZERO));
        assertEquals(1, READ_ONLY_FIVE.compareTo(READ_ONLY_ZERO.mutableCopy()));
        assertEquals(1, READ_ONLY_FIVE.compareTo(READ_ONLY_ZERO.mutableCopy().immutableCopy()));
        assertEquals(1, READ_ONLY_FIVE.mutableCopy().compareTo(READ_ONLY_ZERO));
        assertEquals(1, READ_ONLY_FIVE.mutableCopy().immutableCopy().compareTo(READ_ONLY_ZERO));

        assertEquals(-1, READ_ONLY_ZERO.compareTo(READ_ONLY_FIVE));
        assertEquals(-1, READ_ONLY_ZERO.compareTo(READ_ONLY_FIVE.mutableCopy()));
        assertEquals(-1, READ_ONLY_ZERO.compareTo(READ_ONLY_FIVE.mutableCopy().immutableCopy()));
        assertEquals(-1, READ_ONLY_ZERO.mutableCopy().compareTo(READ_ONLY_FIVE));
        assertEquals(-1, READ_ONLY_ZERO.mutableCopy().immutableCopy().compareTo(READ_ONLY_FIVE));
    }

    @Test
    public void shouldHaveTheSameHashCodeAsDecimalFloat()
    {
        assertEquals(READ_ONLY_ZERO.hashCode(), READ_ONLY_ZERO.mutableCopy().hashCode());
        assertEquals(READ_ONLY_ZERO.hashCode(), READ_ONLY_ZERO.mutableCopy().immutableCopy().hashCode());
        assertEquals(READ_ONLY_ZERO.mutableCopy().hashCode(), READ_ONLY_ZERO.hashCode());
        assertEquals(READ_ONLY_ZERO.mutableCopy().immutableCopy().hashCode(), READ_ONLY_ZERO.hashCode());

        assertNotEquals(READ_ONLY_ZERO.hashCode(), READ_ONLY_FIVE.mutableCopy().hashCode());
        assertNotEquals(READ_ONLY_ZERO.hashCode(), READ_ONLY_FIVE.mutableCopy().immutableCopy().hashCode());
        assertNotEquals(READ_ONLY_ZERO.mutableCopy().hashCode(), READ_ONLY_FIVE.hashCode());
        assertNotEquals(READ_ONLY_ZERO.mutableCopy().immutableCopy().hashCode(), READ_ONLY_FIVE.hashCode());
    }

    @Test
    public void shouldDetectNaNRegardlessIfIsMutableOrNot()
    {
        assertTrue(ReadOnlyDecimalFloat.NAN.isNaNValue());
        assertTrue(ReadOnlyDecimalFloat.NAN.mutableCopy().isNaNValue());
        assertTrue(ReadOnlyDecimalFloat.NAN.mutableCopy().immutableCopy().isNaNValue());
        assertFalse(READ_ONLY_ZERO.isNaNValue());
        assertFalse(READ_ONLY_ZERO.mutableCopy().isNaNValue());
        assertFalse(READ_ONLY_ZERO.mutableCopy().immutableCopy().isNaNValue());
    }
}
