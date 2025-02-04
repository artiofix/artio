/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
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

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.builder.StringRepresentable;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.util.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.UnaryOperator;

import static java.lang.reflect.Modifier.isAbstract;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static uk.co.real_logic.artio.dictionary.generation.CodecGenerationWrapper.dictionaryStream;
import static uk.co.real_logic.artio.dictionary.generation.CodecGenerationWrapper.setupHeader;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_CHAR;
import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_INT;
import static uk.co.real_logic.artio.dictionary.generation.EnumGeneratorTest.assertRepresentation;
import static uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat.VALUE_MAX_VAL;
import static uk.co.real_logic.artio.util.Reflection.*;

@SuppressWarnings("ResultOfMethodCallIgnored")
public abstract class AbstractSharedCodecsTest
{
    protected static final String DICT_1 = "shared.dictionary.1";
    protected static final String DICT_2 = "shared_dictionary_2";
    protected static final String DICT_3 = "shared_dictionary.3";
    protected static final String DICT_3_FIXT = "shared_dictionary_fixt.3";

    protected static final String DICT_1_NORM = "shared_dictionary_1";
    protected static final String DICT_2_NORM = "shared_dictionary_2";
    protected static final String DICT_3_NORM = "shared_dictionary_3";

    protected static final String ORDER_ID = "orderID";
    protected static final String RESET_ORDER_ID = "resetOrderID";
    protected static final String ORDER_ID_LENGTH = "orderIDLength";

    protected static final CodecGenerationWrapper WRAPPER = new CodecGenerationWrapper();
    protected static final String EXECUTION_REPORT = "ExecutionReport";

    protected static final String ALL_FIELDS_MSG =
        "8=FIX.4.4\0019=198\00135=8\00149=sender\00156=target\00134=1\00152= \00137=1\001198=2" +
        "\00117=3\001150=0\00139=2\00155=MSFT\001383=1\001376=GroupInComp\001925=pwd\00154=1\00160= " +
        "\001151=2\001152=1\001154=Y\001404=2\001382=1\001375=aContraBroker\001400=1" +
        "\001401=value\001402=1\001403=inner\001405=ABC\00110=123\001";

    protected static final String ALL_FIELDS_WITH_OVERFLOW_MSG =
        "8=FIX.4.4\0019=198\00135=8\00149=sender\00156=target\00134=1\00152= \00137=1\001198=2" +
        "\00117=3\001150=0\00139=2\00155=MSFT\001383=1\001376=GroupInComp\001925=pwd\00154=1\00160= " +
        "\001151=2\001152=1\001154=Y\001153=" + (VALUE_MAX_VAL + 1) +
        "\001404=2\001382=1\001375=aContraBroker\001400=1" +
        "\001401=value\001402=1\001403=inner\001405=ABC\00110=123\001";

    protected static final String NO_OPTIONAL_SHARED_FIELDS_MSG =
        "8=FIX.4.4\0019=185\00135=8\00149=sender\00156=target\00134=1\00152= \00137=1\001198=2" +
        "\00117=3\001150=0\001383=1\001376=GroupInComp\001925=pwd\00154=1\00160= " +
        "\001151=2\001152=1\001154=Y\001404=2\001382=1\001375=aContraBroker\001400=1\001401=value\001402=1" +
        "\001403=inner\001405=ABC\00110=185\001";

    protected static final String FIELD_SOMETIMES_IN_COMPONENT = "fieldSometimesInComponent";
    protected static final String RESET_FIELD_SOMETIMES_IN_COMPONENT = "resetFieldSometimesInComponent";
    protected static final String FIELD_SOMETIMES_IN_COMPONENT_LENGTH = "fieldSometimesInComponentLength";
    public static final String SOMETIMES_COMPONENT = "SometimesComponent";

    protected static Class<?> executionReportEncoderShared;

    protected static Class<?> logonEncoder1;
    protected static Class<?> logonDecoder1;

    protected static Class<?> executionReportEncoder1;
    protected static Class<?> executionReportEncoder2;
    protected static Class<?> executionReportEncoder3;

    protected static Class<?> executionReportDecoderShared;
    protected static Class<?> executionReportDecoder1;
    protected static Class<?> executionReportDecoder2;

    protected static Class<?> headerEncoder1;
    protected static Class<?> headerEncoder2;
    protected static Class<?> headerEncoderShared;

