/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.dictionary;

import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static uk.co.real_logic.artio.dictionary.generation.CodecConfiguration.DEFAULT_PARENT_PACKAGE;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.*;
import static uk.co.real_logic.artio.dictionary.ir.Field.registerField;

public final class ExampleDictionary
{

    public static final String NO_EG_GROUP = "NoEgGroup";
    public static final String NO_NESTED_EG_GROUP = "NoNestedGroup";
    public static final String NO_SECOND_EG_GROUP = "NoSecondEgGroup";
    public static final String NO_ADMIN_EG_GROUP = "NoAdminEgGroup";
    public static final String NO_COMPONENT_GROUP = "NoComponentGroup";
    public static final String NO_NESTED_COMPONENT_GROUP = "NoNestedComponentGroup";
    public static final String EG_COMPONENT = "EgComponent";
    public static final String EG_NESTED_COMPONENT = "EgNestedComponent";
    public static final String EG_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP = "EgOptionalComponentOfRequiredGroup";
    public static final String FIELDS_MESSAGE = "FieldsMessage";
    public static final String OPTIONAL_COMPONENT_REQUIRED_GROUP_MESSAGE = "OptionalComponentOfRequiredGroupMessage";
    public static final String LOWERCASE_MESSAGE = "lowerCaseMessage";

    public static final String EG_ENUM = DEFAULT_PARENT_PACKAGE + "." + "EgEnum";
    public static final String OTHER_ENUM = DEFAULT_PARENT_PACKAGE + "." + "OtherEnum";
    public static final String STRING_ENUM = DEFAULT_PARENT_PACKAGE + "." + "StringEnum";
    public static final String CURRENCY_ENUM = DEFAULT_PARENT_PACKAGE + "." + "CurrencyEnum";

    public static final String TEST_PARENT_PACKAGE = DEFAULT_PARENT_PACKAGE;

    public static final String TEST_PACKAGE = DEFAULT_PARENT_PACKAGE + ".builder.test";

    public static final String HEARTBEAT_ENCODER = TEST_PACKAGE + ".HeartbeatEncoder";
    public static final String COMPONENT_ENCODER = TEST_PACKAGE + "." + EG_COMPONENT + "Encoder";

    public static final String HEARTBEAT_DECODER = TEST_PACKAGE + ".HeartbeatDecoder";
    public static final String ALL_REQ_FIELD_TYPES_MESSAGE_DECODER = TEST_PACKAGE + ".AllReqFieldTypesMessageDecoder";
    public static final String FIELDS_MESSAGE_DECODER = TEST_PACKAGE + "." + FIELDS_MESSAGE + "Decoder";
    public static final String PHONE_BOOK_MESSAGE_DECODER =
        TEST_PACKAGE + "." + OPTIONAL_COMPONENT_REQUIRED_GROUP_MESSAGE + "Decoder";
    public static final String HEADER_DECODER = TEST_PACKAGE + ".HeaderDecoder";
    public static final String COMPONENT_DECODER = TEST_PACKAGE + "." + EG_COMPONENT + "Decoder";
    public static final String NESTED_COMPONENT_DECODER = TEST_PACKAGE + "." + EG_NESTED_COMPONENT + "Decoder";
    public static final String OTHER_MESSAGE_DECODER = TEST_PACKAGE + ".OtherMessageDecoder";
    public static final String OTHER_MESSAGE_ENCODER = TEST_PACKAGE + ".OtherMessageEncoder";
    public static final String ENUM_TEST_MESSAGE_DECODER = TEST_PACKAGE + ".EnumTestMessageDecoder";
    public static final String ENUM_TEST_MESSAGE_ENCODER = TEST_PACKAGE + ".EnumTestMessageEncoder";
    public static final String ANY_FIELDS_MESSAGE_ENCODER = TEST_PACKAGE + ".AnyFieldsMessageEncoder";

    public static final String PRINTER = TEST_PACKAGE + ".PrinterImpl";

    public static final String ABC = "abc";
    public static final byte[] VALUE_IN_BYTES = {97, 98, 99};
    public static final byte[] LONG_VALUE_IN_BYTES = { 97, 98, 99, 100 };
    public static final byte[] PREFIXED_VALUE_IN_BYTES = {0, 97, 98, 99, 100, 0};
    public static final String TEST_REQ_ID = "testReqID";
    public static final String TEST_REQ_ID_AS_COPY = "testReqIDAsCopy";
    public static final String ON_BEHALF_OF_COMP_ID = "onBehalfOfCompID";
    public static final String INT_FIELD = "intField";
    public static final String LONG_FIELD = "longField";
    public static final String FLOAT_FIELD = "floatField";
    public static final String BOOLEAN_FIELD = "booleanField";
    public static final String DATA_FIELD = "dataField";
    public static final String DATA_LENGTH_FIELD = "dataFieldLength";
    public static final String TEST_REQ_ID_LENGTH = "testReqIDLength";
    public static final String TEST_REQ_ID_OFFSET = "testReqIDOffset";
    public static final String ON_BEHALF_OF_COMP_ID_LENGTH = "onBehalfOfCompIDLength";
    public static final String SOME_TIME_FIELD = "someTimeField";
    public static final String COMPONENT_FIELD = "componentField";

