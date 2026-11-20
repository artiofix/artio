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

import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.dictionary.ir.Field.Value;

import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.INT;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.STRING;
import static uk.co.real_logic.artio.util.CustomMatchers.hasFluentProperty;

public class DictionaryParserTest
{
    private static final String EXAMPLE_FILE = "example_dictionary.xml";

    private static Dictionary dictionary;

    @BeforeAll
    public static void setUp() throws Exception
    {
        dictionary = parseExample();
    }

    @Test
    public void shouldParseVersionNumbers()
    {
        assertEquals("FIXR", dictionary.specType());
        assertEquals(7, dictionary.majorVersion());
        assertEquals(2, dictionary.minorVersion());
    }

    @Test
    public void shouldParseExampleDictionary()
    {
        assertNotNull(dictionary, "Missing dictionary");
        assertNotNull(dictionary.messages(), "Missing messages");
        assertNotNull(dictionary.fields(), "Missing fields");
        assertNotNull(dictionary.components(), "Missing components");
    }

    @Test
    public void shouldParseSimpleField()
    {
        final Field bodyLength = field("BodyLength");

        assertNotNull(bodyLength, "Hasn't found BodyLength");
        assertEquals("BodyLength", bodyLength.name());
        assertEquals(9, bodyLength.number());
        assertEquals(Type.INT, bodyLength.type());

        assertEquals(Type.LONG, field("ExampleLongField").type());
    }

    @Test
    public void shouldParseTestReqID()
    {
        final Field field = field("TestReqID");
        assertEquals("TestReqID", field.name());
        assertEquals(112, field.number());
        assertFalse(field.isEnum());
        assertEquals(STRING, field.type());
    }

    @Test
    public void shouldParseAllFields()
    {
        assertEquals(50, dictionary.fields().size());
    }

    @Test
    public void shouldParseEnumField()
    {
        final Field msgType = field("MsgType");

        assertNotNull(msgType, "Hasn't found MsgType");
        assertEquals("MsgType", msgType.name());
        assertEquals(35, msgType.number());
        assertEquals(STRING, msgType.type());

        final List<Value> values = msgType.values();

        assertEquals(new Value("0", "HEARTBEAT"), values.get(0));
        assertEquals(new Value("8", "EXECUTION_REPORT"), values.get(1));
        assertEquals(new Value("D", "ORDER_SINGLE"), values.get(2));
    }

