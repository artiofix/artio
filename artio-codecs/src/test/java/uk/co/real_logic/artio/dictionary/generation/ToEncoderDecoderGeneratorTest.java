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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.Map;

import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.params.provider.Arguments.of;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.AbstractDecoderGeneratorTest.CODEC_LOGGING;
import static uk.co.real_logic.artio.dictionary.generation.Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY;

@SuppressWarnings("unchecked")
public class ToEncoderDecoderGeneratorTest
{
    public static final String ENCODED_MESSAGE_OTHER_TIMESTAMP =
        "8=FIX.4.4\0019=81\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19800101-00:00:00.001\00110=199\001";

    private static final int BUFFER_SIZE = 1024;

    private static Class<? extends Decoder> heartbeatDecoder;
    private static Class<? extends Encoder> heartbeatEncoder;

    private static Class<? extends Decoder> flyweightHeartbeatDecoder;
    private static Class<? extends Encoder> flyweightHeartbeatEncoder;

    @BeforeAll
    public static void generateClasses() throws ClassNotFoundException
    {
        Map<String, CharSequence> sources = generateClasses(false);

        heartbeatDecoder = decoder(sources);
        if (heartbeatDecoder == null || CODEC_LOGGING)
        {
            System.out.println(sources);
        }
        heartbeatEncoder = encoder(heartbeatDecoder);

        sources = generateClasses(true);

        flyweightHeartbeatDecoder = decoder(sources);
        if (flyweightHeartbeatDecoder == null || CODEC_LOGGING)
        {
            System.out.println(sources);
        }
        flyweightHeartbeatEncoder = encoder(flyweightHeartbeatDecoder);
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

    private static Map<String, CharSequence> generateClasses(final boolean flyweightStringsEnabled)
    {
        final StringWriterOutputManager outputManager = new StringWriterOutputManager();
        final ConstantGenerator constantGenerator = new ConstantGenerator(
            MESSAGE_EXAMPLE, TEST_PACKAGE, null, outputManager);
        final EnumGenerator enumGenerator = new EnumGenerator(MESSAGE_EXAMPLE, TEST_PARENT_PACKAGE, outputManager);
        final DecoderGenerator decoderGenerator = new DecoderGenerator(
            MESSAGE_EXAMPLE, 1, TEST_PACKAGE,
            TEST_PARENT_PACKAGE, TEST_PACKAGE, outputManager, ValidationOn.class,
            RejectUnknownFieldOn.class, RejectUnknownEnumValueOn.class, flyweightStringsEnabled, false, false,
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

    public static Stream<Arguments> data()
    {
        return Stream.of(
            of(ENCODED_MESSAGE),
            of(MULTI_STRING_VALUE_MESSAGE),
            of(MULTI_VALUE_STRING_MESSAGE),
            of(MULTI_CHAR_VALUE_MESSAGE),
            of(MULTI_CHAR_VALUE_NO_ENUM_MESSAGE),
            of(VALID_MULTI_STRING_VALUE_MESSAGE),
            of(NO_OPTIONAL_MESSAGE),
            of(COMPONENT_MESSAGE),
            of(TAG_SPECIFIED_WHERE_INT_VALUE_IS_LARGE),
            of(DERIVED_FIELDS_MESSAGE),
            of(REPEATING_GROUP_MESSAGE),
            of(SINGLE_REPEATING_GROUP_MESSAGE),
            of(NO_REPEATING_GROUP_MESSAGE),
            of(NO_REPEATING_GROUP_IN_REPEATING_GROUP_MESSAGE),
            of(NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE),
            of(MULTIPLE_ENTRY_REPEATING_GROUP),
            of(NESTED_COMPONENT_MESSAGE),
            of(NESTED_GROUP_MESSAGE),
            of(ZERO_REPEATING_GROUP_MESSAGE),
            of(SOH_IN_DATA_FIELD_MESSAGE),
            of(SHORT_TIMESTAMP_MESSAGE)
        );
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void shouldToEncoderProvidedEncoder(final String testCaseMessage) throws Exception
    {
        shouldToEncoderProvidedEncoder(heartbeatDecoder, heartbeatEncoder, testCaseMessage);
    }

    @ParameterizedTest
    @MethodSource(value = "data")
    public void shouldToEncoderProvidedEncoderFlyweight(final String testCaseMessage) throws Exception
    {
        shouldToEncoderProvidedEncoder(flyweightHeartbeatDecoder, flyweightHeartbeatEncoder, testCaseMessage);
    }

    private void shouldToEncoderProvidedEncoder(
        final Class<? extends Decoder> heartbeatDecoder,
        final Class<? extends Encoder> heartbeatEncoder,
        final String testCaseMessage)
        throws Exception
    {
        final Decoder decoder = heartbeatDecoder.getConstructor().newInstance();
        final Encoder encoder = heartbeatEncoder.getConstructor().newInstance();

        final int offset = 1;
        final int messageLength = testCaseMessage.length();

        final MutableAsciiBuffer decodeBuffer = new MutableAsciiBuffer(new byte[BUFFER_SIZE]);
        decodeBuffer.putAscii(offset, testCaseMessage);

        decoder.decode(decodeBuffer, offset, messageLength);

        final Encoder returnedEncoder = decoder.toEncoder(encoder);
        assertSame(encoder, returnedEncoder);

        assertEncodesCorrectly(testCaseMessage, encoder, offset);

        // Test that encoders copy and don't wrap the timestamp field
        decodeBuffer.putAscii(offset, ENCODED_MESSAGE_OTHER_TIMESTAMP);
        decoder.decode(decodeBuffer, offset, ENCODED_MESSAGE_OTHER_TIMESTAMP.length());
        assertEncodesCorrectly(testCaseMessage, encoder, offset);
    }

    public static void assertEncodesCorrectly(final String testCaseMessage, final Encoder encoder, final int offset)
    {
        final MutableAsciiBuffer encodeBuffer = new MutableAsciiBuffer(new byte[BUFFER_SIZE]);
        final long result = encoder.encode(encodeBuffer, offset);
        final int length = Encoder.length(result);
        final int outputOffset = Encoder.offset(result);

        assertEquals(testCaseMessage, encodeBuffer.getAscii(outputOffset, length));
    }
}