    public static final String ALL_REQ_FIELD_TYPES_MESSAGE_NAME = "AllReqFieldTypesMessage";
    public static final String ALL_REQ_FIELD_TYPES_MESSAGE_TYPE = "RF";
    public static final String STRING_RF = "StringRF";
    public static final String INT_RF = "IntRF";
    public static final String CHAR_RF = "CharRF";
    public static final String DECIMAL_RF = "DecimalRF";
    public static final String STRING_ENUM_RF = "StringEnumRF";
    public static final String INT_ENUM_RF = "IntEnumRF";
    public static final String CHAR_ENUM_RF = "CharEnumRF";
    public static final String CURRENCY_ENUM_RF = "CurrencyEnumRF";
    public static final String UTC_TIMESTAMP_RF = "UtcTimestampRF";

    public static final String STRING_ENUM_REQ = "stringEnumReq";
    public static final String INT_ENUM_REQ = "intEnumReq";
    public static final String CHAR_ENUM_REQ = "charEnumReq";

    public static final String HAS_TEST_REQ_ID = "hasTestReqID";
    public static final String HAS_BOOLEAN_FIELD = "hasBooleanField";
    public static final String HAS_DATA_FIELD = "hasDataField";
    public static final String HAS_COMPONENT_FIELD = "hasComponentField";

    public static final String MSG_TYPE = "msgType";
    public static final String BODY_LENGTH = "bodyLength";

    public static final String ANY_FIELDS_MESSAGE_NAME = "AnyFieldsMessage";
    public static final String ANY_FIELDS_MESSAGE_TYPE = "AF";

    public static final long HEARTBEAT_TYPE = '0';

    public static final Dictionary FIELD_EXAMPLE;

    public static final Dictionary MESSAGE_EXAMPLE;

    public static final String HEADER_TO_STRING =
        "  \"header\": {\n" +
        "    \"MessageName\": \"Header\",\n" +
        "    \"BeginString\": \"FIX.4.4\",\n" +
        "    \"MsgType\": \"0\",\n" +
        "  }\n";

    public static final String STRING_ENCODED_MESSAGE_SUFFIX =
        "  \"OnBehalfOfCompID\": \"abc\",\n" +
        "  \"TestReqID\": \"abc\",\n" +
        "  \"IntField\": \"2\",\n" +
        "  \"FloatField\": \"1.1\",\n" +
        "  \"BooleanField\": \"true\",\n" +
        "  \"DataFieldLength\": \"3\",\n" +
        "  \"DataField\": \"123\",\n" +
        "  \"SomeTimeField\": \"19700101-00:00:00.001\"";

    public static final String STRING_GROUP_TWO_ELEMENTS =
        "  \"EgGroupGroup\": [\n" +
        "  {\n" +
        "    \"MessageName\": \"EgGroupGroup\",\n" +
        "    \"GroupField\": \"1\",\n" +
        "  },\n" +
        "  {\n" +
        "    \"MessageName\": \"EgGroupGroup\",\n" +
        "    \"GroupField\": \"2\",\n" +
        "  }\n" +
        "  ]";

    public static final String STRING_GROUP_ONE_ELEMENT =
        "  \"EgGroupGroup\": [\n" +
        "  {\n" +
        "    \"MessageName\": \"EgGroupGroup\",\n" +
        "    \"GroupField\": \"2\",\n" +
        "  }\n" +
        "  ]";

    public static final String STRING_ENCODED_MESSAGE_EXAMPLE =
        "{\n" +
        "  \"MessageName\": \"Heartbeat\",\n" +
        HEADER_TO_STRING +
        STRING_ENCODED_MESSAGE_SUFFIX;

    public static final String STRING_NO_OPTIONAL_MESSAGE_SUFFIX =
        "  \"OnBehalfOfCompID\": \"abc\",\n" +
        "  \"IntField\": \"2\",\n" +
        "  \"FloatField\": \"1.1\",\n" +
        "  \"SomeTimeField\": \"19700101-00:00:00.001\"";

    public static final String STRING_ONLY_TESTREQ_MESSAGE_SUFFIX =
        "  \"OnBehalfOfCompID\": \"abc\",\n" +
        "  \"TestReqID\": \"abc\",\n" +
        "  \"IntField\": \"2\",\n" +
        "  \"FloatField\": \"1.1\",\n" +
        "  \"SomeTimeField\": \"19700101-00:00:00.001\"";

    public static final String STRING_NO_OPTIONAL_MESSAGE_EXAMPLE =
        "{\n" +
        "  \"MessageName\": \"Heartbeat\",\n" +
        HEADER_TO_STRING +
        STRING_NO_OPTIONAL_MESSAGE_SUFFIX;

    public static final String STRING_JUST_LONG_FIELD =
        "\"LongField\": \"9223372036854775807\"";

    public static final String STRING_LONG_FIELD_MESSAGE =
        STRING_NO_OPTIONAL_MESSAGE_EXAMPLE +
        ",\n  " + STRING_JUST_LONG_FIELD;

