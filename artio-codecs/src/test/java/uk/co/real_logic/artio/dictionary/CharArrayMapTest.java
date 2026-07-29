/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CharArrayMapTest
{
    @Test
    public void shouldReturnTrueIfKeyIsPresent()
    {
        //Given
        final Map<String, String> buildFrom = new HashMap<>();
        buildFrom.put("0", "_0");
        buildFrom.put("A", "_A");
        buildFrom.put("AA", "_AAA");
        final CharArrayMap<String> charArrayMap = new CharArrayMap<>(buildFrom);

        //When / Then
        final char[] data = {'0', ' ', 'A', 'A'};
        final CharArrayWrapper wrapper = new CharArrayWrapper();
        wrapper.wrap(data, 0, 1);
        assertTrue(charArrayMap.containsKey(wrapper));
        wrapper.wrap(data, 2, 2);
        assertTrue(charArrayMap.containsKey(wrapper));
    }

    @Test
    public void shouldReturnFalseIfKeyIsNotPresent()
    {
        //Given
        final Map<String, String> buildFrom = new HashMap<>();
        buildFrom.put("0", "_0");
        buildFrom.put("A", "_A");
        buildFrom.put("AA", "_AAA");
        final CharArrayMap<String> charArrayMap = new CharArrayMap<>(buildFrom);

        //When / Then
        final char[] data = {'0', ' ', 'A', 'B'};
        final CharArrayWrapper wrapper = new CharArrayWrapper();
        wrapper.wrap(data, 2, 2);
        assertFalse(charArrayMap.containsKey(wrapper));
    }
}
