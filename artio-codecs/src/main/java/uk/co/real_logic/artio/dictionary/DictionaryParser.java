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

import org.agrona.Verify;
import org.w3c.dom.*;
import uk.co.real_logic.artio.dictionary.generation.CodecConfiguration;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Type;
import uk.co.real_logic.artio.dictionary.ir.Field.Value;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toMap;
import static javax.xml.xpath.XPathConstants.NODESET;
import static uk.co.real_logic.artio.dictionary.ir.Field.Type.*;

/**
 * Parses XML format dictionary files and into instances of
 * {@link uk.co.real_logic.artio.dictionary.ir.Dictionary}.
 */
public final class DictionaryParser
{
    private static final String FIELD_EXPR = "/fix/fields/field";
    private static final String MESSAGE_EXPR = "/fix/messages/message";
    private static final String COMPONENT_EXPR = "/fix/components/component";
    private static final String HEADER_EXPR = "/fix/header/field";
    private static final String TRAILER_EXPR = "/fix/trailer/field";

    public static final String HEADER = "Header";
    public static final String TRAILER = "Trailer";
    public static final String DEFAULT_SPEC_TYPE = "FIX";

    private final DocumentBuilder documentBuilder;
    private final XPathExpression findField;
    private final XPathExpression findMessage;
    private final XPathExpression findComponent;
    private final XPathExpression findHeader;
    private final XPathExpression findTrailer;

    private final boolean allowDuplicates;