    public static final String COMPONENT_TO_STRING =
        "  \"EgComponent\": {\n" +
        "    \"MessageName\": \"EgComponent\",\n" +
        "    \"ComponentField\": \"2\",\n" +
        "    \"ComponentGroupGroup\": [\n" +
        "    {\n" +
        "      \"MessageName\": \"ComponentGroupGroup\",\n" +
        "      \"ComponentGroupField\": \"1\",\n" +
        "      \"RequiredComponentGroupField\": \"10\",\n" +
        "    },\n" +
        "    {\n" +
        "      \"MessageName\": \"ComponentGroupGroup\",\n" +
        "      \"ComponentGroupField\": \"2\",\n" +
        "      \"RequiredComponentGroupField\": \"20\",\n" +
        "    }\n" +
        "    ],\n" +
        "    \"EgNestedComponent\": {\n" +
        "      \"MessageName\": \"EgNestedComponent\",\n" +
        "    }\n" +
        "  }";

    public static final String ENCODED_MESSAGE =
        "8=FIX.4.4\0019=81\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19700101-00:00:00.001\00110=199\001";

    public static final String ENCODED_MESSAGE_FIXT11 =
        "8=FIXT.1.1\0019=81\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19700101-00:00:00.001\00110=021\001";

    public static final String ENCODED_MESSAGE_LONG_DATA =
        "8=FIXT.1.1\0019=84\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=6\001119=123456\001127=19700101-00:00:00.001\00110=186\001";

    public static final String ENCODED_MESSAGE_WITH_SIGNATURE =
        "8=FIX.4.4\0019=102\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19700101-00:00:00.001\00193=11\00189=Good to go!\00110=079\001";

    public static final String ONLY_TESTREQ_ENCODED_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001112=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=034\001";

    public static final String NO_OPTIONAL_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String INVALID_INT_VALUE_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=A\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String INVALID_FLOAT_VALUE_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=A\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String OUT_OF_RANGE_FLOAT_VALUE_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=10000000000000000000000\001" +
        "127=19700101-00:00:00.001\00110=043\001";

    public static final String MISSING_REQUIRED_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String MISSING_REQUIRED_PRICE_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001116=2\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=1\001137=TOM\00110=043\001";

    public static final String MULTIPLE_ENTRY_REPEATING_GROUP =
        "8=FIX.4.4\0019=91\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=2\001137=TOM\001138=2\001137=ANDREY\001138=13\00110=230\001";

    public static final String MULTIPLE_ENTRY_REPEATING_GROUP_WITHOUT_OPTIONAL =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=2\001138=2\001138=13\00110=043\001";

    public static final String MULTI_ENTRY_NESTED_GROUP_MESSAGE =
        "8=FIX.4.4\0019=77\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\001122=2\001123=1\001123=2\001121=2\001122=2\001123=3\001123=4\00110=063\001";

    public static final String MULTI_ENTRY_EG_GROUP_MESSAGE_WITHOUT_NESTED_GROUPS =
        "8=FIX.4.4\0019=77\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\001121=2\00110=063\001";

    public static final String NO_MISSING_REQUIRED_FIELDS_IN_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=75\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=1\001137=TOM\001138=180\00110=116\001";

    public static final String FIELD_DEFINED_TWICE_IN_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=2\001138=180\001137=TOM\001117=1.1\001138=22\001137=KEVIN\00110=043\001";

    public static final String FIELD_DEFINED_IN_REPEATING_GROUPS_APPEARS_OUTSIDE_OF_IT =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001127=19700101-00:00:00.001" +
        "\001137=TEST\001136=2\001138=180\001137=TOM\001138=22\001137=KEVIN\001117=1.1\00110=043\001";

    public static final String UNKNOWN_FIELD_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001117=1.1\0011000=UNKNOWN FIELD\001116=2" +
        "\001127=19700101-00:00:00.001\00110=161\001";

    public static final String NO_REPEATING_GROUP_IN_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String REPEATING_GROUP_WITH_UNKNOWN_FIELD =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=2\001138=180\0011000=UNKNOWN\001137=TOM\001138=123\001137=Barbara\00110=043\001";

    public static final String CONTAINS_UNKNOWN_REPEATING_GROUP =
        "8=FIX.4.4\0019=0049\00135=Z\0011001=USD\0011002=N\0011003=US" +
        "\001136=2\001138=180\0011000=UNKNOWN\001137=TOM\001138=123\001137=Barbara\00110=043\001";

    public static final String CONTAINS_UNKNOWN_NESTED_REPEATING_GROUP =
        "8=FIX.4.4\0019=71\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\001139=4\001140=Tom\001140=Judd\001140=Zam\001140=Stephen\001121=2\00110=053\001";

    public static final String REPEATING_GROUP_WITH_FIELD_UNKNOWN_TO_MESSAGE_BUT_IN_SPEC =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001136=2\001138=180\001501=UNKNOWN\001137=TOM\001138=123\001137=Barbara\00110=043\001";

    public static final String INVALID_TAG_NUMBER_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\0019999=9999\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00199=9999\00110=161\001";

