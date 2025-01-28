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

import uk.co.real_logic.artio.fields.DecimalFloat;

public interface DecimalFloatOverflowHandler
{

    /**
     *
     * @param number DecimalFloat object that represents the float number - implementation should update this object
     * @param charReader object that knows how to create a String or to fetch a char our of the data
     * @param data buffer containing the decimal float value bytes
     * @param offset offset within the buffer where the float number starts
     * @param length length of the float number in bytes
     * @param positionOfOverflow position within the decimal float bytes that corresponds to the digit where the overflow occurred
     * @param positionOfDecimalPoint position within the decimal float bytes that corresponds to the decimal point
     * @param tagId tag id where the float overflow happened
     * @param <Data> generic buffer
     */
    <Data> void handleOverflow(
        DecimalFloat number,
        CharReader<Data> charReader,
        Data data,
        int offset,
        int length,
        int positionOfOverflow,
        int positionOfDecimalPoint,
        int tagId);
}
