/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.generation.OutputManager;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.FieldBagEncoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.dictionary.Generated;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.Entry.Element;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.util.Collections.*;
import static java.util.stream.Collectors.joining;
import static uk.co.real_logic.artio.dictionary.generation.AggregateType.GROUP;
import static uk.co.real_logic.artio.dictionary.generation.AggregateType.HEADER;
import static uk.co.real_logic.artio.dictionary.generation.EnumGenerator.enumName;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.GENERATED_ANNOTATION;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.SUPPRESS_THIS_ESCAPE_ANNOTATION;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.fileHeader;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.importFor;
import static uk.co.real_logic.artio.dictionary.generation.OptionalSessionFields.ENCODER_OPTIONAL_SESSION_FIELDS;
import static uk.co.real_logic.artio.dictionary.generation.OptionalSessionFields.OPTIONAL_FIELD_TYPES;
import static uk.co.real_logic.artio.util.MutableAsciiBuffer.LONGEST_INT_LENGTH;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatClassName;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatPropertyName;

class EncoderGenerator extends Generator
{
    private static final Set<String> USED_SESSION_CODECS = new HashSet<>(Arrays.asList(
        "LogonEncoder",
        "ResendRequestEncoder",
        "LogoutEncoder",
        "HeartbeatEncoder",
        "RejectEncoder",
        "TestRequestEncoder",
        "SequenceResetEncoder",
        "BusinessMessageRejectEncoder"));

    private static final String TRAILER_ENCODE_PREFIX =
        "    long finishMessage(final MutableAsciiBuffer buffer, final int messageStart, final int offset)\n" +
        "    {\n" +
        "        int position = offset;\n" +
        "\n" +
        "        final int checkSum = buffer.computeChecksum(messageStart, position);\n" +
        "        buffer.putBytes(position, checkSumHeader, 0, checkSumHeaderLength);\n" +
        "        position += checkSumHeaderLength;\n" +
        "        buffer.putNaturalPaddedIntAscii(position, 3, checkSum);\n" +
        "        position += 3;\n" +
        "        buffer.putSeparator(position);\n" +
        "        position++;\n" +
        "\n" +
        "        return Encoder.result(position - messageStart, messageStart);\n" +
        "    }" +
        "\n" +
        "    int startTrailer(final MutableAsciiBuffer buffer, final int offset)\n" +
        "    {\n" +
        "        final int start = offset;\n" +
        "        int position = start;\n" +
        "\n";

    // returns offset where message starts
    private static final String HEADER_ENCODE_PREFIX =
        "    int finishHeader(final MutableAsciiBuffer buffer, final int bodyStart, final int bodyLength)\n" +
        "    {\n" +
        "        int position = bodyStart - 1;\n" +
        "\n" +
        "        buffer.putSeparator(position);\n" +
        "        position = buffer.putNaturalIntAsciiFromEnd(bodyLength, position);\n" +
        "        position -= bodyLengthHeaderLength;\n" +
        "        buffer.putBytes(position, bodyLengthHeader, 0, bodyLengthHeaderLength);\n" +
        "\n" +
        "        if (beginStringLength > 0) {\n" +
        "        position--;\n" +
        "        buffer.putSeparator(position);\n" +
        "        position -= beginStringLength;\n" +
        "        buffer.putBytes(position, beginString, beginStringOffset, beginStringLength);\n" +
        "        position -= beginStringHeaderLength;\n" +
        "        buffer.putBytes(position, beginStringHeader, 0, beginStringHeaderLength);\n" +
        "        } else if (" + CODEC_VALIDATION_ENABLED + ")\n" +
        "        {\n" +
        "            throw new EncodingException(\"Missing Field: BeginString\");\n" +
        "        }\n" +
        "\n" +
        "        return position;\n" +
        "    }\n" +
        "\n" +
        "    // 35=...| + other header fields\n" +
        "    public long startMessage(final MutableAsciiBuffer buffer, final int offset)\n" +
        "    {\n" +
        "        final int start = offset + beginStringLength + 16;\n" +
        "        int position = start;";

    private static final String GROUP_ENCODE_PREFIX =
        "    public int encode(final MutableAsciiBuffer buffer, final int offset, final int remainingElements)\n" +
        "    {\n" +
        "        if (remainingElements == 0)\n" +
        "        {\n" +
        "            return 0;\n" +
        "        }\n\n" +
        "        int position = offset;\n\n";

    // returns (offset, length) as long
    private static final String MESSAGE_ENCODE_PREFIX =
        "    public long encode(final MutableAsciiBuffer buffer, final int offset)\n" +
        "    {\n" +
        "        final long startMessageResult = header.startMessage(buffer, offset);\n" +
        "        final int bodyStart = Encoder.offset(startMessageResult);\n" +
        "        int position = bodyStart + Encoder.length(startMessageResult);\n" +
        "\n";

    // returns length as int
    private static final String OTHER_ENCODE_PREFIX =
        "    public int encode(final MutableAsciiBuffer buffer, final int offset)\n" +
        "    {\n" +
        "        int position = offset;\n\n";

    private static final String RESET_NEXT_GROUP =
        "        if (next != null)" +
        "        {\n" +
        "            next.reset();\n" +
        "        }\n";

    static String encoderClassName(final String name)
    {
        return formatClassName(name + "Encoder");
    }

    private final byte[] buffer = new byte[LONGEST_INT_LENGTH + 1];

    private final MutableAsciiBuffer string = new MutableAsciiBuffer(buffer);

    private final String beginString;  // e.g. "FIX.4.4"