    public static final String TAG_SPECIFIED_WITHOUT_A_VALUE_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001116=\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String TAG_SPECIFIED_WHERE_INT_VALUE_IS_INCORRECT_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001116=10\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String TAG_SPECIFIED_WHERE_INT_VALUE_IS_LARGE =
        "8=FIX.4.4\0019=54\00135=0\001115=abc\001116=99\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=108\001";

    public static final String TAG_SPECIFIED_WHERE_STRING_VALUE_IS_INCORRECT_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=ZZZZ\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE =
        "35=0\0018=FIX.4.4\0019=0027\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final byte[] TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE_BYTES =
        TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE.getBytes(US_ASCII);

    public static final String TAG_APPEARS_MORE_THAN_ONCE_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001116=2\001116=1\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=161\001";

    public static final String EMPTY_FIX_TAG_FOR_STRING_FIELD_MESSAGE =
        "8=FIX.4.4\0019=81\00135=0\001115=abc\001112=\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19700101-00:00:00.001\00110=199\001";

    public static final String EMPTY_FIX_TAG_FOR_LONG_FIELD_MESSAGE =
        "8=FIX.4.4\0019=81\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=123\001127=19700101-00:00:00.001\0011008=\00110=199\001";

    public static final String DERIVED_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String MULTI_CHAR_VALUE_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001132=a b\001127=19700101-00:00:00.001\00110=225\001";

    public static final String INCORRECT_BOOLEAN_VALUE_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001118=YY\001127=19700101-00:00:00.001\00110=225\001";

    public static final String INCORRECT_SIZE_NO_ENUM_CHAR_VALUE_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001144=ab\001127=19700101-00:00:00.001\00110=225\001";

    public static final String INCORRECT_SIZE_CHAR_VALUE_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001128=ab\001127=19700101-00:00:00.001\00110=225\001";

    public static final String INCORRECT_SIZE_2_CHAR_VALUE_MESSAGE =
        "8=FIX.4.4\0019=61\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001128=z\001127=19700101-00:00:00.001\00110=225\001";

    public static final String MULTI_CHAR_VALUE_NO_ENUM_MESSAGE =
        "8=FIX.4.4\0019=65\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001134=a b z f\001127=19700101-00:00:00.001\00110=007\001";

    public static final String INVALID_MULTI_CHAR_VALUE_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001132=a b c\00110=043\001";

    public static final String INVALID_MULTI_STRING_VALUE_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001133=ab d\00110=043\001";

    public static final String VALID_MULTI_STRING_VALUE_MESSAGE =
        "8=FIX.4.4\0019=63\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001133=ab cd\001127=19700101-00:00:00.001\00110=171\001";

    public static final String MULTI_VALUE_STRING_MESSAGE =
        "8=FIX.4.4\0019=63\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001133=ab cd\001127=19700101-00:00:00.001\00110=171\001";

    public static final String MULTI_STRING_VALUE_MESSAGE =
        "8=FIX.4.4\0019=63\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001135=ab cd\001127=19700101-00:00:00.001\00110=173\001";

    public static final String SHORTER_STRING_MESSAGE =
        "8=FIX.4.4\0019=52\00135=0\001115=ab\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=199\001";

    public static final String REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=71\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\001121=2\00110=053\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_THREE =
        "8=FIX.4.4\0019=71\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=3\001121=1\001121=1\001121=2\00110=053\001";

    public static final String SINGLE_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=65\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=1\001121=2\00110=052\001";

    public static final String ZERO_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=59\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=0\00110=050\001";

    public static final String NO_REPEATING_GROUP_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\00110=043\001";

    public static final String NESTED_GROUP_MESSAGE =
        "8=FIX.4.4\0019=77\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=1\001121=1\001122=1\001123=1\00110=063\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER =
        "8=FIX.4.4\0019=0071\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\0019999=9999\001121=2\00110=149\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_INVALID_TAG_NUMBER_FIELDS_AFTER =
        "8=FIX.4.4\0019=0071\00135=0\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\0019999=9999\001121=2\001115=abc\001116=2\001117=1.1\00110=149\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_MISSING_REQUIRED_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=0027\00135=0\001115=abc\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=2\001121=1\001121=2\00110=161\001";

    public static final String COMPONENT_MESSAGE =
        "8=FIX.4.4\0019=91\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001124=2\001130=2\001131=1\001404=10\001131=2\001404=20\00110=176\001";

    public static final String NESTED_COMPONENT_MESSAGE =
        "8=FIX.4.4\0019=120\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001124=2\001130=2\001131=1\001404=10\001131=2\001404=20\001141=180\001142=2\001143=99\001143=100\001" +
        "10=252\001";

    public static final String MISSING_REQUIRED_FIELD_IN_GROUP_INSIDE_COMPONENT_MESSAGE =
        "8=FIX.4.4\0019=84\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001124=2\001130=2\001131=1\001404=10\001131=2\001141=180\001142=2\001143=99\001143=100\00110=069\001";

    public static final String SHORT_TIMESTAMP_MESSAGE =
        "8=FIX.4.4\0019=53\00135=0\001115=abc\001116=2\001117=1.1" +
        "\001127=19700101-00:00:00.000\00110=042\001";