    public DictionaryParser(final boolean allowDuplicates)
    {
        this.allowDuplicates = allowDuplicates;
        try
        {
            documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

            final XPath xPath = XPathFactory.newInstance().newXPath();
            findField = xPath.compile(FIELD_EXPR);
            findMessage = xPath.compile(MESSAGE_EXPR);
            findComponent = xPath.compile(COMPONENT_EXPR);
            findHeader = xPath.compile(HEADER_EXPR);
            findTrailer = xPath.compile(TRAILER_EXPR);
        }
        catch (final ParserConfigurationException | XPathExpressionException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public Dictionary parse(final InputStream in, final Dictionary fixtDictionary) throws Exception
    {
        final Document document = documentBuilder.parse(in);
        final Map<String, Field> fields = parseFields(document);
        final Map<Entry, String> forwardReferences = new HashMap<>();
        final Map<String, Component> components = parseComponents(document, fields, forwardReferences);
        final List<Message> messages = parseMessages(document, fields, components, forwardReferences);

        reconnectForwardReferences(forwardReferences, components);
        sanitizeDictionary(fields, messages);
        validateDataFields(messages);
        validateDataFields(components.values());
        validateAnyFields(messages);

        if (fixtDictionary != null)
        {
            final ArrayList<Message> allMessages = new ArrayList<>(fixtDictionary.messages());
            allMessages.addAll(messages);
            final HashMap<String, Field> allFields = new HashMap<>(fixtDictionary.fields());
            allFields.putAll(fields);
            final HashMap<String, Component> allComponents = new HashMap<>(fixtDictionary.components());
            allComponents.putAll(components);

            return new Dictionary(allMessages, allFields, allComponents,
                fixtDictionary.header(), fixtDictionary.trailer(),
                fixtDictionary.specType(), fixtDictionary.majorVersion(), fixtDictionary.minorVersion());
        }
        else
        {
            final NamedNodeMap fixAttributes = document.getElementsByTagName("fix").item(0).getAttributes();
            final int majorVersion = getInt(fixAttributes, "major");
            final int minorVersion = getInt(fixAttributes, "minor");

            final Component header = extractComponent(
                document, fields, findHeader, HEADER, components, forwardReferences);
            final Component trailer = extractComponent(
                document, fields, findTrailer, TRAILER, components, forwardReferences);

            validateDataFieldsInAggregate(header);
            validateDataFieldsInAggregate(trailer);

            final String specType = getValueOrDefault(fixAttributes, "type", DEFAULT_SPEC_TYPE);
            return new Dictionary(messages, fields, components, header, trailer, specType, majorVersion, minorVersion);
        }
    }

    private void validateDataFields(final Collection<? extends Aggregate> aggregates)
    {
        aggregates.forEach(this::validateDataFieldsInAggregate);
    }

    private void validateDataFieldsInAggregate(final Aggregate aggregate)
    {
        final Map<String, Field> nameToField;
        try
        {
            nameToField = aggregate
                .fieldEntries()
                .map(entry -> (Field)entry.element())
                .collect(toMap(Field::name, f -> f));
        }
        catch (final IllegalStateException e)
        {
            throw new IllegalStateException("Exception when processing: " + aggregate.name(), e);
        }

        for (final Entry entry : aggregate.entries())
        {
            entry.forEach(
                field ->
                {
                    checkAssociatedLengthField(nameToField, field, aggregate.name());
                },
                this::validateDataFieldsInAggregate,
                this::validateDataFieldsInAggregate,
                (anyFields) -> {});
        }
    }

    public static void checkAssociatedLengthField(
        final Map<String, Field> nameToField, final Field field, final String aggregateName)
    {
        if (field.type().isDataBased())
        {
            final String name = field.name();
            if (!(hasLengthField(field, "Length", nameToField) ||
                hasLengthField(field, "Len", nameToField)))
            {
                throw new IllegalStateException(
                    String.format("Each DATA field must have a corresponding LENGTH field using the " +
                    "suffix 'Len' or 'Length'. %1$s is missing a length field in %2$s",
                    name,
                    aggregateName));
            }
        }
    }

    private static boolean hasLengthField(final Field field, final String suffix, final Map<String, Field> nameToField)
    {
        final String fieldName = field.name() + suffix;
        final Field associatedLengthField = nameToField.get(fieldName);
        if (associatedLengthField != null)
        {
            final Type type = associatedLengthField.type();
            if (type == LENGTH || type == INT)
            {
                field.associatedLengthField(associatedLengthField);
                return true;
            }
        }

        return false;
    }

    private void validateAnyFields(final List<Message> messages)
    {
        for (final Message message : messages)
        {
            final List<String> anyFieldsEntries = message.anyFieldsEntries()
                .map(Entry::name)
                .collect(Collectors.toList());

            if (anyFieldsEntries.size() > 1)
            {
                throw new IllegalStateException("At most one anyFields entry is allowed, but " + message.name() +
                    " contains multiple: " + anyFieldsEntries);
            }
        }
    }

    private void correctMultiCharacterCharEnums(final Map<String, Field> fields)
    {
        fields.values()
            .stream()
            .filter(Field::isEnum)
            .filter(field -> field.type() == CHAR)
            .filter(this::hasMultipleCharacters)
            .forEach(field -> field.type(STRING));
    }

    private boolean hasMultipleCharacters(final Field field)
    {
        return field.values().stream().anyMatch(value -> value.representation().length() > 1);
    }

    private void reconnectForwardReferences(final Map<Entry, String> forwardReferences,
        final Map<String, Component> components)
    {
        forwardReferences.forEach((entry, name) ->
        {
            final Component component = components.get(name);
            Verify.notNull(component, "element:" + name);
            entry.element(component);
        });
    }

    private Map<String, Component> parseComponents(
        final Document document,
        final Map<String, Field> fields,
        final Map<Entry, String> forwardReferences)
        throws XPathExpressionException
    {
        final Map<String, Component> components = new HashMap<>();
        extractNodes(document, findComponent,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String name = name(attributes);
                final Component component = new Component(name);

                extractEntries(node.getChildNodes(), fields, component.entries(), components, forwardReferences, false);

                components.put(name, component);
            });

        return components;
    }

    private Map<String, Field> parseFields(final Document document) throws XPathExpressionException
    {
        final HashMap<String, Field> fields = new HashMap<>();
        extractNodes(document, findField,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();

                final String name = name(attributes);
                final int number = getInt(attributes, "number");
                final Type type = Type.lookup(getValue(attributes, "type"));
                final String normalisedFieldName = ensureNumInGroupStartsWithNo(name, type);
                final Field field = new Field(number, normalisedFieldName, type);

                extractEnumValues(field.values(), node.getChildNodes());
                final Field oldField = fields.put(name, field);
                if (oldField != null)
                {
                    throw new IllegalStateException(String.format(
                        "Cannot have the same field name defined twice; this is against the FIX spec." +
                        "Details to follow:\n" +
                        "Field : %1$s (%2$s)\n" +
                        "Field : %3$s (%4$s)",
                        field.name(),
                        field.number(),
                        oldField.name(),
                        oldField.number()));
                }
            });

        return fields;
    }

    private static String ensureNumInGroupStartsWithNo(final String name, final Type type)
    {
        if (type == NUMINGROUP)
        {
            return name.startsWith("No") ? name : "No" + name;
        }
        return name;
    }

