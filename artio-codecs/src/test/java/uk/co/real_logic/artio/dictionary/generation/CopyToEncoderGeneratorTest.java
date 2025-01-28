/*
 * Copyright 2015-2020 Monotonic Ltd.
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
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.junit.Assert.assertSame;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.AbstractDecoderGeneratorTest.CODEC_LOGGING;
import static uk.co.real_logic.artio.dictionary.generation.Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY;
import static uk.co.real_logic.artio.dictionary.generation.ToEncoderDecoderGeneratorTest.assertEncodesCorrectly;

@SuppressWarnings("unchecked")
@RunWith(Parameterized.class)
public class CopyToEncoderGeneratorTest
{
    private static final int BUFFER_SIZE = 1024;

    private static Class<? extends Decoder> heartbeatDecoder;
    private static Class<? extends Encoder> heartbeatEncoder;

    @BeforeClass
    public static void setUp() throws ClassNotFoundException
    {
        final Map<String, CharSequence> sources = generateClasses();
        heartbeatDecoder = decoder(sources);
        if (heartbeatDecoder == null || CODEC_LOGGING)
        {
            System.out.println(sources);
        }
        heartbeatEncoder = encoder(heartbeatDecoder);
    }

    private static Class<? extends Encoder> encoder(final Class<?> decoder) throws ClassNotFoundException
    {
        return (Class<? extends Encoder>)decoder.getClassLoader().loadClass(HEARTBEAT_ENCODER);
    }

    private static Class<? extends Decoder> decoder(final Map<String, CharSequence> sources)
        throws ClassNotFoundException
    {
        return (Class<? extends Decoder>)compileInMemory(HEARTBEAT_DECODER, sources);
    }

    private static Map<String, CharSequence> generateClasses()
    {
        final StringWriterOutputManager outputManager = new StringWriterOutputManager();
        final ConstantGenerator constantGenerator = new ConstantGenerator(
            MESSAGE_EXAMPLE, TEST_PACKAGE, null, outputManager);
        final EnumGenerator enumGenerator = new EnumGenerator(MESSAGE_EXAMPLE, TEST_PARENT_PACKAGE, outputManager);
        final DecoderGenerator decoderGenerator = new DecoderGenerator(
            MESSAGE_EXAMPLE, 1, TEST_PACKAGE,
            TEST_PARENT_PACKAGE, TEST_PACKAGE, outputManager, ValidationOn.class,
            RejectUnknownFieldOn.class, RejectUnknownEnumValueOn.class, false, false,
            RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true, null);
        final EncoderGenerator encoderGenerator = new EncoderGenerator(MESSAGE_EXAMPLE, TEST_PACKAGE,
            TEST_PARENT_PACKAGE, outputManager, ValidationOn.class, RejectUnknownFieldOn.class,
            RejectUnknownEnumValueOn.class, RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true);

        constantGenerator.generate();
        enumGenerator.generate();
        encoderGenerator.generate();
        decoderGenerator.generate();

        return outputManager.getSources();
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data()
    {
        return Arrays.asList(
            new Object[] {ENCODED_MESSAGE},
            new Object[] {MULTI_STRING_VALUE_MESSAGE},
            new Object[] {MULTI_VALUE_STRING_MESSAGE},
            new Object[] {MULTI_CHAR_VALUE_MESSAGE},
            new Object[] {MULTI_CHAR_VALUE_NO_ENUM_MESSAGE},
            new Object[] {VALID_MULTI_STRING_VALUE_MESSAGE},
            new Object[] {NO_OPTIONAL_MESSAGE},
            new Object[] {COMPONENT_MESSAGE},
            new Object[] {TAG_SPECIFIED_WHERE_INT_VALUE_IS_LARGE},
            new Object[] {DERIVED_FIELDS_MESSAGE},
            new Object[] {REPEATING_GROUP_MESSAGE},
            new Object[] {SINGLE_REPEATING_GROUP_MESSAGE},
            new Object[] {NO_REPEATING_GROUP_MESSAGE},
            new Object[] {NO_REPEATING_GROUP_IN_REPEATING_GROUP_MESSAGE},
            new Object[] {NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE},
            new Object[] {MULTIPLE_ENTRY_REPEATING_GROUP},
            new Object[] {NESTED_COMPONENT_MESSAGE},
            new Object[] {NESTED_GROUP_MESSAGE},
            new Object[] {ZERO_REPEATING_GROUP_MESSAGE},
            new Object[] {SOH_IN_DATA_FIELD_MESSAGE},
            new Object[] {SHORT_TIMESTAMP_MESSAGE},
            new Object[] {LONG_FIELD_MESSAGE}
        );
    }

    private final String testCaseMessage;

    public CopyToEncoderGeneratorTest(final String testCaseMessage)
    {
        this.testCaseMessage = testCaseMessage;
    }

    @Test
    public void shouldCopyToEncoder() throws Exception
    {
        shouldToEncoderProvidedEncoder(heartbeatDecoder, heartbeatEncoder);
    }

    private void shouldToEncoderProvidedEncoder(
        final Class<? extends Decoder> heartbeatDecoder, final Class<? extends Encoder> heartbeatEncoder)
        throws Exception
    {
        final Decoder decoder = heartbeatDecoder.getConstructor().newInstance();
        final Encoder encoder = heartbeatEncoder.getConstructor().newInstance();

        final int offset = 1;
        final int messageLength = testCaseMessage.length();
        final MutableAsciiBuffer decodeBuffer = new MutableAsciiBuffer(new byte[BUFFER_SIZE]);
        decodeBuffer.putAscii(offset, testCaseMessage);

        decoder.decode(decodeBuffer, offset, messageLength);

        decoder.toEncoder(encoder);

        final Encoder otherEncoder = heartbeatEncoder.getConstructor().newInstance();
        assertSame(encoder.copyTo(otherEncoder), otherEncoder);
        assertEncodesCorrectly(testCaseMessage, otherEncoder, offset);
    }
}