    public static final String EG_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=0049\00135=Z\0011001=GBP\0011002=XLON\0011003=GB" +
        "\0011004=GBP\0011005=XLON\0011006=GB\0011007=12.34\00110=209\001";

    public static final String EG_NO_OPTIONAL_FIELDS_MESSAGE =
        "8=FIX.4.4\0019=0049\00135=Z\0011001=USD\0011002=N\0011003=US\00110=209\001";

    public static final String EG_HIGH_NUMBER_FIELD_MESSAGE =
        "8=FIX.4.4\0019=0049\00135=Z\0019001=1\0011001=USD\0011002=N\0011003=US\00110=209\001";

    public static final String ET_ALL_FIELDS =
        "8=FIX.4.4\0019=0049\00135=ET\001501=a\001502=10\001503=alpha\001511=c\001512=30\001513=gamma\00110=209\001";

    public static final String ET_ONLY_REQ_FIELDS =
        "8=FIX.4.4\0019=0049\00135=ET\001511=d\001512=40\001513=delta\00110=209\001";

    public static final String ET_ONLY_REQ_FIELDS_WITH_BAD_VALUES =
        "8=FIX.4.4\0019=0049\00135=ET\001511=X\001512=-1\001513=X\00110=209\001";

    public static final String ET_MISSING_REQ_FIELD =
        "8=FIX.4.4\0019=0049\00135=ET\001511=d\001512=40\00110=209\001";

    public static final String RF_ALL_FIELDS =
        "8=FIX.4.4\0019=0057\00135=Z\001700=one\001701=10\001702=b\001703=123.456\001" +
        "704=one\001705=10\001706=b\001707=GBP\001708=20240611-14:35:58.012\00110=209\001";

    public static final String RF_NO_FIELDS =
        "8=FIX.4.4\0019=0049\00135=Z\00110=209\001";

    public static final String SOH_IN_DATA_FIELD_MESSAGE =
        "8=FIX.4.4\0019=81\00135=0\001115=abc\001112=abc\001116=2\001117=1.1" +
        "\001118=Y\001200=3\001119=a\001c\001127=19700101-00:00:00.001\00110=246\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_TOO_LOW_NUMBER_FIELD =
        "8=FIX.4.4\0019=71\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=1\001121=1\001121=2\00110=053\001";

    public static final String REPEATING_GROUP_MESSAGE_WITH_TOO_HIGH_NUMBER_FIELD =
        "8=FIX.4.4\0019=71\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=3\001121=1\001121=2\00110=053\001";

    public static final String NESTED_REPEATING_GROUP_MESSAGE_WITH_TOO_LOW_NUMBER_FIELD =
        "8=FIX.4.4\0019=77\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=1\001121=1\001122=1\001123=1\001123=1\00110=063\001";

    public static final String NESTED_REPEATING_GROUP_MESSAGE_WITH_TOO_HIGH_NUMBER_FIELD =
        "8=FIX.4.4\0019=77\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\001120=1\001121=1\001122=2\001123=1\00110=063\001";

    public static final String NON_EMPTY_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP =
        "8=FIX.4.4\0019=81\00135=OCRG\0012001=1\0012002=555-100-1234\00110=246\001";

    public static final String EMPTY_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP =
        "8=FIX.4.4\0019=81\00135=OCRG\00110=246\001";

    public static final String LONG_FIELD_MESSAGE =
        "8=FIX.4.4\0019=78\00135=0\001115=abc\001116=2\001117=1.1\001127=19700101-00:00:00.001" +
        "\0011008=9223372036854775807\00110=033\001";

    public static final int TEST_REQ_ID_TAG = 112;

    public static final String OTHER_MESSAGE_TYPE = "AB";
    public static final byte[] OTHER_MESSAGE_TYPE_BYTES = OTHER_MESSAGE_TYPE.getBytes(US_ASCII);
    public static final long OTHER_MESSAGE_TYPE_PACKED = MessageTypeEncoding.packMessageType(OTHER_MESSAGE_TYPE);
    public static final int INT_FIELD_TAG = 116;
    public static final int FLOAT_FIELD_TAG = 117;

    private static final String ENUM_TEST_MESSAGE = "EnumTestMessage";
    private static final String ENUM_TEST_MESSAGE_TYPE = "ET";
    static final String DATA_FIELD_LENGTH = "DataFieldLength";

    static
    {
        FIELD_EXAMPLE = buildFieldExample();
        MESSAGE_EXAMPLE = buildMessageExample();
    }

