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

import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.util.float_parsing.CharReader;
import uk.co.real_logic.artio.util.float_parsing.DecimalFloatOverflowHandler;

public class FloatOverflowHandlerSample implements DecimalFloatOverflowHandler
{
    @Override
    public <Data> void handleOverflow(
        final DecimalFloat number,
        final CharReader<Data> charReader,
        final Data data,
        final int offset,
        final int length,
        final int positionOfOverflow,
        final int positionOfDecimalPoint,
        final int tagId)
    {
        number.set(999, 1);
    }
}