    EncoderGenerator(
        final Dictionary dictionary,
        final String builderPackage,
        final String builderCommonPackage,
        final OutputManager outputManager,
        final Class<?> validationClass,
        final Class<?> rejectUnknownFieldClass,
        final Class<?> rejectUnknownEnumValueClass,
        final String codecRejectUnknownEnumValueEnabled,
        final boolean fixTagsInJavadoc)
    {
        super(dictionary, builderPackage, builderCommonPackage, outputManager, validationClass, rejectUnknownFieldClass,
            rejectUnknownEnumValueClass, false, codecRejectUnknownEnumValueEnabled, fixTagsInJavadoc, null);

        final Component header = dictionary.header();
        validateHasField(header, BEGIN_STRING);
        validateHasField(header, BODY_LENGTH);

        beginString = dictionary.beginString();
    }

    private void validateHasField(final Component header, final String fieldName)
    {
        if (!header.hasField(fieldName))
        {
            throw new IllegalArgumentException("Header does not contain needed field : " + fieldName);
        }
    }

    protected void generateAggregateFile(final Aggregate aggregate, final AggregateType aggregateType)
    {
        final String className = encoderClassName(aggregate.name());

        outputManager.withOutput(
            className,
            (out) ->
            {
                out.append(fileHeader(thisPackage));

                if (USED_SESSION_CODECS.contains(className))
                {
                    out.append(importFor("uk.co.real_logic.artio.builder.Abstract" + className));
                }
                out.append(importFor(Generated.class));

                generateImports(
                    "Encoder",
                    aggregateType,
                    out,
                    DirectBuffer.class,
                    MutableDirectBuffer.class,
                    UnsafeBuffer.class,
                    AsciiSequenceView.class,
                    FieldBagEncoder.class);
                generateAggregateClass(aggregate, aggregateType, className, out);
            });
    }

    protected Class<?> topType(final AggregateType aggregateType)
    {
        return Encoder.class;
    }

    protected String resetGroup(final Entry entry)
    {
        final Group group = (Group)entry.element();
        final String name = group.name();
        final Entry numberField = group.numberField();
        final String resetMethod = nameOfResetMethod(name);
        if (isSharedParent())
        {
            return String.format(
                "    public abstract void %1$s();\n\n",
                resetMethod);
        }
        else
        {
            return String.format(
                "    public void %1$s()\n" +
                "    {\n" +
                "        if (%2$s != null)\n" +
                "        {\n" +
                "            %2$s.reset();\n" +
                "        }\n" +
                "        %3$s = 0;\n" +
                "        has%4$s = false;\n" +
                "    }\n\n",
                resetMethod,
                formatPropertyName(name),
                formatPropertyName(numberField.name()),
                numberField.name());
        }
    }

    private void generateAggregateClass(
        final Aggregate aggregate,
        final AggregateType type,
        final String className,
        final Writer out) throws IOException
    {
        push(aggregate);

        final boolean isHeader = type == AggregateType.HEADER;
        final boolean isMessage = type == AggregateType.MESSAGE;
        final List<String> interfaces;
        if (isMessage)
        {
            final String parentName =
                (USED_SESSION_CODECS.contains(className)) ?
                "Abstract" + className :
                Encoder.class.getSimpleName();
            interfaces = singletonList(parentName);
        }
        else if (isHeader)
        {
            interfaces = singletonList(SessionHeaderEncoder.class.getName());
        }
        else
        {
            interfaces = emptyList();
        }
        out.append(classDeclaration(
            className, interfaces, type == GROUP, aggregate.isInParent()));
        out.append(constructor(className, aggregate, type, dictionary));
        if (isMessage && !isSharedParent())
        {
            out.append(commonCompoundImports("Encoder", false, ""));
        }
        else if (type == GROUP)
        {
            final Group group = (Group)aggregate;

            // shared parents are abstract, generate the next() method in the concrete children.
            if (isSharedParent())
            {
                out.append(abstractNextMethod(group));
            }
            else
            {
                out.append(nextMethod(group));
            }
        }
        else if (type == HEADER)
        {
            out.append(
                String.format("\n" +
                "    %2$s static final byte[] DEFAULT_BEGIN_STRING=\"%1$s\".getBytes(StandardCharsets.US_ASCII);" +
                "\n\n",
                beginString,
                scope));
        }

        precomputedHeaders(out, aggregate.entries());
        generateSetters(out, className, aggregate.entries());
        out.append(encodeMethod(aggregate.entries(), type));
        final String resetMethod = completeResetMethod(aggregate, isMessage, type);
        out.append(resetMethod);
        out.append(generateAppendTo(aggregate, isMessage));
        out.append(generateCopyTo(aggregate));
        out.append("}\n");

        pop();
    }

    private String classDeclaration(
        final String className,
        final List<String> interfaces,
        final boolean isGroup,
        final boolean inParent)
    {
        final String interfaceList = interfaces.isEmpty() ? "" : " implements " + String.join(", ", interfaces);

        final String extendsClause;
        if (inParent)
        {
            String qualifiedName = className;
            // Groups are inner classes in their parent
            if (isGroup)
            {
                qualifiedName = qualifiedAggregateStackNames(aggregate -> encoderClassName(aggregate.name()));
            }
            extendsClause = " extends " + parentDictPackage() + "." + qualifiedName;
        }
        else
        {
            extendsClause = "";
        }
        return String.format(
            "\n" +
            GENERATED_ANNOTATION +
            SUPPRESS_THIS_ESCAPE_ANNOTATION +
            "public %3$s%4$sclass %1$s%5$s%2$s\n" +
            "{\n",
            className,
            interfaceList,
            isGroup ? "static " : "",
            isSharedParent() ? "abstract " : "",
            extendsClause);
    }