    @SuppressWarnings("MethodLength")
    private static Dictionary buildMessageExample()
    {
        final Map<String, Field> messageEgFields = new HashMap<>();

        final Field beginString = registerField(messageEgFields, 8, "BeginString", Type.STRING);
        final Field bodyLength = registerField(messageEgFields, 9, "BodyLength", INT);
        final Field msgType = registerField(messageEgFields, 35, "MsgType", Type.STRING);
        final Field senderCompID = registerField(messageEgFields, 49, "SenderCompID", Type.STRING);
        final Field targetCompID = registerField(messageEgFields, 56, "TargetCompID", Type.STRING);
        final Field msgSeqNum = registerField(messageEgFields, 34, "MsgSeqNum", Type.SEQNUM);
        final Field senderSubID = registerField(messageEgFields, 50, "SenderSubID", Type.STRING);
        final Field senderLocationID = registerField(messageEgFields, 142, "SenderLocationID", Type.STRING);
        final Field targetSubID = registerField(messageEgFields, 57, "TargetSubID", Type.STRING);
        final Field targetLocationID = registerField(messageEgFields, 143, "TargetLocationID", Type.STRING);
        final Field possDupFlag = registerField(messageEgFields, 43, "PossDupFlag", Type.BOOLEAN);
        final Field possResend = registerField(messageEgFields, 97, "PossResend", Type.BOOLEAN);
        final Field sendingTime = registerField(messageEgFields, 52, "SendingTime", Type.UTCTIMESTAMP);
        final Field origSendingTime = registerField(messageEgFields, 122, "OrigSendingTime", Type.UTCTIMESTAMP);
        final Field lastMsgSeqNumProcessed = registerField(messageEgFields, 369, "LastMsgSeqNumProcessed", Type.SEQNUM);

        final Field signatureLength = registerField(messageEgFields, 93, "SignatureLength", Type.LENGTH);
        final Field signature = registerField(messageEgFields, 89, "Signature", Type.DATA);
        signature.associatedLengthField(signatureLength);
        final Field checkSum = registerField(messageEgFields, 10, "CheckSum", Type.STRING);

        final Field onBehalfOfCompID = registerField(messageEgFields, 115, "OnBehalfOfCompID", Type.STRING)
            .addValue("abc", "abc")
            .addValue("def", "def");

        final Field testReqID = registerField(messageEgFields, TEST_REQ_ID_TAG, "TestReqID", Type.STRING);
        final Field intField = registerField(messageEgFields, INT_FIELD_TAG, "IntField", Type.LENGTH)
            .addValue("1", "ONE")
            .addValue("2", "TWO")
            .addValue("99", "NINETYNINE");

        final Field floatField = registerField(messageEgFields, 117, "FloatField", Type.PRICE);
        final Field booleanField = registerField(messageEgFields, 118, "BooleanField", Type.BOOLEAN);
        final Field dataField = registerField(messageEgFields, 119, "DataField", Type.DATA);
        final Field someTime = registerField(messageEgFields, 127, "SomeTimeField", Type.UTCTIMESTAMP);
        final Field charNoEnumField = registerField(messageEgFields, 144, "CharNoEnumField", Type.CHAR);
        final Field charField = registerField(messageEgFields, 128, "CharField", Type.CHAR)
            .addValue("a", "One")
            .addValue("b", "Two");
        final Field multiCharField = registerField(messageEgFields, 132, "MultiCharField", Type.MULTIPLECHARVALUE)
            .addValue("a", "One")
            .addValue("b", "Two");

        final Field multiValStringField = registerField(messageEgFields, 133, "MultiValueStringField",
            Type.MULTIPLEVALUESTRING)
            .addValue("ab", "One")
            .addValue("cd", "Two");

        final Field multiStringValField = registerField(messageEgFields, 135, "MultiStringValueField",
            Type.MULTIPLEVALUESTRING)
            .addValue("ab", "One")
            .addValue("cd", "Two");

        final Field multiCharFieldNotAnEnum = registerField(messageEgFields, 134, "MultiValueCharNoEnumField",
            Type.MULTIPLECHARVALUE);

        final Field dayOfMonthField = registerField(messageEgFields, 129, "DayOfMonthField", Type.DAYOFMONTH);

        final Group nestedGroup = Group.of(registerField(
            messageEgFields, 122, NO_NESTED_EG_GROUP, INT), messageEgFields);
        nestedGroup.optionalEntry(registerField(messageEgFields, 123, "NestedField", INT));

        final Group egGroup = Group.of(registerField(messageEgFields, 120, NO_EG_GROUP, INT), messageEgFields);
        egGroup.optionalEntry(registerField(messageEgFields, 121, "GroupField", INT));
        egGroup.optionalEntry(nestedGroup);

        final Group groupWithRequiredField = Group.of(registerField(
            messageEgFields, 136, NO_SECOND_EG_GROUP, INT), messageEgFields);
        groupWithRequiredField.optionalEntry(registerField(messageEgFields, 137, "SecondGroupField", STRING));
        groupWithRequiredField.requiredEntry(registerField(messageEgFields, 138, "ThirdGroupField", INT));

        final Group componentGroup = Group.of(registerField(
            messageEgFields, 130, NO_COMPONENT_GROUP, INT), messageEgFields);
        componentGroup.optionalEntry(registerField(messageEgFields, 131, "ComponentGroupField", INT));
        componentGroup.requiredEntry(registerField(messageEgFields, 404, "RequiredComponentGroupField", INT));

        final Group nestedComponentGroup = Group.of(registerField(messageEgFields, 142,
            NO_NESTED_COMPONENT_GROUP, INT), messageEgFields);
        nestedComponentGroup.optionalEntry(registerField(messageEgFields, 143, "NestedComponentGroupField", INT));

        final Component nestedComponent = new Component(EG_NESTED_COMPONENT);
        nestedComponent.optionalEntry(registerField(messageEgFields, 141, "NestedComponentField", INT));
        nestedComponent.optionalEntry(nestedComponentGroup);

        final Component egComponent = new Component(EG_COMPONENT);
        egComponent.optionalEntry(registerField(messageEgFields, 124, "ComponentField", INT));
        egComponent.optionalEntry(componentGroup);
        egComponent.optionalEntry(nestedComponent);

        final Group groupForAdmin = Group.of(registerField(
            messageEgFields, 139, NO_ADMIN_EG_GROUP, INT), messageEgFields);
        groupForAdmin.optionalEntry(registerField(messageEgFields, 140, "AdminFirstGroupField", STRING));

        final Field dataFieldLength = registerField(messageEgFields, 200, DATA_FIELD_LENGTH, Type.LENGTH);
        dataField.associatedLengthField(dataFieldLength);

        final Message heartbeat = new Message("Heartbeat", "0", "admin");
        heartbeat.requiredEntry(onBehalfOfCompID);
        heartbeat.optionalEntry(testReqID);
        heartbeat.requiredEntry(intField);
        heartbeat.requiredEntry(floatField);
        heartbeat.optionalEntry(booleanField);
        heartbeat.optionalEntry(dataFieldLength);
        heartbeat.optionalEntry(dataField);
        heartbeat.optionalEntry(charField);
        heartbeat.optionalEntry(charNoEnumField);
        heartbeat.optionalEntry(multiCharField);
        heartbeat.optionalEntry(multiValStringField);
        heartbeat.optionalEntry(multiStringValField);
        heartbeat.optionalEntry(multiCharFieldNotAnEnum);
        heartbeat.optionalEntry(dayOfMonthField);
        heartbeat.requiredEntry(someTime);
        heartbeat.optionalEntry(egGroup);
        heartbeat.requiredEntry(egComponent);
        heartbeat.optionalEntry(groupWithRequiredField);
        heartbeat.optionalEntry(registerField(messageEgFields, 1008, "LongField", LONG));

        final Component header = new Component("Header");
        header
            .requiredEntry(beginString)
            .requiredEntry(bodyLength)
            .requiredEntry(msgType)
            .optionalEntry(senderCompID)
            .optionalEntry(targetCompID)
            .optionalEntry(msgSeqNum)
            .optionalEntry(senderSubID)
            .optionalEntry(senderLocationID)
            .optionalEntry(targetSubID)
            .optionalEntry(targetLocationID)
            .optionalEntry(possDupFlag)
            .optionalEntry(possResend)
            .optionalEntry(sendingTime)
            .optionalEntry(origSendingTime)
            .optionalEntry(lastMsgSeqNumProcessed);

        final Component trailer = new Component("Trailer");
        trailer.optionalEntry(signatureLength);
        trailer.optionalEntry(signature);
        trailer.requiredEntry(checkSum);

        final Message otherMessage = new Message("OtherMessage", OTHER_MESSAGE_TYPE, "admin");
        otherMessage.optionalEntry(registerField(messageEgFields, 99, "OtherField", INT));

        final Message fieldsMessage = new Message(FIELDS_MESSAGE, "Z", "admin");
        fieldsMessage.requiredEntry(registerField(messageEgFields, 1001, "CurrencyField", CURRENCY));
        fieldsMessage.requiredEntry(registerField(messageEgFields, 1002, "ExchangeField", EXCHANGE));
        fieldsMessage.requiredEntry(registerField(messageEgFields, 1003, "CountryField", COUNTRY));
        fieldsMessage.optionalEntry(registerField(messageEgFields, 1004, "OptionalCurrencyField", CURRENCY));
        fieldsMessage.optionalEntry(registerField(messageEgFields, 1005, "OptionalExchangeField", EXCHANGE));
        fieldsMessage.optionalEntry(registerField(messageEgFields, 1006, "OptionalCountryField", COUNTRY));
        // note: this deliberately breaks the XXXYield pattern here as it tests how we handle a field _called_ yield
        // and its interactions with the incoming "yield" keyword
        fieldsMessage.optionalEntry(registerField(messageEgFields, 1007, "Yield", PERCENTAGE));
        fieldsMessage.optionalEntry(registerField(messageEgFields, 9001, "HighNumberField", INT));
        fieldsMessage.optionalEntry(groupForAdmin);
        fieldsMessage.optionalEntry(nestedComponent);

        final Group entries = Group.of(registerField(
            messageEgFields, 2001, "NoEntries", INT), messageEgFields);
        entries.requiredEntry(registerField(messageEgFields, 2002, "Entry", STRING));

        final Component componentOfRequiredGroup = new Component(EG_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP);
        componentOfRequiredGroup.requiredEntry(entries);

        final Message phoneBookMessage = new Message(OPTIONAL_COMPONENT_REQUIRED_GROUP_MESSAGE, "OCRG", "app");
        phoneBookMessage.optionalEntry(componentOfRequiredGroup);

        final Message lowerCaseMessage = new Message(LOWERCASE_MESSAGE, "LC", "admin");
        lowerCaseMessage.requiredEntry(registerField(messageEgFields, 1001, "CurrencyField", CURRENCY));

        final Message enumTestMessage = new Message(ENUM_TEST_MESSAGE, ENUM_TEST_MESSAGE_TYPE, "app");
        enumTestMessage.optionalEntry(registerField(messageEgFields, 501, "CharEnumOpt", CHAR)
            .addValue("a", "A")
            .addValue("b", "B"));
        enumTestMessage.optionalEntry(registerField(messageEgFields, 502, "IntEnumOpt", INT)
            .addValue("10", "TEN")
            .addValue("20", "TWENTY"));
        enumTestMessage.optionalEntry(registerField(messageEgFields, 503, "stringEnumOpt", STRING)
            .addValue("alpha", "ALPHA")
            .addValue("beta", "BETA"));
        enumTestMessage.requiredEntry(registerField(messageEgFields, 511, "CharEnumReq", CHAR)
            .addValue("c", "C")
            .addValue("d", "D"));
        enumTestMessage.requiredEntry(registerField(messageEgFields, 512, "IntEnumReq", INT)
            .addValue("30", "THIRTY")
            .addValue("40", "FORTY"));
        enumTestMessage.requiredEntry(registerField(messageEgFields, 513, "StringEnumReq", STRING)
            .addValue("gamma", "GAMMA")
            .addValue("delta", "DELTA"));

        final Message allReqFieldTypesMessage = new Message(ALL_REQ_FIELD_TYPES_MESSAGE_NAME,
            ALL_REQ_FIELD_TYPES_MESSAGE_TYPE, "app");
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 700, STRING_RF, STRING));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 701, INT_RF, INT));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 702, CHAR_RF, CHAR));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 703, DECIMAL_RF, FLOAT));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 704, STRING_ENUM_RF, STRING)
            .addValue("one", "ONE").addValue("two", "TWO"));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 705, INT_ENUM_RF, INT)
            .addValue("-1", "NEG_ONE").addValue("10", "TEN"));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 706, CHAR_ENUM_RF, CHAR)
            .addValue("a", "APPLE").addValue("b", "BANANA"));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 707, CURRENCY_ENUM_RF, CURRENCY)
            .addValue("USD", "US_Dollar").addValue("GBP", "Pound"));
        allReqFieldTypesMessage.requiredEntry(registerField(messageEgFields, 708, UTC_TIMESTAMP_RF, UTCTIMESTAMP));

        final Message anyFieldsMessage = new Message(ANY_FIELDS_MESSAGE_NAME, ANY_FIELDS_MESSAGE_TYPE, "app");
        anyFieldsMessage.requiredEntry(registerField(messageEgFields, 5005, "LongField2", LONG));
        anyFieldsMessage.optionalEntry(new AnyFields("TrailingAnyFields"));

        final List<Message> messages = asList(heartbeat, otherMessage, fieldsMessage, lowerCaseMessage,
            allReqFieldTypesMessage, enumTestMessage, phoneBookMessage, anyFieldsMessage);

        final Map<String, Component> components = new HashMap<>();
        components.put(EG_COMPONENT, egComponent);
        components.put(EG_NESTED_COMPONENT, nestedComponent);
        components.put(EG_OPTIONAL_COMPONENT_OF_REQUIRED_GROUP, componentOfRequiredGroup);

        return new Dictionary(messages, messageEgFields, components, header, trailer, "FIX", 4, 4);
    }

    private static Dictionary buildFieldExample()
    {
        final Field egEnum = new Field(123, "EgEnum", CHAR)
            .addValue("a", "AnEntry")
            .addValue("b", "AnotherEntry");

        final Field otherEnum = new Field(124, "OtherEnum", INT)
            .addValue("1", "AnEntry")
            .addValue("12", "AnotherEntry")
            .addValue("99", "ThirdEntry");

        final Field stringEnum = new Field(126, "stringEnum", STRING)
            .addValue("0", "_0")
            .addValue("A", "_A")
            .addValue("AA", "_AAA");

        final Field multiStringValueEnum = new Field(126, "multiStringValueEnum", MULTIPLESTRINGVALUE)
            .addValue("0", "_0")
            .addValue("A", "_A")
            .addValue("AA", "_AAA");

        final Field currencyEnum = new Field(707, "CurrencyEnum", CURRENCY)
            .addValue("USD", "US_Dollar")
            .addValue("GBP", "Pound");

        final Map<String, Field> fieldEgFields = new HashMap<>();
        fieldEgFields.put("EgEnum", egEnum);
        fieldEgFields.put("OtherEnum", otherEnum);
        fieldEgFields.put("stringEnum", stringEnum);
        fieldEgFields.put("multiStringValueEnum", multiStringValueEnum);
        fieldEgFields.put("egNotEnum", new Field(125, "EgNotEnum", Type.CHAR));
        fieldEgFields.put("currencyEnum", currencyEnum);

        return new Dictionary(emptyList(), fieldEgFields, emptyMap(), null, null, "FIX", 4, 4);
    }
}
