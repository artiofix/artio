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
package uk.co.real_logic.artio;

/**
 * Parses the arguments of Artio's command line tools, naming the argument when a value is invalid.
 */
public final class CommandLineUtil
{
    private CommandLineUtil()
    {
    }

    /**
     * Parse an int argument.
     *
     * @param value the argument's value.
     * @param name  the argument's name, used in the error message.
     * @return the parsed value.
     * @throws IllegalArgumentException if the value isn't an int.
     */
    public static int parseInt(final String value, final String name)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (final NumberFormatException ex)
        {
            throw invalidNumber(value, name, ex);
        }
    }

    /**
     * Parse a long argument.
     *
     * @param value the argument's value.
     * @param name  the argument's name, used in the error message.
     * @return the parsed value.
     * @throws IllegalArgumentException if the value isn't a long.
     */
    public static long parseLong(final String value, final String name)
    {
        try
        {
            return Long.parseLong(value);
        }
        catch (final NumberFormatException ex)
        {
            throw invalidNumber(value, name, ex);
        }
    }

    private static IllegalArgumentException invalidNumber(
        final String value, final String name, final NumberFormatException ex)
    {
        return new IllegalArgumentException(name + " must be a number, but was '" + value + "'", ex);
    }
}
