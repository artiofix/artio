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

import org.agrona.AsciiNumberFormatException;
import org.agrona.LangUtil;
import org.agrona.generation.OutputManager;
import org.agrona.generation.ResourceConsumer;
import uk.co.real_logic.artio.builder.CommonDecoderImpl;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.Generated;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.fields.*;
import uk.co.real_logic.artio.util.MessageTypeEncoding;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static uk.co.real_logic.artio.dictionary.generation.AggregateType.*;
import static uk.co.real_logic.artio.dictionary.generation.ConstantGenerator.sizeHashSet;
import static uk.co.real_logic.artio.dictionary.generation.EncoderGenerator.encoderClassName;
import static uk.co.real_logic.artio.dictionary.generation.EnumGenerator.NULL_VAL_NAME;
import static uk.co.real_logic.artio.dictionary.generation.EnumGenerator.enumName;
import static uk.co.real_logic.artio.dictionary.generation.GenerationUtil.*;
import static uk.co.real_logic.artio.dictionary.generation.OptionalSessionFields.DECODER_OPTIONAL_SESSION_FIELDS;
import static uk.co.real_logic.artio.dictionary.generation.OptionalSessionFields.OPTIONAL_FIELD_TYPES;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatPropertyName;

// TODO: optimisations
// skip decoding the msg type, since its known
// skip decoding the body string, since its known
// use ordering of fields to reduce branching
// skip decoding of unread header fields - eg: sender/target comp id.
// optimise the checksum definition to use an int and be calculated or ignored, have optional validation.
// evaluate utc parsing, adds about 100 nanos
// remove the REQUIRED_FIELDS validation when there are no required fields

class DecoderGenerator extends Generator
{
    private static final Set<String> REQUIRED_SESSION_CODECS = new HashSet<>(Arrays.asList(
        "LogonDecoder",
        "LogoutDecoder",
        "RejectDecoder",
        "TestRequestDecoder",
        "SequenceResetDecoder",
        "HeartbeatDecoder",
        "ResendRequestDecoder",
        "UserRequestDecoder"));

    public static final String REQUIRED_FIELDS = "REQUIRED_FIELDS";
    private static final String GROUP_FIELDS = "GROUP_FIELDS";
    private static final String ALL_GROUP_FIELDS = "ALL_GROUP_FIELDS";

    // Has to be generated everytime since HeaderDecoder and TrailerDecoder are generated.
    private static final String MESSAGE_DECODER =
        importFor(Decoder.class) +
        importFor(Generated.class) +
        "\n" +
        GENERATED_ANNOTATION +
        "public interface MessageDecoder extends Decoder\n" +
        "{\n" +
        "    HeaderDecoder header();\n" +
        "\n" +
        "    TrailerDecoder trailer();\n" +
        "}";

    public static final int INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP =
        RejectReason.INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP.representation();
    public static final int INVALID_TAG_NUMBER =
        RejectReason.INVALID_TAG_NUMBER.representation();
    public static final int REQUIRED_TAG_MISSING =
        RejectReason.REQUIRED_TAG_MISSING.representation();
    public static final int TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE =
        RejectReason.TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE.representation();
    public static final int TAG_SPECIFIED_WITHOUT_A_VALUE =
        RejectReason.TAG_SPECIFIED_WITHOUT_A_VALUE.representation();
    public static final int VALUE_IS_INCORRECT =
        RejectReason.VALUE_IS_INCORRECT.representation();

    public static final int TAG_APPEARS_MORE_THAN_ONCE =
        RejectReason.TAG_APPEARS_MORE_THAN_ONCE.representation();
    public static final int TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER = 14;

    static String decoderClassName(final Aggregate aggregate)
    {
        return decoderClassName(aggregate.name());
    }

    static String decoderClassName(final String name)
    {
        return name + "Decoder";
    }

    private final int initialBufferSize;
    private final String encoderPackage;
    /**
     * Wrap empty buffer instead of throwing an exception if an optional string is unset.
     */
    private final boolean wrapEmptyBuffer;

    DecoderGenerator(
        final Dictionary dictionary,
        final int initialBufferSize,
        final String thisPackage,
        final String commonPackage,
        final String encoderPackage,
        final OutputManager outputManager,
        final Class<?> validationClass,
        final Class<?> rejectUnknownFieldClass,
        final Class<?> rejectUnknownEnumValueClass,
        final boolean flyweightsEnabled,
        final boolean wrapEmptyBuffer,
        final String codecRejectUnknownEnumValueEnabled,
        final boolean fixTagsInJavadoc,
        final String decimalFloatOverflowHandler)
    {
        super(
            dictionary,
            thisPackage,
            commonPackage,
            outputManager,
            validationClass,
            rejectUnknownFieldClass,
            rejectUnknownEnumValueClass,
            flyweightsEnabled,
            codecRejectUnknownEnumValueEnabled,
            fixTagsInJavadoc,
            decimalFloatOverflowHandler);
        this.initialBufferSize = initialBufferSize;
        this.encoderPackage = encoderPackage;
        this.wrapEmptyBuffer = wrapEmptyBuffer;
    }

    public void generate()
    {
        generateMessageDecoderInterface();
        super.generate();
    }

    private void generateMessageDecoderInterface()
    {
        outputManager.withOutput(
            "MessageDecoder",
            (out) ->
            {
                out.append(fileHeader(thisPackage));

                out.append(MESSAGE_DECODER);
            });
    }

    protected void generateAggregateFile(final Aggregate aggregate, final AggregateType type)
    {
        if (type == COMPONENT)
        {
            componentInterface((Component)aggregate);
            return;
        }

        final String className = decoderClassName(aggregate);

        outputManager.withOutput(
            className,
            (out) ->
            {
                out.append(fileHeader(thisPackage));

                if (REQUIRED_SESSION_CODECS.contains(className))
                {
                    out.append(importFor("uk.co.real_logic.artio.decoder.Abstract" + className));
                }
                else if (type == HEADER)
                {
                    out.append(importFor(MessageTypeEncoding.class));
                    out.append(importFor(SessionHeaderDecoder.class));
                }
                out.append(importFor(AsciiNumberFormatException.class));
                out.append(importFor(Generated.class));

                generateImports(out, type);

                importEncoders(aggregate, out);

                generateAggregateClass(aggregate, type, className, out);
            });
    }

    private void importEncoders(final Aggregate aggregate, final Writer out) throws IOException
    {
        final String encoderFullClassName = encoderPackage + "." + encoderClassName(aggregate.name());
        out.write(importFor(encoderFullClassName));

        importChildEncoderClasses(aggregate, out, encoderFullClassName);
    }

    private void importChildEncoderClasses(
        final Aggregate aggregate, final Writer out, final String encoderFullClassName) throws IOException
    {
        for (final Entry entry : aggregate.entries())
        {
            if (entry.isComponent())
            {
                final String componentClassName = encoderPackage + "." + encoderClassName(entry.name());
                out.write(importFor(componentClassName));
                importChildEncoderClasses((Aggregate)entry.element(), out, componentClassName);
            }
            else if (entry.isGroup())
            {
                final String entryClassName = encoderClassName(entry.name());
                out.write(importStaticFor(encoderFullClassName, entryClassName));
                importChildEncoderClasses(
                    (Aggregate)entry.element(), out, encoderFullClassName + "." + entryClassName);
            }
        }
    }

    private void generateAggregateClass(
        final Aggregate aggregate,
        final AggregateType type,
        final String className,
        final Writer out) throws IOException
    {
        push(aggregate);

        final boolean isMessage = type == MESSAGE;
        final boolean isGroup = type == GROUP;
        final List<String> interfaces = aggregate
            .componentEntries()
            .map((comp) -> decoderClassName((Aggregate)comp.element()))
            .collect(toList());

        if (isMessage)
        {
            interfaces.add("MessageDecoder");

            if (REQUIRED_SESSION_CODECS.contains(className))
            {
                interfaces.add("Abstract" + className);
            }
        }
        else if (type == HEADER)
        {
            interfaces.add(SessionHeaderDecoder.class.getSimpleName());
        }

        out.append(classDeclaration(className, interfaces, false, aggregate.isInParent(), isGroup));
        if (decimalFloatOverflowHandler != null && type != HEADER && type != GROUP)
        {
            out.append(String.format("    public %s() {\n\n", className));
            out.append(String.format("        decimalFloatOverflowHandler = new %s",
                decimalFloatOverflowHandler + "();\n\n"));
            out.append("    }\n\n");
        }
        generateValidation(out, aggregate, type);
        if (isMessage)
        {
            final Message message = (Message)aggregate;
            // Normally generate these, but if its shared only generate it in the parent
            if (!message.isInParent() || isSharedParent())
            {
                out.append(messageType(message.fullType(), message.packedType()));
            }

            if (!isSharedParent())
            {
                final List<Field> fields = compileAllFieldsFor(message);
                final String messageFieldsSet = generateFieldDictionary(fields, MESSAGE_FIELDS, false);
                out.append(commonCompoundImports("Decoder", true, messageFieldsSet));
            }
        }
        groupMethods(out, aggregate);
        headerMethods(out, aggregate, type);
        generateGetters(out, className, aggregate.entries(), aggregate.isInParent());
        out.append(decodeMethod(aggregate.entries(), aggregate, type));
        out.append(completeResetMethod(
            isMessage, aggregate.entries(), additionalReset(isGroup), aggregate.isInParent()));
        out.append(generateAppendTo(aggregate, isMessage));
        out.append(generateToEncoder(aggregate));
        out.append("}\n");
        pop();
    }

