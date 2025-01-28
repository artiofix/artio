/*
 * Copyright 2015-2024 Real Logic Limited.
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

import org.agrona.generation.StringWriterOutputManager;
import org.junit.BeforeClass;
import org.junit.Test;
import uk.co.real_logic.artio.builder.Printer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY;

public class PrinterGeneratorTest
{
    private static final StringWriterOutputManager OUTPUT_MANAGER = new StringWriterOutputManager();
    private static final ConstantGenerator CONSTANT_GENERATOR = new ConstantGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, null, OUTPUT_MANAGER);
    private static final EnumGenerator ENUM_GENERATOR = new EnumGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, OUTPUT_MANAGER);

    private static final DecoderGenerator DECODER_GENERATOR = new DecoderGenerator(
        MESSAGE_EXAMPLE, 1, TEST_PACKAGE, TEST_PARENT_PACKAGE, TEST_PACKAGE,
        OUTPUT_MANAGER, ValidationOn.class,
        RejectUnknownFieldOff.class, RejectUnknownEnumValueOn.class, false, false,
        Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true, null);
    private static final EncoderGenerator ENCODER_GENERATOR = new EncoderGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, TEST_PARENT_PACKAGE, OUTPUT_MANAGER, ValidationOn.class,
        RejectUnknownFieldOn.class, RejectUnknownEnumValueOn.class, RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY,
        true);

    private static final PrinterGenerator PRINTER_GENERATOR = new PrinterGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, OUTPUT_MANAGER);
    private static Class<?> printer;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[8 * 1024]);

    @BeforeClass
    public static void generate() throws Exception
    {
        CONSTANT_GENERATOR.generate();
        ENUM_GENERATOR.generate();
        ENCODER_GENERATOR.generate();
        DECODER_GENERATOR.generate();
        PRINTER_GENERATOR.generate();
        final Map<String, CharSequence> sources = OUTPUT_MANAGER.getSources();
        printer = compileInMemory(PRINTER, sources);
        if (printer == null)
        {
            System.out.println(sources);
        }
    }

    @Test
    public void shouldPrettyPrintAMessage() throws Exception
    {
        final Printer printer = printer();
        buffer.putAscii(1, ENCODED_MESSAGE);

        final String string = printer.toString(buffer, 1, ENCODED_MESSAGE.length(), HEARTBEAT_TYPE);

        assertThat(string, containsString(STRING_ENCODED_MESSAGE_EXAMPLE));
    }

    private Printer printer()
        throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException
    {
        return (Printer)PrinterGeneratorTest.printer.getConstructor().newInstance();
    }
}
