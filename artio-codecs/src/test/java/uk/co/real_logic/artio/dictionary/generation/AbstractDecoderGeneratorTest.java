/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd., Monotonic Ltd.
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
import org.agrona.collections.IntHashSet;
import org.agrona.generation.StringWriterOutputManager;
import org.hamcrest.Matcher;
import org.junit.Test;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.ExampleDictionary;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.RejectReason;
import uk.co.real_logic.artio.fields.UtcTimestampDecoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;
import uk.co.real_logic.artio.util.Reflection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static org.agrona.generation.CompilerUtil.compileInMemory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.builder.Decoder.NO_ERROR;
import static uk.co.real_logic.artio.dictionary.ExampleDictionary.*;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.*;
import static uk.co.real_logic.artio.dictionary.generation.DecoderGenerator.*;
import static uk.co.real_logic.artio.dictionary.generation.EnumGenerator.UNKNOWN_NAME;
import static uk.co.real_logic.artio.fields.DecimalFloat.MISSING_FLOAT;
import static uk.co.real_logic.artio.util.CustomMatchers.assertTargetThrows;
import static uk.co.real_logic.artio.util.MessageTypeEncoding.packMessageType;
import static uk.co.real_logic.artio.util.Reflection.*;

public abstract class AbstractDecoderGeneratorTest
{
    static final boolean CODEC_LOGGING = Boolean.getBoolean("fix.codec.log");

    private static final char[] ABC = "abc".toCharArray();
    private static final char[] AB = "ab".toCharArray();
    private static final String ON_BEHALF_OF_COMP_ID = "onBehalfOfCompID";
    private static final char[] MULTI_CHAR_VALUE = "a b".toCharArray();
    private static final char[] MULTI_CHAR_VALUE_NO_ENUM = "a b z f".toCharArray();
    private static final char[] MULTI_VALUE_STRING = "ab cd".toCharArray();
    private static final String CHAR_ENUM_OPT = "charEnumOpt";
    private static final String INT_ENUM_OPT = "intEnumOpt";
    private static final String STRING_ENUM_OPT = "stringEnumOpt";
    public static final int CAPACITY = 8 * 1024;

    private static Map<String, CharSequence> sourcesWithValidation;