    private String classDeclaration(
        final String className,
        final List<String> interfaces,
        final boolean isStatic,
        final boolean inParent,
        final boolean isGroup)
    {
        final String interfaceList = interfaces.isEmpty() ? "" : " implements " + String.join(", ", interfaces);

        final String extendsClause;
        if (inParent)
        {
            String qualifiedName = className;
            // Groups are inner classes in their parent
            if (isGroup)
            {
                qualifiedName = qualifiedSharedAggregateDecoderNames();
            }
            extendsClause = parentDictPackage() + "." + qualifiedName;
        }
        else
        {
            extendsClause = "CommonDecoderImpl";
        }
        return String.format(
            "\n" +
            GENERATED_ANNOTATION +
            SUPPRESS_THIS_ESCAPE_ANNOTATION +
            "public %3$s%4$sclass %1$s extends %5$s%2$s\n" +
            "{\n",
            className,
            interfaceList,
            isStatic ? "static " : "",
            isSharedParent() ? "abstract " : "",
            extendsClause);
    }

    private String qualifiedSharedAggregateDecoderNames()
    {
        return qualifiedAggregateStackNames(aggregate ->
            (aggregate instanceof Group ? "Abstract" : "") + decoderClassName(aggregate.name()));
    }

    private List<Field> compileAllFieldsFor(final Message message)
    {
        final Stream<Field> messageBodyFields = extractFields(message.entries());
        final Stream<Field> headerFields = extractFields(dictionary.header().entries());
        final Stream<Field> trailerFields = extractFields(dictionary.trailer().entries());

        return Stream.of(headerFields, messageBodyFields, trailerFields)
            .reduce(Stream::concat)
            .orElseGet(Stream::empty)
            .collect(toList());
    }

    private void headerMethods(final Writer out, final Aggregate aggregate, final AggregateType type)
        throws IOException
    {
        if (type == HEADER && !isSharedParent())
        {
            // Default constructor so that the header decoder can be used independently to parser headers.
            out.append(
                "    public HeaderDecoder()\n" +
                "    {\n" +
                "        this(new TrailerDecoder());\n" +
                "    }\n\n");
            wrapTrailerInConstructor(out, aggregate);

            generatePackedMessageTypeMethod(out);
        }
    }

    private void generatePackedMessageTypeMethod(final Writer out) throws IOException
    {
        if (flyweightsEnabled)
        {
            out.append(
                "    public long messageType()\n" +
                "    {\n" +
                "        return buffer.getMessageType(msgTypeOffset, msgTypeLength);\n" +
                "    }\n\n");
        }
        else
        {
            out.append(
                "    public long messageType()\n" +
                "    {\n" +
                "        return MessageTypeEncoding.packMessageType(msgType(), msgTypeLength());\n" +
                "    }\n\n");
        }
    }

    private void groupClass(final Group group, final Writer out) throws IOException
    {
        final String className = groupClassName(group);
        generateAggregateClass(group, GROUP, className, out);
    }

    private String groupClassName(final Group group)
    {
        String className = decoderClassName(group);
        if (isSharedParent())
        {
            // group classes on components can be imported from two different components so we prefix Abstract to the
            // name of the abstract parent group class in order to resolve the ambiguity.
            className = "Abstract" + className;
        }
        return className;
    }

    protected Class<?> topType(final AggregateType aggregateType)
    {
        return Decoder.class;
    }

    protected String resetGroup(final Entry entry)
    {
        final Group group = (Group)entry.element();
        final String name = group.name();
        final String resetMethod = nameOfResetMethod(name);

        if (isSharedParent())
        {
            return String.format("    public abstract void %1$s();\n", resetMethod);
        }
        else
        {
            final Entry numberField = group.numberField();
            return String.format(
                "    public void %1$s()\n" +
                "    {\n" +
                "        for (final %2$s %6$s : %5$s.iterator())\n" +
                "        {\n" +
                "            %6$s.reset();\n" +
                "            if (%6$s.next() == null)\n" +
                "            {\n" +
                "                break;\n" +
                "            }\n" +
                "        }\n" +
                "        %3$s = MISSING_INT;\n" +
                "        has%4$s = false;\n" +
                "    }\n\n",
                resetMethod,
                decoderClassName(name),
                formatPropertyName(numberField.name()),
                numberField.name(),
                iteratorFieldName(group),
                formatPropertyName(decoderClassName(name)));
        }
    }

    private String iteratorClassName(final Group group, final boolean ofParent)
    {
        final String prefix = ofParent ? "Abstract" : "";
        return prefix + group.name() + "Iterator";
    }

    private String iteratorFieldName(final Group group)
    {
        return formatPropertyName(iteratorClassName(group, false));
    }

    protected String resetLength(final String name)
    {
        return String.format(
            "    public void %1$s()\n" +
            "    {\n" +
            "        %2$sLength = 0;\n" +
            "    }\n\n",
            nameOfResetMethod(name),
            formatPropertyName(name));
    }

    protected String resetRequiredFloat(final String name)
    {
        final String lengthReset = flyweightsEnabled ? "        %1$sLength = 0;\n" : "";

        return String.format(
            "    public void %2$s()\n" +
            "    {\n" +
            lengthReset +
            "        %1$s.reset();\n" +
            "    }\n\n",
            formatPropertyName(name),
            nameOfResetMethod(name));
    }

    protected String resetRequiredInt(final Field field)
    {
        return resetFieldValue(field, "MISSING_INT");
    }

    protected String resetRequiredLong(final Field field)
    {
        return resetFieldValue(field, "MISSING_LONG");
    }

    private String additionalReset(final boolean isGroup)
    {
        return
            "        buffer = null;\n" +
            "        if (" + CODEC_VALIDATION_ENABLED + ")\n" +
            "        {\n" +
            "            invalidTagId = Decoder.NO_ERROR;\n" +
            "            rejectReason = Decoder.NO_ERROR;\n" +
            "            missingRequiredFields.clear();\n" +
            (isGroup ? "" :
                "            unknownFields.clear();\n" +
                "            alreadyVisitedFields.clear();\n") +
            "        }\n";
    }

    private void generateValidation(final Writer out, final Aggregate aggregate, final AggregateType type)
        throws IOException
    {
        if (isSharedParent())
        {
            out.append("    public final IntHashSet " + REQUIRED_FIELDS + " = new IntHashSet();\n\n");
            return;
        }

        final List<Field> requiredFields = requiredFields(aggregate.entries()).collect(toList());
        out.append(generateFieldDictionary(requiredFields, REQUIRED_FIELDS, true));

        if (aggregate.containsGroup())
        {
            final List<Field> groupFields = aggregate
                .allFieldsIncludingComponents()
                .map(Entry::element)
                .map(element -> (Field)element)
                .collect(toList());
            final String groupFieldString = generateFieldDictionary(groupFields, GROUP_FIELDS, true);
            out.append(groupFieldString);
        }

        final String enumValidation = aggregate
            .allFieldsIncludingComponents()
            .filter((entry) -> entry.element().isEnumField())
            .map(this::generateEnumValidation)
            .collect(joining("\n"));

        //maybe this should look at groups on components too?
        final String groupValidation = aggregate
            .allGroupsIncludingComponents()
            .map(this::generateGroupValidation)
            .collect(joining("\n"));

        final boolean isMessage = type == MESSAGE;
        final boolean isGroup = type == GROUP;
        final String messageValidation = isMessage ?
            "        if (" + CODEC_REJECT_UNKNOWN_FIELD_ENABLED + " && unknownFieldsIterator.hasNext())\n" +
            "        {\n" +
            "            invalidTagId = unknownFieldsIterator.nextValue();\n" +
            "            rejectReason = Constants.ALL_FIELDS.contains(invalidTagId) ? " +
            TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE + " : " + INVALID_TAG_NUMBER + ";\n" +
            "            return false;\n" +
            "        }\n" +
            "        if (!header.validate())\n" +
            "        {\n" +
            "            invalidTagId = header.invalidTagId();\n" +
            "            rejectReason = header.rejectReason();\n" +
            "            return false;\n" +
            "        }\n" +
            "        else if (!trailer.validate())\n" +
            "        {\n" +
            "            invalidTagId = trailer.invalidTagId();\n" +
            "            rejectReason = trailer.rejectReason();\n" +
            "            return false;\n" +
            "        }\n" :
            "";

        out.append(String.format(
            (isGroup ? generateAllGroupFields(aggregate) :
            "    private final IntHashSet alreadyVisitedFields = new IntHashSet(%5$d);\n\n" +
            "    private final IntHashSet unknownFields = new IntHashSet(10);\n\n") +
            "    private final IntHashSet missingRequiredFields = new IntHashSet(%1$d);\n\n" +
            "    public boolean validate()\n" +
            "    {\n" +
            // validation for some tags performed in the decode method
            "        if (rejectReason != Decoder.NO_ERROR)\n" +
            "        {\n" +
            "            return false;\n" +
            "        }\n" +
            "        final IntIterator missingFieldsIterator = missingRequiredFields.iterator();\n" +
            (isMessage ? "        final IntIterator unknownFieldsIterator = unknownFields.iterator();\n" : "") +
            "%2$s" +
            "        if (missingFieldsIterator.hasNext())\n" +
            "        {\n" +
            "            invalidTagId = missingFieldsIterator.nextValue();\n" +
            "            rejectReason = " + REQUIRED_TAG_MISSING + ";\n" +
            "            return false;\n" +
            "        }\n" +
            "%3$s" +
            "%4$s" +
            "        return true;\n" +
            "    }\n\n",
            sizeHashSet(requiredFields),
            messageValidation,
            enumValidation,
            groupValidation,
            2 * aggregate.allFieldsIncludingComponents().count()));
    }

