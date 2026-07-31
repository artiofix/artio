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

import org.agrona.generation.StringWriterOutputManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Objects;

import static java.lang.reflect.Modifier.*;
import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.AcceptorGenerator.*;
import static uk.co.real_logic.artio.dictionary.generation.Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY;

public class AcceptorGeneratorTest
{
    private static final StringWriterOutputManager OUTPUT_MANAGER = new StringWriterOutputManager();
    private static final ConstantGenerator CONSTANT_GENERATOR =
        new ConstantGenerator(MESSAGE_EXAMPLE, TEST_PACKAGE, null, OUTPUT_MANAGER);
    private static final EnumGenerator ENUM_GENERATOR = new EnumGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, OUTPUT_MANAGER);
    private static final EncoderGenerator ENCODER_GENERATOR = new EncoderGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, TEST_PARENT_PACKAGE, OUTPUT_MANAGER, ValidationOn.class,
        RejectUnknownFieldOn.class, RejectUnknownEnumValueOn.class, RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true);
    private static final DecoderGenerator DECODER_GENERATOR = new DecoderGenerator(
        MESSAGE_EXAMPLE, 1, TEST_PACKAGE, TEST_PARENT_PACKAGE, TEST_PACKAGE, OUTPUT_MANAGER, ValidationOn.class,
        RejectUnknownFieldOff.class, RejectUnknownEnumValueOn.class, false, false, false,
        Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true, null);
    private static final AcceptorGenerator ACCEPTOR_GENERATOR = new AcceptorGenerator(
        MESSAGE_EXAMPLE, TEST_PACKAGE, OUTPUT_MANAGER);
    private static Class<?> acceptor;
    private static Class<?> decoder;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[8 * 1024]);

    @BeforeAll
    public static void generate() throws Exception
    {
        CONSTANT_GENERATOR.generate();
        ENUM_GENERATOR.generate();
        ENCODER_GENERATOR.generate();
        DECODER_GENERATOR.generate();
        ACCEPTOR_GENERATOR.generate();
        final Map<String, CharSequence> sources = OUTPUT_MANAGER.getSources();

        acceptor = compileInMemory(TEST_PACKAGE + "." + DICTIONARY_ACCEPTOR, sources);
        Objects.requireNonNull(acceptor, "acceptor must not be null");

        decoder = acceptor.getClassLoader().loadClass(TEST_PACKAGE + "." + DICTIONARY_DECODER);
        if (acceptor == null || decoder == null)
        {
            System.out.println(sources);
        }
    }

    @Test
    public void shouldGenerateAcceptor()
    {
        assertNotNull(acceptor, "Failed to generate acceptor");

        final int modifiers = acceptor.getModifiers();
        assertTrue(isPublic(modifiers), "Not public");
        assertTrue(isInterface(modifiers), "Not interface");
    }

    @Test
    public void shouldGenerateDecoder()
    {
        assertNotNull(decoder, "Failed to generate decoder");

        final int modifiers = decoder.getModifiers();
        assertTrue(isPublic(modifiers), "Not public");
        assertFalse(isAbstract(modifiers), "Not instantiable");
    }

    @Test
    public void shouldInvokeAppropriateAcceptor() throws Exception
    {
        final boolean[] called = { false };
        final Object acceptorInst = Proxy.newProxyInstance(
            acceptor.getClassLoader(),
            new Class<?>[]{AcceptorGeneratorTest.acceptor},
            (proxy, method, args) ->
            {
                called[0] = true;
                assertEquals("onHeartbeat", method.getName());

                final Object argument = args[0];
                assertNotNull(argument, "Missing first argument");
                assertEquals("HeartbeatDecoder", argument.getClass().getSimpleName());

                return null;
            });


        final Object decoderInst = decoder.getDeclaredConstructor(acceptor).newInstance(acceptorInst);

        onMessage(decoderInst);

        assertTrue(called[0], "Proxy not invoked");
    }

    private void onMessage(final Object inst) throws Exception
    {
        buffer.putAscii(1, ENCODED_MESSAGE);
        decoder.getMethod(ON_MESSAGE, AsciiBuffer.class, int.class, int.class, long.class)
               .invoke(inst, buffer, 1, ENCODED_MESSAGE.length(), '0');
    }
}
