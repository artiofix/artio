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
package uk.co.real_logic.artio.dictionary.generation;

import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.fields.RejectReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SharedCodecsWithDefaultOverflowHandlerTest extends AbstractSharedCodecsTest
{

    @BeforeAll
    public static void generate() throws Exception
    {
        AbstractSharedCodecsTest.generate(null);
    }

    @Test
    public void shouldForwardOverflowToDefaultHandler() throws Exception
    {
        final Decoder decoder = executionReportDecoder1();
        WRAPPER.decode(decoder, ALL_FIELDS_WITH_OVERFLOW_MSG);

        assertFalse(decoder.validate());
        assertEquals(RejectReason.INCORRECT_DATA_FORMAT_FOR_VALUE, RejectReason.decode(decoder.rejectReason()));
    }
}
