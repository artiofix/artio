/*
 * Copyright 2015-2025 Real Logic Limited., Adaptive Financial Consulting Ltd., Monotonic Ltd.
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

import org.agrona.AsciiSequenceView;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.generation.StringWriterOutputManager;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.EncodingException;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.FieldBagEncoder;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.util.Reflection;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.AbstractDecoderGeneratorTest.assertAppendToMatches;
import static uk.co.real_logic.artio.dictionary.generation.CodecConfiguration.DEFAULT_PARENT_PACKAGE;
import static uk.co.real_logic.artio.util.Reflection.*;

public class EncoderGeneratorTest
{
    private static Map<String, CharSequence> sources;
    private static Class<?> heartbeat;
    private static Class<?> enumTestMessage;
    private static Class<?> otherMessage;
    private static Class<?> heartbeatWithoutValidation;
    private static Class<?> anyFieldsMessage;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[8 * 1024]);

    @BeforeAll
    public static void generate() throws Exception
    {
        sources = generateSources(true);
        if (AbstractDecoderGeneratorTest.CODEC_LOGGING)
        {
            System.out.println(sources);
        }
        heartbeat = compileInMemory(HEARTBEAT_ENCODER, sources);
        if (heartbeat == null && !AbstractDecoderGeneratorTest.CODEC_LOGGING)
        {
            System.out.println(sources);
        }

        Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        final ClassLoader classLoader = heartbeat.getClassLoader();
        enumTestMessage = classLoader.loadClass(ENUM_TEST_MESSAGE_ENCODER);
        otherMessage = classLoader.loadClass(OTHER_MESSAGE_ENCODER);
        anyFieldsMessage = classLoader.loadClass(ANY_FIELDS_MESSAGE_ENCODER);

        final Map<String, CharSequence> sourcesWithoutValidation = generateSources(false);
        heartbeatWithoutValidation = compileInMemory(HEARTBEAT_ENCODER, sourcesWithoutValidation);
    }

    private static Map<String, CharSequence> generateSources(final boolean validation)
    {
        final Class<?> validationClass = validation ? ValidationOn.class : ValidationOff.class;
        final Class<?> rejectUnknownField = RejectUnknownFieldOff.class;
        final Class<?> rejectUnknownEnumValue = RejectUnknownEnumValueOn.class;
        final StringWriterOutputManager outputManager = new StringWriterOutputManager();
        final EnumGenerator enumGenerator = new EnumGenerator(MESSAGE_EXAMPLE, TEST_PARENT_PACKAGE, outputManager);
        final EncoderGenerator encoderGenerator =
            new EncoderGenerator(MESSAGE_EXAMPLE, TEST_PACKAGE, TEST_PARENT_PACKAGE, outputManager, validationClass,
            rejectUnknownField, rejectUnknownEnumValue, Generator.RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY,
            true);
        enumGenerator.generate();
        encoderGenerator.generate();
        return outputManager.getSources();
    }

    @Test
    public void generatesEncoderClass()
    {
        assertNotNull(heartbeat, "Not generated anything");
        assertNotNull(heartbeat);
        assertTrue(Encoder.class.isAssignableFrom(heartbeat));

        final int modifiers = heartbeat.getModifiers();
        assertFalse(isAbstract(modifiers), "Not instantiable");
        assertTrue(isPublic(modifiers), "Not public");
    }

    @Test
    public void generatesSetters() throws Exception
    {
        heartbeat.getMethod(ON_BEHALF_OF_COMP_ID, CharSequence.class);
        final Class<?> stringEnumClass = heartbeat.getClassLoader()
            .loadClass(DEFAULT_PARENT_PACKAGE + ".OnBehalfOfCompID");
        heartbeat.getMethod(ON_BEHALF_OF_COMP_ID, stringEnumClass);
    }

    @Test
    public void stringSettersWriteToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setTestReqId(encoder);

        assertTestReqIsValue(encoder);
    }

    @Test
    public void shouldNotWriteDataForEmptyCharSequence() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setTestReqIdTo(encoder, "");

        assertArrayEquals(new byte[0], getTestReqIdBytes(encoder));
        assertTestReqIdOffset(0, encoder);
        assertTestReqIdLength(0, encoder);
    }

    @Test
    public void shouldNotWriteDataForEmptyAsciiSequenceView() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setTestReqIdTo(encoder, new AsciiSequenceView());

        assertArrayEquals(new byte[0], getTestReqIdBytes(encoder));
        assertTestReqIdOffset(0, encoder);
        assertTestReqIdLength(0, encoder);
    }

    @Test
    public void shouldNotUseAsciiSequenceViewAfterReset() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        final byte[] originalValue = LONG_VALUE_IN_BYTES;
        final byte[] byteArray = originalValue.clone();

        final AsciiSequenceView asciiSequenceView = new AsciiSequenceView();
        asciiSequenceView.wrap(new UnsafeBuffer(byteArray), 0, byteArray.length);

        setTestReqIdTo(encoder, asciiSequenceView);
        reset(encoder);
        setTestReqIdTo(encoder, "xxx");

        assertArrayEquals(originalValue, byteArray);
    }

    @Test
    public void stringSettersResizeByteArray() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setTestReqIdTo(encoder, "abcd");

        assertArrayEquals(LONG_VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdLength(4, encoder);
        assertTestReqIdOffset(0, encoder);
    }

    @Test
    public void shouldWriteByteArraySettersToFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        heartbeat
            .getMethod(TEST_REQ_ID, byte[].class)
            .invoke(encoder, VALUE_IN_BYTES);

        assertTestReqIsValue(encoder);
        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void offsetAndLengthByteArraySettersWriteFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setTestReqIdBytes(encoder);

        assertArrayEquals(PREFIXED_VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdOffset(1, encoder);
        assertTestReqIdLength(3, encoder);

        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void offsetAndLengthByteArrayCopyingSettersWriteFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        heartbeat
            .getMethod(TEST_REQ_ID_AS_COPY, byte[].class, int.class, int.class)
            .invoke(encoder, PREFIXED_VALUE_IN_BYTES, 1, 3);

        assertArrayEquals(VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdOffset(0, encoder);
        assertTestReqIdLength(3, encoder);

        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void shouldWriteDirectBufferSettersToFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        heartbeat
            .getMethod(TEST_REQ_ID, DirectBuffer.class)
            .invoke(encoder, new UnsafeBuffer(VALUE_IN_BYTES));

        assertTestReqIsValue(encoder);
        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void offsetAndLengthDirectBufferSettersWriteFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setTestReqIdBuffer(encoder);

        assertArrayEquals(PREFIXED_VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdOffset(1, encoder);
        assertTestReqIdLength(3, encoder);

        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void shouldWriteAsciiSequenceViewSetters() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        heartbeat
            .getMethod(TEST_REQ_ID, AsciiSequenceView.class)
            .invoke(encoder, new AsciiSequenceView(new UnsafeBuffer(PREFIXED_VALUE_IN_BYTES), 1, 3));

        assertArrayEquals(PREFIXED_VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdOffset(1, encoder);
        assertTestReqIdLength(3, encoder);

        assertEncodesTestReqIdFully(encoder);
    }

    @Test
    public void charArraySettersWriteToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        final Object value = new char[]{ 'a', 'b', 'c' };
        heartbeat.getMethod(TEST_REQ_ID, char[].class)
            .invoke(encoder, value);

        assertTestReqIsValue(encoder);
    }

    @Test
    public void stringSettersByEnumToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setEnum(encoder,
            ON_BEHALF_OF_COMP_ID,
            DEFAULT_PARENT_PACKAGE + ".OnBehalfOfCompID",
            "abc");
        assertOnBehalfOfCompIDValue(encoder, "abc");
    }

    @Test
    public void stringSettersByEnumDoesNothingNullValue() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        setEnum(encoder,
            ON_BEHALF_OF_COMP_ID,
            DEFAULT_PARENT_PACKAGE + ".OnBehalfOfCompID",
            "NULL_VAL"
        );
        assertOnBehalfOfCompIDValue(encoder, "");
    }

    @Test
    public void stringSettersByEnumThrowForUnknownValue() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        assertThrows(EncodingException.class, () ->
        {
            setEnum(encoder,
                ON_BEHALF_OF_COMP_ID,
                DEFAULT_PARENT_PACKAGE + ".OnBehalfOfCompID",
                "ARTIO_UNKNOWN"
            );
        });
    }

    @Test
    public void intSettersWriteToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setInt(encoder, INT_FIELD, 1);

        assertEquals(1, getField(encoder, INT_FIELD));
    }

    @Test
    public void intSettersByEnumWriteToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        setEnum(encoder, INT_FIELD, DEFAULT_PARENT_PACKAGE + ".IntField", "ONE");

        assertEquals(1, getField(encoder, INT_FIELD));
    }

    @Test
    public void intSettersByEnumDoesNothingForNullValue() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        setEnum(encoder, INT_FIELD, DEFAULT_PARENT_PACKAGE + ".IntField", "NULL_VAL");
        assertEquals(0, getField(encoder, INT_FIELD));
    }

    @Test
    public void intSettersByEnumThrowForUnknownValue() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        assertThrows(EncodingException.class, () ->
        {
            setEnum(encoder, INT_FIELD, DEFAULT_PARENT_PACKAGE + ".IntField", "ARTIO_UNKNOWN");
        });
    }

    @Test
    public void floatSettersWriteToFields() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        final DecimalFloat value = new DecimalFloat(1, 2);

        setFloat(encoder, FLOAT_FIELD, value);

        assertEquals(value, getField(encoder, FLOAT_FIELD));
    }

    @Test
    public void flagsForOptionalFieldsInitiallyUnset() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();
        assertFalse(hasTestReqId(encoder), "hasTestReqId initially true");
    }

    @Test
    public void flagsForOptionalFieldsUpdated() throws Exception
    {
        final Object encoder = heartbeat.getConstructor().newInstance();

        setTestReqId(encoder);

        assertTrue(hasTestReqId(encoder), "hasTestReqId not updated");
    }

    @Test
    public void encodesValues() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setupHeader(encoder);
        setupTrailer(encoder);
        setOptionalFields(encoder);

        assertEncodesTo(encoder, ENCODED_MESSAGE);
    }

    @Test
    public void shouldSupportLongFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setLongField(encoder);
        setupHeader(encoder);
        setupTrailer(encoder);

        assertEncodesTo(encoder, LONG_FIELD_MESSAGE);

        assertToStringAndAppendToMatches(encoder, containsString(STRING_JUST_LONG_FIELD));
    }

    @Test
    public void encodesValuesWithOptionalTrailerFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setupHeader(encoder);
        setupTrailer(encoder, "Good to go!");

        setOptionalFields(encoder);
        assertEncodesTo(encoder, ENCODED_MESSAGE_WITH_SIGNATURE);
    }

    @Test
    public void encodesValuesWithBeginString() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setupHeader(encoder, "FIXT.1.1");
        setupTrailer(encoder);

        setOptionalFields(encoder);
        assertEncodesTo(encoder, ENCODED_MESSAGE_FIXT11);
    }

    @Test
    public void encodesDataWithoutOverflowFromPreviousMessage() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setupHeader(encoder, "FIXT.1.1");
        setupTrailer(encoder);
        setTestReqIdTo(encoder, ABC);
        setBoolean(encoder, BOOLEAN_FIELD, true);
        setByteArrayAsCopy(encoder, DATA_FIELD, new byte[]{ '1', '2', '3', '4', '5', '6' }, 0, 6);
        setInt(encoder, DATA_LENGTH_FIELD, 6);

        assertEncodesTo(encoder, ENCODED_MESSAGE_LONG_DATA);

        encoder.reset();

        setRequiredFields(encoder);
        setupHeader(encoder, "FIXT.1.1");
        setupTrailer(encoder);
        setTestReqIdTo(encoder, ABC);
        setBoolean(encoder, BOOLEAN_FIELD, true);
        setByteArrayAsCopy(encoder, DATA_FIELD, new byte[]{ '1', '2', '3' }, 0, 3);
        setInt(encoder, DATA_LENGTH_FIELD, 3);

        assertEncodesTo(encoder, ENCODED_MESSAGE_FIXT11);
    }

    @Test
    public void encodeDecimalFloatUsingRawValueAndScale() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setFloatFieldRawValues(encoder);
        setupHeader(encoder);
        setupTrailer(encoder);
        setOptionalFields(encoder);

        assertEncodesTo(encoder, ENCODED_MESSAGE);
    }

    @Test
    public void encodeDecimalFloatWithoutAlteringSentinelValue() throws Exception
    {
        //Given
        final Encoder encoder = newHeartbeat();
        final DecimalFloat zero = DecimalFloat.ZERO.mutableCopy();

        setFloat(encoder, FLOAT_FIELD, zero);

        //When
        setFloatFieldRawValues(encoder);

        //Then
        assertThat(zero, is(new DecimalFloat()));
        assertThat(DecimalFloat.ZERO, is(new DecimalFloat()));
    }

    @Test
    public void ignoresMissingOptionalValues() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setupHeader(encoder);
        setupTrailer(encoder);

        assertEncodesTo(encoder, NO_OPTIONAL_MESSAGE);
    }

    @Test
    public void automaticallyComputesDerivedHeaderAndTrailerFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        assertEncodesTo(encoder, DERIVED_FIELDS_MESSAGE);
    }

    @Test
    public void shouldGenerateHumanReadableToString() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        assertThat(encoder.toString(), containsString(STRING_NO_OPTIONAL_MESSAGE_SUFFIX));
    }

    @Test
    public void shouldIncludeOptionalFieldsInToString() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setOptionalFields(encoder);

        assertThat(encoder.toString(), containsString(STRING_ENCODED_MESSAGE_SUFFIX));
    }

    @Test
    public void shouldEncodeShorterStringsAfterLongerStrings() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        encoder.encode(buffer, 1);

        setCharSequence(encoder, ON_BEHALF_OF_COMP_ID, "ab");

        assertEncodesTo(encoder, SHORTER_STRING_MESSAGE);
    }

    @Test
    public void shouldEncodeGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        assertEncodesTo(encoder, REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldEncodeNestedGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);

        final Object group = getEgGroup(encoder, 1);
        setGroupField(group, 1);

        setNestedField(group);

        assertEncodesTo(encoder, NESTED_GROUP_MESSAGE);
    }

    @Test
    public void shouldResetOptionalFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setOptionalFields(encoder);

        reset(encoder);

        setRequiredFields(encoder);

        assertEncodesTo(encoder, NO_OPTIONAL_MESSAGE);
    }

    @Test
    public void shouldResetFlagForMissingRequiredIntFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newHeartbeat();

            setRequiredFields(encoder);

            encoder.reset();

            setOnBehalfOfCompID(encoder);
            setFloatField(encoder);
            setSomeTimeField(encoder, 1);

            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldResetRequiredFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);

        reset(encoder);

        assertFalse(hasOnBehalfOfCompID(encoder));
    }

    @Test
    public void shouldResetComponents() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setupComponent(encoder);

        reset(encoder);

        setRequiredFields(encoder);
        assertEncodesTo(encoder, NO_OPTIONAL_MESSAGE);
    }

    @Test
    public void shouldResetGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        final Object group = getEgGroup(encoder, 1);
        setGroupField(group, 1);
        setNestedField(group);

        reset(encoder);

        setRequiredFields(encoder);
        assertEncodesTo(encoder, NO_OPTIONAL_MESSAGE);
    }

    @Test
    public void shouldEncodeGroupsOfSizeZero() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        final Object group = getEgGroup(encoder, 1);
        setGroupField(group, 1);
        setNestedField(group);

        getEgGroup(encoder, 0);

        assertEncodesTo(encoder, ZERO_REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldEncodeGroupsAfterReset() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        reset(encoder);

        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        assertEncodesTo(encoder, REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldEncodeShorterGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        setRequiredFields(encoder);
        setEgGroupToOneElement(encoder);

        assertEncodesTo(encoder, SINGLE_REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldEncodeShorterGroupsAfterReset() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        reset(encoder);

        setRequiredFields(encoder);
        setEgGroupToOneElement(encoder);

        assertEncodesTo(encoder, SINGLE_REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldEncodeDefaultBeginStringAfterReset() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setupHeader(encoder, "FIXT.1.1");
        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        reset(encoder);

        setRequiredFields(encoder);
        setEgGroupToOneElement(encoder);

        assertEncodesTo(encoder, SINGLE_REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldIgnoreUnnecessaryGroupNextCalls() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        Object egGroup = setEgGroupToOneElement(encoder);

        egGroup = next(egGroup);
        egGroup = next(egGroup);
        next(egGroup);

        assertEncodesTo(encoder, SINGLE_REPEATING_GROUP_MESSAGE);
    }

    @Test
    public void shouldValidateMissingRequiredStringFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {

            final Encoder encoder = newHeartbeat();

            setFloatField(encoder);
            setSomeTimeField(encoder, 0);

            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredFloatFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newHeartbeat();

            setOnBehalfOfCompID(encoder);
            setSomeTimeField(encoder, 0);

            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredIntFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newHeartbeat();

            setOnBehalfOfCompID(encoder);
            setFloatField(encoder);
            setSomeTimeField(encoder, 1);

            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredTemporalFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newHeartbeat();

            setOnBehalfOfCompID(encoder);
            setFloatField(encoder);

            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredCharEnumFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newEnumTestMessage();

            setEnum(
                encoder,
                INT_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".IntEnumReq",
                "THIRTY");
            setEnum(
                encoder,
                STRING_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".StringEnumReq",
                "GAMMA");
            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredIntEnumFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newEnumTestMessage();

            setEnum(
                encoder,
                CHAR_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".CharEnumReq",
                "C");
            setEnum(
                encoder,
                STRING_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".StringEnumReq",
                "GAMMA");
            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void shouldValidateMissingRequiredStringEnumFields() throws Exception
    {
        assertThrows(EncodingException.class, () ->
        {
            final Encoder encoder = newEnumTestMessage();

            setEnum(
                encoder,
                CHAR_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".CharEnumReq",
                "C");
            setEnum(
                encoder,
                INT_ENUM_REQ,
                DEFAULT_PARENT_PACKAGE + ".IntEnumReq",
                "THIRTY");
            encoder.encode(buffer, 1);
        });
    }

    @Test
    public void canDisableRequiredStringFieldValidation() throws Exception
    {
        final Encoder encoder = (Encoder)heartbeatWithoutValidation.getConstructor().newInstance();

        setFloatField(encoder);
        setSomeTimeField(encoder, 0);

        encoder.encode(buffer, 1);
    }

    @Test
    public void canDisableRequiredFloatFieldValidation() throws Exception
    {
        final Encoder encoder = (Encoder)heartbeatWithoutValidation.getConstructor().newInstance();

        setOnBehalfOfCompID(encoder);
        setSomeTimeField(encoder, 0);

        encoder.encode(buffer, 1);
    }

    @Test
    public void canDisableRequiredTemporalFieldValidation() throws Exception
    {
        final Encoder encoder = (Encoder)heartbeatWithoutValidation.getConstructor().newInstance();

        setOnBehalfOfCompID(encoder);
        setFloatField(encoder);

        encoder.encode(buffer, 1);
    }

    @Test
    public void shouldEncodeShortTimestamp() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder, 0);

        assertEncodesTo(encoder, SHORT_TIMESTAMP_MESSAGE);
    }

    @Test
    public void shouldGenerateComponentClass() throws Exception
    {
        final Class<?> component = compileInMemory(COMPONENT_ENCODER, sources);

        assertNotNull(component);
    }

    @Test
    public void shouldGenerateFieldTagsInJavadoc()
    {
        final String egComponent = sources.get("null." + EG_COMPONENT + "Encoder").toString();
        assertThat(egComponent, containsString("/* ComponentField = 124 */\n    public"));

        final String header = sources.get("null.HeaderEncoder").toString();
        assertThat(header, containsString("/* BeginString = 8 */\n    public"));
    }

    @Test
    public void shouldEncodeComponents() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);

        setupComponent(encoder);

        assertEncodesTo(encoder, COMPONENT_MESSAGE);
    }

    @Test
    public void shouldBeAbleToToStringComponentValues() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);

        setupComponent(encoder);

        assertToStringAndAppendToMatches(encoder, containsString(COMPONENT_TO_STRING));
    }

    @Test
    public void shouldGenerateToStringForGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        assertToStringAndAppendToMatches(encoder, containsString(STRING_GROUP_TWO_ELEMENTS));
    }

    @Test
    public void shouldGenerateToStringForShorterGroups() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setEgGroupToTwoElements(encoder);

        setRequiredFields(encoder);
        setEgGroupToOneElement(encoder);

        assertToStringAndAppendToMatches(encoder, containsString(STRING_GROUP_ONE_ELEMENT));
    }

    @Test
    public void shouldGenerateToStringForShorterGroupsAfterReset() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);
        setEgGroupToTwoElements(encoder);

        reset(encoder);

        setRequiredFields(encoder);
        setEgGroupToOneElement(encoder);

        assertToStringAndAppendToMatches(encoder, containsString(STRING_GROUP_ONE_ELEMENT));
    }

    @Test
    public void shouldToStringShorterStringsAfterLongerStrings() throws Exception
    {
        final Encoder encoder = newHeartbeat();

        setRequiredFields(encoder);

        setCharSequence(encoder, ON_BEHALF_OF_COMP_ID, "ab");

        assertToStringAndAppendToMatches(encoder, containsString("ab"));
        assertToStringAndAppendToMatches(encoder, not(containsString("abc")));
    }

    private void assertToStringAndAppendToMatches(final Encoder encoder, final Matcher<String> matcher)
    {
        assertThat(encoder.toString(), matcher);

        assertAppendToMatches(encoder::appendTo, matcher);
    }

    @Test
    public void shouldGenerateHasMethodsForFields() throws Exception
    {
        final Encoder encoder = newHeartbeat();
        setRequiredFields(encoder);

        assertTrue(hasOnBehalfOfCompID(encoder));
        assertFalse(hasTestReqID(encoder));
    }

    @Test
    public void shouldGenerateTwoCharacterMessageTypes() throws Exception
    {
        final Encoder encoder = (Encoder)otherMessage.getConstructor().newInstance();

        assertThat(encoder.toString(), containsString("\"MsgType\": \"" + OTHER_MESSAGE_TYPE + "\""));
        assertEncodesTo(encoder, "8=FIX.4.4\0019=6\00135=AB\00110=247\001");
    }

    @Test
    public void shouldGenerateAnyFieldsEncoderIfRequested() throws Exception
    {
        final Encoder encoder = newAnyFieldsMessage();
        final Encoder copy = newAnyFieldsMessage();

        setLong(encoder, "longField2", 1000000);

        final FieldBagEncoder trailingAnyFields = getAnyFieldsEncoder(encoder, "trailingAnyFields");
        trailingAnyFields.addIntField(123, 456);
        trailingAnyFields.addAsciiStringField(39999, "lorem ipsum");

        assertThat(encoder.toString(), allOf(
            containsString("\"MsgType\": \"" + ANY_FIELDS_MESSAGE_TYPE + "\""),
            containsString("\"LongField2\": \"1000000\""),
            containsString("\"TrailingAnyFields\": \"123=456\00139999=lorem ipsum\001\"")
        ));

        final String expectedFix1 = "8=FIX.4.4\0019=45\00135=AF\001" +
            "5005=1000000\001123=456\00139999=lorem ipsum\00110=188\001";
        assertEncodesTo(encoder, expectedFix1);

        encoder.copyTo(copy);
        assertEncodesTo(copy, expectedFix1);

        reset(encoder);
        setLong(encoder, "longField2", 350);

        assertThat(encoder.toString(), not(containsString("TrailingAnyFields")));

        final String expectedFix2 = "8=FIX.4.4\0019=15\00135=AF\0015005=350\00110=203\001";
        assertEncodesTo(encoder, expectedFix2);

        encoder.copyTo(copy);
        assertEncodesTo(copy, expectedFix2);
    }

    private void setNestedField(final Object group) throws Exception
    {
        final Object nestedGroup = getNestedGroup(group, 1);
        setInt(nestedGroup, "nestedField", 1);
    }

    private boolean hasOnBehalfOfCompID(final Encoder encoder) throws Exception
    {
        return (boolean)get(encoder, "hasOnBehalfOfCompID");
    }

    private boolean hasTestReqID(final Encoder encoder) throws Exception
    {
        return (boolean)get(encoder, "hasTestReqID");
    }

    private void setupComponent(final Encoder encoder) throws Exception
    {
        final Object egComponent = getEgComponent(encoder);
        setInt(egComponent, COMPONENT_FIELD, 2);

        Object componentGroup = getComponentGroup(egComponent, 2);
        setComponentGroupField(componentGroup, 1);
        setRequiredComponentGroupField(componentGroup, 10);

        componentGroup = next(componentGroup);
        setComponentGroupField(componentGroup, 2);
        setRequiredComponentGroupField(componentGroup, 20);
    }

    private void setEgGroupToTwoElements(final Encoder encoder) throws Exception
    {
        Object egGroup = getEgGroup(encoder, 2);
        setGroupField(egGroup, 1);

        egGroup = next(egGroup);
        setGroupField(egGroup, 2);
    }

    private Object setEgGroupToOneElement(final Encoder encoder) throws Exception
    {
        final Object egGroup = getEgGroup(encoder, 1);
        setGroupField(egGroup, 2);
        return egGroup;
    }


    private void setGroupField(final Object group, final int value) throws Exception
    {
        setInt(group, "groupField", value);
    }

    private void setComponentGroupField(final Object group, final int value) throws Exception
    {
        setInt(group, "componentGroupField", value);
    }

    private void setRequiredComponentGroupField(final Object group, final int value) throws Exception
    {
        setInt(group, "requiredComponentGroupField", value);
    }

    private void setupHeader(final Encoder encoder) throws Exception
    {
        setupHeader(encoder, "FIX.4.4");
    }

    private void setupHeader(final Encoder encoder, final String beginString) throws Exception
    {
        final Object header = Reflection.get(encoder, "header");
        setCharSequence(header, "beginString", beginString);
        setCharSequence(header, MSG_TYPE, "0");
    }

    private void setRequiredFields(final Encoder encoder) throws Exception
    {
        setRequiredFields(encoder, 1);
    }

    private void setRequiredFields(final Encoder encoder, final int someTime) throws Exception
    {
        setOnBehalfOfCompID(encoder);
        setIntField(encoder);
        setFloatField(encoder);
        setSomeTimeField(encoder, someTime);
    }

    private void setOnBehalfOfCompID(final Encoder encoder) throws Exception
    {
        setCharSequence(encoder, ON_BEHALF_OF_COMP_ID, "abc");
    }

    private void setFloatField(final Encoder encoder) throws Exception
    {
        setFloat(encoder, FLOAT_FIELD, new DecimalFloat(11, 1));
    }

    private void setFloatFieldRawValues(final Encoder encoder) throws Exception
    {
        setFloat(encoder, FLOAT_FIELD, 11, 1);
    }

    private void setIntField(final Encoder encoder) throws Exception
    {
        setInt(encoder, INT_FIELD, 2);
    }

    private void setLongField(final Encoder encoder) throws Exception
    {
        setLong(encoder, LONG_FIELD, Long.MAX_VALUE);
    }

    private void setSomeTimeField(final Encoder encoder, final int someTime) throws Exception
    {
        final UtcTimestampEncoder utcTimestampEncoder = new UtcTimestampEncoder();
        final int length = utcTimestampEncoder.encode(someTime);
        encoder.getClass()
            .getMethod(SOME_TIME_FIELD, byte[].class, int.class)
            .invoke(encoder, utcTimestampEncoder.buffer(), length);
    }

    private void setOptionalFields(final Encoder encoder) throws Exception
    {
        setTestReqIdTo(encoder, ABC);
        setBoolean(encoder, BOOLEAN_FIELD, true);
        setByteArray(encoder, DATA_FIELD, new byte[]{ '1', '2', '3' });
        setInt(encoder, DATA_LENGTH_FIELD, 3);
    }

    private FieldBagEncoder getAnyFieldsEncoder(final Encoder encoder, final String name) throws Exception
    {
        return (FieldBagEncoder)get(encoder, name);
    }

    private void setupTrailer(final Encoder encoder) throws Exception
    {
        setupTrailer(encoder, "");
    }

    private void setupTrailer(final Encoder encoder, final String signature) throws Exception
    {
        final Object trailer = Reflection.get(encoder, "trailer");

        if (signature.length() > 0)
        {
            setByteArray(trailer, "signature", signature.getBytes(StandardCharsets.US_ASCII));
            setInt(trailer, "signatureLength", signature.length());
        }

        setCharSequence(trailer, "checkSum", "12");
    }

    private void assertEncodesTo(final Encoder encoder, final String expectedValue)
    {
        final long result = encoder.encode(buffer, 1);
        final int length = Encoder.length(result);
        final int offset = Encoder.offset(result);
        assertEquals(expectedValue, buffer.getAscii(offset, expectedValue.length()));
        assertEquals(expectedValue.length(), length);
    }

    private void assertTestReqIsValue(final Object encoder) throws Exception
    {
        assertArrayEquals(VALUE_IN_BYTES, getTestReqIdBytes(encoder));
        assertTestReqIdOffset(0, encoder);
        assertTestReqIdLength(3, encoder);
    }

    private byte[] getTestReqIdBytes(final Object encoder) throws Exception
    {
        return getBytesField(encoder, TEST_REQ_ID);
    }

    private byte[] getBytesField(final Object encoder, final String fieldName) throws Exception
    {
        return ((UnsafeBuffer)getField(encoder, fieldName)).byteArray();
    }

    private void assertOnBehalfOfCompIDValue(final Object encoder, final String value) throws Exception
    {
        assertArrayEquals(value.getBytes(), getBytesField(encoder, ON_BEHALF_OF_COMP_ID));
        assertEquals(value.length(), getField(encoder, ON_BEHALF_OF_COMP_ID_LENGTH));
    }

    private boolean hasTestReqId(final Object encoder) throws Exception
    {
        return (boolean)get(encoder, HAS_TEST_REQ_ID);
    }

    private void setTestReqId(final Object encoder) throws Exception
    {
        setTestReqIdTo(encoder, ABC);
    }

    private void setTestReqIdTo(final Object encoder, final String value) throws Exception
    {
        setCharSequence(encoder, TEST_REQ_ID, value);
    }

    private void setTestReqIdTo(final Object encoder, final AsciiSequenceView asciiSequenceView) throws Exception
    {
        heartbeat
            .getMethod(TEST_REQ_ID, AsciiSequenceView.class)
            .invoke(encoder, asciiSequenceView);
    }

    private Encoder newHeartbeat() throws Exception
    {
        return (Encoder)heartbeat.getConstructor().newInstance();
    }

    private Encoder newEnumTestMessage() throws Exception
    {
        return (Encoder)enumTestMessage.getConstructor().newInstance();
    }

    private Encoder newAnyFieldsMessage() throws Exception
    {
        return (Encoder)anyFieldsMessage.getConstructor().newInstance();
    }

    private void assertTestReqIdLength(final int expectedLength, final Object encoder) throws Exception
    {
        assertEquals(expectedLength, getField(encoder, TEST_REQ_ID_LENGTH));
    }

    private void assertTestReqIdOffset(final int expectedOffset, final Object encoder) throws Exception
    {
        assertEquals(expectedOffset, getField(encoder, TEST_REQ_ID_OFFSET));
    }

    private void assertEncodesTestReqIdFully(final Encoder encoder) throws Exception
    {
        setRequiredFields(encoder);
        assertThat(encoder.toString(), containsString(STRING_ONLY_TESTREQ_MESSAGE_SUFFIX));
        assertEncodesTo(encoder, ONLY_TESTREQ_ENCODED_MESSAGE);
    }

    private void setTestReqIdBytes(
        final Object encoder) throws Exception
    {
        heartbeat
            .getMethod(TEST_REQ_ID, byte[].class, int.class, int.class)
            .invoke(encoder, PREFIXED_VALUE_IN_BYTES, 1, 3);
    }

    private void setTestReqIdBuffer(
        final Object encoder) throws Exception
    {
        heartbeat
            .getMethod(TEST_REQ_ID, DirectBuffer.class, int.class, int.class)
            .invoke(encoder, new UnsafeBuffer(PREFIXED_VALUE_IN_BYTES), 1, 3);
    }
}