    private String completeResetMethod(
        final Aggregate aggregate, final boolean isMessage, final AggregateType type)
    {
        final String additionalReset;
        switch (type)
        {
            case GROUP:
                additionalReset = RESET_NEXT_GROUP;
                break;
            case HEADER:
                additionalReset = "        beginStringAsCopy(DEFAULT_BEGIN_STRING, 0, DEFAULT_BEGIN_STRING.length);\n";
                break;
            default:
                additionalReset = "";
        }
        return super.completeResetMethod(isMessage, aggregate.entries(), additionalReset, aggregate.isInParent());
    }

    private void generateGroupClass(final Group group, final Writer out)
        throws IOException
    {
        final String className = encoderClassName(group.name());
        generateAggregateClass(group, GROUP, className, out);
    }

    private String nextMethod(final Group group)
    {
        return String.format(
            "    private %1$s next = null;\n\n" +
            "    public %1$s next()\n" +
            "    {\n" +
            "        if (next == null)\n" +
            "        {\n" +
            "            next = new %1$s();\n" +
            "        }\n" +
            "        return next;\n" +
            "    }\n\n",
            encoderClassName(group.name())
        );
    }

    private String abstractNextMethod(final Group group)
    {
        return String.format(
            "    public abstract %1$s next();\n\n",
            encoderClassName(group.name()));
    }

    private String constructor(
        final String className, final Aggregate aggregate, final AggregateType type, final Dictionary dictionary)
    {
        if (type == AggregateType.MESSAGE)
        {
            final Component header = dictionary.header();
            final Message message = (Message)aggregate;
            final long packedType = message.packedType();
            final String fullType = message.fullType();

            final String msgType = header.hasField(MSG_TYPE) && !isSharedParent() ?
                String.format("        header.msgType(\"%s\");\n", fullType) : "";
            return String.format(
                "    public long messageType()\n" +
                "    {\n" +
                "        return %sL;\n" +
                "    }\n\n" +
                "    public %s()\n" +
                "    {\n" +
                "%s" +
                "    }\n\n",
                packedType,
                className,
                msgType);
        }
        else if (type == AggregateType.HEADER)
        {
            return String.format(
                "    public %s()\n" +
                "    {\n" +
                "        beginStringAsCopy(DEFAULT_BEGIN_STRING, 0, DEFAULT_BEGIN_STRING.length);\n" +
                "    }\n\n",
                className);
        }
        else
        {
            return "";
        }
    }

    private void generateSetters(
        final Writer out, final String className, final List<Entry> entries)
        throws IOException
    {
        final List<String> optionalFields = ENCODER_OPTIONAL_SESSION_FIELDS.get(className);
        final Set<String> missingOptionalFields = (optionalFields == null) ? emptySet() : new HashSet<>(optionalFields);

        for (final Entry entry : entries)
        {
            generateSetter(className, entry, out, missingOptionalFields);
        }

        generateMissingOptionalSessionFields(out, className, missingOptionalFields);
        generateOptionalSessionFieldsSupportedMethods(optionalFields, missingOptionalFields, out);
    }

    private void generateMissingOptionalSessionFields(
        final Writer out, final String className, final Set<String> missingOptionalFields)
        throws IOException
    {
        for (final String optionalField : missingOptionalFields)
        {
            final String propertyName = formatPropertyName(optionalField);

            final Type type = OPTIONAL_FIELD_TYPES.get(optionalField);
            switch (type)
            {
                case STRING:
                {
                    generateMissingStringOptionalSessionFields(out, className, optionalField, propertyName);
                    break;
                }

                case INT:
                {
                    out.append(String.format(
                        "    public %2$s %1$s(final int value)\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n",
                        propertyName,
                        className));
                    break;
                }

                default:
                    throw new UnsupportedOperationException("Unknown field type for: '" + optionalField + "'");
            }
        }
    }

    private void generateMissingStringOptionalSessionFields(
        final Writer out, final String className, final String optionalField, final String propertyName)
        throws IOException
    {
        out.append(String.format(
            "    public %2$s %1$s(final DirectBuffer value, final int offset, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final DirectBuffer value, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final DirectBuffer value)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final byte[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final byte[] value, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final byte[] value)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public boolean has%3$s()\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public String %1$sAsString()\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final CharSequence value)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final AsciiSequenceView value)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final char[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final char[] value, final int length)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public %2$s %1$s(final char[] value)\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public MutableDirectBuffer %1$s()\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }\n" +
            "\n" +
            "    public void reset%3$s()\n" +
            "    {\n" +
            "        throw new UnsupportedOperationException();\n" +
            "    }",
            propertyName,
            className,
            optionalField));
    }

    private void generateSetter(
        final String className,
        final Entry entry,
        final Writer out,
        final Set<String> optionalFields)
    {
        if (!isBodyLength(entry))
        {
            entry.forEach(
                (field) ->
                {
                    optionalFields.remove(field.name());
                    if (!entry.isInParent())
                    {
                        out.append(generateFieldSetter(className, field));
                    }
                },
                (group) -> generateGroup(className, group, out, optionalFields),
                (component) -> generateComponentField(encoderClassName(entry.name()), component, out),
                (anyFields) -> generateAnyFields(entry, out));
        }
    }