    private static Class<?> heartbeatWithoutValidation;
    private static Class<?> heartbeatWithoutEnumValueValidation;
    private static Class<?> heartbeatWithRejectingUnknownFields;
    private static Class<?> heartbeat;
    private static Class<?> component;
    private static Class<?> otherMessage;
    private static Class<?> fieldsMessage;
    private static Class<?> phoneBookMessage;
    private static Class<?> allReqFieldTypesMessage;
    private static Class<?> enumTestMessage;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[CAPACITY]);

    static void generate(final boolean flyweightStringsEnabled) throws Exception
    {
        sourcesWithValidation = generateSources(
            true, false, true, flyweightStringsEnabled, false);
        final Map<String, CharSequence> sourcesWithNoEnumValueValidation = generateSources(
            true, false, false, flyweightStringsEnabled, false);
        final Map<String, CharSequence> sourcesWithoutValidation = generateSources(
            false, false, true, flyweightStringsEnabled, true);
        final Map<String, CharSequence> sourcesRejectingUnknownFields = generateSources(
            true, true, true, flyweightStringsEnabled, false);
        heartbeat = compileInMemory(HEARTBEAT_DECODER, sourcesWithValidation);
        if (heartbeat == null || CODEC_LOGGING)
        {
            System.err.println("sourcesWithValidation = " + sourcesWithValidation);
        }

        Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        component = heartbeat.getClassLoader().loadClass(COMPONENT_DECODER);
        fieldsMessage = heartbeat.getClassLoader().loadClass(FIELDS_MESSAGE_DECODER);
        phoneBookMessage = heartbeat.getClassLoader().loadClass(PHONE_BOOK_MESSAGE_DECODER);
        compileInMemory(HEADER_DECODER, sourcesWithValidation);
        otherMessage = compileInMemory(OTHER_MESSAGE_DECODER, sourcesWithValidation);
        enumTestMessage = compileInMemory(ENUM_TEST_MESSAGE_DECODER, sourcesWithValidation);

        heartbeatWithoutValidation = compileInMemory(HEARTBEAT_DECODER, sourcesWithoutValidation);
        heartbeatWithoutEnumValueValidation = compileInMemory(HEARTBEAT_DECODER, sourcesWithNoEnumValueValidation);
        heartbeatWithRejectingUnknownFields = compileInMemory(HEARTBEAT_DECODER, sourcesRejectingUnknownFields);
        allReqFieldTypesMessage = compileInMemory(ALL_REQ_FIELD_TYPES_MESSAGE_DECODER, sourcesWithoutValidation);
        if (heartbeatWithoutValidation == null || CODEC_LOGGING)
        {
            System.err.println("sourcesWithoutValidation = " + sourcesWithoutValidation);
        }
    }

    private static Map<String, CharSequence> generateSources(
        final boolean validation, final boolean rejectingUnknownFields, final boolean rejectingUnknownEnumValue,
        final boolean flyweightStringsEnabled, final boolean wrapEmptyBuffer)
    {
        final Class<?> validationClass = validation ? ValidationOn.class : ValidationOff.class;
        final Class<?> rejectUnknownField = rejectingUnknownFields ?
            RejectUnknownFieldOn.class : RejectUnknownFieldOff.class;
        final Class<?> rejectUnknownEnumValue = rejectingUnknownEnumValue ?
            RejectUnknownEnumValueOn.class : RejectUnknownEnumValueOff.class;
        final StringWriterOutputManager outputManager = new StringWriterOutputManager();
        final ConstantGenerator constantGenerator = new ConstantGenerator(
            MESSAGE_EXAMPLE, TEST_PACKAGE, null, outputManager);
        final EnumGenerator enumGenerator = new EnumGenerator(MESSAGE_EXAMPLE, TEST_PARENT_PACKAGE, outputManager);
        final DecoderGenerator decoderGenerator = new DecoderGenerator(
            MESSAGE_EXAMPLE, 1, TEST_PACKAGE, TEST_PARENT_PACKAGE, TEST_PACKAGE,
            outputManager, validationClass, rejectUnknownField,
            rejectUnknownEnumValue, flyweightStringsEnabled, wrapEmptyBuffer,
            String.valueOf(rejectingUnknownEnumValue), true, null);
        final EncoderGenerator encoderGenerator = new EncoderGenerator(MESSAGE_EXAMPLE, TEST_PACKAGE,
            TEST_PARENT_PACKAGE, outputManager, ValidationOn.class, RejectUnknownFieldOn.class,
            RejectUnknownEnumValueOn.class, RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY, true);

        constantGenerator.generate();
        enumGenerator.generate();
        decoderGenerator.generate();
        encoderGenerator.generate();
        return outputManager.getSources();
    }

    @Test
    public void generatesDecoderClass()
    {
        assertNotNull("Not generated anything", heartbeat);
        assertIsDecoder(heartbeat);

        final int modifiers = heartbeat.getModifiers();
        assertFalse("Not instantiable", isAbstract(modifiers));
        assertTrue("Not public", isPublic(modifiers));
    }

    @Test
    public void shouldGenerateFieldTagsInJavadoc()
    {
        final String egComponent = sourcesWithValidation.get("null." + EG_COMPONENT + "Decoder").toString();
        assertThat(egComponent, containsString("/* ComponentField = 124 */\n    public"));

        final String header = sourcesWithValidation.get("null.HeaderDecoder").toString();
        assertThat(header, containsString("/* BeginString = 8 */\n    public"));
    }

    @Test
    public void generatesGetters() throws NoSuchMethodException
    {
        assertHasMethod(ON_BEHALF_OF_COMP_ID, char[].class, heartbeat);
    }

    @Test
    public void flagsForOptionalFieldsInitiallyUnset() throws Exception
    {
        final Object decoder = heartbeat.getConstructor().newInstance();
        assertFalse("hasTestReqId initially true", hasTestReqId(decoder));
    }

    @Test(expected = InvocationTargetException.class)
    public void missingOptionalFieldCausesGetterToThrow() throws Exception
    {
        final Object decoder = heartbeat.getConstructor().newInstance();

        Reflection.get(decoder, TEST_REQ_ID);
    }

    @Test
    public void shouldNotRetainStringFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals("one", getMethod(decoder, STRING_RF + "AsString"));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals("", getMethod(decoder, STRING_RF + "AsString"));
    }

    @Test
    public void shouldNotRetainUtcTimestampFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals("20240611-14:35:58.012", getMethod(decoder, UTC_TIMESTAMP_RF + "AsString"));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals("", getMethod(decoder, UTC_TIMESTAMP_RF + "AsString"));
    }

    @Test
    public void shouldNotRetainIntegerFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals(10, getMethod(decoder, INT_RF));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals(Integer.MIN_VALUE, getMethod(decoder, INT_RF));
    }

    @Test
    public void shouldNotRetainCharFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals('b', getMethod(decoder, CHAR_RF));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals('\u0001', getMethod(decoder, CHAR_RF));
    }

    @Test
    public void shouldNotRetainDecimalFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals(new DecimalFloat(123456, 3), getMethod(decoder, DECIMAL_RF));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals(DecimalFloat.MISSING_FLOAT, getMethod(decoder, DECIMAL_RF));
    }

    @Test
    public void shouldNotRetainStringEnumFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals("one", getStringEnumAsString(decoder));
        assertEquals("ONE", getStringEnumAsEnum(decoder));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals("", getStringEnumAsString(decoder));
        assertEquals(UNKNOWN_NAME, getStringEnumAsEnum(decoder));
    }

    private String getStringEnumAsEnum(final Decoder decoder) throws Throwable
    {
        return getMethod(decoder, STRING_ENUM_RF + "AsEnum").toString();
    }

    private Object getStringEnumAsString(final Decoder decoder) throws Throwable
    {
        return getMethod(decoder, STRING_ENUM_RF + "AsString");
    }

    @Test
    public void shouldNotRetainCurrencyEnumFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertValid(decoder);
        assertEquals("GBP", getCurrencyEnumAsString(decoder));
        assertEquals("Pound", getCurrencyEnumAsEnum(decoder));

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals("", getCurrencyEnumAsString(decoder));
        assertEquals(UNKNOWN_NAME, getCurrencyEnumAsEnum(decoder));
    }

    private String getCurrencyEnumAsEnum(final Decoder decoder) throws Throwable
    {
        return getMethod(decoder, CURRENCY_ENUM_RF + "AsEnum").toString();
    }

    private Object getCurrencyEnumAsString(final Decoder decoder) throws Throwable
    {
        return getMethod(decoder, CURRENCY_ENUM_RF + "AsString");
    }

    @Test
    public void shouldNotRetainIntEnumFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals(10, getMethod(decoder, INT_ENUM_RF));
        assertEquals("TEN", getMethod(decoder, INT_ENUM_RF + "AsEnum").toString());

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals(MISSING_INT, getMethod(decoder, INT_ENUM_RF));
        assertEquals(UNKNOWN_NAME, getMethod(decoder, INT_ENUM_RF + "AsEnum").toString());
    }

    @Test
    public void shouldNotRetainCharEnumFromPreviousMessagesForRequiredFieldsWhenReset() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);
        assertEquals('b', getMethod(decoder, CHAR_ENUM_RF));
        assertEquals("BANANA", getMethod(decoder, CHAR_ENUM_RF + "AsEnum").toString());

        decoder.reset();
        decode(RF_NO_FIELDS, decoder);
        assertEquals(MISSING_CHAR, getMethod(decoder, CHAR_ENUM_RF));
        assertEquals(UNKNOWN_NAME, getMethod(decoder, CHAR_ENUM_RF + "AsEnum").toString());
    }

    @Test
    public void shouldDecodeValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldSupportLongFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(LONG_FIELD_MESSAGE);

        assertEquals(Long.MAX_VALUE, getLongField(decoder));
        assertValid(decoder);

        assertToStringAndAppendToMatches(decoder, containsString(STRING_LONG_FIELD_MESSAGE));
    }

    @Test
    public void shouldDecodeEnumValuesUsingAsEnumMethods() throws Exception
    {
        final Decoder decoder = enumTestMessageDecoder();
        decode(ET_ALL_FIELDS, decoder);
        assertEquals('a', getRepresentation(get(decoder, CHAR_ENUM_OPT + "AsEnum")));
        assertEquals(10, getRepresentation(get(decoder, INT_ENUM_OPT + "AsEnum")));
        assertEquals("alpha", getRepresentation(get(decoder, STRING_ENUM_OPT + "AsEnum")));
        assertEquals('c', getRepresentation(get(decoder, CHAR_ENUM_REQ + "AsEnum")));
        assertEquals(30, getRepresentation(get(decoder, INT_ENUM_REQ + "AsEnum")));
        assertEquals("gamma", getRepresentation(get(decoder, STRING_ENUM_REQ + "AsEnum")));
        assertValid(decoder);
    }

    @Test
    public void shouldDecodeMissingOptionalEnumValuesAsSentinelsUsingAsEnumMethods() throws Exception
    {
        final Decoder decoder = enumTestMessageDecoder();
        decode(ET_ONLY_REQ_FIELDS, decoder);
        assertEquals(ENUM_MISSING_CHAR, getRepresentation(get(decoder, CHAR_ENUM_OPT + "AsEnum")));
        assertEquals(ENUM_MISSING_INT, getRepresentation(get(decoder, INT_ENUM_OPT + "AsEnum")));
        assertEquals(ENUM_MISSING_STRING, getRepresentation(get(decoder, STRING_ENUM_OPT + "AsEnum")));
        assertValid(decoder);
    }

    @Test
    public void decodesBadEnumValuesAsSentinelsUsingAsEnumMethods() throws Exception
    {
        final Decoder decoder = enumTestMessageDecoder();
        decode(ET_ONLY_REQ_FIELDS_WITH_BAD_VALUES, decoder);
        assertEquals(ENUM_UNKNOWN_CHAR, getRepresentation(get(decoder, CHAR_ENUM_REQ + "AsEnum")));
        assertEquals(ENUM_UNKNOWN_INT, getRepresentation(get(decoder, INT_ENUM_REQ + "AsEnum")));
        assertEquals(ENUM_UNKNOWN_STRING, getRepresentation(get(decoder, STRING_ENUM_REQ + "AsEnum")));
        assertInvalid(decoder);
    }

    @Test
    public void decodesMissingRequiredEnumFieldUsingAsEnumMethod() throws Exception
    {
        final Decoder decoder = enumTestMessageDecoder();
        decode(ET_MISSING_REQ_FIELD, decoder);
        assertEquals(UNKNOWN_NAME, get(decoder, STRING_ENUM_REQ + "AsEnum").toString());
        assertEquals(ENUM_UNKNOWN_STRING, getRepresentation(get(decoder, STRING_ENUM_REQ + "AsEnum")));
        assertInvalid(decoder);
    }

    @Test
    public void shouldIgnoreMissingOptionalValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void parsesMessagesWithSeparatorInsideDataField() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(SOH_IN_DATA_FIELD_MESSAGE);

        assertTrue(hasDataField(decoder));
        assertArrayEquals(new byte[]{ 'a', '\001', 'c' }, getDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void setsMissingOptionalValues() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        assertTrue(hasTestReqId(decoder));
        assertTrue(hasBooleanField(decoder));
        assertTrue(hasDataField(decoder));

        assertArrayEquals(ABC, getTestReqId(decoder));
        assertEquals(true, getBooleanField(decoder));
        assertArrayEquals(new byte[]{ '1', '2', '3' }, getDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void hasMessageTypeFlag() throws Exception
    {
        final long messageType = (long)getStatic(heartbeat, "MESSAGE_TYPE");

        assertEquals(HEARTBEAT_TYPE, messageType);
    }

    @Test
    public void decodesCommonComponents() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        final SessionHeaderDecoder header = getHeader(decoder);
        assertEquals(81, getBodyLength(header));

        final Object trailer = getTrailer(decoder);
        assertEquals("199", getChecksum(trailer));
    }

    @Test
    public void shouldResetFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        decoder.reset();

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));
        assertFalse(hasComponentField(decoder));
        assertFalse(hasNoEgGroupGroupCounter(decoder));

        assertEquals(MISSING_FLOAT, getFloatField(decoder));
        assertEquals(MISSING_INT, getIntField(decoder));
    }

    @Test
    public void shouldToString() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        assertToStringAndAppendToMatches(decoder, containsString(STRING_NO_OPTIONAL_MESSAGE_EXAMPLE));

        assertValid(decoder);
    }

    @Test
    public void shouldToStringIncludeOptionalFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ENCODED_MESSAGE);

        assertToStringAndAppendToMatches(decoder, containsString(STRING_ENCODED_MESSAGE_EXAMPLE));
    }

    @Test
    public void shouldToStringShorterStringsAfterLongerStrings() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        decode(SHORTER_STRING_MESSAGE, decoder);

        assertToStringAndAppendToMatches(decoder, containsString("\"OnBehalfOfCompID\": \"ab\","));
    }

    @Test
    public void shouldToStringComponent() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(COMPONENT_MESSAGE);

        assertToStringAndAppendToMatches(decoder, containsString("  \"ComponentField\": \"2\""));
    }

    @Test
    public void shouldToStringRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertToStringAndAppendToMatches(decoder, containsString(STRING_GROUP_TWO_ELEMENTS));
    }

    @Test
    public void shouldToStringRepeatingGroupsWithoutMutatingIterator() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        final Iterator<?> iterator = getEgGroupIterator(decoder);

        assertHasNext(iterator);
        Object group = iterator.next();
        assertEquals(1, getGroupField(group));

        assertThat(decoder.toString(), containsString(STRING_GROUP_TWO_ELEMENTS));

        assertHasNext(iterator);
        group = iterator.next();
        assertEquals(2, getGroupField(group));
        assertNotHasNext(iterator);
    }

    private void assertHasNext(final Iterator<?> iterator)
    {
        assertTrue("fails hasNext()", iterator.hasNext());
    }

    private void assertToStringAndAppendToMatches(final Decoder decoder, final Matcher<String> matcher)
    {
        assertThat(decoder.toString(), matcher);

        assertAppendToMatches(decoder::appendTo, matcher);
    }

    static void assertAppendToMatches(
        final Function<StringBuilder, StringBuilder> appendTo, final Matcher<String> matcher)
    {
        final StringBuilder builder = new StringBuilder();
        final StringBuilder newBuilder = appendTo.apply(builder);
        final String builderString = builder.toString();
        assertThat(builderString, matcher);
        assertSame(builder, newBuilder);
    }

    @Test
    public void shouldDecodeShorterStringsAfterLongerStrings() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));

        decode(SHORTER_STRING_MESSAGE, decoder);

        assertArrayEquals(AB, getOnBehalfOfCompId(decoder));
    }

    @Test
    public void shouldDecodeRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsAfterReset() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(REPEATING_GROUP_MESSAGE, decoder);

        assertValidRepeatingGroupDecoded(decoder);
    }

    // Reproduction for a reported bug
    @Test
    public void shouldNotThrowInAResetOfARepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        buffer.wrap("nonsense".getBytes(StandardCharsets.US_ASCII));

        decoder.reset();

        buffer.wrap(new byte[CAPACITY]);
        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldNotThrowInAResetOfARepeatingGroupWithLengthZero() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ZERO_REPEATING_GROUP_MESSAGE);

        assertNotHasNext(decoder);

        buffer.wrap("nonsense".getBytes(StandardCharsets.US_ASCII));

        decoder.reset();

        buffer.wrap(new byte[CAPACITY]);
        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeShorterRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeShorterRepeatingGroupsAfterReset() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(SINGLE_REPEATING_GROUP_MESSAGE, decoder);

        assertSingleRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeNestedRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NESTED_GROUP_MESSAGE);

        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(1, getGroupField(group));
        assertNull(next(group));

        final Object nestedGroup = getNestedGroup(group);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder$NestedGroupGroupDecoder",
            nestedGroup.getClass().getName());
        assertEquals(1, get(nestedGroup, "nestedField"));
        assertNull(next(nestedGroup));

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeComponents() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(COMPONENT_MESSAGE);

        assertEquals(2, get(decoder, "componentField"));

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeNestedComponentsWithRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NESTED_COMPONENT_MESSAGE);

        assertEquals(2, get(decoder, "componentField"));
        assertEquals(180, get(decoder, "nestedComponentField"));
        assertEquals(2, get(decoder, "noNestedComponentGroupGroupCounter"));
        assertNotNull(get(decoder, "nestedComponentGroupGroupIterator"));
        final Class<?> nestedComponent = heartbeat.getClassLoader().loadClass(NESTED_COMPONENT_DECODER);

        assertHasMethod("nestedComponentField", int.class, nestedComponent);
        assertHasMethod("noNestedComponentGroupGroupCounter", int.class, nestedComponent);

        assertValid(decoder);
    }

    @Test
    public void shouldGenerateComponentInterface() throws Exception
    {
        assertTrue(
            "heartbeat doesn't implement its component",
            component.isAssignableFrom(heartbeat));

        assertHasComponentFieldGetter();
    }

    @Test
    public void shouldGenerateRequiredFieldsDictionary() throws Exception
    {
        final Decoder decoder = newHeartbeat();
        final Object allFieldsField = getRequiredFields(decoder);
        assertThat(allFieldsField, instanceOf(IntHashSet.class));

        @SuppressWarnings("unchecked") final Set<Integer> allFields = (Set<Integer>)allFieldsField;
        assertThat(allFields, hasItem(INT_FIELD_TAG));
        assertThat(allFields, not(hasItem(112)));
        assertThat(allFields, not(hasItem(999)));
    }

    @Test
    public void shouldValidateMissingRequiredFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MISSING_REQUIRED_FIELDS_MESSAGE);

        assertInvalid(decoder, REQUIRED_TAG_MISSING, INT_FIELD_TAG);
    }

    @Test
    public void shouldValidateMissingRequiredPriceFields() throws Exception
    {
        final Decoder decoder = newHeartbeat();
        assertFloatFieldIsNan(decoder);

        decode(MISSING_REQUIRED_PRICE_FIELDS_MESSAGE, decoder);

        assertInvalid(decoder, REQUIRED_TAG_MISSING, FLOAT_FIELD_TAG);
    }

    // --------------------------------------------------------------
    // Without Validation
    // --------------------------------------------------------------

    @Test
    public void shouldUseNaNToDenoteMissingRequiredPriceFieldsWithValidationDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(MISSING_REQUIRED_PRICE_FIELDS_MESSAGE);
        assertFloatFieldIsNan(decoder);
    }

    private void assertFloatFieldIsNan(final Decoder decoder) throws Exception
    {
        final DecimalFloat floatField = (DecimalFloat)getFloatField(decoder);
        assertTrue(floatField.toString(), floatField.isNaNValue());
    }

    @Test
    public void shouldNotValidateDataFormatForIntsWithValidationDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(INVALID_INT_VALUE_MESSAGE);

        assertEquals(MISSING_INT, getIntField(decoder));
    }

    @Test
    public void shouldNotValidateDataFormatForFloatsWithValidationDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(INVALID_FLOAT_VALUE_MESSAGE);

        assertEquals(DecimalFloat.MISSING_FLOAT, getFloatField(decoder));
    }

    @Test
    public void shouldValidateMissingRequiredFieldsInGroupsInsideComponents() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MISSING_REQUIRED_FIELD_IN_GROUP_INSIDE_COMPONENT_MESSAGE);

        assertFalse("Passed validation with missing fields", decoder.validate());
        assertEquals("Wrong tag id", 404, decoder.invalidTagId());
        assertEquals("Wrong reject reason", REQUIRED_TAG_MISSING, decoder.rejectReason());
    }

    @Test
    public void shouldValidateMissingRequiredFieldsInRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE);

        assertFalse("Passed validation with missing fields", decoder.validate());
        assertEquals("Wrong tag id", 138, decoder.invalidTagId());
        assertEquals("Wrong reject reason", REQUIRED_TAG_MISSING, decoder.rejectReason());
    }

    @Test
    public void shouldValidateIfNoRequiredFieldsMissingInRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE);

        assertTrue("Failed validation when it should have passed", decoder.validate());
    }

    @Test
    public void shouldLeaveDecoderInUsableIfValidationFailsDuringRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(FIELD_DEFINED_TWICE_IN_MESSAGE);

        assertFalse("Passed validation when it should have passed", decoder.validate());
        assertEquals("Wrong reject reason", TAG_APPEARS_MORE_THAN_ONCE, decoder.rejectReason());

        decoder.reset();
        decode(NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE, decoder);
        assertTrue("Failed validation when it should have passed", decoder.validate());
    }

    @Test
    public void shouldIgnoreFieldDefinedAtGroupLevelButAppearsAtTheMessageLevel() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(FIELD_DEFINED_IN_REPEATING_GROUPS_APPEARS_OUTSIDE_OF_IT);

        assertTrue("Failed validation when it should have passed", decoder.validate());
    }

    @Test
    public void shouldSupportGroupNumbersGreaterThanTheNumberOfElementsInTheGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(
            REPEATING_GROUP_MESSAGE_WITH_TOO_HIGH_NUMBER_FIELD);

        assertInvalid(decoder, INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP, 120);
    }

    @Test
    public void shouldReasonablyValidateGroupNumbersLessThanTheNumberOfElementsInTheGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(
            REPEATING_GROUP_MESSAGE_WITH_TOO_LOW_NUMBER_FIELD);

        assertInvalid(decoder, INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP, 120);
    }

    //this test was added for issue #525
    @Test
    public void shouldReasonablyValidateGroupNumbersLessThanTheNumberOfElementsInTheGroupList() throws Exception
    {
        final Decoder decoder = (Decoder)heartbeatWithRejectingUnknownFields.getConstructor().newInstance();

        decodeHeartbeatWithRejectingUnknownFields(
            decoder, this::assertValid, REPEATING_GROUP_MESSAGE_WITH_THREE);
        decodeHeartbeatWithRejectingUnknownFields(
            decoder, decoder1 ->
            assertInvalid(decoder1,
            INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP,
            120), REPEATING_GROUP_MESSAGE_WITH_TOO_HIGH_NUMBER_FIELD);
        decodeHeartbeatWithRejectingUnknownFields(
            decoder, this::assertValid, REPEATING_GROUP_MESSAGE_WITH_THREE);
    }

    @Test
    public void shouldSupportGroupNumbersGreaterThanTheNumberOfElementsInTheNestedGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(
            NESTED_REPEATING_GROUP_MESSAGE_WITH_TOO_HIGH_NUMBER_FIELD);

        assertInvalid(decoder, INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP, 122);
    }

    @Test
    public void shouldReasonablyValidateGroupNumbersLessThanTheNumberOfElementsInTheNestedGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(
            NESTED_REPEATING_GROUP_MESSAGE_WITH_TOO_LOW_NUMBER_FIELD);

        assertInvalid(decoder, INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP, 122);
    }

    @Test
    public void shouldLeaveDecoderInUsableIfUnknownFieldForRepeatingGroupReachedAndRejectingOn() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(REPEATING_GROUP_WITH_UNKNOWN_FIELD);

        assertInvalid(decoder, INVALID_TAG_NUMBER, 1000);

        decoder.reset();
        decode(NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE, decoder);
        assertTrue("Failed validation when it should have passed", decoder.validate());
    }

    @Test
    public void shouldSkipUnknownFieldInRepeatingGroupAndPassValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_WITH_UNKNOWN_FIELD);

        assertTrue("Failed validation with missing fields", decoder.validate());

        Object group = get(decoder, "secondEgGroupGroup");
        assertEquals("TOM", get(group, "secondGroupFieldAsString"));
        assertEquals(180, get(group, "thirdGroupField"));

        group = next(group);
        assertEquals("Barbara", get(group, "secondGroupFieldAsString"));
        assertEquals(123, get(group, "thirdGroupField"));
    }

    @Test
    public void shouldSkipUnknownRepeatingGroup() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.getConstructor().newInstance();
        decode(CONTAINS_UNKNOWN_REPEATING_GROUP, decoder);

        assertValid(decoder);

        assertRequiredFieldsMessageFieldsDecoded(decoder, "USD", "N", "US");

        assertOptionalDifferentFieldsNotDecoded(decoder);
    }

    @Test
    public void shouldSkipUnknownNestedRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(CONTAINS_UNKNOWN_NESTED_REPEATING_GROUP);

        assertValid(decoder);

        canIterateOverGroup(decoder);
    }

    @Test
    public void shouldFailValidationForUnknownFieldInsideRepeatingGroupWhenUnknownFieldPropIsSet() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(REPEATING_GROUP_WITH_UNKNOWN_FIELD);

        assertFalse("Passed validation with missing fields", decoder.validate());
        assertEquals("Wrong tag id", 1000, decoder.invalidTagId());
        assertEquals("Wrong reject reason", INVALID_TAG_NUMBER, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagNumbersWhenUnknownFieldPropIsSet() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(INVALID_TAG_NUMBER_MESSAGE);

        assertFalse("Passed validation with invalid tag number", decoder.validate());
        assertEquals("Wrong tag id", 9999, decoder.invalidTagId());
        assertEquals("Wrong reject reason", INVALID_TAG_NUMBER, decoder.rejectReason());
    }

    @Test
    public void shouldFailValidationRegardingUnknownFieldRatherThanMissingRequiredFieldWhenUnknownFieldPropIsSet()
        throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithRejectingUnknownFields(UNKNOWN_FIELD_MESSAGE);

        assertFalse("Passed validation with invalid tag number ", decoder.validate());
        assertEquals("Wrong tag id", 1000, decoder.invalidTagId());
        assertEquals("Wrong reject reason", INVALID_TAG_NUMBER, decoder.rejectReason());
    }

    @Test
    public void shouldValidateTagNumbersDefinedForThisMessageWhenUnknownFieldPropIsSet() throws Exception
    {
        final Decoder decoder =
            decodeHeartbeatWithRejectingUnknownFields(TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE_MESSAGE);

        assertFalse("Passed validation with invalid tag number", decoder.validate());
        assertEquals("Wrong tag id", 99, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE, decoder.rejectReason());
    }

    @Test
    public void shouldSkipFieldUnknownToMessageButDefinedInFIXSpec() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_WITH_FIELD_UNKNOWN_TO_MESSAGE_BUT_IN_SPEC);
        assertValid(decoder);

        Object group = get(decoder, "secondEgGroupGroup");
        assertEquals("TOM", get(group, "secondGroupFieldAsString"));
        assertEquals(180, get(group, "thirdGroupField"));

        group = next(group);
        assertEquals("Barbara", get(group, "secondGroupFieldAsString"));
        assertEquals(123, get(group, "thirdGroupField"));
    }

    @Test
    public void shouldValidateIfNoRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_REPEATING_GROUP_IN_REPEATING_GROUP_MESSAGE);

        assertTrue("Failed validation when it should have passed", decoder.validate());
    }

    @Test
    public void shouldSkipUnknownFieldForMessageAndPassValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INVALID_TAG_NUMBER_MESSAGE);

        assertTrue("Failed validation with invalid tag number", decoder.validate());

        assertEquals(2, getIntField(decoder));
    }

    @Test
    public void shouldValidateTagSpecifiedWithMissingValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WITHOUT_A_VALUE_MESSAGE);

        assertFalse("Passed validation with missing value", decoder.validate());
        assertEquals("Wrong tag id", INT_FIELD_TAG, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_SPECIFIED_WITHOUT_A_VALUE, decoder.rejectReason());
    }

    @Test
    public void shouldValidateIntBasedEnum() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WHERE_INT_VALUE_IS_INCORRECT_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", INT_FIELD_TAG, decoder.invalidTagId());
        assertEquals("Wrong reject reason", VALUE_IS_INCORRECT, decoder.rejectReason());
    }

    @Test
    public void shouldSupportLargerIntBasedEnum() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WHERE_INT_VALUE_IS_LARGE);

        assertTrue(decoder.validate());
        assertEquals(99, getIntField(decoder));
    }

    @Test
    public void shouldValidateStringBasedEnum() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_WHERE_STRING_VALUE_IS_INCORRECT_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 115, decoder.invalidTagId());
        assertEquals("Wrong reject reason", VALUE_IS_INCORRECT, decoder.rejectReason());
    }

    @Test
    public void shouldValidateEnumMissingValueIfEnumValidationDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutEnumValue(TAG_SPECIFIED_WITHOUT_A_VALUE_MESSAGE);

        assertFalse("Passed validation with missing value", decoder.validate());
        assertEquals("Wrong tag id", INT_FIELD_TAG, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_SPECIFIED_WITHOUT_A_VALUE, decoder.rejectReason());
    }

    @Test
    public void shouldNotValidateIntBasedEnumIfDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutEnumValue(TAG_SPECIFIED_WHERE_INT_VALUE_IS_INCORRECT_MESSAGE);

        assertTrue("Should be no validation for incorrect enum value", decoder.validate());
        final Object intFieldEnum = get(decoder, "intFieldAsEnum");
        assertEquals(ENUM_UNKNOWN_INT, getRepresentation(intFieldEnum));
    }

    @Test
    public void shouldValidateStringBasedEnumIfDisabled() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutEnumValue(TAG_SPECIFIED_WHERE_STRING_VALUE_IS_INCORRECT_MESSAGE);

        assertTrue("Should be no validation for incorrect enum value", decoder.validate());
        final Object onBehalfEnum = get(decoder, "onBehalfOfCompIDAsEnum");
        assertEquals(ENUM_UNKNOWN_STRING, getRepresentation(onBehalfEnum));
    }

    @Test
    public void shouldValidateTagsAppearingMoreThanOnce() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_APPEARS_MORE_THAN_ONCE_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", INT_FIELD_TAG, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_APPEARS_MORE_THAN_ONCE, decoder.rejectReason());
    }

    @Test
    public void shouldResetTheInvalidAccessors() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_APPEARS_MORE_THAN_ONCE_MESSAGE);
        decoder.validate();

        decoder.reset();

        assertEquals("Failed to reset tag id", NO_ERROR, decoder.invalidTagId());
        assertEquals("Failed to reset reject reason", NO_ERROR, decoder.rejectReason());
    }

    @Test
    public void shouldValidateFirstThreeFieldsAreInOrder() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE);

        assertFalse("Passed validation with incorrect value", decoder.validate());
        assertEquals("Wrong tag id", 9, decoder.invalidTagId());
        assertEquals("Wrong reject reason", TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER, decoder.rejectReason());
    }

    @Test
    public void shouldBeAbleToExtractStringsFromStringFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        assertEquals("abc", get(decoder, "onBehalfOfCompIDAsString"));
        assertNull(get(decoder, "testReqIDAsString"));
    }

    @Test
    public void shouldBeAbleToExtractStringsAsAsciiSequenceViewFromStringFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        final AsciiSequenceView actual = getAsciiSequenceView(decoder, "onBehalfOfCompID");
        assertEquals("abc", actual.toString());
        assertTargetThrows(() -> getAsciiSequenceView(decoder, "testReqID"), IllegalArgumentException.class,
            "No value for optional field: TestReqID");
    }

    @Test
    public void shouldBeAbleToExtractEnumFromStringFields() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_OPTIONAL_MESSAGE);

        final Object onBehalfEnum = get(decoder, "onBehalfOfCompIDAsEnum");
        assertEquals("abc", getRepresentation(onBehalfEnum));
    }


    @Test
    public void shouldProduceCorrectMessageTypeForTwoCharTypes() throws Exception
    {
        final byte[] messageTypeBytes = (byte[])getStatic(otherMessage, "MESSAGE_TYPE_BYTES");
        final long messageTypePacked = (long)getStatic(otherMessage, "MESSAGE_TYPE");

        assertEquals(OTHER_MESSAGE_TYPE_PACKED, messageTypePacked);
        assertArrayEquals(OTHER_MESSAGE_TYPE_BYTES, messageTypeBytes);
    }

    @Test
    public void shouldResetAllRepeatingGroupEntries() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTIPLE_ENTRY_REPEATING_GROUP);
        Object group = get(decoder, "secondEgGroupGroup");
        assertEquals("TOM", get(group, "secondGroupFieldAsString"));

        group = next(group);
        assertEquals("ANDREY", get(group, "secondGroupFieldAsString"));

        decoder.reset();
        decode(MULTIPLE_ENTRY_REPEATING_GROUP_WITHOUT_OPTIONAL, decoder);

        group = get(decoder, "secondEgGroupGroup");
        assertNull(get(group, "secondGroupFieldAsString"));

        group = next(group);
        assertNull(get(group, "secondGroupFieldAsString"));
    }

    @Test
    public void shouldResetAllNestedRepeatingGroupEntries() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTI_ENTRY_NESTED_GROUP_MESSAGE);
        assertEquals(2, getNoEgGroupGroupCounter(decoder));

        Object group = getEgGroup(decoder);
        assertNestedRepeating(group, 1, 1, 2);

        group = next(group);
        assertNestedRepeating(group, 2, 3, 4);

        decoder.reset();

        decode(MULTI_ENTRY_EG_GROUP_MESSAGE_WITHOUT_NESTED_GROUPS, decoder);
        assertEquals(2, getNoEgGroupGroupCounter(decoder));

        group = getEgGroup(decoder);

        // Although the message does not have nestedEg tags, the decoders will remain
        // This is to ensure allocation is done only when it's necessary to add new elements to a repeating group
        // for more details see issue https://github.com/artiofix/artio/issues/532
        assertNotNull(getNestedGroup(group));

        group = next(group);
        assertNotNull(getNestedGroup(group));
    }

    @Test
    public void shouldHaveAllNestedRepeatingGroupEntriesNullifiedWhenMessageDoesNotHaveIt() throws Exception
    {
        Object group;

        final Decoder decoder = decodeHeartbeat(MULTI_ENTRY_EG_GROUP_MESSAGE_WITHOUT_NESTED_GROUPS);
        assertEquals(2, getNoEgGroupGroupCounter(decoder));

        group = getEgGroup(decoder);
        assertNull(getNestedGroup(group));

        group = next(group);
        assertNull(getNestedGroup(group));
    }

    @Test
    public void shouldDecodeShortTimestampMessageCorrectly() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(SHORT_TIMESTAMP_MESSAGE);

        final byte[] someTime = getSomeTimeField(decoder);
        final UtcTimestampDecoder someTimeDecoder = new UtcTimestampDecoder(true);
        final long someTimeValue = someTimeDecoder.decode(someTime, someTime.length);
        assertEquals(0, someTimeValue);

        final String someTimeFieldAsString = getSomeTimeFieldAsString(decoder);
        assertEquals("19700101-00:00:00.000", someTimeFieldAsString);
    }

    @Test
    public void shouldGenerateIteratorForRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        canIterateOverGroup(decoder);

        canIterateOverGroup(decoder);
    }

    @Test
    public void shouldGenerateIterableForRepeatingGroups() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(REPEATING_GROUP_MESSAGE);

        canIterateOverGroupUsingForEach(decoder);

        canIterateOverGroupUsingForEach(decoder);
    }

    @Test
    public void shouldBeAbleToUseIteratorForZeroRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(ZERO_REPEATING_GROUP_MESSAGE);

        assertNotHasNext(decoder);
    }

    @Test
    public void shouldBeAbleToUseIteratorForNoRepeatingGroup() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(NO_REPEATING_GROUP_MESSAGE);

        assertNotHasNext(decoder);
    }

    @Test
    public void shouldDecodeDifferentFieldTypes() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.getConstructor().newInstance();
        decode(EG_FIELDS_MESSAGE, decoder);

        assertRequiredFieldsMessageFieldsDecoded(decoder, "GBP", "XLON", "GB");

        final char[] gbp = "GBP".toCharArray();
        final char[] xlon = "XLON".toCharArray();
        final char[] gb = "GB".toCharArray();

        assertArrayEquals(gbp, getOptionalCurrencyField(decoder));
        assertArrayEquals(xlon, getOptionalExchangeField(decoder));
        assertArrayEquals(gb, getOptionalCountryField(decoder));

        assertEquals("GBP", getOptionalCurrencyFieldAsString(decoder));
        assertEquals("XLON", getOptionalExchangeFieldAsString(decoder));
        assertEquals("GB", getOptionalCountryFieldAsString(decoder));

        assertEquals("GBP", getOptionalCurrencyFieldAsView(decoder).toString());
        assertEquals("XLON", getOptionalExchangeFieldAsView(decoder).toString());
        assertEquals("GB", getOptionalCountryFieldAsView(decoder).toString());

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeDifferentFieldTypesWithoutOptionalFields() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.getConstructor().newInstance();
        decode(EG_NO_OPTIONAL_FIELDS_MESSAGE, decoder);

        assertRequiredFieldsMessageFieldsDecoded(decoder, "USD", "N", "US");

        assertOptionalDifferentFieldsNotDecoded(decoder);

        assertValid(decoder);
    }

    @Test
    public void shouldResetDifferentFieldTypes() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.getConstructor().newInstance();
        decode(EG_FIELDS_MESSAGE, decoder);

        decoder.reset();

        assertRequiredFieldsMessageFieldsAsStringDecoded(decoder, "", "", "");
        assertOptionalDifferentFieldsNotDecoded(decoder);

        decode(EG_NO_OPTIONAL_FIELDS_MESSAGE, decoder);
        assertRequiredFieldsMessageFieldsDecoded(decoder, "USD", "N", "US");
        assertOptionalDifferentFieldsNotDecoded(decoder);

        assertValid(decoder);
    }

    @Test
    public void messageWithNoRepeatingGroupsIsValidWhereRequiredRepeatingGroupIsInOptionalComponent() throws Exception
    {
        final Decoder decoder = (Decoder)phoneBookMessage.getConstructor().newInstance();
        decode(EMPTY_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP, decoder);

        assertValid(decoder);
    }

    @Test
    public void messageWithRepeatingGroupsIsValidWhereRequiredRepeatingGroupIsInOptionalComponent() throws Exception
    {
        final Decoder decoder = (Decoder)phoneBookMessage.getConstructor().newInstance();
        decode(NON_EMPTY_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP, decoder);

        assertValid(decoder);
    }

    // -----------------------------------------------------
    //             Validation Off Test Cases
    // -----------------------------------------------------

    @Test
    public void shouldIgnoreMissingRequiredFieldsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(MISSING_REQUIRED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        // Missing int field
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));
    }

    @Test
    public void shouldIgnoreMissingRequiredFieldsWithRepeatingGroupWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(
            REPEATING_GROUP_MESSAGE_WITH_MISSING_REQUIRED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        // Missing int field
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldIgnoreInvalidTagNumberWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(INVALID_TAG_NUMBER_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));
    }

    @Test
    public void shouldIgnoreInvalidTagNumberInGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER);

        assertRepeatingGroupAndFieldsDecoded(decoder);
    }

    @Test
    public void shouldIgnoreInvalidTagNumberInGroupsFieldAfterWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(
            REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER_FIELDS_AFTER);

        assertRepeatingGroupAndFieldsDecoded(decoder);
    }

    @Test
    public void decodesValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(DERIVED_FIELDS_MESSAGE);

        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValid(decoder);
    }

    @Test
    public void decodesMultiCharValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTI_CHAR_VALUE_MESSAGE);
        assertArrayEquals(MULTI_CHAR_VALUE, getMultiCharField(decoder));

        assertValid(decoder);
    }

    @Test
    public void decodesInvalidSizeBooleanValueWithNoValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(INCORRECT_BOOLEAN_VALUE_MESSAGE);
        assertEquals(true, getBooleanField(decoder));
        assertEquals(2, getInt(decoder, "booleanFieldLength"));

        assertValid(decoder);
    }

    @Test
    public void shouldNotRetainInvalidBooleanCharValueSizeAfterNewMessageParsed() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INCORRECT_BOOLEAN_VALUE_MESSAGE);
        assertEquals(true, getBooleanField(decoder));
        assertEquals(2, getInt(decoder, "booleanFieldLength"));

        assertInvalid(decoder, 5, 118);

        decoder.reset();

        decode(ENCODED_MESSAGE, decoder);
        assertEquals(true, getBooleanField(decoder));
        assertEquals(1, getInt(decoder, "booleanFieldLength"));
        assertValid(decoder);

    }

    @Test
    public void decodesInvalidSizeBooleanValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INCORRECT_BOOLEAN_VALUE_MESSAGE);
        assertEquals(true, getBooleanField(decoder));
        assertEquals(2, getInt(decoder, "booleanFieldLength"));

        assertInvalid(decoder, 5, 118);
    }

    @Test
    public void decodesInvalidSizeNoEnumCharValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INCORRECT_SIZE_NO_ENUM_CHAR_VALUE_MESSAGE);
        assertEquals('a', getChar(decoder, "charNoEnumField"));
        assertEquals(2, getInt(decoder, "charNoEnumFieldLength"));

        assertInvalid(decoder, 5, 144);
    }

    @Test
    public void decodesInvalidSizeCharValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INCORRECT_SIZE_CHAR_VALUE_MESSAGE);
        assertEquals('a', getCharField(decoder));
        assertEquals(2, getInt(decoder, "charFieldLength"));

        assertInvalid(decoder, 5, 128);
    }

    @Test
    public void decodesInvalidCharValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INCORRECT_SIZE_2_CHAR_VALUE_MESSAGE);
        assertEquals('z', getCharField(decoder));
        assertEquals(1, getInt(decoder, "charFieldLength"));

        assertInvalid(decoder, 5, 128);
    }

    @Test
    public void doesNotValidateIfNoEnumValuesPresent() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTI_CHAR_VALUE_NO_ENUM_MESSAGE);
        assertArrayEquals(MULTI_CHAR_VALUE_NO_ENUM, getMultiCharNoEnumField(decoder));

        assertValid(decoder);
    }

    @Test
    public void multiCharValueThatFailsValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INVALID_MULTI_CHAR_VALUE_MESSAGE);

        assertInvalid(decoder);
    }

    @Test
    public void multiStringValueThatFailsValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(INVALID_MULTI_STRING_VALUE_MESSAGE);

        assertInvalid(decoder);
    }


    @Test
    public void multiStringValueThatPassesValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(VALID_MULTI_STRING_VALUE_MESSAGE);

        assertValid(decoder);
    }

    @Test
    public void decodesMultiValueString() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTI_VALUE_STRING_MESSAGE);

        assertArrayEquals(MULTI_VALUE_STRING, getMultiValStringField(decoder));

        assertValid(decoder);
    }

    //This is the same as MultipleValueString (it was renamed in FIX 5)
    @Test
    public void decodesMultiStringValue() throws Exception
    {
        final Decoder decoder = decodeHeartbeat(MULTI_STRING_VALUE_MESSAGE);

        assertArrayEquals(MULTI_VALUE_STRING, getMultiStringValField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldIgnoreMissingOptionalValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(DERIVED_FIELDS_MESSAGE);

        assertFalse(hasTestReqId(decoder));
        assertFalse(hasBooleanField(decoder));
        assertFalse(hasDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void setsMissingOptionalValuesWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(ENCODED_MESSAGE);

        assertTrue(hasTestReqId(decoder));
        assertTrue(hasBooleanField(decoder));
        assertTrue(hasDataField(decoder));

        assertArrayEquals(ABC, getTestReqId(decoder));
        assertEquals(true, getBooleanField(decoder));
        assertArrayEquals(new byte[]{ '1', '2', '3' }, getDataField(decoder));

        assertValid(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeRepeatingGroupsAfterResetWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(REPEATING_GROUP_MESSAGE);

        assertValidRepeatingGroupDecoded(decoder);

        decoder.reset();

        decode(REPEATING_GROUP_MESSAGE, decoder);

        assertValidRepeatingGroupDecoded(decoder);
    }

    @Test
    public void shouldDecodeNestedRepeatingGroupsWithoutValidation() throws Exception
    {
        final Decoder decoder = decodeHeartbeatWithoutValidation(NESTED_GROUP_MESSAGE);

        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(1, getGroupField(group));
        assertNull(next(group));

        final Object nestedGroup = getNestedGroup(group);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder$NestedGroupGroupDecoder",
            nestedGroup.getClass().getName());
        assertEquals(1, get(nestedGroup, "nestedField"));
        assertNull(next(nestedGroup));

        assertValid(decoder);
    }

    @Test
    public void shouldSupportHighNumberedFields() throws Exception
    {
        final Decoder decoder = (Decoder)fieldsMessage.getConstructor().newInstance();
        decode(EG_HIGH_NUMBER_FIELD_MESSAGE, decoder);

        assertValid(decoder);

        final int highNumberField = getInt(decoder, "highNumberField");
        assertEquals(highNumberField, 1);
    }

    @Test
    public void shouldHandleMalformedMessage() throws Exception
    {
        final Decoder decoder1 = decodeHeartbeat("8=FIX.4.4\u00019=105\u000135=0\001115=abc");
        final Decoder decoder2 = decodeHeartbeat("8=FIX.4.4\u00019=105\u000135=0\001115");
        final Decoder decoder3 = decodeHeartbeat("8=FIX.4.4\u00019=105\u000135=0\001115=");
        final Decoder decoder4 = decodeHeartbeat("8=FIX.4.4\u00019=105\u000135=0\001115=abc\u0001 ");

        assertThat(decoder1.validate(), is(false));
        assertThat(decoder2.validate(), is(false));
        assertThat(decoder3.validate(), is(false));
        assertThat(decoder4.validate(), is(false));

        assertRejectReason(decoder1, RejectReason.VALUE_IS_INCORRECT);
        assertRejectReason(decoder2, RejectReason.REQUIRED_TAG_MISSING);
        assertRejectReason(decoder3, RejectReason.VALUE_IS_INCORRECT);
        assertRejectReason(decoder4, RejectReason.REQUIRED_TAG_MISSING);
    }

    @Test
    public void shouldExtractPackedMessageType() throws Throwable
    {
        final Decoder decoder = createRequiredFieldMessageDecoder();
        decode(RF_ALL_FIELDS, decoder);

        final long messageType = decoder.header().messageType();
        assertEquals(packMessageType("Z"), messageType);
    }

    private void assertRejectReason(final Decoder decoder, final RejectReason expectedReason)
    {
        assertThat(RejectReason.decode(decoder.rejectReason()), is(expectedReason));
    }

    private void assertRepeatingGroupAndFieldsDecoded(final Decoder decoder) throws Exception
    {
        assertArrayEquals(ABC, getOnBehalfOfCompId(decoder));
        assertEquals(2, getIntField(decoder));
        assertEquals(new DecimalFloat(11, 1), getFloatField(decoder));

        assertValidRepeatingGroupDecoded(decoder);
    }

    private void assertOptionalDifferentFieldsNotDecoded(final Decoder decoder) throws Exception
    {
        assertNull(getOptionalCurrencyFieldAsString(decoder));
        assertNull(getOptionalExchangeFieldAsString(decoder));
        assertNull(getOptionalCountryFieldAsString(decoder));
    }

    private void assertRequiredFieldsMessageFieldsDecoded(
        final Decoder decoder, final String currency, final String exchange, final String country) throws Exception
    {
        final char[] currencyChars = currency.toCharArray();
        final char[] exchangeChars = exchange.toCharArray();
        final char[] countryChars = country.toCharArray();

        final int currencyFieldLength = getCurrencyFieldLength(decoder);
        final int exchangeFieldLength = getExchangeFieldLength(decoder);
        final int countryFieldLength = getCountryFieldLength(decoder);

        assertEquals(currencyChars.length, currencyFieldLength);
        assertEquals(exchangeChars.length, exchangeFieldLength);
        assertEquals(countryChars.length, countryFieldLength);

        assertArrayEquals(currencyChars, Arrays.copyOf(getCurrencyField(decoder), currencyFieldLength));
        assertArrayEquals(exchangeChars, Arrays.copyOf(getExchangeField(decoder), exchangeFieldLength));
        assertArrayEquals(countryChars, Arrays.copyOf(getCountryField(decoder), countryFieldLength));

        assertRequiredFieldsMessageFieldsAsStringDecoded(decoder, currency, exchange, country);
        assertRequiredFieldsMessageFieldsAsViewDecoded(decoder, currency, exchange, country);
    }

    private void assertRequiredFieldsMessageFieldsAsStringDecoded(
        final Decoder decoder, final String currency, final String exchange, final String country) throws Exception
    {
        assertEquals(currency, getCurrencyFieldAsString(decoder));
        assertEquals(exchange, getExchangeFieldAsString(decoder));
        assertEquals(country, getCountryFieldAsString(decoder));
    }

    private void assertRequiredFieldsMessageFieldsAsViewDecoded(
        final Decoder decoder, final String currency, final String exchange, final String country) throws Exception
    {
        assertEquals(currency, getCurrencyFieldAsView(decoder).toString());
        assertEquals(exchange, getExchangeFieldAsView(decoder).toString());
        assertEquals(country, getCountryFieldAsView(decoder).toString());
    }

    private String getOptionalCountryFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalCountryFieldAsString");
    }

    private String getOptionalExchangeFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalExchangeFieldAsString");
    }

    private String getOptionalCurrencyFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "optionalCurrencyFieldAsString");
    }

    private String getCountryFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "countryFieldAsString");
    }

    private String getExchangeFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "exchangeFieldAsString");
    }

    private String getCurrencyFieldAsString(final Decoder decoder) throws Exception
    {
        return getString(decoder, "currencyFieldAsString");
    }

    private char[] getOptionalCountryField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalCountryField");
    }

    private char[] getOptionalExchangeField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalExchangeField");
    }

    private char[] getOptionalCurrencyField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "optionalCurrencyField");
    }

    private char[] getCountryField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "countryField");
    }

    private char[] getMultiCharField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "multiCharField");
    }

    private char getCharField(final Decoder decoder) throws Exception
    {
        return getChar(decoder, "charField");
    }

    private char[] getMultiCharNoEnumField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "multiValueCharNoEnumField");
    }

    private char[] getMultiValStringField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "multiValueStringField");
    }

    private char[] getMultiStringValField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "multiStringValueField");
    }

    private char[] getExchangeField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "exchangeField");
    }

    private char[] getCurrencyField(final Decoder decoder) throws Exception
    {
        return getChars(decoder, "currencyField");
    }

    private AsciiSequenceView getOptionalCountryFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "countryField");
    }

    private AsciiSequenceView getOptionalExchangeFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "exchangeField");
    }

    private AsciiSequenceView getOptionalCurrencyFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "currencyField");
    }

    private AsciiSequenceView getCountryFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "countryField");
    }

    private AsciiSequenceView getExchangeFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "exchangeField");
    }

    private AsciiSequenceView getCurrencyFieldAsView(final Decoder decoder) throws Exception
    {
        return getAsciiSequenceView(decoder, "currencyField");
    }

    private int getCurrencyFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "currencyFieldLength");
    }

    private int getExchangeFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "exchangeFieldLength");
    }

    private int getCountryFieldLength(final Decoder decoder) throws Exception
    {
        return getInt(decoder, "countryFieldLength");
    }

    private void canIterateOverGroup(final Decoder decoder) throws Exception
    {
        final Iterator<?> iterator = getEgGroupIterator(decoder);

        assertHasNext(iterator);
        Object group = iterator.next();
        assertEquals(1, getGroupField(group));

        assertHasNext(iterator);
        group = iterator.next();
        assertEquals(2, getGroupField(group));

        assertNotHasNext(iterator);
    }

    private void canIterateOverGroupUsingForEach(final Decoder decoder) throws Exception
    {
        final Iterable<?> iterator = getEgGroupIterable(decoder);
        int count = 0;

        for (final Object group : iterator)
        {
            count++;
            assertEquals(count, getGroupField(group));
        }

        assertEquals(2, count);

    }

    private void assertNotHasNext(final Decoder decoder) throws Exception
    {
        final Iterator<?> iterator = getEgGroupIterator(decoder);
        assertNotHasNext(iterator);
    }

    private void assertNotHasNext(final Iterator<?> iterator)
    {
        assertFalse("hasNext() when it shouldn't", iterator.hasNext());
    }

    void assertValidRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertRepeatingGroupDecoded(decoder);

        assertValid(decoder);
    }

    private void assertRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertEquals(2, getNoEgGroupGroupCounter(decoder));

        Object group = getEgGroup(decoder);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder",
            group.getClass().getName());
        assertEquals(1, getGroupField(group));

        group = next(group);
        assertEquals(2, getGroupField(group));
        assertNull(next(group));
    }

    void assertSingleRepeatingGroupDecoded(final Decoder decoder) throws Exception
    {
        assertEquals(1, getNoEgGroupGroupCounter(decoder));

        final Object group = getEgGroup(decoder);
        assertEquals(2, getGroupField(group));

        assertValid(decoder);
    }

    private Object getStatic(final Class<?> cls, final String field) throws IllegalAccessException, NoSuchFieldException
    {
        return cls.getField(field).get(null);
    }

    private void assertHasComponentFieldGetter() throws NoSuchMethodException, ClassNotFoundException
    {
        assertHasMethod("componentField", int.class, component);
        assertHasMethod(HAS_COMPONENT_FIELD, boolean.class, component);

        final Class<?> clazz = heartbeat.getClassLoader().loadClass(
            "uk.co.real_logic.artio.builder.test.EgComponentDecoder$ComponentGroupGroupDecoder");

        assertHasMethod("componentGroupGroup", clazz, component);
    }

    private void assertHasMethod(final String name, final Class<?> expectedReturnType, final Class<?> component)
        throws NoSuchMethodException
    {
        final Method method = component.getMethod(name);
        assertEquals(expectedReturnType, method.getReturnType());
    }

    private int getNoEgGroupGroupCounter(final Decoder decoder) throws Exception
    {
        return (int)get(decoder, "noEgGroupGroupCounter");
    }

    private boolean hasNoEgGroupGroupCounter(final Decoder decoder) throws Exception
    {
        return (boolean)get(decoder, "hasNoEgGroupGroupCounter");
    }

    private int getGroupField(final Object group) throws Exception
    {
        return (int)get(group, "groupField");
    }

    private int getBodyLength(final SessionHeaderDecoder header) throws Exception
    {
        return (int)get(header, ExampleDictionary.BODY_LENGTH);
    }

    private Object getTrailer(final Decoder trailer) throws Exception
    {
        return get(trailer, "trailer");
    }

    private String getChecksum(final Object trailer) throws Exception
    {
        return (String)get(trailer, "checkSumAsString");
    }

    private SessionHeaderDecoder getHeader(final Decoder decoder) throws Exception
    {
        return (SessionHeaderDecoder)get(decoder, "header");
    }

    Decoder decodeHeartbeat(final String example) throws Exception
    {
        final Decoder decoder = newHeartbeat();
        decode(example, decoder);
        return decoder;
    }

    Decoder newHeartbeat()
        throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException
    {
        return (Decoder)heartbeat.getConstructor().newInstance();
    }

    private Decoder decodeHeartbeatWithoutEnumValue(final String example) throws Exception
    {
        final Decoder decoder = (Decoder)heartbeatWithoutEnumValueValidation.getConstructor().newInstance();
        decode(example, decoder);
        return decoder;
    }

    Decoder decodeHeartbeatWithoutValidation(final String example) throws Exception
    {
        final Decoder decoder = (Decoder)heartbeatWithoutValidation.getConstructor().newInstance();
        decode(example, decoder);
        return decoder;
    }

    private Decoder decodeHeartbeatWithRejectingUnknownFields(final Decoder decoder,
        final Consumer<Decoder> consumer,
        final String example)
    {
        decode(example, decoder);
        consumer.accept(decoder);
        return decoder;
    }

    private Decoder decodeHeartbeatWithRejectingUnknownFields(final String example) throws Exception
    {
        final Decoder decoder = (Decoder)heartbeatWithRejectingUnknownFields.getConstructor().newInstance();
        decode(example, decoder);
        return decoder;
    }

    void decode(final String example, final Decoder decoder)
    {
        buffer.putAscii(1, example);
        decoder.reset();
        decoder.decode(buffer, 1, example.length());
    }

    private void assertIsDecoder(final Class<?> cls)
    {
        assertTrue("Isn't a decoder", Decoder.class.isAssignableFrom(cls));
    }

    private boolean hasTestReqId(final Object encoder) throws Exception
    {
        return (boolean)getField(encoder, HAS_TEST_REQ_ID);
    }

    private boolean hasDataField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_DATA_FIELD);
    }

    private boolean hasComponentField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_COMPONENT_FIELD);
    }

    private boolean hasBooleanField(final Decoder decoder) throws Exception
    {
        return (boolean)getField(decoder, HAS_BOOLEAN_FIELD);
    }

    Object getFloatField(final Decoder decoder) throws Exception
    {
        return get(decoder, FLOAT_FIELD);
    }

    Object getIntField(final Object decoder) throws Exception
    {
        return get(decoder, INT_FIELD);
    }

    long getLongField(final Object decoder) throws Exception
    {
        return (long)get(decoder, LONG_FIELD);
    }

    private char[] getOnBehalfOfCompId(final Decoder decoder) throws Exception
    {
        return getCharArray(decoder, ON_BEHALF_OF_COMP_ID);
    }

    private byte[] getDataField(final Decoder decoder) throws Exception
    {
        return getBytes(decoder, DATA_FIELD);
    }

    private byte[] getSomeTimeField(final Decoder decoder) throws Exception
    {
        return getBytes(decoder, SOME_TIME_FIELD);
    }

    private String getSomeTimeFieldAsString(final Decoder decoder) throws Exception
    {
        return (String)get(decoder, SOME_TIME_FIELD + "AsString");
    }

    private Object getBooleanField(final Decoder decoder) throws Exception
    {
        return get(decoder, BOOLEAN_FIELD);
    }

    private char[] getTestReqId(final Decoder decoder) throws Exception
    {
        return getCharArray(decoder, TEST_REQ_ID);
    }

    private char[] getCharArray(final Decoder decoder, final String name) throws Exception
    {
        final char[] value = (char[])get(decoder, name);
        final int length = (int)get(decoder, name + "Length");
        return Arrays.copyOf(value, length);
    }

    private void assertValid(final Decoder decoder)
    {
        if (!decoder.validate())
        {
            fail("Failed validation with reason: " + RejectReason.decode(decoder.rejectReason()) +
                " for tag: " + decoder.invalidTagId());
        }
    }

    private void assertInvalid(final Decoder decoder)
    {
        final boolean isValid = decoder.validate();
        assertFalse(String.format(
            "Decoder fails validation due to: %s for tag: %d", decoder.rejectReason(), decoder.invalidTagId()),
            isValid);
    }

    private void assertInvalid(final Decoder decoder, final int rejectReason, final int invalidTagId)
    {
        final boolean isValid = decoder.validate();
        assertFalse(String.format(
            "Decoder fails validation due to: %s for tag: %d", decoder.rejectReason(), decoder.invalidTagId()),
            isValid);
        assertEquals("Wrong reject reason with invalidTagId=" + decoder.invalidTagId(),
            rejectReason, decoder.rejectReason());
        assertEquals("Wrong tag id with rejectReason=" + decoder.rejectReason(),
            invalidTagId, decoder.invalidTagId());
    }

    private Object getRequiredFields(final Decoder decoder) throws IllegalAccessException, NoSuchFieldException
    {
        return heartbeat.getField(REQUIRED_FIELDS).get(decoder);
    }

    private Object getMethod(final Object decoder, final String fieldName) throws Throwable
    {
        final char[] chars = fieldName.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return get(decoder, new String(chars));
    }

    private Decoder createRequiredFieldMessageDecoder() throws Exception
    {
        return (Decoder)allReqFieldTypesMessage.getConstructor().newInstance();
    }

    private void assertNestedRepeating(final Object group, final int groupField,
        final int nestedValue1, final int nestedValue2)
        throws Exception
    {
        assertEquals(groupField, getGroupField(group));

        Object nestedGroup = getNestedGroup(group);
        assertEquals(
            heartbeat.getName() + "$EgGroupGroupDecoder$NestedGroupGroupDecoder",
            nestedGroup.getClass().getName());

        final boolean expectingValue1 = nestedValue1 != CodecUtil.MISSING_INT;
        assertEquals(expectingValue1, get(nestedGroup, "hasNestedField"));
        if (expectingValue1)
        {
            assertEquals(nestedValue1, get(nestedGroup, "nestedField"));
        }

        nestedGroup = next(nestedGroup);
        final boolean expectingValue2 = nestedValue2 != CodecUtil.MISSING_INT;
        assertEquals(expectingValue2, get(nestedGroup, "hasNestedField"));
        if (expectingValue2)
        {
            assertEquals(nestedValue2, get(nestedGroup, "nestedField"));
        }
    }

    private Decoder enumTestMessageDecoder() throws Exception
    {
        return (Decoder)enumTestMessage.getConstructor().newInstance();
    }
}