    public static void generate(final String decimalFloatOverflowHandler) throws Exception
    {
        WRAPPER.generate(config ->
        {
            config
                .decimalFloatOverflowHandler(
                  decimalFloatOverflowHandler)
                .sharedCodecsEnabled()
                .withDictionary(DICT_1, dictionaryStream(DICT_1))
                .withDictionary(DICT_2, dictionaryStream(DICT_2))
                .withDictionary(DICT_3, dictionaryStream(DICT_3_FIXT), dictionaryStream(DICT_3));
        });

        executionReportEncoder1 = WRAPPER.compile(executionReportEncoder(DICT_1_NORM));
        executionReportEncoder2 = loadClass(executionReportEncoder(DICT_2_NORM));
        executionReportEncoder3 = loadClass(executionReportEncoder(DICT_3_NORM));
        executionReportEncoderShared = loadClass(executionReportEncoder(null));

        executionReportDecoder1 = loadClass(executionReportDecoder(DICT_1_NORM));
        executionReportDecoder2 = loadClass(executionReportDecoder(DICT_2_NORM));
        executionReportDecoderShared = loadClass(executionReportDecoder(null));

        headerEncoder1 = loadClass(headerEncoder(DICT_1_NORM));
        headerEncoder2 = loadClass(headerEncoder(DICT_2_NORM));
        headerEncoderShared = loadClass(headerEncoder(null));

        logonEncoder1 = loadClass(logonEncoder(DICT_1_NORM));
        logonDecoder1 = loadClass(logonDecoder(DICT_1_NORM));
    }

    protected static String executionReportEncoder(final String dictNorm)
    {
        return encoder(dictNorm, EXECUTION_REPORT);
    }

    protected static String executionReportDecoder(final String dictNorm)
    {
        return decoder(dictNorm, EXECUTION_REPORT);
    }