    private String generateFieldSetter(final String className, final Field field)
    {
        final String name = field.name();
        final String fieldName = formatPropertyName(name);
        final String hasField = String.format("    %2$s boolean has%1$s;\n\n", name, scope) + hasGetter(name);
        final String javadoc = generateAccessorJavadoc(field);

        final String hasAssign = String.format("        has%s = true;\n", name);

        final String enumSetter = shouldGenerateClassEnumMethods(field) ?
            enumSetter(className, fieldName, enumName(field.name())) : "";

        final Function<String, String> generateSetter =
            (type) -> generateSetter(name, type, fieldName, hasField, className, hasAssign, enumSetter, javadoc);

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
                return generateStringSetter(className, fieldName, name, enumSetter, javadoc);
            case BOOLEAN:
                return generateSetter.apply("boolean");

            case CHAR:
                return generateSetter.apply("char");

            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return generateSetter.apply("int");

            case LONG:
                return generateSetter.apply("long");

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return decimalFloatSetter(fieldName, hasField, className, hasAssign, enumSetter, javadoc);

            case DATA:
            case XMLDATA:
                // DATA fields always come with their own Length field defined by the schema
                return generateSetter.apply("byte[]") +
                    generateCopyingDataSetter(className, fieldName, hasAssign, javadoc);

            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case UTCDATEONLY:
            case UTCTIMEONLY:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return generateBytesSetter(className, fieldName, name, javadoc);

            default: throw new UnsupportedOperationException("Unknown type: " + field.type());
        }
    }

    private String generateCopyingDataSetter(
        final String className, final String fieldName, final String hasAssign, final String javadoc)
    {
        return String.format(
            "    %4$spublic %2$s %1$sAsCopy(final byte[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        %1$s = copyInto(%1$s, value, offset, length);\n" +
            "%3$s" +
            "        return this;\n" +
            "    }\n\n",
            fieldName,
            className,
            hasAssign,
            javadoc);
    }

    private void generateGroup(
        final String className,
        final Group group,
        final Writer out,
        final Set<String> optionalFields)
        throws IOException
    {
        generateGroupClass(group, out);

        final Entry numberField = group.numberField();

        final boolean inParent = group.isInParent();
        if (!inParent)
        {
            generateSetter(className, numberField, out, optionalFields);
        }

        // Abstraction layer in shared parent, implementation in children
        if (isSharedParent())
        {
            out.append(String.format(
                "\n    public abstract %1$s %2$s(final int numberOfElements);\n\n",
                encoderClassName(group.name()),
                formatPropertyName(group.name())));
        }
        else
        {
            out.append(String.format(
                "\n" +
                "    private %1$s %2$s = null;\n\n" +
                "    public %1$s %2$s(final int numberOfElements)\n" +
                "    {\n" +
                "        has%3$s = true;\n" +
                "        %4$s = numberOfElements;\n" +
                "        if (%2$s == null)\n" +
                "        {\n" +
                "            %2$s = new %1$s();\n" +
                "        }\n" +
                "        return %2$s;\n" +
                "    }\n\n",
                encoderClassName(group.name()),
                formatPropertyName(group.name()),
                numberField.name(),
                formatPropertyName(numberField.name())));
        }
    }

    private String generateBytesSetter(
        final String className, final String fieldName, final String name, final String javadoc)
    {
        return String.format(
            "    %4$s final MutableDirectBuffer %1$s = new UnsafeBuffer();\n" +
            "    %4$s byte[] %1$sInternalBuffer = %1$s.byteArray();\n" +
            "    %4$s int %1$sOffset = 0;\n" +
            "    %4$s int %1$sLength = 0;\n\n" +
            "    %5$spublic %2$s %1$s(final DirectBuffer value, final int offset, final int length)\n" +
            "    {\n" +
            "        %1$s.wrap(value);\n" +
            "        %1$sOffset = offset;\n" +
            "        %1$sLength = length;\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$s(final DirectBuffer value, final int length)\n" +
            "    {\n" +
            "        return %1$s(value, 0, length);\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$s(final DirectBuffer value)\n" +
            "    {\n" +
            "        return %1$s(value, 0, value.capacity());\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$s(final byte[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        %1$s.wrap(value);\n" +
            "        %1$sOffset = offset;\n" +
            "        %1$sLength = length;\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$sAsCopy(final byte[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        if (copyInto(%1$s, value, offset, length))\n" +
            "        {\n" +
            "            %1$sInternalBuffer = %1$s.byteArray();\n" +
            "        }\n" +
            "        %1$sOffset = 0;\n" +
            "        %1$sLength = length;\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$s(final byte[] value, final int length)\n" +
            "    {\n" +
            "        return %1$s(value, 0, length);\n" +
            "    }\n\n" +
            "    %5$spublic %2$s %1$s(final byte[] value)\n" +
            "    {\n" +
            "        return %1$s(value, 0, value.length);\n" +
            "    }\n\n" +
            "    %5$spublic boolean has%3$s()\n" +
            "    {\n" +
            "        return %1$sLength > 0;\n" +
            "    }\n\n" +
            "    %5$spublic MutableDirectBuffer %1$s()\n" +
            "    {\n" +
            "        return %1$s;\n" +
            "    }\n\n" +
            "    %5$spublic String %1$sAsString()\n" +
            "    {\n" +
            "        return %1$s.getStringWithoutLengthAscii(%1$sOffset, %1$sLength);\n" +
            "    }\n\n",
            fieldName,
            className,
            name,
            scope,
            javadoc);
    }

    private String generateStringSetter(
        final String className,
        final String fieldName,
        final String name,
        final String enumSetter,
        final String javadoc)
    {
        return String.format(
            "%2$s" +
            "    %5$spublic %3$s %1$s(final CharSequence value)\n" +
            "    {\n" +
            "        if (toBytes(value, %1$s))\n" +
            "        {\n" +
            "            %1$sInternalBuffer = %1$s.byteArray();\n" +
            "        }\n" +
            "        %1$sOffset = 0;\n" +
            "        %1$sLength = value.length();\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    %5$spublic %3$s %1$s(final AsciiSequenceView value)\n" +
            "    {\n" +
            "        final DirectBuffer buffer = value.buffer();\n" +
            "        if (buffer != null)\n" +
            "        {\n" +
            "            %1$s.wrap(buffer);\n" +
            "            %1$sOffset = value.offset();\n" +
            "            %1$sLength = value.length();\n" +
            "        }\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    %5$spublic %3$s %1$s(final char[] value)\n" +
            "    {\n" +
            "        return %1$s(value, 0, value.length);\n" +
            "    }\n\n" +
            "    %5$spublic %3$s %1$s(final char[] value, final int length)\n" +
            "    {\n" +
            "        return %1$s(value, 0, length);\n" +
            "    }\n\n" +
            "    %5$spublic %3$s %1$s(final char[] value, final int offset, final int length)\n" +
            "    {\n" +
            "        if (toBytes(value, %1$s, offset, length))\n" +
            "        {\n" +
            "            %1$sInternalBuffer = %1$s.byteArray();\n" +
            "        }\n" +
            "        %1$sOffset = 0;\n" +
            "        %1$sLength = length;\n" +
            "        return this;\n" +
            "    }\n\n" +
            "%4$s",
            fieldName,
            generateBytesSetter(className, fieldName, name, javadoc),
            className,
            enumSetter,
            javadoc);
    }

    private String generateSetter(
        final String name,
        final String type,
        final String fieldName,
        final String optionalField,
        final String className,
        final String optionalAssign,
        final String enumSetter,
        final String javadoc)
    {
        return String.format(
            "    %1$s %2$s %3$s;\n\n" +
            "%4$s" +
            "    %8$spublic %5$s %3$s(%2$s value)\n" +
            "    {\n" +
            "        %3$s = value;\n" +
            "%6$s" +
            "        return this;\n" +
            "    }\n\n" +
            "    %8$spublic %2$s %3$s()\n" +
            "    {\n" +
            "        return %3$s;\n" +
            "    }\n\n" +
            "%7$s",
            isBodyLength(name) ? "public" : scope,
            type,
            fieldName,
            optionalField,
            className,
            optionalAssign,
            enumSetter,
            javadoc);
    }

    private String decimalFloatSetter(
        final String fieldName,
        final String optionalField,
        final String className,
        final String optionalAssign,
        final String enumSetter,
        final String javadoc)
    {
        return String.format(
            "    %6$s final DecimalFloat %1$s = new DecimalFloat();\n\n" +
            "%2$s" +
            "    %7$spublic %3$s %1$s(ReadOnlyDecimalFloat value)\n" +
            "    {\n" +
            "        %1$s.set(value);\n" +
            "%4$s" +
            "        return this;\n" +
            "    }\n\n" +
            "    %7$spublic %3$s %1$s(long value, int scale)\n" +
            "    {\n" +
            "        %1$s.set(value, scale);\n" +
            "%4$s" +
            "        return this;\n" +
            "    }\n\n" +
            "    %7$spublic DecimalFloat %1$s()\n" +
            "    {\n" +
            "        return %1$s;\n" +
            "    }\n\n" +
            "%5$s",
            fieldName,
            optionalField,
            className,
            optionalAssign,
            enumSetter,
            scope,
            javadoc);
    }

    private String enumSetter(
        final String className,
        final String fieldName,
        final String enumType)
    {
        return String.format(
            "    public %1$s %2$s(%3$s value)\n" +
            "    {\n" +
            "        if (" + CODEC_VALIDATION_ENABLED + ")\n" +
            "        {\n" +
            "            if (value == %3$s.ARTIO_UNKNOWN)\n" +
            "            {\n" +
            "                throw new EncodingException(\"Invalid Value Field: " + fieldName +
            " Value: \" + value );\n" +
            "            }\n" +
            "            if (value == %3$s.NULL_VAL)\n" +
            "            {\n" +
            "                return this;\n" +
            "            }\n" +
            "        }\n" +
            "        return %2$s(value.representation());\n" +
            "    }\n\n",
            className, fieldName, enumType
        );
    }

    private String encodeMethod(final List<Entry> entries, final AggregateType aggregateType)
    {
        if (isSharedParent())
        {
            return "";
        }

        final String prefix;
        switch (aggregateType)
        {
            case TRAILER:
                prefix = TRAILER_ENCODE_PREFIX;
                break;

            case GROUP:
                prefix = GROUP_ENCODE_PREFIX;
                break;

            case MESSAGE:
                prefix = MESSAGE_ENCODE_PREFIX;
                break;

            case HEADER:
                prefix = HEADER_ENCODE_PREFIX;
                break;

            default:
                prefix = OTHER_ENCODE_PREFIX;
                break;
        }

        final String body = entries.stream()
            .map(this::encodeEntry)
            .collect(joining("\n"));

        String suffix;
        if (aggregateType == AggregateType.MESSAGE)
        {
            suffix =
                "        position += trailer.startTrailer(buffer, position);\n" +
                "\n" +
                "        final int messageStart = header.finishHeader(buffer, bodyStart, position - bodyStart);\n" +
                "        return trailer.finishMessage(buffer, messageStart, position);\n" +
                "    }\n\n";
        }
        else if (aggregateType == AggregateType.HEADER)
        {
            suffix =
                "\n" +
                "        return Encoder.result(position - start, start);\n" +
                "    }\n\n";
        }
        else if (aggregateType == AggregateType.TRAILER)
        {
            suffix =
                "        return position - start;\n" +
                "    }\n\n";
        }
        else
        {
            suffix =
                "        return position - offset;\n" +
                "    }\n\n";

            if (aggregateType == GROUP)
            {
                suffix =
                    "        if (next != null)\n" +
                    "        {\n" +
                    "            position += next.encode(buffer, position, remainingElements - 1);\n" +
                    "        }\n" + suffix;
            }
        }

        return prefix + body + suffix;
    }

    private String encodeEntry(final Entry entry)
    {
        if (isBodyLength(entry) || isBeginString(entry) || isCheckSum(entry))
        {
            return "";
        }
        else
        {
            return entry.matchEntry(this::encodeField, this::encodeGroup, this::encodeComponent, this::encodeAnyFields);
        }
    }

    private String encodeField(final Entry entry)
    {
        final Element element = entry.element();
        final Field field = (Field)element;
        final String name = field.name();
        final String fieldName = formatPropertyName(name);
        final Field.Type type = field.type();
        final boolean mustCheckFlag = hasFlag(entry, field);
        final boolean mustCheckLength = type.hasLengthField(false);
        final boolean needsMissingThrow =
            (mustCheckFlag || mustCheckLength) && entry.required() && !"MsgSeqNum".equals(name);

        final String enablingPrefix;
        final boolean needsIndent;
        if (mustCheckFlag)
        {
            enablingPrefix = String.format("        if (has%s)\n        {\n", name);
            needsIndent = true;
        }
        else if (mustCheckLength)
        {
            enablingPrefix = String.format("        if (%sLength > 0)\n        {\n", fieldName);
            needsIndent = true;
        }
        else
        {
            enablingPrefix = "";
            needsIndent = false;
        }

        final String enablingSuffix = enablingSuffix(name, mustCheckFlag, mustCheckLength, needsMissingThrow);
        final String tag = formatTag(fieldName, enablingPrefix);
        final String indent = indent(needsIndent);
        switch (type)
        {
            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return putValue(fieldName, tag, "Int", enablingSuffix, indent);

            case LONG:
                return putValue(fieldName, tag, "Long", enablingSuffix, indent);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return putValue(fieldName, tag, "Float", enablingSuffix, indent);

            case CHAR:
                return putValue(fieldName, tag, "Char", enablingSuffix, indent);

            case BOOLEAN:
                return putValue(fieldName, tag, "Boolean", enablingSuffix, indent);

            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
            case LOCALMKTDATE:
            case UTCTIMESTAMP:
            case MONTHYEAR:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return encodeStringField(fieldName, enablingSuffix, tag, indent);

            case DATA:
            case XMLDATA:
                return String.format(
                    "%1$s" +
                    "%4$s        buffer.putBytes(position, %2$s);\n" +
                    "%4$s        position += %2$s.length;\n" +
                    "%4$s        buffer.putSeparator(position);\n" +
                    "%4$s        position++;\n" +
                    "%3$s",
                    tag,
                    fieldName,
                    enablingSuffix,
                    indent);

            default:
                throw new UnsupportedOperationException("Unknown type: " + type);
        }
    }

    private String enablingSuffix(
        final String name, final boolean mustCheckFlag, final boolean mustCheckLength, final boolean needsMissingThrow)
    {
        String enablingSuffix = mustCheckFlag || mustCheckLength ? "        }\n" : "";
        if (needsMissingThrow)
        {
            enablingSuffix = enablingSuffix +
                "        else if (" + CODEC_VALIDATION_ENABLED + ")\n" +
                "        {\n" +
                "            throw new EncodingException(\"Missing Field: " + name + "\");\n" +
                "        }\n";
        }
        return enablingSuffix;
    }

    private String encodeStringField(
        final String fieldName, final String optionalSuffix, final String tag, final String indent)
    {
        return String.format(
            "%1$s" +
            "%4$s        buffer.putBytes(position, %2$s, %2$sOffset, %2$sLength);\n" +
            "%4$s        position += %2$sLength;\n" +
            "%4$s        buffer.putSeparator(position);\n" +
            "%4$s        position++;\n" +
            "%3$s",
            tag,
            fieldName,
            optionalSuffix,
            indent);
    }

    private String encodeGroup(final Entry entry)
    {
        final Group group = (Group)entry.element();
        return String.format(
            "%1$s\n" +
            "        if (%2$s != null)\n" +
            "        {\n" +
            "            position += %2$s.encode(buffer, position, %3$s);\n" +
            "        }\n\n",
            encodeField(group.numberField()),
            formatPropertyName(group.name()),
            formatPropertyName(group.numberField().name()));
    }

    private String encodeComponent(final Entry entry)
    {
        return String.format(
            "            position += %1$s.encode(buffer, position);\n",
            formatPropertyName(entry.name()));
    }

    private void generateAnyFields(final Entry entry, final Writer out) throws IOException
    {
        if (isSharedParent())
        {
            out.write(String.format(
                "    public abstract FieldBagEncoder %1$s();\n\n",
                formatPropertyName(entry.name())
            ));
        }
        else
        {
            out.write(String.format(
                "    private final FieldBagEncoder %1$s = new FieldBagEncoder();\n" +
                "\n" +
                "    public FieldBagEncoder %1$s()\n" +
                "    {\n" +
                "        return %1$s;\n" +
                "    }\n\n",
                formatPropertyName(entry.name())
            ));
        }
    }

    protected String resetAnyFields(final List<Entry> entries, final StringBuilder methods)
    {
        if (isSharedParent())
        {
            return "";
        }

        return resetAllBy(entries, methods, Entry::isAnyFields, entry -> "", this::resetAnyFields);
    }

    private String resetAnyFields(final Entry entry)
    {
        return String.format(
            "        %1$s.reset();\n",
            formatPropertyName(entry.name()));
    }

    private String encodeAnyFields(final Entry entry)
    {
        if (isSharedParent())
        {
            return "";
        }

        return String.format(
            "        if (!%1$s.isEmpty())\n" +
            "        {\n" +
            "            position += %1$s.encode(buffer, position);\n" +
            "        }\n\n",
            formatPropertyName(entry.name()));
    }

    protected String anyFieldsAppendTo(final AnyFields element)
    {
        if (isSharedParent())
        {
            return "";
        }

        return String.format(
            "        if (!%1$s.isEmpty())\n" +
            "        {\n" +
            "            indent(builder, level);\n" +
            "            builder.append(\"\\\"%2$s\\\": \\\"\");\n" +
            "            %1$s.appendTo(builder);\n" +
            "            builder.append(\"\\\",\\n\");\n" +
            "        }\n",
            formatPropertyName(element.name()),
            element.name());
    }

    private String anyFieldsCopyTo(final AnyFields element, final String encoderName)
    {
        if (isSharedParent())
        {
            return "";
        }

        return String.format(
            "        %1$s.copyTo(%2$s.%1$s());\n",
            formatPropertyName(element.name()),
            encoderName);
    }

    private String formatTag(final String fieldName, final String optionalPrefix)
    {
        final String indent = indent(!optionalPrefix.isEmpty());
        return String.format(
            "%1$s" +
            "%3$s        buffer.putBytes(position, %2$sHeader, 0, %2$sHeaderLength);\n" +
            "%3$s        position += %2$sHeaderLength;\n",
            optionalPrefix,
            fieldName,
            indent);
    }

    private String indent(final boolean needsIndent)
    {
        return needsIndent ? "    " : "";
    }

    private String putValue(
        final String fieldName,
        final String tag,
        final String type,
        final String optionalSuffix,
        final String indent)
    {
        return String.format(
            "%1$s" +
            "%5$s        position += buffer.put%2$sAscii(position, %3$s);\n" +
            "%5$s        buffer.putSeparator(position);\n" +
            "%5$s        position++;\n" +
            "%4$s",
            tag,
            type,
            fieldName,
            optionalSuffix,
            indent);
    }

    private void precomputedHeaders(final Writer out, final List<Entry> entries) throws IOException
    {
        for (final Entry entry : entries)
        {
            final Element element = entry.element();
            if (element instanceof Field)
            {
                precomputedFieldHeader(out, (Field)element);
            }
            else if (element instanceof Group)
            {
                final Group group = (Group)element;
                final Entry numberFieldEntry = group.numberField();
                precomputedFieldHeader(out, (Field)numberFieldEntry.element());
            }
        }
    }

    private void precomputedFieldHeader(final Writer out, final Field field) throws IOException
    {
        final String name = field.name();
        final String fieldName = formatPropertyName(name);
        final int length = string.putIntAscii(0, field.number());
        final String bytes = IntStream
            .range(0, length)
            .mapToObj(i -> String.valueOf(buffer[i]))
            .collect(joining(", ", "", ", (byte) '='"));

        out.append(String.format(
            "    %4$s static final int %sHeaderLength = %d;\n" +
            "    %4$s static final byte[] %1$sHeader = new byte[] {%s};\n\n",
            fieldName,
            length + 1,
            bytes,
            scope));
    }

    protected String stringAppendTo(final String fieldName)
    {
        return String.format("appendBuffer(builder, %1$s, %1$sOffset, %1$sLength)", fieldName);
    }

    protected String timeAppendTo(final String fieldName)
    {
        return stringAppendTo(fieldName);
    }

    protected String dataAppendTo(final Field field, final String fieldName)
    {
        final Field associatedLengthField = field.associatedLengthField();
        Objects.requireNonNull(associatedLengthField, "Length field for: " + fieldName);
        final String lengthName = formatPropertyName(associatedLengthField.name());
        return String.format("appendData(builder, %1$s, %2$s)", fieldName, lengthName);
    }

    protected String componentAppendTo(final Component component)
    {
        // appendTo shared components in children
        if (isSharedParent())
        {
            return "";
        }
        else
        {
            final String name = component.name();
            return String.format(
                "    indent(builder, level);\n" +
                "    builder.append(\"\\\"%1$s\\\": \");\n" +
                "    %2$s.appendTo(builder, level + 1);\n" +
                "    builder.append(\"\\n\");\n",
                name,
                formatPropertyName(name));
        }
    }

    private void generateComponentField(
        final String className, final Component element, final Writer out)
        throws IOException
    {
        if (isSharedParent())
        {
            out.append(String.format(
                "    public abstract %1$s %2$s();\n\n",
                className,
                formatPropertyName(element.name())));
        }
        else
        {
            out.append(String.format(
                "    private final %1$s %2$s = new %1$s();\n" +
                "    public %1$s %2$s()\n" +
                "    {\n" +
                "        return %2$s;\n" +
                "    }\n\n",
                className,
                formatPropertyName(element.name())));
        }
    }

    protected String resetLength(final String name)
    {
        return String.format(
            "    public void %1$s()\n" +
            "    {\n" +
            "        %2$sLength = 0;\n" +
            "        %2$s.wrap(%2$sInternalBuffer);\n" +
            "    }\n\n",
            nameOfResetMethod(name),
            formatPropertyName(name));
    }

    protected String resetRequiredFloat(final String name)
    {
        return resetByFlag(name);
    }

    protected String resetRequiredInt(final Field field)
    {
        return resetByFlag(field.name());
    }

    protected String resetRequiredLong(final Field field)
    {
        return resetByFlag(field.name());
    }

    protected boolean hasFlag(final Entry entry, final Field field)
    {
        final Type type = field.type();
        return (!entry.required() && !type.hasLengthField(false)) ||
            type.isFloatBased() || type.isIntBased() || type.isCharBased();
    }

    protected String resetTemporalValue(final String name)
    {
        return resetLength(name);
    }

    protected String resetComponents(final List<Entry> entries, final StringBuilder methods)
    {
        // reset shared components in children
        if (isSharedParent())
        {
            return "";
        }
        else
        {
            return entries
                .stream()
                .filter(Entry::isComponent)
                .map(this::callComponentReset)
                .collect(joining());
        }
    }

    private String callComponentReset(final Entry entry)
    {
        return String.format(
            "        %1$s.reset();\n",
            formatPropertyName(entry.name()));
    }

    protected String resetStringBasedData(final String name)
    {
        return resetLength(name);
    }

    protected String groupEntryAppendTo(final Group group, final String name)
    {
        // only append groups in the children
        if (isSharedParent())
        {
            return "";
        }

        final Entry numberField = group.numberField();

        return String.format(
            "        if (has%2$s)\n" +
            "        {\n" +
            "            indent(builder, level);\n" +
            "            builder.append(\"\\\"%1$s\\\": [\\n\");\n" +
            "            final int %3$s = this.%3$s;\n" +
            "            %5$s %4$s = this.%4$s;\n" +
            "            for (int i = 0; i < %3$s; i++)\n" +
            "            {\n" +
            "                indent(builder, level);\n" +
            "                %4$s.appendTo(builder, level + 1);\n" +
            "                if (i < (%3$s - 1))\n" +
            "                {\n" +
            "                    builder.append(',');\n" +
            "                }\n" +
            "                builder.append('\\n');\n" +
            "                %4$s = %4$s.next();\n" +
            "            }\n" +
            "            indent(builder, level);\n" +
            "            builder.append(\"],\\n\");\n" +
            "        }\n",
            name,
            group.numberField().name(),
            formatPropertyName(numberField.name()),
            formatPropertyName(name),
            encoderClassName(name));
    }

    private String generateCopyTo(final Aggregate aggregate)
    {
        final String entriesCopyTo = aggregate
            .entries()
            .stream()
            .map(this::generateEntryCopyTo)
            .collect(joining("\n"));
        final String name = aggregate.name();

        return String.format(
            "    public %1$s copyTo(final Encoder encoder)\n" +
            "    {\n" +
            "        return copyTo((%1$s)encoder);\n" +
            "    }\n\n" +
            "    public %1$s copyTo(final %1$s encoder)\n" +
            "    {\n" +
            "        encoder.reset();\n" +
            "%2$s" +
            "        return encoder;\n" +
            "    }\n\n",
            encoderClassName(name),
            entriesCopyTo);
    }

    private String generateEntryCopyTo(final Entry entry)
    {
        return generateEntryCopyTo(entry, "encoder");
    }

    private String generateEntryCopyTo(final Entry entry, final String encoderName)
    {
        if (isBodyLength(entry))
        {
            return "";
        }

        final Entry.Element element = entry.element();
        final String name = entry.name();
        if (element instanceof Field)
        {
            final Field field = (Field)element;

            if (appendToChecksHasGetter(entry, field))
            {
                return String.format(
                    "        if (has%1$s())\n" +
                        "        {\n" +
                        "%2$s\n" +
                        "        }\n",
                    name,
                    indentedFieldCopyTo(encoderName, field, "            "));
            }
            else
            {
                return indentedFieldCopyTo(encoderName, field, "        ");
            }
        }
        else if (element instanceof Group)
        {
            return groupEntryCopyTo((Group)element, name, encoderName);
        }
        else if (element instanceof Component)
        {
            return componentCopyTo((Component)element, encoderName);
        }
        else if (element instanceof AnyFields)
        {
            return anyFieldsCopyTo((AnyFields)element, encoderName);
        }

        return "";
    }

    private String indentedFieldCopyTo(final String encoderName, final Field field, final String replacement)
    {
        final String fieldCopyTo = fieldCopyTo(field, encoderName);
        return NEWLINE
            .matcher(fieldCopyTo)
            .replaceAll(replacement);
    }

    protected String groupEntryCopyTo(final Group group, final String name, final String encoderName)
    {
        // only append groups in the children
        if (isSharedParent())
        {
            return "";
        }

        final String numberField = group.numberField().name();

        return String.format(
            "        if (has%1$s)\n" +
            "        {\n" +
            "            final int size = this.%4$s;\n" +
            "            %2$s %3$s = this.%3$s;\n" +
            "            %6$s %3$sEncoder = %5$s.%3$s(size);\n" +
            "            for (int i = 0; i < size; i++)\n" +
            "            {\n" +
            "                if (%3$s != null)\n" +
            "                {\n" +
            "                    %3$s.copyTo(%3$sEncoder);\n" +
            "                    %3$s = %3$s.next();\n" +
            "                    %3$sEncoder = %3$sEncoder.next();\n" +
            "                }\n" +
            "            }\n" +
            "        }\n",
            numberField,
            encoderClassName(name),
            formatPropertyName(name),
            formatPropertyName(numberField),
            encoderName,
            encoderClassName(name));
    }

    protected String componentCopyTo(final Component component, final String encoderName)
    {
        // copyTo shared components in children
        if (isSharedParent())
        {
            return "";
        }
        else
        {
            final String name = component.name();
            final String varName = formatPropertyName(name);

            return String.format(
                "\n        %1$s.copyTo(%2$s.%1$s());",
                varName,
                encoderName);
        }
    }

    private String fieldCopyTo(final Field field, final String encoderName)
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
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return String.format("%2$s.%1$sAsCopy(%1$s.byteArray(), 0, %1$sLength);", fieldName, encoderName);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:

            case INT:
            case LENGTH:
            case BOOLEAN:
            case CHAR:
            case SEQNUM:
            case DAYOFMONTH:
            case LONG:
                return String.format("%2$s.%1$s(this.%1$s());", fieldName, encoderName);

            case DATA:
            case XMLDATA:
                final String lengthName = formatPropertyName(field.associatedLengthField().name());

                return String.format(
                    "%3$s.%1$sAsCopy(this.%1$s(), 0, %2$s());%n%3$s.%2$s(%2$s());", fieldName, lengthName, encoderName);

            case NUMINGROUP:
                // Deliberately blank since it gets set by the group CopyTo logic.
            default:
                return "";
        }
    }

    protected String optionalReset(final Field field, final String name)
    {
        return field.type().hasLengthField(false) ? resetLength(name) : resetByFlag(name);
    }

    protected boolean appendToChecksHasGetter(final Entry entry, final Field field)
    {
        return hasFlag(entry, field) || field.type().hasLengthField(false);
    }
}