    private String generateAllGroupFields(final Aggregate groupAggregate)
    {
        final List<Field> allGroupFields = groupAggregate
            .fieldEntries()
            .map((entry) -> (Field)entry.element())
            .collect(toList());
        return generateFieldDictionary(allGroupFields, ALL_GROUP_FIELDS, true);
    }

    private String generateFieldDictionary(
        final Collection<Field> fields, final String name, final boolean shouldGenerateValidationGating)
    {
        final String addFields = fields
            .stream()
            .map((field) -> addField(field, name, shouldGenerateValidationGating ? "            " : "        "))
            .collect(joining());
        final String generatedFieldEntryCode;

        if (shouldGenerateValidationGating)
        {
            generatedFieldEntryCode =
                "        if (" + CODEC_VALIDATION_ENABLED + ")\n" +
                "        {\n" +
                "%s" +
                "        }\n";
        }
        else
        {
            generatedFieldEntryCode = "%s";
        }

        final int hashMapSize = sizeHashSet(fields);
        return String.format(
            "    public final IntHashSet %2$s = new IntHashSet(%1$d);\n\n" +
            "    {\n" +
            "%3$s" +
            "    }\n\n",
            hashMapSize,
            name,
            String.format(generatedFieldEntryCode, addFields));
    }

    public static String addField(final Field field, final String name, final String prefix)
    {
        return String.format(
            "%1$s%2$s.add(Constants.%3$s);\n",
            prefix,
            name,
            constantName(field.name()));
    }

    private CharSequence generateEnumValidation(final Entry entry)
    {
        final Field field = (Field)entry.element();
        if (!EnumGenerator.hasEnumGenerated(field))
        {
            return "";
        }

        final String name = entry.name();
        final int tagNumber = field.number();
        final Type type = field.type();
        final String propertyName = formatPropertyName(name);

        final boolean isPrimitive = type.isIntBased() || type == Type.CHAR;

        final String enumValidation = String.format(
            "        if (" + codecRejectUnknownEnumValueEnabled + " && !%1$s.isValid(%2$s%4$s))\n" +
            "        {\n" +
            "            invalidTagId = %3$s;\n" +
            "            rejectReason = " + VALUE_IS_INCORRECT + ";\n" +
            "            return false;\n" +
            "        }\n",
            enumName(name),
            propertyName,
            tagNumber,
            isPrimitive ? "()" : "Wrapper");

        final String enumValidationMethod;
        if (type.isMultiValue())
        {
            enumValidationMethod =
                String.format(
                    "          int %1$sOffset = 0;\n" +
                    "          for (int i = 0; i < %1$sLength; i++)\n" +
                    "          {\n" +
                    "              if (this.%1$s()[i] == ' ')\n" +
                    "              {\n" +
                    "                  %1$sWrapper.wrap(this.%1$s(), %1$sOffset, i - %1$sOffset);\n" +
                    "%2$s" +
                    "                  %1$sOffset = i + 1;\n" +
                    "              }\n" +
                    "          }\n" +
                    "          %1$sWrapper.wrap(this.%1$s(), %1$sOffset, %1$sLength - %1$sOffset);\n" +
                    "%2$s",
                    propertyName,
                    enumValidation
                );
        }
        else
        {
            enumValidationMethod =
                String.format(
                    (isPrimitive ? "" : "        %1$sWrapper.wrap(this.%1$s(), %1$sLength);\n") +
                    "%2$s",
                    propertyName,
                    enumValidation
                );
        }

        return
            entry.required() ? enumValidationMethod :
            String.format(
                "        if (has%1$s)\n" +
                "        {\n" +
                "%2$s" +
                "        }\n",
                entry.name(),
                enumValidationMethod
            );
    }

    private CharSequence generateGroupValidation(final Entry entry)
    {
        final Group group = (Group)entry.element();
        final Entry numberField = group.numberField();
        final String numberFieldName = numberField.name();
        final boolean required = entry.required();
        final String validationCode = String.format(
            "%3$s        {\n" +
            "%3$s            int count = 0;\n" +
            "%3$s            final %4$s iterator = %2$s.iterator();\n" +
            "%3$s            for (final %1$s group : iterator)\n" +
            "%3$s            {\n" +
            "%3$s                count++;" +
            "%3$s                if (!group.validate())\n" +
            "%3$s                {\n" +
            "%3$s                    invalidTagId = group.invalidTagId();\n" +
            "%3$s                    rejectReason = group.rejectReason();\n" +
            "%3$s                    return false;\n" +
            "%3$s                }\n" +
            "%3$s            }\n" +
            "%3$s            if (count != iterator.numberFieldValue())\n" +
            "%3$s            {\n" +
            "%3$s                invalidTagId = %5$s;\n" +
            "%3$s                rejectReason = %6$s;\n" +
            "%3$s                return false;\n" +
            "%3$s            }\n" +
            "%3$s        }\n",
            decoderClassName(group),
            iteratorFieldName(group),
            required ? "" : "    ",
            iteratorClassName(group, false),
            numberField.number(),
            INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP);

        if (required)
        {
            return validationCode;
        }
        else
        {
            return String.format(
                "        if (has%1$s)\n" +
                "        {\n" +
                "%2$s" +
                "        }\n",
                numberFieldName,
                validationCode
            );
        }
    }

    private Stream<Field> requiredFields(final List<Entry> entries)
    {
        return entries
            .stream()
            .filter(Entry::required)
            .flatMap(this::extractRequiredFields);
    }

    private Stream<Field> extractRequiredFields(final Entry entry)
    {
        return entry.match(
            (e, field) -> Stream.of(field),
            (e, group) -> Stream.of((Field)group.numberField().element()),
            (e, component) -> requiredFields(component.entries()),
            (e, anyFields) -> Stream.empty());
    }

    private Stream<Field> extractFields(final Entry entry)
    {
        return entry.match(
            (e, field) -> Stream.of(field),
            (e, group) -> Stream.concat(Stream.of((Field)group.numberField().element()),
                                group.entries().stream().flatMap(this::extractFields)),
            (e, component) -> component.entries().stream().flatMap(this::extractFields),
            (e, anyFields) -> Stream.empty());
    }

    private Stream<Field> extractFields(final List<Entry> entries)
    {

        return entries.stream().flatMap(this::extractFields);
    }

    private void componentInterface(final Component component)
    {
        push(component);

        final String className = decoderClassName(component);
        outputManager.withOutput(className,
            (out) ->
            {
                out.append(fileHeader(thisPackage));
                final List<String> interfaces = component.allComponents()
                    .map((comp) -> decoderClassName((Aggregate)comp.element()))
                    .collect(toList());

                if (component.isInParent())
                {
                    interfaces.add(parentDictPackage() + "." + className);
                }

                final String interfaceExtension = interfaces.isEmpty() ? "" : " extends " +
                    String.join(", ", interfaces);

                out.append(importFor(AsciiNumberFormatException.class));
                generateImports(out, AggregateType.COMPONENT);
                importEncoders(component, out);
                out.append(importFor(Generated.class));

                out.append(String.format(
                    "\n" +
                    GENERATED_ANNOTATION +
                    "public interface %1$s %2$s\n" +
                    "{\n\n",
                    className,
                    interfaceExtension));

                for (final Entry entry : component.entries())
                {
                    // We need to generate the group entry in the component interface even if the entry is in the
                    // parent interface because we've got a more specialised Group + Iterator class to generate
                    if (!entry.isInParent() || entry.isGroup())
                    {
                        componentInterfaceGetter(entry, out);
                    }
                }
                out.append("\n}\n");
            });

        pop();
    }

    private void generateImports(final Writer out, final AggregateType component) throws IOException
    {
        generateImports("Decoder", component, out,
            Encoder.class, CommonDecoderImpl.class);
    }