    protected static String headerEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "Header");
    }

    protected static String logonEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "Logon");
    }

    protected static String logonDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "Logon");
    }

    protected static String newOrderSingleEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "NewOrderSingle");
    }

    protected static String anyFieldsOneEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "AnyFieldsOne");
    }

    protected static String anyFieldsTwoEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "AnyFieldsTwo");
    }

    protected static String constants(final String dictNorm)
    {
        return className(dictNorm, (dictNorm == null ? "Shared" : "") + "Constants", "", "");
    }

    protected static String newOrderSingleDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "NewOrderSingle");
    }

    protected static String instrumentEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "Instrument");
    }

    protected static String instrumentDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "Instrument");
    }

    protected static String contraBrokersGroupEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "ExecutionReportEncoder$ContraBrokersGroup");
    }

    protected static String contraBrokersGroupDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "ExecutionReportDecoder$" + groupDecoderPrefix(dictNorm) + "ContraBrokersGroup");
    }

    protected static String outerNestedGroupGroupEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "ExecutionReportEncoder$OuterNestedGroupGroup");
    }

    protected static String outerNestedGroupGroupDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "ExecutionReportDecoder$" + groupDecoderPrefix(dictNorm) + "OuterNestedGroupGroup");
    }

    protected static String innerNestedGroupGroupEncoder(final String dictNorm)
    {
        return outerNestedGroupGroupEncoder(dictNorm) + "$InnerNestedGroupGroupEncoder";
    }

    protected static String innerNestedGroupGroupDecoder(final String dictNorm)
    {
        return outerNestedGroupGroupDecoder(dictNorm) + "$" + groupDecoderPrefix(dictNorm) +
            "InnerNestedGroupGroupDecoder";
    }

    protected static String groupInCompEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "InstrumentEncoder$GroupInCompGroup");
    }

    protected static String groupInCompDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "InstrumentDecoder$" + groupDecoderPrefix(dictNorm) + "GroupInCompGroup");
    }

    protected static String groupDecoderPrefix(final String dictNorm)
    {
        return dictNorm == null ? "Abstract" : "";
    }

    protected static String iterator(final String dictNorm, final String groupName)
    {
        return className(dictNorm, groupName, "Iterator", "decoder.");
    }

    protected static String nonSharedComponentEncoder(final String dictNorm)
    {
        return encoder(dictNorm, "NonSharedComponent");
    }

    protected static String nonSharedComponentDecoder(final String dictNorm)
    {
        return decoder(dictNorm, "NonSharedComponent");
    }

    protected static String execType(final String dictNorm)
    {
        return enumOf(dictNorm, "ExecType");
    }

    protected static String collisionEnum(final String dictNorm)
    {
        return enumOf(dictNorm, "CollisionEnum");
    }

    protected static String equalCountEnum(final String dictNorm)
    {
        return enumOf(dictNorm, "EqualCountEnum");
    }

    protected static String missingEnum(final String dictNorm)
    {
        return enumOf(dictNorm, "MissingEnum");
    }

    protected static String enumOf(final String dictNorm, final String messageName)
    {
        return className(dictNorm, messageName, "", "");
    }

    protected static String encoder(final String dictNorm, final String messageName)
    {
        return WRAPPER.encoder(dictNorm, messageName);
    }

    protected static String decoder(final String dictNorm, final String messageName)
    {
        return WRAPPER.decoder(dictNorm, messageName);
    }

    protected static String className(
        final String dictNorm,
        final String messageName,
        final String suffix,
        final String prefix)
    {
        return WRAPPER.className(dictNorm, messageName, suffix, prefix);
    }

    @Test
    public void shouldGenerateClassStructure()
    {
        assertNotNull(executionReportEncoder2);
        assertNotNull(executionReportEncoder3);
        assertNotNull(executionReportEncoderShared);

        assertNotNull(executionReportDecoder2);
        assertNotNull(executionReportDecoderShared);

        assertAbstract(executionReportEncoderShared);
        assertNotAbstract(executionReportEncoder1);

        assertAbstract(executionReportDecoderShared);
        assertNotAbstract(executionReportDecoder2);

        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder1));
        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder2));
        assertTrue(executionReportEncoderShared.isAssignableFrom(executionReportEncoder3));

        assertTrue(executionReportDecoderShared.isAssignableFrom(executionReportDecoder2));
    }

    @Test
    public void shouldShareMessageConstants() throws NoSuchFieldException, NoSuchMethodException
    {
        executionReportDecoderShared.getDeclaredField("MESSAGE_TYPE");
        executionReportDecoderShared.getDeclaredField("MESSAGE_TYPE_AS_STRING");
        executionReportDecoderShared.getDeclaredField("MESSAGE_TYPE_CHARS");
        executionReportDecoderShared.getDeclaredField("MESSAGE_TYPE_BYTES");

        noField(executionReportDecoder1, "MESSAGE_TYPE");
        noField(executionReportDecoder1, "MESSAGE_TYPE_AS_STRING");
        noField(executionReportDecoder1, "MESSAGE_TYPE_CHARS");
        noField(executionReportDecoder1, "MESSAGE_TYPE_BYTES");

        executionReportEncoderShared.getDeclaredMethod("messageType");
    }

    protected void assertAbstract(final Class<?> cls)
    {
        assertTrue(isAbstract(cls.getModifiers()), cls + " not abstract");
    }

    protected void assertNotAbstract(final Class<?> cls)
    {
        assertFalse(isAbstract(cls.getModifiers()), cls + " abstract");
    }

    @Test
    public void shouldDecodeEncodedMessage() throws Exception
    {
        final Encoder encoder = executionReportEncoder1();
        setupHeader(encoder);
        setupEncoder(encoder, true);
        WRAPPER.assertEncodes(encoder, ALL_FIELDS_MSG);

        final Decoder decoder = executionReportDecoder1();
        WRAPPER.decode(decoder);

        final Encoder encoder2 = executionReportEncoder1();
        decoder.toEncoder(encoder2);
        setupHeader(encoder2);

        WRAPPER.assertEncodes(encoder2, ALL_FIELDS_MSG);
    }

    @Test
    public void shouldCopyToOtherEncoders() throws Exception
    {
        final Encoder encoder = executionReportEncoder1();
        setupEncoder(encoder, true);

        final Encoder encoder2 = executionReportEncoder1();
        encoder.copyTo(encoder2);
        setupHeader(encoder2);

        WRAPPER.assertEncodes(encoder2, ALL_FIELDS_MSG);
    }

    @Test
    public void shouldResetEncoders() throws Exception
    {
        final Encoder encoder = executionReportEncoder1();
        setupHeader(encoder);
        setupEncoder(encoder, true);

        encoder.reset();

        assertFalse(getBoolean(encoder, "hasOrderID"), encoder.toString());
        assertEquals(MISSING_CHAR, getChar(encoder, "side"), encoder.toString());

        final SessionHeaderEncoder header = encoder.header();
        assertFalse(header.hasMsgSeqNum(), header.toString());
        assertFalse(header.hasSenderCompID(), header.toString());

        // Also test that we don't hit UOE issues with required fields
        final Encoder logonEncoder = (Encoder)newInstance(logonEncoder1);
        logonEncoder.reset();
    }

    @Test
    public void shouldResetDecoders() throws Exception
    {
        final Decoder decoder = executionReportDecoder1();
        WRAPPER.decode(decoder, ALL_FIELDS_MSG);

        decoder.reset();

        assertEquals(0, getInt(decoder, ORDER_ID_LENGTH), decoder.toString());
        assertEquals(MISSING_CHAR, getChar(decoder, "side"), decoder.toString());

        final SessionHeaderDecoder header = decoder.header();
        assertEquals(MISSING_INT, header.msgSeqNum(), header.toString());
        assertEquals(0, header.senderCompIDLength(), header.toString());

        // Also test that we don't hit UOE issues with required fields
        final Decoder logonDecoder = (Decoder)newInstance(logonDecoder1);
        logonDecoder.reset();
        assertFalse(getBoolean(logonDecoder, "hasUsername"));
    }

    @Test
    public void shouldMakeRequiredFieldOptionalIfOptionalInSomeDictionaries() throws Exception
    {
        // OrdStatus optional in dict 2
        // Symbol in Instrument in dict 2

        final Encoder encoder = executionReportEncoder1();
        setupHeader(encoder);
        setupEncoder(encoder, false);
        WRAPPER.assertEncodes(encoder, NO_OPTIONAL_SHARED_FIELDS_MSG);
    }

    @Test
    public void shouldSupportMissingEnumsInSomeDictionaries() throws Exception
    {
        // No exectype in dict 2, Enum still generated in shared dict

        noClass(execType(DICT_1_NORM));
        noClass(execType(DICT_2_NORM));
        loadClass(execType(null));
    }

    @Test
    public void shouldNotShareFieldsWhenTheyHaveClashingTypes() throws Exception
    {
        final String clashingType = "clashingType";
        assertDecoderNotShared(clashingType);
        assertEncoderNotShared(clashingType);
    }

    @Test
    public void shouldShareFieldsWhenTheyHaveSameBaseType() throws Exception
    {
        final String combinableType = "combinableType";
        assertDecoderShared(combinableType);
        assertEncoderShared(combinableType);
    }

    @Test
    public void shouldShareComponents() throws Exception
    {
        // Instrument is common to all and should be shared
        final Class<?> sharedInstrumentEncoder = loadClass(instrumentEncoder(null));
        final Class<?> sharedInstrumentDecoder = loadClass(instrumentDecoder(null));
        loadClass(instrumentEncoder(DICT_1_NORM));
        loadClass(instrumentDecoder(DICT_1_NORM));
        loadClass(instrumentEncoder(DICT_2_NORM));
        loadClass(instrumentDecoder(DICT_2_NORM));

        // Fields on shared decoder
        sharedInstrumentEncoder.getDeclaredMethod("symbol");
        sharedInstrumentDecoder.getDeclaredMethod("symbol");
        sharedInstrumentDecoder.getDeclaredMethod("symbolLength");

        // Component that is unique to 1 dictionary
        noClass(nonSharedComponentEncoder(null));
        noClass(nonSharedComponentDecoder(null));
        loadClass(nonSharedComponentEncoder(DICT_1_NORM));
        loadClass(nonSharedComponentDecoder(DICT_1_NORM));
        noClass(nonSharedComponentEncoder(DICT_2_NORM));
        noClass(nonSharedComponentDecoder(DICT_2_NORM));

        // NB: NOS which has shared component but isn't shared
    }

    @Test
    public void shouldShareGroups() throws Exception
    {
        assertSharedGroup(
            "contraBroker",
            "ExecutionReportDecoder$ContraBrokersGroup",
            "ExecutionReportDecoder$AbstractContraBrokersGroup",
            AbstractSharedCodecsTest::contraBrokersGroupEncoder,
            AbstractSharedCodecsTest::contraBrokersGroupDecoder);

        // group inside component
        assertSharedGroup(
            "groupInCompField",
            "InstrumentDecoder$GroupInCompGroup",
            "InstrumentDecoder$AbstractGroupInCompGroup",
            AbstractSharedCodecsTest::groupInCompEncoder,
            AbstractSharedCodecsTest::groupInCompDecoder);

        // NB: NOS has no parent but inherits from the generic interface
    }

    @Test
    public void shouldShareNestedGroups() throws Exception
    {
        assertSharedGroup(
            "outerNestedGroupField",
            "ExecutionReportDecoder$OuterNestedGroupGroup",
            "ExecutionReportDecoder$AbstractOuterNestedGroupGroup",
            AbstractSharedCodecsTest::outerNestedGroupGroupEncoder,
            AbstractSharedCodecsTest::outerNestedGroupGroupDecoder);

        assertSharedGroup(
            "innerNestedGroupField",
            "ExecutionReportDecoder$OuterNestedGroupGroupDecoder$InnerNestedGroupGroup",
            "ExecutionReportDecoder$AbstractOuterNestedGroupGroupDecoder$AbstractInnerNestedGroupGroup",
            AbstractSharedCodecsTest::innerNestedGroupGroupEncoder,
            AbstractSharedCodecsTest::innerNestedGroupGroupDecoder);
    }

    protected void assertSharedGroup(
        final String methodName,
        final String iterator1,
        final String sharedIterator,
        final UnaryOperator<String> encoderName,
        final UnaryOperator<String> decoderName) throws Exception
    {
        // Contra Brokers group is common to all and should be shared
        final Class<?> contraBrokersGroupEncoder1 = loadClass(encoderName.apply(DICT_1_NORM));
        final Class<?> contraBrokersGroupDecoder1 = loadClass(decoderName.apply(DICT_1_NORM));
        loadClass(encoderName.apply(DICT_2_NORM));
        loadClass(decoderName.apply(DICT_2_NORM));

        final Class<?> sharedContraBrokersGroupEncoder = loadClass(encoderName.apply(null));
        final Class<?> sharedContraBrokersGroupDecoder = loadClass(decoderName.apply(null));

        assertTrue(sharedContraBrokersGroupEncoder.isAssignableFrom(contraBrokersGroupEncoder1));
        assertTrue(sharedContraBrokersGroupDecoder.isAssignableFrom(contraBrokersGroupDecoder1));

        // Fields on shared decoder
        sharedContraBrokersGroupEncoder.getDeclaredMethod(methodName);
        sharedContraBrokersGroupDecoder.getDeclaredMethod(methodName);

        // Iterators
        final Class<?> contraBrokersGroupIterator1 = loadClass(iterator(DICT_1_NORM, iterator1));
        final Class<?> sharedContraBrokersGroupIterator = loadClass(iterator(null, sharedIterator));
        assertTrue(sharedContraBrokersGroupIterator.isAssignableFrom(contraBrokersGroupIterator1));
        assertTrue(Iterator.class.isAssignableFrom(sharedContraBrokersGroupIterator));
        assertTrue(Iterable.class.isAssignableFrom(sharedContraBrokersGroupIterator));

        final ParameterizedType parameterizedSharedIterator =
            (ParameterizedType)contraBrokersGroupIterator1.getGenericSuperclass();
        assertEquals(contraBrokersGroupDecoder1, parameterizedSharedIterator.getActualTypeArguments()[0]);
    }

    @Test
    public <T extends Enum<T>> void shouldBuildEnumUnions() throws Exception
    {
        final String collisionEnumName = collisionEnum(null);
        final Class<T> collisionEnum = WRAPPER.loadClass(collisionEnumName);
        assertTrue(collisionEnum.isEnum());

        final T newValue = enumValue(collisionEnum, "NEW");
        final T fillValue = enumValue(collisionEnum, "FILL");
        final T canceledValue = enumValue(collisionEnum, "CANCELED");

        // Clash for names and representations results in most common pair being used for name / representation
        assertRepresentation('0', newValue);
        assertRepresentation('1', fillValue);
        assertRepresentation('2', canceledValue);

        // Collision based upon a name not generated, name put in javadoc
        noEnum(collisionEnum, "VALUE_CLASH");
        assertSourceContains(collisionEnumName, "/** Altnames: VALUE_CLASH */ NEW('0')");

        // Overloads generated for other name collision combinations
        assertRepresentation('N', enumValue(collisionEnum, "NEW_N"));
        assertRepresentation('F', enumValue(collisionEnum, "FILL_F"));
        assertRepresentation('C', enumValue(collisionEnum, "CANCELED_C"));
    }

    protected void assertSourceContains(final String className, final String substring)
    {
        final CharSequence enumSource = WRAPPER.sources().get(className);
        MatcherAssert.assertThat(enumSource.toString(), containsString(substring));
    }

    @Test
    public <T extends Enum<T>> void shouldBuildEnumsOfMergingCharWithString() throws Exception
    {
        // We've merged a char and a String into a String

        final String stringAndCharEnumName = enumOf(null, "StringAndCharEnum");
        final Class<T> stringAndCharEnum = WRAPPER.loadClass(stringAndCharEnumName);
        assertTrue(stringAndCharEnum.isEnum());

        assertEquals(
            5,
            stringAndCharEnum.getEnumConstants().length,
            Arrays.toString(stringAndCharEnum.getEnumConstants()));
        enumValue(stringAndCharEnum, "NEW");
        enumValue(stringAndCharEnum, "FILL");
        enumValue(stringAndCharEnum, "CANCELED");
        assertTrue(StringRepresentable.class.isAssignableFrom(stringAndCharEnum));
    }

    @Test
    public void shouldSupportFieldsSometimesBeingEnums() throws Exception
    {
        // Missing enum isn't an enum in dict 2 but is in other dictionaries
        // Generate the enum type but don't generate the AsEnum method so people can optionally use it

        final Class<?> missingEnum = loadClass(missingEnum(null));
        assertTrue(missingEnum.isEnum());

        assertEquals("[NEW, FILL, CANCELED, NULL_VAL, ARTIO_UNKNOWN]",
            Arrays.toString(missingEnum.getEnumConstants()));

        executionReportEncoderShared.getDeclaredMethod("missingEnum", char.class);
        noMethod(executionReportEncoderShared, "missingEnum", missingEnum);

        executionReportDecoderShared.getDeclaredMethod("missingEnum");
        noMethod(executionReportDecoderShared, "missingEnumAsEnum");
    }

    @Test
    public <T extends Enum<T>> void shouldSupportConsistNamesWithEqualNumbersOfOverloads() throws Exception
    {
        final String equalCountEnumName = equalCountEnum(null);
        final Class<T> equalCountEnum = WRAPPER.loadClass(equalCountEnumName);
        assertTrue(equalCountEnum.isEnum());

        final T value1 = enumValue(equalCountEnum, "ORDER_CANCEL_REQUEST");
        final T value2 = enumValue(equalCountEnum, "ORDER_CANCEL_REPLACE_REQUEST");

        assertRepresentation('1', value1);
        assertRepresentation('2', value2);

        assertSourceContains(equalCountEnumName, "/** Altnames: ORDCXLREQ */ ORDER_CANCEL_REQUEST");
        assertSourceContains(equalCountEnumName, "/** Altnames: ORDCXLREPREQ */ ORDER_CANCEL_REPLACE_REQUEST");
    }

    protected <T extends Enum<T>> T enumValue(final Class<T> collisionEnum, final String name)
    {
        try
        {
            return Enum.valueOf(collisionEnum, name);
        }
        catch (final IllegalArgumentException e)
        {
            System.err.println(Arrays.toString(collisionEnum.getEnumConstants()));
            throw e;
        }
    }

    protected <T extends Enum<T>> void noEnum(final Class<T> enumClass, final String name)
    {
        try
        {
            final T value = Enum.valueOf(enumClass, name);
            fail("Found enum value " + value + " for " + name + " in " + enumClass);
        }
        catch (final IllegalArgumentException e)
        {
            // Deliberately blank
        }
    }

    @Test
    public void shouldShareMethods() throws Exception
    {
        // No exectype in dict 2, Enum still generated in shared dict

        assertEncoderShared(ORDER_ID, CharSequence.class);
        assertEncoderShared(RESET_ORDER_ID);

        assertDecoderShared(ORDER_ID);
        assertDecoderShared(ORDER_ID_LENGTH);
        assertDecoderShared(RESET_ORDER_ID);

        final String resetMessage = "resetMessage";
        executionReportDecoderShared.getDeclaredMethod(resetMessage);
        executionReportDecoder2.getDeclaredMethod(resetMessage);
        executionReportEncoderShared.getDeclaredMethod(resetMessage);
        executionReportEncoder1.getDeclaredMethod(resetMessage);

        final String reset = "reset";
        assertEncoderNotShared(reset);
        assertDecoderNotShared(reset);
    }

    protected void assertEncoderShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        executionReportEncoderShared.getDeclaredMethod(methodName, parameterTypes);
        assertNotOnEncoderChildren(methodName, parameterTypes);
    }

    protected void assertNotOnEncoderChildren(final String methodName, final Class<?>... parameterTypes)
    {
        noMethod(executionReportEncoder1, methodName, parameterTypes);
        noMethod(executionReportEncoder2, methodName, parameterTypes);
        noMethod(executionReportEncoder3, methodName, parameterTypes);
    }

    protected void assertDecoderShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        executionReportDecoderShared.getDeclaredMethod(methodName, parameterTypes);
        noMethod(executionReportDecoder2, methodName, parameterTypes);
    }

    protected void assertEncoderNotShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        noMethod(executionReportEncoderShared, methodName, parameterTypes);
        executionReportEncoder1.getDeclaredMethod(methodName, parameterTypes);
        executionReportEncoder2.getDeclaredMethod(methodName, parameterTypes);
        executionReportEncoder3.getDeclaredMethod(methodName, parameterTypes);
    }

    protected void assertDecoderNotShared(final String methodName, final Class<?>... parameterTypes)
        throws NoSuchMethodException
    {
        noMethod(executionReportDecoderShared, methodName, parameterTypes);
        executionReportDecoder2.getDeclaredMethod(methodName, parameterTypes);
    }

    @Test
    public void shouldSupportFieldMissingInSomeDictionaries() throws Exception
    {
        // ER.SecondaryOrderID on 1 but not others
        final String hasSecondaryOrderID = "hasSecondaryOrderID";
        executionReportEncoder1.getDeclaredMethod(hasSecondaryOrderID);
        noMethod(executionReportEncoder2, hasSecondaryOrderID);
        noMethod(executionReportEncoderShared, hasSecondaryOrderID);

        // OnBehalfOfCompID on header 1
        final String hasOnBehalfOfCompID = "hasOnBehalfOfCompID";
        headerEncoder1.getDeclaredMethod(hasOnBehalfOfCompID);
        noMethod(headerEncoder2, hasOnBehalfOfCompID);
        noMethod(headerEncoderShared, hasOnBehalfOfCompID);
    }

    @Test
    public void shouldSupportMessagesMissingInSomeDictionaries() throws ClassNotFoundException
    {
        // dict 2 doesn't have NOS so shared doesn't
        noClass(newOrderSingleEncoder(null));
        loadClass(newOrderSingleEncoder(DICT_1_NORM));
        noClass(newOrderSingleEncoder(DICT_2_NORM));

        noClass(newOrderSingleDecoder(null));
        loadClass(newOrderSingleDecoder(DICT_1_NORM));
        noClass(newOrderSingleDecoder(DICT_2_NORM));
    }

    @Test
    public void shouldGenerateSharedConstantsClass() throws Exception
    {
        final Class<?> sharedConstants = loadClass(constants(null));
        final Class<?> dict1Constants = loadClass(constants(DICT_1_NORM));

        // Hierarchy
        assertTrue(sharedConstants.isAssignableFrom(dict1Constants));

        // Shared Message Constants
        sharedConstants.getDeclaredField("LOGON_MESSAGE_AS_STR");
        sharedConstants.getDeclaredField("LOGON_MESSAGE");
        noField(dict1Constants, "LOGON_MESSAGE_AS_STR");
        noField(dict1Constants, "LOGON_MESSAGE");

        // Shared Field Constants
        sharedConstants.getDeclaredField("BEGIN_STRING");
        noField(dict1Constants, "BEGIN_STRING");
    }

    @Test
    public void shouldShareFieldsThatAreSometimesInComponents() throws Exception
    {
        // FieldSometimesInComponent is in a component in dict 1, and directly on the ER in other dicts
        final Class<?> sometimesComponentEncoder = loadClass(sometimesComponentEncoder(null));
        final Class<?> sometimesComponentDecoder = loadClass(sometimesComponentDecoder(null));
        loadClass(sometimesComponentEncoder(DICT_1_NORM));
        loadClass(sometimesComponentDecoder(DICT_1_NORM));
        loadClass(sometimesComponentEncoder(DICT_2_NORM));
        loadClass(sometimesComponentDecoder(DICT_2_NORM));

        sometimesComponentEncoder.getMethod(FIELD_SOMETIMES_IN_COMPONENT);
        sometimesComponentDecoder.getMethod(FIELD_SOMETIMES_IN_COMPONENT);
        sometimesComponentDecoder.getMethod(FIELD_SOMETIMES_IN_COMPONENT_LENGTH);

        assertNotOnEncoderChildren(FIELD_SOMETIMES_IN_COMPONENT, CharSequence.class);
    }

    @Test
    public void shouldSupportAnyFields() throws Exception
    {
        // any fields shared between all dicts
        final Class<?> anyFieldsOneEncoder1 = loadClass(anyFieldsOneEncoder(DICT_1_NORM));
        final Class<?> anyFieldsOneEncoderShared = loadClass(anyFieldsOneEncoder(null));

        // any fields not shared between all dicts
        final Class<?> anyFieldsTwoEncoder1 = loadClass(anyFieldsTwoEncoder(DICT_1_NORM));
        final Class<?> anyFieldsTwoEncoderShared = loadClass(anyFieldsTwoEncoder(null));

        final String trailingAnyFields = "trailingAnyFields";

        anyFieldsOneEncoder1.getMethod(trailingAnyFields);
        anyFieldsOneEncoderShared.getMethod(trailingAnyFields);
        noField(anyFieldsOneEncoderShared, trailingAnyFields); // only in children

        anyFieldsTwoEncoder1.getMethod(trailingAnyFields);
        noMethod(anyFieldsTwoEncoderShared, trailingAnyFields);
        noField(anyFieldsTwoEncoderShared, trailingAnyFields);
    }

    protected String sometimesComponentDecoder(final String dictNorm)
    {
        return decoder(dictNorm, SOMETIMES_COMPONENT);
    }

    protected String sometimesComponentEncoder(final String dictNorm)
    {
        return encoder(dictNorm, SOMETIMES_COMPONENT);
    }

    protected static Class<Object> loadClass(final String className) throws ClassNotFoundException
    {
        return WRAPPER.loadClass(className);
    }

    protected void noMethod(final Class<?> cls, final String name, final Class<?>... paramTypes)
    {
        try
        {
            final Method method = cls.getDeclaredMethod(name, paramTypes);
            fail("Found method: " + method + " which shouldn't exist");
        }
        catch (final NoSuchMethodException e)
        {
            // Deliberately blank
        }
    }

    protected void noField(final Class<?> cls, final String name)
    {
        try
        {
            final Field field = cls.getDeclaredField(name);
            fail("Found field: " + field + " which shouldn't exist");
        }
        catch (final NoSuchFieldException e)
        {
            // Deliberately blank
        }
    }

    protected void setupEncoder(final Encoder encoder, final boolean optionalFields) throws Exception
    {
        setCharSequence(encoder, ORDER_ID, "1");
        setCharSequence(encoder, "secondaryOrderID", "2");
        setCharSequence(encoder, "execID", "3");
        setChar(encoder, "execType", '0');
        if (optionalFields)
        {
            setChar(encoder, "ordStatus", '2');
        }

        final Object instrument = Reflection.get(encoder, "instrument");
        if (optionalFields)
        {
            setCharSequence(instrument, "symbol", "MSFT");
        }

        final Object groupInComp = get(instrument, "groupInCompGroup", 1);
        setCharSequence(groupInComp, "groupInCompField", "GroupInComp");

        final Object contraBrokers = get(encoder, "contraBrokersGroup", 1);
        setCharSequence(contraBrokers, "contraBroker", "aContraBroker");

        final Object outerNestedGroup = get(encoder, "outerNestedGroupGroup", 1);
        setCharSequence(outerNestedGroup, "outerNestedGroupField", "value");
        final Object innerNestedGroup = get(outerNestedGroup, "innerNestedGroupGroup", 1);
        setCharSequence(innerNestedGroup, "innerNestedGroupField", "inner");

        final Object nonSharedComponent = Reflection.get(encoder, "nonSharedComponent");
        setCharSequence(nonSharedComponent, "newPassword", "pwd");

        setChar(encoder, "side", '1');
        setByteArray(encoder, "transactTime", " ".getBytes(StandardCharsets.US_ASCII));
        setChar(encoder, "collisionEnum", '2');
        setChar(encoder, "missingEnum", '1');
        setBoolean(encoder, "clashingType", true);
        setCharSequence(encoder, "stringAndCharEnum", "2");

        final Object semiSharedComponent = Reflection.get(encoder, "semiSharedComponent");
        setCharSequence(semiSharedComponent, "semiSharedField", "ABC");
    }

    protected Decoder executionReportDecoder1() throws Exception
    {
        return (Decoder)newInstance(executionReportDecoder1);
    }

    protected Object newInstance(final Class<?> cls) throws Exception
    {
        return cls.getConstructor().newInstance();
    }

    protected Encoder executionReportEncoder1() throws Exception
    {
        return (Encoder)newInstance(executionReportEncoder1);
    }

    protected void noClass(final String className)
    {
        WRAPPER.noClass(className);
    }
}
