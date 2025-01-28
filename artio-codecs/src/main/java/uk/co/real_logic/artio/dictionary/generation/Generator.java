/*
 * Copyright 2015-2024 Real Logic Limited., Monotonic Ltd.
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
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.generation.OutputManager;
import uk.co.real_logic.artio.EncodingException;
import uk.co.real_logic.artio.dictionary.CharArraySet;
import uk.co.real_logic.artio.dictionary.CharArrayWrapper;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.Entry.Element;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.LocalMktDateEncoder;
import uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static java.util.regex.Pattern.MULTILINE;
import static java.util.stream.Collectors.joining;
import static uk.co.real_logic.artio.dictionary.generation.AggregateType.*;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.importFor;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.importStaticFor;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatPropertyName;

public abstract class Generator
{
    public static final String MSG_TYPE = "MsgType";
    public static final String BEGIN_STRING = "BeginString";
    public static final String BODY_LENGTH = "BodyLength";

    public static final String CODEC_VALIDATION_ENABLED = "CODEC_VALIDATION_ENABLED";
    public static final String CODEC_REJECT_UNKNOWN_FIELD_ENABLED = "CODEC_REJECT_UNKNOWN_FIELD_ENABLED";
    public static final String RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY = "CODEC_REJECT_UNKNOWN_ENUM_VALUE_ENABLED";
    public static final Pattern NEWLINE = Pattern.compile("^", MULTILINE);
    public static final String MESSAGE_FIELDS = "messageFields";

    protected String commonCompoundImports(final String form, final boolean headerWrapsTrailer,
        final String messageFieldsSet)
    {
        final String headerParameter = headerWrapsTrailer ? "trailer" : "";
        return String.format(
            "%3$s" +
            "    private final Trailer%1$s trailer = new Trailer%1$s();\n\n" +
            "    public Trailer%1$s trailer()\n" +
            "    {\n" +
            "        return trailer;\n" +
            "    }\n\n" +

            "    private final Header%1$s header = new Header%1$s(%2$s);\n\n" +
            "    public Header%1$s header()\n" +
            "    {\n" +
            "        return header;\n" +
            "    }\n\n",
            form,
            headerParameter,
            messageFieldsSet);
    }

    private static final String COMMON_COMPOUND_IMPORTS =
        "import %1$s.Header%2$s;\n" +
        "import %1$s.Trailer%2$s;\n";

    protected final Dictionary dictionary;
    protected final String thisPackage;
    private final String commonPackage;
    protected final OutputManager outputManager;
    protected final Class<?> validationClass;
    protected final Class<?> rejectUnknownFieldClass;
    private final Class<?> rejectUnknownEnumValueClass;
    protected final boolean flyweightsEnabled;
    protected final String codecRejectUnknownEnumValueEnabled;
    protected final String scope;
    protected final String decimalFloatOverflowHandler;
    protected final boolean fixTagsInJavadoc;

    protected final Deque<Aggregate> aggregateStack = new ArrayDeque<>();

    protected Generator(
        final Dictionary dictionary,
        final String thisPackage,
        final String commonPackage,
        final OutputManager outputManager,
        final Class<?> validationClass,
        final Class<?> rejectUnknownFieldClass,
        final Class<?> rejectUnknownEnumValueClass,
        final boolean flyweightsEnabled,
        final String codecRejectUnknownEnumValueEnabled,
        final boolean fixTagsInJavadoc,
        final String decimalFloatOverflowHandler)
    {
        this.dictionary = dictionary;
        this.thisPackage = thisPackage;
        this.commonPackage = commonPackage;
        this.outputManager = outputManager;
        this.validationClass = validationClass;
        this.rejectUnknownFieldClass = rejectUnknownFieldClass;
        this.rejectUnknownEnumValueClass = rejectUnknownEnumValueClass;
        this.flyweightsEnabled = flyweightsEnabled;
        this.codecRejectUnknownEnumValueEnabled = codecRejectUnknownEnumValueEnabled;
        this.fixTagsInJavadoc = fixTagsInJavadoc;

        scope = dictionary.shared() ? "protected" : "private";
        this.decimalFloatOverflowHandler = decimalFloatOverflowHandler;
    }

    public void generate()
    {
        generateAggregateFile(dictionary.header(), AggregateType.HEADER);
        generateAggregateFile(dictionary.trailer(), AggregateType.TRAILER);
        dictionary.components().forEach((name, component) -> generateAggregateFile(component, COMPONENT));
        dictionary.messages().forEach((msg) -> generateAggregateFile(msg, MESSAGE));
    }

    protected abstract void generateAggregateFile(Aggregate aggregate, AggregateType type);

    protected abstract Class<?> topType(AggregateType aggregateType);

    protected void generateImports(
        final String compoundSuffix,
        final AggregateType type,
        final Writer out,
        final Class<?>... extraImports) throws IOException
    {
        out
            .append(importFor(MutableDirectBuffer.class))
            .append(importFor(AsciiSequenceView.class))
            .append(importStaticFor(CodecUtil.class))
            .append(importStaticFor(SessionConstants.class))
            .append(importFor(topType(MESSAGE)));

        if (topType(GROUP) != topType(MESSAGE))
        {
            out.append(importFor(topType(GROUP)));
        }

        out .append(type == MESSAGE ? String.format(COMMON_COMPOUND_IMPORTS, thisPackage, compoundSuffix) : "")
            .append(importFor(ReadOnlyDecimalFloat.class))
            .append(importFor(DecimalFloat.class))
            .append(importFor(MutableAsciiBuffer.class))
            .append(importFor(AsciiBuffer.class))
            .append(importFor(LocalMktDateEncoder.class))
            .append(importFor(UtcTimestampEncoder.class))
            .append(importFor(StandardCharsets.class))
            .append(importFor(Arrays.class))
            .append(importFor(CharArraySet.class))
            .append(importFor(IntHashSet.class))
            .append(importFor(IntHashSet.IntIterator.class))
            .append(importFor(EncodingException.class))
            .append(importFor(CharArrayWrapper.class));

        for (final Class<?> extraImport : extraImports)
        {
            out.append(importFor(extraImport));
        }

        out .append(importStaticFor(StandardCharsets.class, "US_ASCII"))
            .append(importStaticFor(validationClass, CODEC_VALIDATION_ENABLED))
            .append(importStaticFor(rejectUnknownFieldClass, CODEC_REJECT_UNKNOWN_FIELD_ENABLED))
            .append(importStaticFor(rejectUnknownEnumValueClass, RUNTIME_REJECT_UNKNOWN_ENUM_VALUE_PROPERTY));

        if (!thisPackage.equals(commonPackage) && !commonPackage.isEmpty())
        {
            out.append(importFor(commonPackage + ".*"));
        }

        if (hasParent())
        {
            out.append(importFor(parentDictCommonPackage() + ".*"));
        }
    }

    protected String completeResetMethod(
        final boolean isMessage,
        final List<Entry> entries,
        final String additionalReset,
        final boolean isInParent)
    {
        final StringBuilder methods = new StringBuilder();

        final String resetEntries = resetEntries(entries, methods);

        if (isMessage)
        {
            final String reset = isSharedParent() ? "" : String.format(
                "    public void reset()\n" +
                "    {\n" +
                "        header.reset();\n" +
                "        trailer.reset();\n" +
                "        resetMessage();\n" +
                "%1$s" +
                "    }\n\n",
                additionalReset);

            final String resetParent = isInParent ? "        super.resetMessage();\n" : "";
            return String.format(
                "%1$s" +
                "    public void resetMessage()\n" +
                "    {\n" +
                "%4$s" +
                "%2$s" +
                "    }\n\n" +
                "%3$s",
                reset,
                resetEntries,
                methods,
                resetParent);
        }
        else
        {
            final String resetParent = isInParent ? "        super.reset();\n" : "";
            return String.format(
                "    public void reset()\n" +
                "    {\n" +
                "%4$s" +
                "%1$s" +
                "%2$s" +
                "    }\n\n" +
                "%3$s",
                resetEntries,
                isSharedParent() ? "" : additionalReset,
                methods,
                resetParent);
        }
    }

    protected String resetEntries(final List<Entry> entries, final StringBuilder methods)
    {
        return resetFields(entries, methods) +
            resetComponents(entries, methods) +
            resetGroups(entries, methods) +
            resetAnyFields(entries, methods);
    }

    private String resetFields(final List<Entry> entries, final StringBuilder methods)
    {
        return resetAllBy(
            entries,
            methods,
            entry -> entry.isField() && !entry.isInParent(),
            (entry) -> resetField(entry.required(), (Field)entry.element()),
            this::callResetMethod);
    }

    protected String resetAllBy(
        final List<Entry> entries,
        final StringBuilder methods,
        final Predicate<Entry> predicate,
        final Function<Entry, String> methodFactory,
        final Function<Entry, String> callFactory)
    {
        methods.append(entries
            .stream()
            .filter(predicate)
            .map(methodFactory)
            .collect(joining()));

        return entries
            .stream()
            .filter(predicate)
            .map(callFactory)
            .collect(joining());
    }

    private String resetGroups(final List<Entry> entries, final StringBuilder methods)
    {
        return resetAllBy(
            entries,
            methods,
            Entry::isGroup,
            this::resetGroup,
            this::callResetMethod);
    }

    protected abstract String resetGroup(Entry entry);

    protected abstract String resetAnyFields(List<Entry> entries, StringBuilder methods);

    private String resetField(final boolean isRequired, final Field field)
    {
        final String name = field.name();

        if (isNotResettableField(name))
        {
            return "";
        }

        if (!isRequired)
        {
            return optionalReset(field, name);
        }

        switch (field.type())
        {
            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return resetRequiredInt(field);

            case LONG:
                return resetRequiredLong(field);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return resetRequiredFloat(name);

            case CHAR:
                return resetFieldValue(field, "MISSING_CHAR");

            case DATA:
            case XMLDATA:
                return resetFieldValue(field, "null");

            case BOOLEAN:
                return resetFieldValue(field, "false");

            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return resetStringBasedData(name);

            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return resetTemporalValue(name);

            default:
                throw new IllegalArgumentException("Unknown type: " + field.type());
        }
    }

    protected abstract String resetRequiredInt(Field field);

    protected abstract String resetRequiredLong(Field field);

    protected abstract String optionalReset(Field field, String name);

    protected abstract String resetTemporalValue(String name);

    protected abstract String resetComponents(List<Entry> entries, StringBuilder methods);

    protected abstract String resetStringBasedData(String name);

    protected String nameOfResetMethod(final String name)
    {
        return "reset" + name;
    }

    private String callResetMethod(final Entry entry)
    {
        if (isNotResettableField(entry.name()))
        {
            return "";
        }

        return String.format(
            "        this.%1$s();\n",
            nameOfResetMethod(entry.name()));
    }

    protected String hasField(final Entry entry)
    {
        final String name = entry.name();
        return entry.required() ?
            "" :
            String.format("    %2$s boolean has%1$s;\n\n", name, scope);
    }

    protected String resetNothing(final String name)
    {
        return String.format(
            "    public void %1$s()\n" +
            "    {\n" +
            "    }\n\n",
            nameOfResetMethod(name));
    }

    private boolean isNotResettableField(final String name)
    {
        return isDerivedField(name) || isPreCalculatedField(name);
    }

    private boolean isPreCalculatedField(final String name)
    {
        return "MessageName".equals(name) || BEGIN_STRING.equals(name) || MSG_TYPE.equals(name);
    }

    private boolean isDerivedField(final String name)
    {
        return isBodyLength(name) || isCheckSum(name);
    }

    protected abstract String resetRequiredFloat(String name);

    protected abstract String resetLength(String name);

    protected String resetByFlag(final String name)
    {
        return String.format(
            "    public void %2$s()\n" +
            "    {\n" +
            "        has%1$s = false;\n" +
            "    }\n\n",
            name,
            nameOfResetMethod(name));
    }

    protected String resetFieldValue(final Field field, final String resetValue)
    {
        final String name = field.name();
        final boolean hasLengthField = field.type().hasLengthField(flyweightsEnabled);
        final String lengthReset = hasLengthField ? "        %2$sLength = 0;\n" : "";

        return String.format(
            "    public void %1$s()\n" +
            "    {\n" +
            lengthReset +
            "        %2$s = %3$s;\n" +
            "    }\n\n",
            nameOfResetMethod(name),
            formatPropertyName(name),
            resetValue);
    }

    protected String generateAppendTo(final Aggregate aggregate, final boolean hasCommonCompounds)
    {
        final String entriesToString = aggregate
            .entries()
            .stream()
            .map(this::generateEntryAppendTo)
            .collect(joining("\n"));

        final String prefix;
        if (hasCommonCompounds && !isSharedParent())
        {
            prefix =
                "        builder.append(\"  \\\"header\\\": \");\n" +
                "        header.appendTo(builder, level + 1);\n" +
                "        builder.append(\"\\n\");\n";
        }
        else
        {
            prefix = "";
        }

        return String.format(
            "    public String toString()\n" +
            "    {\n" +
            "        return appendTo(new StringBuilder()).toString();\n" +
            "    }\n\n" +
            "    public StringBuilder appendTo(final StringBuilder builder)\n" +
            "    {\n" +
            "        return appendTo(builder, 1);\n" +
            "    }\n\n" +
            "    public StringBuilder appendTo(final StringBuilder builder, final int level)\n" +
            "    {\n" +
            "        builder.append(\"{\\n\");" +
            "        indent(builder, level);\n" +
            "        builder.append(\"\\\"MessageName\\\": \\\"%1$s\\\",\\n\");\n" +
            "%2$s" +
            "%3$s" +
            "        indent(builder, level - 1);\n" +
            "        builder.append(\"}\");\n" +
            "        return builder;\n" +
            "    }\n\n",
            aggregate.name(),
            prefix,
            entriesToString);
    }

    protected String generateEntryAppendTo(final Entry entry)
    {
        //"  \"OnBehalfOfCompID\": \"abc\",\n" +

        if (isBodyLength(entry))
        {
            return "";
        }

        final Element element = entry.element();
        final String name = entry.name();
        if (element instanceof Field)
        {
            final Field field = (Field)element;
            final String value = fieldAppendTo(field);

            final String fieldAppender = String.format(
                "        indent(builder, level);\n" +
                "        builder.append(\"\\\"%1$s\\\": \\\"\");\n" +
                "        %2$s;\n" +
                "        builder.append(\"\\\",\\n\");\n",
                name,
                value);

            if (appendToChecksHasGetter(entry, field))
            {
                final String indentedFieldAppender = NEWLINE
                    .matcher(fieldAppender)
                    .replaceAll("    ");
                return String.format(
                    "        if (has%1$s())\n" +
                        "        {\n" +
                        "%2$s" +
                        "        }\n",
                    name,
                    indentedFieldAppender);
            }
            else
            {
                return fieldAppender;
            }
        }
        else if (element instanceof Group)
        {
            return groupEntryAppendTo((Group)element, name);
        }
        else if (element instanceof Component)
        {
            return componentAppendTo((Component)element);
        }
        else if (element instanceof AnyFields)
        {
            return anyFieldsAppendTo((AnyFields)element);
        }

        return "";
    }

    protected abstract boolean appendToChecksHasGetter(Entry entry, Field field);

    protected abstract String groupEntryAppendTo(Group element, String name);

    protected abstract String anyFieldsAppendTo(AnyFields element);

    protected abstract boolean hasFlag(Entry entry, Field field);

    protected String hasGetter(final String name)
    {
        return String.format(
            "    public boolean has%s()\n" +
            "    {\n" +
            "        return has%1$s;\n" +
            "    }\n\n",
            name);
    }

    protected abstract String componentAppendTo(Component component);

    protected String fieldAppendTo(final Field field)
    {
        final String fieldName = formatPropertyName(field.name());
        switch (field.type())
        {
            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return stringAppendTo(fieldName);

            // Call the getter for other choices in order to ensure that the flyweight version is populated
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return timeAppendTo(fieldName);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                if (flyweightsEnabled)
                {
                    return String.format("this.%1$s().appendTo(builder)", fieldName);
                }

                return String.format("%1$s.appendTo(builder)", fieldName);

            case DATA:
            case XMLDATA:
                return dataAppendTo(field, fieldName);

            default:
                if (flyweightsEnabled)
                {
                    return String.format("builder.append(this.%1$s())", fieldName);
                }

                return String.format("builder.append(%1$s)", fieldName);
        }
    }

    protected abstract String timeAppendTo(String fieldName);

    protected abstract String dataAppendTo(Field field, String fieldName);

    protected boolean isCheckSum(final Entry entry)
    {
        return entry != null && isCheckSum(entry.name());
    }

    private boolean isCheckSum(final String name)
    {
        return "CheckSum".equals(name);
    }

    protected boolean isBodyLength(final Entry entry)
    {
        return entry != null && isBodyLength(entry.name());
    }

    protected boolean isBeginString(final Entry entry)
    {
        return entry != null && BEGIN_STRING.equals(entry.name());
    }

    protected boolean isBodyLength(final String name)
    {
        return BODY_LENGTH.equals(name);
    }

    void generateOptionalSessionFieldsSupportedMethods(
        final List<String> optionalFields, final Set<String> missingOptionalFields, final Writer out)
        throws IOException
    {
        if (optionalFields != null)
        {
            for (final String optionalField : optionalFields)
            {
                final boolean inDictionary = !missingOptionalFields.contains(optionalField);

                out.append(String.format(
                    "    public boolean supports%1$s()\n" +
                    "    {\n" +
                    "        return %2$s;\n" +
                    "    }\n\n",
                    optionalField,
                    inDictionary));
            }
        }
    }

    boolean isSharedParent()
    {
        return dictionary.shared();
    }

    boolean hasParent()
    {
        return dictionary.hasSharedParent();
    }

    String parentDictPackage()
    {
        return toParentDictPackage(thisPackage);
    }

    String parentDictCommonPackage()
    {
        return toParentDictPackage(commonPackage);
    }

    private String toParentDictPackage(final String whichPackage)
    {
        return whichPackage.replace("." + dictionary.name(), "");
    }

    protected abstract String stringAppendTo(String fieldName);

    protected String indent(final int times, final String suffix)
    {
        final StringBuilder sb = new StringBuilder(times * 4 + suffix.length());
        IntStream.range(0, times).forEach((ignore) -> sb.append("    "));
        sb.append(suffix);

        return sb.toString();
    }

    boolean shouldGenerateClassEnumMethods(final Field field)
    {
        return EnumGenerator.hasEnumGenerated(field) && !field.type().isMultiValue() &&
            !field.hasSharedSometimesEnumClash();
    }

    Aggregate currentAggregate()
    {
        return aggregateStack.peekLast();
    }

    Aggregate parentAggregate()
    {
        final Aggregate current = aggregateStack.removeLast();
        final Aggregate parent = aggregateStack.peekLast();
        push(current);
        return parent;
    }

    void pop()
    {
        aggregateStack.removeLast();
    }

    void push(final Aggregate aggregate)
    {
        aggregateStack.addLast(aggregate);
    }

    String qualifiedAggregateStackNames(final Function<Aggregate, String> toName)
    {
        return aggregateStack.stream().map(toName).collect(joining("."));
    }

    protected String generateAccessorJavadoc(final Field field)
    {
        if (fixTagsInJavadoc)
        {
            return "/* " + field.name() + " = " + field.number() + " */\n    ";
        }
        else
        {
            return "";
        }
    }
}
