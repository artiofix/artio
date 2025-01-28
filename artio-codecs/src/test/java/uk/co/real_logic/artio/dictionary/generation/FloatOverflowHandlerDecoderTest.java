/*
 * Copyright 2025 Adaptive Financial Consulting Ltd.
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

import static uk.co.real_logic.artio.dictionary.generation.CodecGenerationWrapper.dictionaryStream;
import static uk.co.real_logic.artio.util.Reflection.get;


import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.util.FloatOverflowHandlerSample;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * This test ensures that for a given dictionary, the generated decoder uses the provided handler when an overflow
 * occurs.
 * It also ensures that the default (existing before this change) behavior is used when no handler is provided.
 */
public class FloatOverflowHandlerDecoderTest
{
    public static final String MESSAGE_WITH_OVERFLOW =
        "8=FIX.4.4\u00019=122\u000135=D\u000149=initiator\u000156=acceptor\u000134=1" +
        "\u000152=20231220-13:12:16.020\u000144=922337203.6854775807999\u000110=0\u0001";
    private static final CodecGenerationWrapper WRAPPER = new CodecGenerationWrapper();

    private static Class<?> nosDecoder;

    private Decoder decoder;

    public static void withHandler(final String handler) throws Exception
    {
        WRAPPER.generate(config ->
        {
            config
                .fileStreams(dictionaryStream("float_overflow_dictionary"))
                .decimalFloatOverflowHandler(handler);
        });

        WRAPPER.compile(WRAPPER.encoder(null, "NewOrderSingle"));
        nosDecoder = WRAPPER.decoder("NewOrderSingle");
    }

    public void initWithHandler() throws Exception
    {
        withHandler(FloatOverflowHandlerSample.class.getName());
        decoder = (Decoder)nosDecoder.getDeclaredConstructor().newInstance();
    }

    public void initWithoutHandler() throws Exception
    {
        withHandler(null);
        decoder = (Decoder)nosDecoder.getDeclaredConstructor().newInstance();
    }

    @Test
    public void shouldUseHandlerWhenOverflowOccurs() throws Exception
    {
        initWithHandler();
        WRAPPER.decode(decoder, MESSAGE_WITH_OVERFLOW);

        Assertions.assertTrue(decoder.validate());
        assertPrice(decoder, new DecimalFloat(999L, 1));
    }

    @Test
    public void shouldUseDefaultWhenOverflowOccurs() throws Exception
    {
        initWithoutHandler();
        WRAPPER.decode(decoder, MESSAGE_WITH_OVERFLOW);

        assertPrice(decoder, ReadOnlyDecimalFloat.MISSING_FLOAT);
        Assertions.assertFalse(decoder.validate());
    }

    private void assertPrice(final Object decoder, final Object priceValue) throws Exception
    {
        Assertions.assertEquals(priceValue, get(decoder, "price"));
    }

}