    private void componentInterfaceGetter(final Entry entry, final Writer out)
    {
        entry.forEach(
            (field) -> out.append(fieldInterfaceGetter(entry, field)),
            (group) -> groupInterfaceGetter(group, out),
            (component) -> {},
            (anyFields) -> {});
    }

    private void groupInterfaceGetter(final Group group, final Writer out) throws IOException
    {
        groupClass(group, out);
        generateGroupIterator(out, group);

        final Entry numberField = group.numberField();
        final boolean ofParent = isSharedParent();
        out.append(String.format("    public %1$s %2$s();\n",
            iteratorClassName(group, ofParent),
            iteratorFieldName(group)));
        out.append(fieldInterfaceGetter(numberField, (Field)numberField.element()));

        out.append(String.format(
            "    public %1$s %2$s();\n",
            groupClassName(group),
            formatPropertyName(group.name())));
    }

    private void wrappedForEachEntry(
        final Aggregate aggregate, final Writer out, final ResourceConsumer<Entry> consumer)
        throws IOException
    {
        out.append("\n");
        aggregate
            .entries()
            .forEach((t) ->
            {
                try
                {
                    consumer.accept(t);
                }
                catch (final IOException ex)
                {
                    LangUtil.rethrowUnchecked(ex);
                }
            });
        out.append("\n");
    }

    private String fieldInterfaceGetter(final Entry entry, final Field field)
    {
        final String name = field.name();
        final String fieldName = formatPropertyName(name);
        final Type type = field.type();

        final String javadoc = generateAccessorJavadoc(field);

        final String length = type.isStringBased() ?
            String.format("    %2$spublic int %1$sLength();\n", fieldName, javadoc) : "";

        final String stringAsciiView = type.isStringBased() ?
            String.format("    %2$spublic AsciiSequenceView %1$s(AsciiSequenceView view);\n", fieldName, javadoc) : "";

        final String optional = !entry.required() ?
            String.format("    %2$spublic boolean has%1$s();\n", name, javadoc) : "";

        final String enumDecoder = shouldGenerateClassEnumMethods(field) ?
            String.format("    %3$spublic %1$s %2$sAsEnum();\n", name, fieldName, javadoc) : "";

        return String.format(
            "    %7$spublic %1$s %2$s();\n" +
            "%3$s" +
            "%4$s" +
            "%5$s" +
            "%6$s",
            javaTypeOf(type),
            fieldName,
            optional,
            length,
            enumDecoder,
            stringAsciiView,
            javadoc);
    }

    private void generateGetter(
        final Entry entry, final Writer out, final Set<String> missingOptionalFields,
        final boolean aggregateIsInParent)
    {
        entry.forEach(
            (field) ->
            {
                missingOptionalFields.remove(field.name());
                out.append(fieldGetter(entry, field, aggregateIsInParent));
            },
            (group) -> groupGetter(group, out, aggregateIsInParent),
            (component) ->
            {
                // Components can be shared, but without every message of the given type implementing that component
                final boolean componentIsInParent = aggregateIsInParent && entry.isInParent();
                componentGetter(component, out, missingOptionalFields, componentIsInParent);
            },
            (anyFields) -> {});
    }

    private void groupMethods(final Writer out, final Aggregate aggregate) throws IOException
    {
        if (aggregate instanceof Group)
        {
            final Group group = (Group)aggregate;

            wrapTrailerAndMessageFieldsInGroupConstructor(out, group);

            out.append(String.format(
                "    private %1$s next = null;\n\n" +
                "    public %1$s next()\n" +
                "    {\n" +
                "        return next;\n" +
                "    }\n\n" +
                "    private IntHashSet seenFields = new IntHashSet(%2$d);\n\n",
                groupClassName(group),
                sizeHashSet(group.entries())));
        }
    }

    private void wrapTrailerAndMessageFieldsInGroupConstructor(final Writer out, final Aggregate aggregate)
        throws IOException
    {
        if (isSharedParent())
        {
            return;
        }

        out.append(String.format(
            "    private final TrailerDecoder trailer;\n" +
            "    private final IntHashSet %1$s;\n" +
            "    public %2$s(final TrailerDecoder trailer, final IntHashSet %1$s)\n",
            MESSAGE_FIELDS,
            decoderClassName(aggregate)));

        out.append(String.format(
            "    {\n" +
            "        this.trailer = trailer;\n" +
            "        this.%1$s = %1$s;\n",
            MESSAGE_FIELDS));

        if (decimalFloatOverflowHandler != null)
        {
            out.append(String.format("        decimalFloatOverflowHandler = new %s",
                decimalFloatOverflowHandler + "();\n\n"));
        }

        out.append("    }\n\n");
    }

    private void wrapTrailerInConstructor(final Writer out, final Aggregate aggregate) throws IOException
    {
        out.append(String.format(
            "    private final TrailerDecoder trailer;\n" +
            "    public %1$s(final TrailerDecoder trailer)\n" +
            "    {\n" +
            "        this.trailer = trailer;\n" +
            "    }\n\n",
            decoderClassName(aggregate)));
    }

    private String messageType(final String fullType, final long packedType)
    {
        return String.format(
            "    public static final long MESSAGE_TYPE = %1$dL;\n\n" +
            "    public static final String MESSAGE_TYPE_AS_STRING = \"%2$s\";\n\n" +
            "    public static final char[] MESSAGE_TYPE_CHARS = MESSAGE_TYPE_AS_STRING.toCharArray();\n\n" +
            "    public static final byte[] MESSAGE_TYPE_BYTES = MESSAGE_TYPE_AS_STRING.getBytes(US_ASCII);\n\n",
            packedType,
            fullType);
    }

    private void generateGetters(
        final Writer out, final String className, final List<Entry> entries, final boolean aggregateIsInParent)
        throws IOException
    {
        final List<String> optionalFields = DECODER_OPTIONAL_SESSION_FIELDS.get(className);
        // Remove from missingOptionalFields during generateGetter
        final Set<String> missingOptionalFields = optionalFields == null ? emptySet() : new HashSet<>(optionalFields);

        for (final Entry entry : entries)
        {
            generateGetter(entry, out, missingOptionalFields, aggregateIsInParent);
        }

        generateMissingOptionalSessionFields(out, missingOptionalFields);
        generateOptionalSessionFieldsSupportedMethods(optionalFields, missingOptionalFields, out);
    }