    private int getInt(final NamedNodeMap attributes, final String attributeName)
    {
        try
        {
            return Integer.parseInt(getValue(attributes, attributeName));
        }
        catch (final NumberFormatException ex)
        {
            throw new IllegalStateException(
                "Invalid integer for: " + attributeName + " in " + toString(attributes), ex);
        }
    }

    private void extractEnumValues(final List<Value> values, final NodeList childNodes)
    {
        forEach(childNodes,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String representation = getValue(attributes, "enum");
                final String description = getValue(attributes, "description");
                values.add(new Value(representation, enumDescriptionToJavaName(description)));
            });
    }

    private List<Message> parseMessages(
        final Document document,
        final Map<String, Field> fields,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences) throws XPathExpressionException
    {
        final ArrayList<Message> messages = new ArrayList<>();

        extractNodes(document, findMessage,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();

                final String name = name(attributes);
                final String fullType = getValue(attributes, "msgtype");
                final String category = getValue(attributes, "msgcat");
                final Message message = new Message(name, fullType, category);

                extractEntries(node.getChildNodes(), fields, message.entries(), components, forwardReferences, true);

                messages.add(message);
            });

        return messages;
    }

    private void extractEntries(
        final NodeList childNodes,
        final Map<String, Field> fields,
        final List<Entry> entries,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences,
        final boolean isMessage)
    {
        forEach(childNodes,
            (node) ->
            {
                final NamedNodeMap attributes = node.getAttributes();
                final String name = name(attributes);
                if (name.trim().length() == 0)
                {
                    return;
                }

                final boolean required = isRequired(attributes);
                final Consumer<Entry.Element> newEntry =
                    (element) ->
                    {
                        Verify.notNull(element, "element for " + name);
                        entries.add(new Entry(required, element));
                    };

                switch (node.getNodeName())
                {
                    case "field":
                        newEntry.accept(fields.get(name));
                        break;

                    case "group":
                        final Group group = Group.of(fields.get(name), fields);
                        extractEntries(
                            node.getChildNodes(),
                            fields,
                            group.entries(),
                            components,
                            forwardReferences,
                            false);
                        newEntry.accept(group);
                        break;

                    case "component":
                        final Component component = components.get(name);
                        final Entry entry = new Entry(required, component);
                        if (component == null)
                        {
                            forwardReferences.put(entry, name);
                        }
                        entries.add(entry);
                        break;

                    case "anyFields":
                        if (!isMessage)
                        {
                            throw new IllegalStateException(
                                "Only messages can contain anyFields entries (" + name + ")");
                        }
                        newEntry.accept(new AnyFields(name));
                        break;
                }
            });
    }

    private Component extractComponent(
        final Document document,
        final Map<String, Field> fields,
        final XPathExpression expression,
        final String name,
        final Map<String, Component> components,
        final Map<Entry, String> forwardReferences)
        throws XPathExpressionException
    {
        final Component component = new Component(name);
        final NodeList nodes = evaluate(document, expression);
        extractEntries(nodes, fields, component.entries(), components, forwardReferences, false);

        return component;
    }

    private String name(final NamedNodeMap attributes)
    {
        return getValue(attributes, "name");
    }

    private boolean isRequired(final NamedNodeMap attributes)
    {
        // We interpret missing required clauses as being optional.
        final String required = getOptionalValue(attributes, "required");
        return "Y".equals(required);
    }

    private String getValue(final NamedNodeMap attributes, final String attributeName)
    {
        Objects.requireNonNull(attributes, "Null attributes for " + attributeName);
        final String optionalValue = getOptionalValue(attributes, attributeName);
        return Objects.requireNonNull(optionalValue,
            "Empty item for: " + attributeName + " in " + toString(attributes));
    }

    private String toString(final NamedNodeMap attributes)
    {
        return IntStream.range(0, attributes.getLength())
                        .mapToObj(i ->
                        {
                            final Node node = attributes.item(i);
                            return node.getNodeName() + "=" + node.getNodeValue();
                        })
                        .collect(Collectors.joining(",", "{", "}"));
    }

    private String getOptionalValue(final NamedNodeMap attributes, final String attributeName)
    {
        Objects.requireNonNull(attributes, "Null attributes for " + attributeName);
        final Node attributeNode = attributes.getNamedItem(attributeName);
        return attributeNode == null ? null : attributeNode.getNodeValue();
    }

    private String getValueOrDefault(final NamedNodeMap attributes,
        final String attributeName,
        final String defaultValue)
    {
        Objects.requireNonNull(attributes, "Null attributes for " + attributeName);
        final String value = getOptionalValue(attributes, attributeName);
        return value == null ? defaultValue : value;
    }

    private void extractNodes(
        final Document document, final XPathExpression expression, final Consumer<Node> handler)
        throws XPathExpressionException
    {
        forEach(evaluate(document, expression), handler);
    }

    private NodeList evaluate(final Document document, final XPathExpression expression) throws XPathExpressionException
    {
        return (NodeList)expression.evaluate(document, NODESET);
    }

    private void forEach(final NodeList nodes, final Consumer<Node> handler)
    {
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Node node = nodes.item(i);
            if (node instanceof Element)
            {
                handler.accept(node);
            }
        }
    }

    private void sanitizeDictionary(final Map<String, Field> fields,
        final List<Message> messages)
    {
        correctMultiCharacterCharEnums(fields);
        identifyDuplicateFieldDefinitionsForMessages(messages);
    }

    // Some dodgy ECNs extend off-the-shelf QuickFIX dictionary, and include same field into message/group twice:
    // once via component, once explicitly. Duplicate field can be safely discarded.
    private void identifyDuplicateFieldDefinitionsForMessages(final List<Message> messages)
    {
        final StringBuilder errorMessage = new StringBuilder();
        for (final Message message : messages)
        {
            final Set<Integer> allFieldsForMessage = new HashSet<>();
            identifyDuplicateFieldDefinitionsForMessage(
                message.name(), message, allFieldsForMessage, new ArrayDeque<>(), errorMessage);
        }

        if (errorMessage.length() > 0)
        {
            if (!allowDuplicates)
            {
                throw new IllegalStateException(String.format(
                        "%sUse -D%s=true to allow duplicated fields (Dangerous. May break parser). " +
                        "If using shared codecs then duplicate fields are defined on a per dictionary basis," +
                        " using SharedCodecConfiguration.withDictionary().",
                        errorMessage,
                        CodecConfiguration.FIX_CODECS_ALLOW_DUPLICATE_FIELDS_PROPERTY));
            }
        }
    }

    private static void identifyDuplicateFieldDefinitionsForMessage(
        final String messageName,
        final Aggregate aggregate,
        final Set<Integer> allFields,
        final Deque<String> path,
        final StringBuilder errorCollector)
    {
        for (final Entry e : aggregate.entries())
        {
            e.forEach(
                (field) -> addField(messageName, field, allFields, path, errorCollector),
                (group) ->
                {
                    path.addLast(group.name());
                    identifyDuplicateFieldDefinitionsForMessage(
                        messageName,
                        group,
                        allFields,
                        path,
                        errorCollector);
                    path.removeLast();
                },
                (component) ->
                {
                    path.addLast(component.name());
                    identifyDuplicateFieldDefinitionsForMessage(
                        messageName,
                        component,
                        allFields,
                        path,
                        errorCollector);
                    path.removeLast();
                },
                (anyFields) -> {}
            );
        }
    }

    private static void addField(
        final String messageName,
        final Field field,
        final Set<Integer> fieldsForMessage,
        final Deque<String> path,
        final StringBuilder errorCollector)
    {
        if (!fieldsForMessage.add(field.number()))
        {
            if (errorCollector.length() == 0)
            {
                errorCollector.append(
                    "Cannot have the same field defined more than once on a message; this is " +
                    "against the FIX spec. Details to follow:\n");
            }
            errorCollector.append("Message: ")
                .append(messageName)
                .append(" Field : ")
                .append(field.name())
                .append(" (")
                .append(field.number())
                .append(")");

            if (!path.isEmpty())
            {
                errorCollector.append(" Through Path: ").append(path.toString());
            }

            errorCollector.append('\n');
        }
    }

    public static String enumDescriptionToJavaName(final String enumDescription)
    {
        final StringBuilder enumName = new StringBuilder();

        final char firstChar = enumDescription.charAt(0);
        if (Character.isJavaIdentifierStart(firstChar))
        {
            enumName.append(firstChar);
        }
        else if (Character.isJavaIdentifierPart(firstChar))
        {
            enumName.append('_').append(firstChar);
        }
        else
        {
            enumName.append('_');
        }

        for (int i = 1; i < enumDescription.length(); i++)
        {
            final char nextChar = enumDescription.charAt(i);
            if (Character.isJavaIdentifierPart(nextChar))
            {
                enumName.append(nextChar);
            }
            else
            {
                enumName.append('_');
            }
        }

        return enumName.toString();
    }
}