    @Test
    public void shouldParseEnumFieldWhereDescriptionIsNotAValidJavaIdentifierAtStart()
    {
        final Field msgType = field("ClearingFeeIndicator");

        assertNotNull(msgType, "Hasn't found ClearingFeeIndicator");
        assertEquals("ClearingFeeIndicator", msgType.name());
        assertEquals(635, msgType.number());
        assertEquals(STRING, msgType.type());

        final List<Value> values = msgType.values();

        assertEquals(new Value("H", "_106H_AND_106J_FIRMS"), values.get(4));
        assertEquals(new Value("1", "_1ST_YEAR_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(8));
        assertEquals(new Value("2", "_2ND_YEAR_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(9));
        assertEquals(new Value("3", "_3RD_YEAR_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(10));
        assertEquals(new Value("4", "_4TH_YEAR_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(11));
        assertEquals(new Value("5", "_5TH_YEAR_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(12));
        assertEquals(new Value("9", "_6TH_YEAR_AND_BEYOND_DELEGATE_TRADING_FOR_HIS_OWN_ACCOUNT"), values.get(13));
    }

    @Test
    public void shouldParseEnumFieldWhereDescriptionIsNotAValidJavaIdentifierInMiddle()
    {
        final Field msgType = field("TrdRegPublicationType");

        assertNotNull(msgType, "Hasn't found TrdRegPublicationType");
        assertEquals("TrdRegPublicationType", msgType.name());
        assertEquals(2669, msgType.number());
        assertEquals(INT, msgType.type());

        final List<Value> values = msgType.values();

        assertEquals(new Value("0", "Pre_trade_transparency_waiver"), values.get(0));
        assertEquals(new Value("1", "Post_trade_deferral"), values.get(1));
    }

    @Test
    public void shouldParseAllEnum()
    {
        assertEquals(12, countEnumFields());
    }

    @Test
    public void shouldParseSimpleMessage()
    {
        final Message heartbeat = dictionary.messages().get(0);

        assertEquals("Heartbeat", heartbeat.name());
        assertEquals('0', heartbeat.packedType());
        assertEquals("admin", heartbeat.category());

        final Entry entry = heartbeat.entries().get(0);
        assertThat(entry, isRequiredField("TestReqID", false));
    }

    @Test
    public void shouldParseAllMessages()
    {
        assertEquals(4, dictionary.messages().size());
    }

    @Test
    public void shouldPrependNumInGroupWithNoForFix44RepeatingGroup()
    {
        final Field field = field("LinesOfText");
        assertThat(field.name(), is("NoLinesOfText"));
        final Message newsMessage = dictionary
            .messages()
            .stream()
            .filter((m) -> m.name().equals("News"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Did not find news message"));
        final Entry linesOfText = newsMessage.entries().get(0);
        assertTrue(linesOfText.isGroup());
        assertThat(((Group)linesOfText.element()).numberField().name(), is("NoLinesOfTextGroupCounter"));

    }

    @Test
    public void shouldHaveHeader()
    {
        final List<Entry> entries = dictionary.header().entries();

        assertThat(entries, hasSize(3));
        assertThat(entries, hasItem(isRequiredField("BeginString", true)));
        assertThat(entries, hasItem(isRequiredField("BodyLength", true)));
        assertThat(entries, hasItem(isRequiredField("MsgType", true)));
    }

    @Test
    public void shouldHaveTrailer()
    {
        final List<Entry> entries = dictionary.trailer().entries();

        assertThat(entries, hasItems(isRequiredField("CheckSum", true)));
    }

    @Test
    public void shouldParseGroups()
    {
        final Message newOrderSingle = newOrderSingle();
        final List<Entry> entries = newOrderSingle.entries();

        assertThat(entries, hasItem(
            withElement(isGroup("TradingSessionsGroup",
            withEntries(hasItems(isField("TradingSessionID")))))));
    }

    @Test
    public void shouldParseComponents()
    {
        final Message newOrderSingle = newOrderSingle();

        assertThat(newOrderSingle.entries(),
            hasItem(isRequiredComponent("Members")));
    }

    @Test
    public void shouldParseNestedGroups()
    {
        final Component members = component("Members");
        final Group noMemberIDs = (Group)members.entries().get(0).element();

        final Entry noMemberSubIDs = noMemberIDs.entries().get(1);
        assertFalse(noMemberSubIDs.required());
        assertThat(noMemberSubIDs.element(), instanceOf(Group.class));
    }

    @Test
    public void shouldParseAnyFieldsEntries()
    {
        final Message executionReport = message("ExecutionReport");
        final List<Entry> anyFieldsEntries = executionReport.anyFieldsEntries().collect(toList());
        assertThat(anyFieldsEntries, hasSize(1));
        assertEquals("TrailingAnyFields", anyFieldsEntries.get(0).name());
    }

    @Test
    public void shouldDedupeFieldsIncludedTwice()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_fields_included_twice.xml"),
            IllegalStateException.class,
            "Cannot have the same field defined more than once on a message; this is against the FIX spec. " +
            "Details to follow:\n" +
            "Message: DedupeFieldsTest Field : MemberSubID (104)\n"
        );
    }

    @Test
    public void shouldDedupeFieldsIncludedTwiceTurnOn() throws Exception
    {
        shouldThrow(() -> parseDictionary("example_duplicate_dictionary.xml"),
            IllegalStateException.class,
            "Cannot have the same field defined more than once on a message; this is against the FIX spec. " +
            "Details to follow:\n" +
            "Message: DuplicatedFieldMessage Field : MemberID (100) Through Path: [Members, MemberIDsGroup]\n"
        );
        parseDictionary("example_duplicate_dictionary.xml", true);
    }

    @Test
    public void shouldFailIfTwoFieldsAppearInSameMessage()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_field_in_group_and_message.xml"),
            IllegalStateException.class,
            "Cannot have the same field defined more than once on a message; this is against the FIX spec. " +
            "Details to follow:\n" +
            "Message: PoorlyDefinedMessage Field : MemberSubID (104) Through Path: [Members, NextComponent]\n" +
            "Message: PoorlyDefinedMessage Field : MemberSubID (104) Through Path: [NextComponent]\n");
    }

    @Test
    public void shouldFailIfFieldDefinitionDuplicated()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_duplicate_field_definitions.xml"),
            IllegalStateException.class,
            "Cannot have the same field name defined twice; this is against the FIX spec." +
            "Details to follow:\n" +
            "Field : MemberID (101)\n" +
            "Field : MemberID (100)");
    }

    @Test
    public void shouldNotAllowDataFieldWithoutLength()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_data_field_without_length.xml"),
            IllegalStateException.class,
            "Each DATA field must have a corresponding LENGTH field using the suffix 'Len' or 'Length'." +
            " RawData is missing a length field in EgGroupGroup");
    }

    @Test
    public void shouldNotAllowMultipleAnyFieldsEntries()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_multiple_any_fields_entries.xml"),
            IllegalStateException.class,
            "At most one anyFields entry is allowed," +
            " but MultipleAnyFieldsEntriesTest contains multiple: [PrecedingAnyFields, TrailingAnyFields]");
    }

    @Test
    public void shouldNameTheAttributeWhenANumberIsInvalid()
    {
        shouldThrow(() -> parseDictionary("example_invalid_dictionary_non_numeric_field_number.xml"),
            IllegalStateException.class,
            "Invalid integer for: number in {name=MemberID,number=abc,type=STRING}");
    }

    private Component component(final String name)
    {
        return dictionary.components().get(name);
    }

    private Message newOrderSingle()
    {
        return dictionary.messages().get(1);
    }

    private long countEnumFields()
    {
        return dictionary
            .fields()
            .values()
            .stream()
            .filter(Field::isEnum)
            .count();
    }

    private Message message(final String name)
    {
        return dictionary.messages().stream()
            .filter(message -> message.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Message " + name + " not found"));
    }

    private Field field(final String name)
    {
        return dictionary.fields().get(name);
    }

    private static Dictionary parseExample() throws Exception
    {
        return parseDictionary(EXAMPLE_FILE);
    }

    private static Dictionary parseDictionary(final String name) throws Exception
    {
        return parseDictionary(name, false);
    }

    private static Dictionary parseDictionary(final String name, final boolean allowDuplicates) throws Exception
    {
        return new DictionaryParser(allowDuplicates)
                .parse(DictionaryParserTest.class.getResourceAsStream(name), null);
    }

    private <T> Matcher<T> withElement(final Matcher<?> valueMatcher)
    {
        return hasFluentProperty("element", valueMatcher);
    }

    private <T> Matcher<T> withName(final Matcher<?> valueMatcher)
    {
        return hasFluentProperty("name", valueMatcher);
    }

    private <T> Matcher<T> isRequired(final boolean required)
    {
        return hasFluentProperty("required", equalTo(required));
    }

    private <T> Matcher<T> withEntries(final Matcher<?> valueMatcher)
    {
        return hasFluentProperty("entries", valueMatcher);
    }

    private <T> Matcher<T> isField(final String name)
    {
        return withElement(equalTo(field(name)));
    }

    private <T> Matcher<T> isRequiredField(final String name, final boolean required)
    {
        return allOf(isRequired(required), isField(name));
    }

    private <T> Matcher<T> isComponent(final String name)
    {
        return withElement(equalTo(component(name)));
    }

    private <T> Matcher<T> isRequiredComponent(final String name)
    {
        return allOf(isRequired(false), isComponent(name));
    }

    private <T> Matcher<T> isGroup(final String name, final Matcher<T> valueMatcher)
    {
        return allOf(instanceOf(Group.class), withName(equalTo(name)), valueMatcher);
    }

    private static <T extends Exception> void shouldThrow(
        final ThrowingRunnable runnable,
        final Class<T> expectedException,
        final String message)
    {
        try
        {
            runnable.run();
            fail("Expected exception " + expectedException + " with message '" + message + "' but nothing was thrown");
        }
        catch (final Exception e)
        {
            try
            {
                assertThat(e.getClass(), typeCompatibleWith(expectedException));
                assertThat(e.getMessage(), containsString(message));
            }
            catch (final AssertionError error)
            {
                e.printStackTrace();
                throw error;
            }
        }
    }

    interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
