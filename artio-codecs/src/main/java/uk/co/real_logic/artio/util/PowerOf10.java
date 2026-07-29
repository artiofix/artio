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

public class PowerOf10
{
    public static final long[] POWERS_OF_TEN = new long[]
        {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
        100_000_000L, 1_000_000_000L, 10_000_000_000L, 100_000_000_000L,
        1_000_000_000_000L, 10_000_000_000_000L, 100_000_000_000_000L,
        1_000_000_000_000_000L, 10_000_000_000_000_000L, 100_000_000_000_000_000L,
        1_000_000_000_000_000_000L, Long.MAX_VALUE};
    public static final int HIGHEST_POWER_OF_TEN = 18;

    public static long pow10(final int rhs)
    {
        return POWERS_OF_TEN[rhs];
    }
}