    private void generateMissingOptionalSessionFields(
        final Writer out, final Set<String> missingOptionalFields)
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
                    out.append(String.format(
                        "    public char[] %1$s()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n" +
                        "    public boolean has%2$s()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n" +
                        "    public int %1$sLength()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n" +
                        "    public String %1$sAsString()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n" +
                        "    public AsciiSequenceView %1$s(final AsciiSequenceView view)\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n",
                        propertyName,
                        optionalField));
                    break;
                }

                case INT:
                {
                    out.append(String.format(
                        "    public int %1$s()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n" +
                        "    public boolean has%2$s()\n" +
                        "    {\n" +
                        "        throw new UnsupportedOperationException();\n" +
                        "    }\n\n",
                        propertyName,
                        optionalField));
                    break;
                }

                default:
                    throw new UnsupportedOperationException("Unknown field type for: '" + optionalField + "'");
            }
        }
    }

    private void componentGetter(
        final Component component,
        final Writer out,
        final Set<String> missingOptionalFields,
        final boolean aggregateIsInParent)
        throws IOException
    {
        push(component);
        wrappedForEachEntry(component, out,
            (entry) -> generateGetter(entry, out, missingOptionalFields, aggregateIsInParent));
        pop();
    }

    private void groupGetter(
        final Group group,
        final Writer out,
        final boolean aggregateIsShared)
        throws IOException
    {
        // The component interface will generate the group class
        final Aggregate currentAggregate = currentAggregate();
        final boolean inComponent = currentAggregate instanceof Component;
        if (!inComponent)
        {
            groupClass(group, out);
            generateGroupIterator(out, group);
        }

        final Entry numberField = group.numberField();
        // Try to generate the number field as high up the hierarchy as possible.
        // In the parent class if it's there, if it's a component then that's an interface so it can't be generated
        // there
        final String prefix = group.isInParent() && (aggregateIsShared && !inComponent) ? "" : fieldGetter(
            numberField, (Field)numberField.element(), aggregateIsShared);

        final String groupClassName = groupClassName(group);

        if (isSharedParent())
        {
            out.append(String.format(
                "\n" +
                "    public abstract %1$s %2$s();\n\n" +
                "%3$s\n" +
                "    public abstract %4$s %5$s();\n\n",
                groupClassName,
                formatPropertyName(group.name()),
                prefix,
                iteratorClassName(group, true),
                iteratorFieldName(group)));
        }
        else
        {
            out.append(String.format(
                "\n" +
                "    private %1$s %2$s = null;\n" +
                "    public %1$s %2$s()\n" +
                "    {\n" +
                "        return %2$s;\n" +
                "    }\n\n" +
                "%3$s\n" +
                "    private %4$s %5$s = new %4$s(this);\n" +
                "    public %4$s %5$s()\n" +
                "    {\n" +
                "        return %5$s.iterator();\n" +
                "    }\n\n",
                groupClassName,
                formatPropertyName(group.name()),
                prefix,
                iteratorClassName(group, false),
                iteratorFieldName(group)));
        }
    }

    private void generateGroupIterator(final Writer out, final Group group) throws IOException
    {
        final String numberFieldName = group.numberField().name();
        final String formattedNumberFieldName = formatPropertyName(numberFieldName);
        final String numberFieldReset =
            group.numberField().required() ?
            String.format("parent.%1$s()", formattedNumberFieldName) :
            String.format("parent.has%1$s() ? parent.%2$s() : 0", numberFieldName, formattedNumberFieldName);

        final boolean sharedParent = isSharedParent();
        final String iteratorClassName = iteratorClassName(group, sharedParent);
        final String groupDecoderName = groupClassName(group);
        final String parentDecoderName = qualifiedSharedAggregateDecoderNames();
        final String groupPropertyName = formatPropertyName(group.name());

        if (sharedParent)
        {
            out.append(String.format(
                "    " + GENERATED_ANNOTATION +
                "    public abstract class %1$s<T extends %2$s> implements Iterable<T>, java.util.Iterator<T>\n" +
                "    {\n" +
                "        private final %3$s parent;\n" +
                "        private int remainder;\n" +
                "        private T current;\n\n" +
                "        public %1$s(final %3$s parent)\n" +
                "        {\n" +
                "            this.parent = parent;\n" +
                "        }\n\n" +
                "        public boolean hasNext()\n" +
                "        {\n" +
                "            return remainder > 0 && current != null;\n" +
                "        }\n\n" +
                "        @SuppressWarnings(\"unchecked\")\n" +
                "        public T next()\n" +
                "        {\n" +
                "            remainder--;\n" +
                "            final T value = current;\n" +
                "            current = (T)current.next();\n" +
                "            return value;\n" +
                "        }\n\n" +
                "        public int numberFieldValue()\n" +
                "        {\n" +
                "            return %4$s;\n" +
                "        }\n\n" +
                "        @SuppressWarnings(\"unchecked\")\n" +
                "        public void reset()\n" +
                "        {\n" +
                "            remainder = numberFieldValue();\n" +
                "            current = (T)parent.%5$s();\n" +
                "        }\n\n" +
                "    }\n\n",
                iteratorClassName,
                groupDecoderName,
                parentDecoderName,
                numberFieldReset,
                groupPropertyName));
        }
        else if (group.isInParent())
        {
            final String parentLocation = parentDictPackage() + "." + parentDecoderName + "." +
                iteratorClassName(group, true);
            out.append(String.format(
                "    public class %1$s extends %4$s<%2$s>\n" +
                "    {\n" +
                "        public %1$s(final %3$s parent)\n" +
                "        {\n" +
                "            super(parent);\n" +
                "        }\n\n" +
                "        public %1$s iterator()\n" +
                "        {\n" +
                "            reset();\n" +
                "            return this;\n" +
                "        }\n\n" +
                "    }\n\n",
                iteratorClassName,
                groupDecoderName,
                parentDecoderName,
                parentLocation));
        }
        else
        {
            generateNormalGroupIterator(
                out, numberFieldReset, iteratorClassName, groupDecoderName, groupPropertyName);
        }
    }

    private void generateNormalGroupIterator(
        final Writer out,
        final String numberFieldReset,
        final String iteratorClassName,
        final String groupDecoderName,
        final String groupPropertyName)
        throws IOException
    {
        final String parentDecoderName = decoderClassName(currentAggregate());
        out.append(String.format(
            "    " + GENERATED_ANNOTATION +
            "    public class %1$s implements Iterable<%2$s>, java.util.Iterator<%2$s>\n" +
            "    {\n" +
            "        private final %3$s parent;\n" +
            "        private int remainder;\n" +
            "        private %2$s current;\n\n" +
            "        public %1$s(final %3$s parent)\n" +
            "        {\n" +
            "            this.parent = parent;\n" +
            "        }\n\n" +
            "        public boolean hasNext()\n" +
            "        {\n" +
            "            return remainder > 0 && current != null;\n" +
            "        }\n\n" +
            "        public %2$s next()\n" +
            "        {\n" +
            "            remainder--;\n" +
            "            final %2$s value = current;\n" +
            "            current = current.next();\n" +
            "            return value;\n" +
            "        }\n\n" +
            "        public int numberFieldValue()\n" +
            "        {\n" +
            "            return %4$s;\n" +
            "        }\n\n" +
            "        public void reset()\n" +
            "        {\n" +
            "            remainder = numberFieldValue();\n" +
            "            current = parent.%5$s();\n" +
            "        }\n\n" +
            "        public %1$s iterator()\n" +
            "        {\n" +
            "            reset();\n" +
            "            return this;\n" +
            "        }\n\n" +
            "    }\n\n",
            iteratorClassName,
            groupDecoderName,
            parentDecoderName,
            numberFieldReset,
            groupPropertyName));
    }

    private String fieldGetter(
        final Entry entry,
        final Field field,
        final boolean aggregateIsInParent)
    {
        // Entry may exist in common parent component interface but there may not be a common parent message
        if (entry.isInParent() && aggregateIsInParent)
        {
            return "";
        }

        final String name = field.name();
        final String fieldName = formatPropertyName(name);
        final Type type = field.type();
        final String optionalCheck = optionalCheck(entry);
        final String asStringBody = generateAsStringBody(entry, name, fieldName);
        final String javadoc = generateAccessorJavadoc(field);

        final String extraStringDecode = type.isStringBased() ? String.format(
            "    %4$spublic String %1$sAsString()\n" +
            "    {\n" +
            "        return %3$s;\n" +
            "    }\n\n" +
            "    %4$spublic AsciiSequenceView %1$s(final AsciiSequenceView view)\n" +
            "    {\n" +
            "%2$s" +
            "        return view.wrap(buffer, %1$sOffset, %1$sLength);\n" +
            "    }\n\n",
            fieldName, wrapEmptyBuffer ? wrapEmptyBuffer(entry) : optionalCheck, asStringBody, javadoc) : "";

        // Need to keep offset and length split due to the abject fail that is the DATA type.
        final String lengthBasedFields = type.hasLengthField(flyweightsEnabled) ? String.format(
            "    %4$s int %1$sLength;\n\n" +
            "    %5$spublic int %1$sLength()\n" +
            "    {\n" +
            "%2$s" +
            "        return %1$sLength;\n" +
            "    }\n\n" +
            "%3$s",
            fieldName, optionalCheck, extraStringDecode, scope, javadoc) : "";

        final String offsetField = type.hasOffsetField(flyweightsEnabled) ?
            String.format("    %3$s int %1$sOffset;\n\n%2$s", fieldName, lengthBasedFields, scope) : "";

        final String enumValueDecoder = String.format(
            type.isStringBased() ?
            "%1$s.decode(%2$sWrapper)" :
            // Need to ensure that decode the field
            (flyweightsEnabled && (type.isIntBased() || type.isFloatBased())) ?
            "%1$s.decode(this.%2$s())" :
            "%1$s.decode(%2$s)",
            enumName(name),
            fieldName);
        final String enumStringBasedWrapperField =
            String.format("    %2$s final CharArrayWrapper %1$sWrapper = new CharArrayWrapper();\n", fieldName, scope);
        final String enumDecoder = shouldGenerateClassEnumMethods(field) ?
            String.format(
            "%4$s" +
            "    %7$spublic %6$s %2$sAsEnum()\n" +
            "    {\n" +
            (!entry.required() ? "        if (!has%1$s)\n return %6$s.%5$s;\n" : "") +
            (type.isStringBased() ? "        %2$sWrapper.wrap(this.%2$s(), %2$sLength);\n" : "") +
            "        return %3$s;\n" +
            "    }\n\n",
            name, fieldName, enumValueDecoder, enumStringBasedWrapperField, NULL_VAL_NAME, enumName(name),
            javadoc) : (field.type().isMultiValue() || field.type() == Type.STRING) ? enumStringBasedWrapperField : "";

        final String lazyInitialisation = fieldLazyInstantialisation(field, fieldName);

        return String.format(
            "    %10$s %1$s %2$s%3$s;\n\n" +
            "%4$s" +
            "    %11$spublic %1$s %2$s()\n" +
            "    {\n" +
            "%5$s" +
            "%9$s" +
            "        return %2$s;\n" +
            "    }\n\n" +
            "%6$s\n" +
            "%7$s\n" +
            "%8$s",
            javaTypeOf(type),
            fieldName,
            fieldInitialisation(type),
            hasField(entry),
            optionalCheck,
            optionalGetter(entry),
            offsetField,
            enumDecoder,
            flyweightsEnabled ? lazyInitialisation : "",
            scope,
            javadoc);
    }

    private String wrapEmptyBuffer(final Entry entry)
    {
        return entry.required() ? "" : String.format(
          "        if (!has%s)\n" +
          "        {\n" +
          "            return view.wrap(buffer, 0, 0);\n" +
          "        }\n\n",
          entry.name());
    }

    private String generateAsStringBody(final Entry entry, final String name, final String fieldName)
    {
        final String asStringBody;

        if (flyweightsEnabled)
        {
            asStringBody = String.format(entry.required() ?
                "buffer != null ? buffer.getStringWithoutLengthAscii(%1$sOffset, %1$sLength) : \"\"" :
                "has%2$s ? buffer.getStringWithoutLengthAscii(%1$sOffset, %1$sLength) : null",
                fieldName,
                name);
        }
        else
        {
            asStringBody = String.format(entry.required() ?
                "new String(%1$s, 0, %1$sLength)" :
                "has%2$s ? new String(%1$s, 0, %1$sLength) : null",
                fieldName,
                name);
        }
        return asStringBody;
    }

    private static String fieldLazyInstantialisation(final Field field, final String fieldName)
    {
        final int tag = field.number();
        switch (field.type())
        {
            // We read and cache the number in group field so that it doesn't get re-read during a reset.
            case NUMINGROUP:
                return String.format(
                    "%1$s = groupNoField(buffer, %1$s, has%2$s, %1$sOffset, %1$sLength, %3$d, " +
                        CODEC_VALIDATION_ENABLED + ");\n",
                    fieldName,
                    field.name(),
                    field.number());

            case INT:
            case LENGTH:
            case SEQNUM:
            case DAYOFMONTH:
                return lengthBasedFieldLazyInitialization(fieldName, "getIntFlyweight(buffer",
                    ", " + tag + ", " + CODEC_VALIDATION_ENABLED);

            case LONG:
                return lengthBasedFieldLazyInitialization(fieldName, "getLongFlyweight(buffer",
                    ", " + tag + ", " + CODEC_VALIDATION_ENABLED);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return lengthBasedFieldLazyInitialization(fieldName, "getFloatFlyweight(buffer, " +
                    fieldName, ", " + tag + ", " + CODEC_VALIDATION_ENABLED);

            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return lengthBasedFieldLazyInitialization(fieldName, "buffer.getChars(" + fieldName, "");

            case DATA:
            case XMLDATA:
                // Length extracted separately from a preceding field
                final Field associatedLengthField = field.associatedLengthField();
                if (associatedLengthField == null)
                {
                    throw new IllegalStateException("No associated length field for: " + field);
                }
                final String associatedFieldName = formatPropertyName(associatedLengthField.name());
                return String.format(
                    "        if (buffer != null && %2$s > 0)\n" +
                    "        {\n" +
                    "            %1$s = buffer.getBytes(%1$s, %1$sOffset, %2$s);\n" +
                    "        }\n",
                    fieldName,
                    associatedFieldName);

            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case TZTIMEONLY:
            case TZTIMESTAMP:
            case MONTHYEAR:
                return lengthBasedFieldLazyInitialization(fieldName, "buffer.getBytes(" + fieldName, "");

            case BOOLEAN:
            case CHAR:
            default:
                return "";
        }
    }

    private static String lengthBasedFieldLazyInitialization(
        final String fieldName, final String decodeMethod, final String endArgs)
    {
        return String.format(
            "        if (buffer != null && %1$sLength > 0)\n" +
            "        {\n" +
            "            %1$s = %2$s, %1$sOffset, %1$sLength%3$s);\n" +
            "        }\n",
            fieldName,
            decodeMethod,
            endArgs);
    }

    private String fieldInitialisation(final Type type)
    {
        switch (type)
        {
            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return String.format(" = new char[%d]", initialBufferSize);

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return " = DecimalFloat.newNaNValue()";

            case BOOLEAN:
                return "";

            case CHAR:
                return " = MISSING_CHAR";

            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return " = MISSING_INT";

            case LONG:
                return " = MISSING_LONG";

            case DATA:
            case XMLDATA:
                return initByteArray(initialBufferSize);

            case UTCTIMESTAMP:
                return initByteArray(UtcTimestampDecoder.LENGTH_WITH_MICROSECONDS);

            case LOCALMKTDATE:
                return initByteArray(LocalMktDateDecoder.LENGTH);

            case UTCTIMEONLY:
                return initByteArray(UtcTimeOnlyDecoder.LONG_LENGTH);

            case UTCDATEONLY:
                return initByteArray(UtcDateOnlyDecoder.LENGTH);

            case MONTHYEAR:
                return initByteArray(MonthYear.LONG_LENGTH);

            case TZTIMEONLY:
                return initByteArray(UtcTimeOnlyDecoder.LONG_LENGTH + 7);

            case TZTIMESTAMP:
                return initByteArray(UtcTimestampDecoder.LENGTH_WITH_MICROSECONDS + 7);

            default:
                throw new UnsupportedOperationException("Unknown type: " + type);
        }
    }

    private String initByteArray(final int initialBufferSize)
    {
        return String.format(" = new byte[%d]", initialBufferSize);
    }

    private String optionalGetter(final Entry entry)
    {
        return entry.required() ? "" : hasGetter(entry.name());
    }

    private String optionalCheck(final Entry entry)
    {
        return entry.required() ? "" : String.format(
            "        if (!has%s)\n" +
            "        {\n" +
            "            throw new IllegalArgumentException(\"No value for optional field: %1$s\");\n" +
            "        }\n\n",
            entry.name());
    }

    private String javaTypeOf(final Type type)
    {
        switch (type)
        {
            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                return "int";

            case LONG:
                return "long";

            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                return "DecimalFloat";

            case CHAR:
                return "char";

            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                return "char[]";

            case BOOLEAN:
                return "boolean";

            case DATA:
            case XMLDATA:
            case UTCTIMESTAMP:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case MONTHYEAR:
            case LOCALMKTDATE:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return "byte[]";

            default:
                throw new UnsupportedOperationException("Unknown type: " + type);
        }
    }

    private String decodeMethod(final List<Entry> entries, final Aggregate aggregate, final AggregateType type)
    {
        if (isSharedParent())
        {
            return "";
        }

        final boolean hasCommonCompounds = type == MESSAGE;
        final boolean isGroup = type == GROUP;
        final boolean isHeader = type == HEADER;
        final String endGroupCheck = endGroupCheck(aggregate, isGroup);
        final String prefix = generateDecodePrefix(aggregate, hasCommonCompounds, isGroup, isHeader, endGroupCheck);
        final String body = entries.stream()
            .map(this::decodeEntry)
            .collect(joining("\n", "", "\n"));

        final String suffix =
            "            default:\n" +
            "                if (!" + CODEC_REJECT_UNKNOWN_FIELD_ENABLED + ")\n" +
            "                {\n" +
            (isGroup ?
            "                    seenFields.remove(tag);\n" :
            "                    alreadyVisitedFields.remove(tag);\n") +
            "                }\n" +
            (isGroup ? "" :
            "                else\n" +
            "                {\n" +
            "                    if (!" + unknownFieldPredicate(type) + ")\n" +
            "                    {\n" +
            "                        unknownFields.add(tag);\n" +
            "                    }\n" +
            "                }\n") +

            // Skip the thing if it's a completely unknown field and you aren't validating messages
            "                if (" + CODEC_REJECT_UNKNOWN_FIELD_ENABLED +
            " || " + unknownFieldPredicate(type) + ")\n" +
            "                {\n" +
            decodeTrailerOrReturn(hasCommonCompounds, 5) +
            "                }\n" +
            "\n" +
            "            }\n\n" +
            "            if (position < (endOfField + 1))\n" +
            "            {\n" +
            "                position = endOfField + 1;\n" +
            "            }\n" +
            "        }\n" +
            decodeTrailerOrReturn(hasCommonCompounds, 2) +
            "    }\n\n";
        return prefix + body + suffix;
    }

    private String generateDecodePrefix(
        final Aggregate aggregate,
        final boolean hasCommonCompounds,
        final boolean isGroup,
        final boolean isHeader,
        final String endGroupCheck)
    {
        return "    public int decode(final AsciiBuffer buffer, final int offset, final int length)\n" +
            "    {\n" +
            "        // Decode " + aggregate.name() + "\n" +
            "        int seenFieldCount = 0;\n" +
            "        if (" + CODEC_VALIDATION_ENABLED + ")\n" +
            "        {\n" +
            "            missingRequiredFields.copy(" + REQUIRED_FIELDS + ");\n" +
            (isGroup ? "" : "            alreadyVisitedFields.clear();\n") +
            "        }\n" +
            "        this.buffer = buffer;\n" +
            "        final int end = offset + length;\n" +
            "        int position = offset;\n" +
            "        int positionIter = position;\n" +
            (hasCommonCompounds ? "        position += header.decode(buffer, position, length);\n" : "") +
            (isGroup ? "        seenFields.clear();\n" : "") +
            "        int tag;\n\n" +
            "        while (position < end)\n" +
            "        {\n" +
            "            final int equalsPosition = buffer.scan(position, end, '=');\n" +
            "            if (equalsPosition == AsciiBuffer.UNKNOWN_INDEX)\n" +
            "            {\n" +
            "               return position;\n" +
            "            }\n" +
            "            tag = buffer.getInt(position, equalsPosition);\n" +
            endGroupCheck +
            "            final int valueOffset = equalsPosition + 1;\n" +
            "            int endOfField = buffer.scan(valueOffset, end, START_OF_HEADER);\n" +
            "            if (endOfField == AsciiBuffer.UNKNOWN_INDEX)\n" +
            "            {\n" +
            "                rejectReason = " + VALUE_IS_INCORRECT + ";\n" +
            "                break;\n" +
            "            }\n" +
            "            final int valueLength = endOfField - valueOffset;\n" +
            "            if (" + CODEC_VALIDATION_ENABLED + ")\n" +
            "            {\n" +
            "                if (tag <= 0)\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + INVALID_TAG_NUMBER + ";\n" +
            "                }\n" +
            "                else if (valueLength == 0)\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + TAG_SPECIFIED_WITHOUT_A_VALUE + ";\n" +
            "                }\n" +
            headerValidation(isHeader) +
            (isGroup ? "" :
            "                if (!alreadyVisitedFields.add(tag))\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + TAG_APPEARS_MORE_THAN_ONCE + ";\n" +
            "                }\n") +
            "                missingRequiredFields.remove(tag);\n" +
            "                seenFieldCount++;\n" +
            "            }\n\n" +
            "            switch (tag)\n" +
            "            {\n";
    }

    private String decodeTrailerOrReturn(final boolean hasCommonCompounds, final int indent)
    {
        return (hasCommonCompounds ?
            indent(indent, "position += trailer.decode(buffer, position, end - position);\n") : "") +
            indent(indent, "return position - offset;\n");
    }

    private String unknownFieldPredicate(final AggregateType type)
    {
        switch (type)
        {
            case TRAILER:
                return REQUIRED_FIELDS + ".contains(tag)";
            case HEADER:
                //HeaderDecoder has the special requirement of being used independently so cannot use context
                // of message to determine if field is unknown
                return "true";
            case MESSAGE:
                return "(trailer." + REQUIRED_FIELDS + ".contains(tag))";
            default:
                return "(trailer." + REQUIRED_FIELDS + ".contains(tag) || " + MESSAGE_FIELDS + ".contains(tag))";
        }
    }

    private String endGroupCheck(final Aggregate aggregate, final boolean isGroup)
    {
        final String endGroupCheck;
        if (isGroup)
        {
            endGroupCheck = String.format(
                "            if (!seenFields.add(tag))\n" +
                "            {\n" +
                "                if (next == null)\n" +
                "                {\n" +
                "                    next = new %1$s(trailer, %2$s);\n" +
                "                }\n" +
                "                return position - offset;\n" +
                "            }\n",
                decoderClassName(aggregate),
                MESSAGE_FIELDS);
        }
        else
        {
            endGroupCheck = "";
        }

        return endGroupCheck;
    }

    private String headerValidation(final boolean isHeader)
    {
        return isHeader ?
            "                else if (seenFieldCount == 0 && tag != 8)\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER + ";\n" +
            "                }\n" +
            "                else if (seenFieldCount == 1 && tag != 9)\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER + ";\n" +
            "                }\n" +
            "                else if (seenFieldCount == 2 && tag != 35)\n" +
            "                {\n" +
            "                    invalidTagId = tag;\n" +
            "                    rejectReason = " + TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER + ";\n" +
            "                }\n" :
            "";
    }

    private String decodeEntry(final Entry entry)
    {
        return entry.matchEntry(
            (e) -> decodeField(e, ""),
            this::decodeGroup,
            this::decodeComponent,
            (e) -> "");
    }

    private String decodeComponent(final Entry entry)
    {
        final Component component = (Component)entry.element();
        return component
            .entries()
            .stream()
            .map(this::decodeEntry)
            .collect(joining("\n", "", "\n"));
    }

    protected String componentAppendTo(final Component component)
    {
        return component
            .entries()
            .stream()
            .map(this::generateEntryAppendTo)
            .collect(joining("\n"));
    }

    private String decodeGroup(final Entry entry)
    {
        final Group group = (Group)entry.element();

        final Entry numberField = group.numberField();
        final String groupNumberField = formatPropertyName(numberField.name());
        final String getNumberField;
        if (flyweightsEnabled)
        {
            // Pass missing int here to force re-read as we're in the decode method and don't want a stale cached value
            getNumberField = String.format(
                "this.%1$s = groupNoField(buffer, MISSING_INT, has%2$s, %1$sOffset, %1$sLength, %3$d, " +
                CODEC_VALIDATION_ENABLED + ")",
                groupNumberField,
                numberField.name(),
                numberField.number());
        }
        else
        {
            getNumberField = "this." + groupNumberField;
        }

        final String parseGroup = String.format(
            "                if (%1$s == null)\n" +
            "                {\n" +
            "                    %1$s = new %2$s(trailer, %5$s);\n" +
            "                }\n" +
            "                %2$s %1$sCurrent = %1$s;\n" +
            "                position = endOfField + 1;\n" +
            "                final int %3$s = %4$s;\n" +
            "                for (int i = 0; i < %3$s && position < end; i++)\n" +
            "                {\n" +
            "                    if (%1$sCurrent != null)\n" +
            "                    {\n" +
            "                        positionIter = %1$sCurrent.decode(buffer, position, end - position);\n" +
            "                        if (positionIter == 0 && " + CODEC_VALIDATION_ENABLED + ")\n" +
            "                        {\n" +
            "                                invalidTagId = tag;\n" +
            "                                rejectReason = " + INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP + ";\n" +
            "                                break;\n" +
            "                        }\n" +
            "                        else\n" +
            "                        {\n" +
            "                                position += positionIter;\n" +
            "                        }\n" +
            "                        %1$sCurrent = %1$sCurrent.next();\n" +
            "                    }\n" +
            "                }\n" +
            "                if (" + CODEC_VALIDATION_ENABLED + ")\n" +
            "                {\n" +
            "                    final int checkEqualsPosition = buffer.scan(position, end, '=');\n" +
            "                    if (checkEqualsPosition != AsciiBuffer.UNKNOWN_INDEX)\n" +
            "                    {\n" +
            "                        final int checkTag = buffer.getInt(position, checkEqualsPosition);\n" +
            "                        if (%1$s." + ALL_GROUP_FIELDS + ".contains(checkTag))\n" +
            "                        {\n" +
            "                            invalidTagId = tag;\n" +
            "                            rejectReason = %6$s;\n" +
            "                            while (%1$sCurrent != null) \n" +
            "                            {\n" +
            "                               position += %1$sCurrent.decode(buffer, position, end - position);\n" +
            "                               %1$sCurrent = %1$sCurrent.next();\n" +
            "                            }\n" +
            "                            return position - offset;\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n",
            formatPropertyName(group.name()),
            decoderClassName(group),
            groupNumberField,
            // Have to make a call to initialise the group number at this point when flyweighting.
            getNumberField,
            MESSAGE_FIELDS,
            INCORRECT_NUMINGROUP_COUNT_FOR_REPEATING_GROUP);

        return decodeField(group.numberField(), parseGroup);
    }

    private String decodeField(final Entry entry, final String suffix)
    {
        // Uses variables from surrounding context:
        // int tag = the tag number of the field
        // int valueOffset = starting index of the value
        // int valueLength = the number of bytes for the value
        // int endOfField = the end index of the value

        final Field field = (Field)entry.element();
        final String name = entry.name();
        final String fieldName = formatPropertyName(name);

        return String.format(
            "            case Constants.%s:\n" +
            "%s" +
            "%s" +
            "%s" +
            "%s" +
            "%s" +
            "                break;\n",
            constantName(name),
            optionalAssign(entry),
            fieldDecodeMethod(field, fieldName),
            storeOffsetForVariableLengthFields(field.type(), fieldName),
            storeLengthForVariableLengthFields(field.type(), fieldName),
            suffix);
    }

    private String storeLengthForVariableLengthFields(final Type type, final String fieldName)
    {
        return type.hasLengthField(flyweightsEnabled) ?
            String.format("                %sLength = valueLength;\n", fieldName) :
            "";
    }

    private String storeOffsetForVariableLengthFields(final Type type, final String fieldName)
    {
        return type.hasOffsetField(flyweightsEnabled) ?
            String.format("                %sOffset = valueOffset;\n", fieldName) :
            "";
    }

    private String optionalAssign(final Entry entry)
    {
        return entry.required() ? "" : String.format("                has%s = true;\n", entry.name());
    }

    private String fieldDecodeMethod(final Field field, final String fieldName)
    {
        final String prefix = String.format("                %s = ", fieldName);
        final String decodeMethod;
        switch (field.type())
        {
            case INT:
            case LENGTH:
            case SEQNUM:
            case NUMINGROUP:
            case DAYOFMONTH:
                if (flyweightsEnabled)
                {
                    return "";
                }
                decodeMethod = String.format(
                    "getInt(buffer, valueOffset, endOfField, %d, " + CODEC_VALIDATION_ENABLED + ")", field.number());
                break;
            case LONG:
                if (flyweightsEnabled)
                {
                    return "";
                }
                decodeMethod = String.format(
                    "getLong(buffer, valueOffset, endOfField, %d, " + CODEC_VALIDATION_ENABLED + ")", field.number());
                break;
            case FLOAT:
            case PRICE:
            case PRICEOFFSET:
            case QTY:
            case QUANTITY:
            case PERCENTAGE:
            case AMT:
                if (flyweightsEnabled)
                {
                    return "";
                }
                decodeMethod = String.format(
                    "getFloat(buffer, %s, valueOffset, valueLength, %d, " + CODEC_VALIDATION_ENABLED +
                      ", decimalFloatOverflowHandler)",
                    fieldName, field.number());
                break;
            case CHAR:
                decodeMethod = "buffer.getChar(valueOffset)";
                break;
            case STRING:
            case MULTIPLEVALUESTRING:
            case MULTIPLESTRINGVALUE:
            case MULTIPLECHARVALUE:
            case CURRENCY:
            case EXCHANGE:
            case COUNTRY:
            case LANGUAGE:
                if (flyweightsEnabled)
                {
                    return "";
                }
                decodeMethod = String.format("buffer.getChars(%s, valueOffset, valueLength)", fieldName);
                break;

            case BOOLEAN:
                decodeMethod = "buffer.getBoolean(valueOffset)";
                break;
            case DATA:
            case XMLDATA:
                // Length extracted separately from a preceding field
                final String associatedFieldName = formatPropertyName(field.associatedLengthField().name());
                if (flyweightsEnabled)
                {
                    return String.format(
                        "                endOfField = valueOffset + this.%1$s();\n", associatedFieldName);
                }
                return String.format(
                    "                %1$s = buffer.getBytes(%1$s, valueOffset, %2$s);\n" +
                    "                endOfField = valueOffset + %2$s;\n",
                    fieldName,
                    associatedFieldName);
            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case UTCTIMEONLY:
            case UTCDATEONLY:
            case TZTIMEONLY:
            case TZTIMESTAMP:
            case MONTHYEAR:
                if (flyweightsEnabled)
                {
                    return "";
                }
                decodeMethod = String.format("buffer.getBytes(%s, valueOffset, valueLength)", fieldName);
                break;

            default:
                throw new UnsupportedOperationException("Unknown type: " + field.type() + " in " + fieldName);
        }
        return prefix + decodeMethod + ";\n";
    }

    protected String stringAppendTo(final String fieldName)
    {
        return String.format("builder.append(this.%1$s(), 0, %1$sLength())", fieldName);
    }

    protected String dataAppendTo(final Field field, final String fieldName)
    {
        final String lengthName = formatPropertyName(field.associatedLengthField().name());

        if (flyweightsEnabled)
        {
            return String.format("appendData(builder, this.%1$s(), this.%2$s())", fieldName, lengthName);
        }

        return String.format("appendData(builder, %1$s, %2$s)", fieldName, lengthName);
    }

    protected String timeAppendTo(final String fieldName)
    {
        if (flyweightsEnabled)
        {
            return String.format("appendData(builder, this.%1$s(), %1$sLength())", fieldName);
        }

        return String.format("appendData(builder, %1$s, %1$sLength)", fieldName);
    }

    protected boolean hasFlag(final Entry entry, final Field field)
    {
        return !entry.required();
    }

    protected String resetTemporalValue(final String name)
    {
        return resetStringBasedData(name);
    }

    protected String resetComponents(final List<Entry> entries, final StringBuilder methods)
    {
        return entries
            .stream()
            .filter(Entry::isComponent)
            .map((entry) -> resetEntries(((Component)entry.element()).entries(), methods))
            .collect(joining());
    }

    protected String resetStringBasedData(final String name)
    {
        return String.format(
            "    public void %1$s()\n" +
                    "    {\n" +
                    "        %2$sOffset = 0;\n" +
                    "        %2$sLength = 0;\n" +
                    "    }\n\n",
            nameOfResetMethod(name),
            formatPropertyName(name));
    }

    protected String groupEntryAppendTo(final Group group, final String name)
    {
        if (isSharedParent())
        {
            return "";
        }

        final String numberField = group.numberField().name();
        return String.format(
            "        if (has%2$s)\n" +
            "        {\n" +
            "            indent(builder, level);\n" +
            "            builder.append(\"\\\"%1$s\\\": [\\n\");\n" +
            "            %3$s %4$s = this.%4$s;\n" +
            "            for (int i = 0, size = this.%5$s; i < size; i++)\n" +
            "            {\n" +
            "                indent(builder, level);\n" +
            "                %4$s.appendTo(builder, level + 1);\n" +
            "                if (%4$s.next() != null)\n" +
            "                {\n" +
            "                    builder.append(',');\n" +
            "                }\n" +
            "                builder.append('\\n');\n" +
            "                %4$s = %4$s.next();" +
            "            }\n" +
            "            indent(builder, level);\n" +
            "            builder.append(\"],\\n\");\n" +
            "        }\n",
            name,
            numberField,
            decoderClassName(name),
            formatPropertyName(name),
            formatPropertyName(numberField));
    }

    private String generateToEncoder(final Aggregate aggregate)
    {
        final String entriesToEncoder = aggregate
            .entries()
            .stream()
            .map(this::generateEntryToEncoder)
            .collect(joining("\n"));
        final String name = aggregate.name();

        String encoderClassName = encoderClassName(name);
        // Resolve ambiguous name with inherited parent aggregate
        if (aggregate instanceof Group)
        {
            encoderClassName = encoderClassName(parentAggregate().name()) + "." + encoderClassName;
        }

        return String.format(
            "    public %1$s toEncoder(final Encoder encoder)\n" +
            "    {\n" +
            "        return toEncoder((%1$s)encoder);\n" +
            "    }\n\n" +
            "    public %1$s toEncoder(final %1$s encoder)\n" +
            "    {\n" +
            "        encoder.reset();\n" +
            "%2$s" +
            "        return encoder;\n" +
            "    }\n\n",
            encoderClassName,
            entriesToEncoder);
    }

    private String generateEntryToEncoder(final Entry entry)
    {
        return generateEntryToEncoder(entry, "encoder");
    }

    private String generateEntryToEncoder(final Entry entry, final String encoderName)
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
                    indentedFieldToEncoder(encoderName, field, "            "));
            }
            else
            {
                return indentedFieldToEncoder(encoderName, field, "        ");
            }
        }
        else if (element instanceof Group)
        {
            return groupEntryToEncoder((Group)element, name, encoderName);
        }
        else if (element instanceof Component)
        {
            return componentToEncoder((Component)element, encoderName);
        }

        return "";
    }

    private String indentedFieldToEncoder(final String encoderName, final Field field, final String replacement)
    {
        final String fieldToEncoder = fieldToEncoder(field, encoderName);
        return NEWLINE
            .matcher(fieldToEncoder)
            .replaceAll(replacement);
    }

    protected String groupEntryToEncoder(final Group group, final String name, final String encoderName)
    {
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
            "                    %3$s.toEncoder(%3$sEncoder);\n" +
            "                    %3$s = %3$s.next();\n" +
            "                    %3$sEncoder = %3$sEncoder.next();\n" +
            "                }\n" +
            "            }\n" +
            "        }\n",
            numberField,
            decoderClassName(name),
            formatPropertyName(name),
            formatPropertyName(numberField),
            encoderName,
            encoderClassName(name));
    }

    protected String componentToEncoder(final Component component, final String encoderName)
    {
        final String name = component.name();
        final String varName = formatPropertyName(name);

        final String prefix = String.format(
            "\n        final %1$s %2$s = %3$s.%2$s();",
            encoderClassName(name),
            varName,
            encoderName);

        return component
            .entries()
            .stream()
            .map((entry) -> generateEntryToEncoder(entry, varName))
            .collect(joining("\n",
                prefix,
                "\n"));
    }

    private String fieldToEncoder(final Field field, final String encoderName)
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
                return String.format("%2$s.%1$s(this.%1$s(), 0, %1$sLength());", fieldName, encoderName);

            case UTCTIMEONLY:
            case UTCDATEONLY:
            case UTCTIMESTAMP:
            case LOCALMKTDATE:
            case MONTHYEAR:
            case TZTIMEONLY:
            case TZTIMESTAMP:
                return String.format("%2$s.%1$sAsCopy(this.%1$s(), 0, %1$sLength());", fieldName, encoderName);

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
                    "%3$s.%1$sAsCopy(this.%1$s(), 0, %2$s());%n%3$s.%2$s(this.%2$s());",
                    fieldName, lengthName, encoderName);

            case NUMINGROUP:
                // Deliberately blank since it gets set by the group ToEncoder logic.
            default:
                return "";
        }
    }

    protected String optionalReset(final Field field, final String name)
    {
        return resetByFlag(name);
    }

    protected boolean appendToChecksHasGetter(final Entry entry, final Field field)
    {
        return hasFlag(entry, field);
    }

    protected String anyFieldsAppendTo(final AnyFields element)
    {
        return "";
    }

    protected String resetAnyFields(final List<Entry> entries, final StringBuilder methods)
    {
        return "";
    }
}
