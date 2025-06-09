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

import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.MutableInteger;
import uk.co.real_logic.artio.dictionary.DictionaryParser;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Value;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;

class CodecSharer
{
    // Used in sharedNameToField in order to denote a name that has a clash
    private static final Field CLASH_SENTINEL = new Field(-1, "SHARING_CLASH", Field.Type.INT);
    private static final Comparator<Map.Entry<String, Long>> ENUM_NAME_ORDER =
        Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue) // Dictonary Count
        .thenComparingInt(e -> e.getKey().length()); // Length of name

    private final List<Dictionary> inputDictionaries;

    private final Map<String, Field> sharedNameToField = new HashMap<>();
    private final Map<String, Field.Type> widenedFields = new HashMap<>();
    private final Map<String, Group> sharedIdToGroup = new HashMap<>();
    private final Set<String> commonGroupIds = new HashSet<>();
    private final Map<String, Component> sharedNameToComponent = new HashMap<>();
    private final Set<String> commonAnyFields = new HashSet<>();

    CodecSharer(final List<Dictionary> inputDictionaries)
    {
        this.inputDictionaries = inputDictionaries;
    }

    public void share()
    {
        findSharedFields();

        findSharedGroups();
        findSharedComponents();
        findCommonAnyFields();
        final Map<String, Message> nameToSharedMessage = findSharedMessages();
        findComponentsWithSharedFieldsNotInAllDictionaries(nameToSharedMessage);

        final Component header = findSharedComponent(Dictionary::header);
        final Component trailer = findSharedComponent(Dictionary::trailer);
        final String specType = DictionaryParser.DEFAULT_SPEC_TYPE;
        final int majorVersion = 0;
        final int minorVersion = 0;
        final List<Message> messages = new ArrayList<>(nameToSharedMessage.values());

        final Dictionary sharedDictionary = new Dictionary(
            messages,
            sharedNameToField,
            sharedNameToComponent,
            header,
            trailer,
            specType,
            majorVersion,
            minorVersion);

        updateRequiredEntries(sharedDictionary);

        sharedDictionary.shared(true);
        inputDictionaries.forEach(dict -> connectToSharedDictionary(sharedDictionary, dict));
        inputDictionaries.add(sharedDictionary);
    }

    private void updateRequiredEntries(final Dictionary sharedDictionary)
    {
        updateRequiredEntriesInAggregates(sharedDictionary.components().values());
        updateRequiredEntriesInAggregates(sharedDictionary.messages());
    }

    private void updateRequiredEntriesInAggregates(final Collection<? extends Aggregate> aggregates)
    {
        aggregates.forEach(sharedComponent ->
        {
            sharedComponent.allFieldsIncludingComponents().forEach(entry ->
            {
                final boolean required = entry.required();
                entry.sharedChildEntries().forEach(childEntry -> childEntry.required(required));
            });
        });
    }

    private void findSharedGroups()
    {
        findSharedAggregates(
            sharedIdToGroup,
            commonGroupIds,
            dict -> concat(grpPairs(dict.messages()), grpPairs(dict.components().values())).collect(toList()),
            (comp, sharedGroup) -> true,
            this::copyOf,
            this::sharedGroupId);
    }

    interface GetName<T extends Aggregate> extends Function<AggPath<T>, String>
    {
    }

    interface CopyOf<T extends Aggregate> extends BiFunction<List<Aggregate>, T, T>
    {
    }

    private String sharedGroupId(final AggPath<Group> pair)
    {
        return pair.parents().stream().map(Aggregate::name).collect(joining(".")) + "." + pair.agg().name();
    }

    private Stream<AggPath<Group>> grpPairs(final Collection<? extends Aggregate> aggregates)
    {
        return aggregates.stream().flatMap(agg -> grpPairs(agg, Collections.emptyList()));
    }

    // Groups within this aggregate and groups within groups, but not groups within components within this aggregate
    public Stream<AggPath<Group>> grpPairs(final Aggregate aggregate, final List<Aggregate> prefix)
    {
        final List<Aggregate> parents = path(prefix, aggregate);

        return aggregate.entriesWith(ele -> ele instanceof Group)
            .flatMap(e ->
            {
                final Group group = (Group)e.element();
                final Stream<AggPath<Group>> pair = Stream.of(new AggPath<>(parents, group));
                return concat(pair, grpPairs(group, parents));
            });
    }

    private List<Aggregate> path(final List<Aggregate> prefix, final Aggregate aggregate)
    {
        final List<Aggregate> parents = new ArrayList<>(prefix);
        parents.add(aggregate);
        return parents;
    }

    private void findSharedComponents()
    {
        // find components in all dictionaries
        findSharedAggregates(
            sharedNameToComponent,
            null,
            dict -> addFakeParents(dict.components().values()),
            (comp, sharedComp) -> true,
            this::copyOf,
            pair -> pair.agg().name());
    }

    // Aim is to maximise sharing between dictionaries by
    private void findComponentsWithSharedFieldsNotInAllDictionaries(final Map<String, Message> nameToSharedMessage)
    {
        // Shared messages within dictionaries
        final Map<String, List<Message>> sharedMessageNameToMessages = inputDictionaries
            .stream()
            .flatMap(dictionary -> dictionary
            .messages()
            .stream()
            .filter(msg -> nameToSharedMessage.containsKey(msg.name())))
            .collect(groupingBy(Message::name));

        // components not in all dictionaries
        final Map<String, List<Component>> otherNameToComponents = inputDictionaries
            .stream()
            .flatMap(dictionary -> dictionary
            .components()
            .entrySet()
            .stream()
            .filter(e -> !sharedNameToComponent.containsKey(e.getKey())))
            .map(Map.Entry::getValue)
            .collect(groupingBy(Component::name));

        otherNameToComponents.forEach((componentName, components) ->
        {
            final Set<String> sharedFieldNames = findFieldsInAllInstancesOfComponent(components);
            if (sharedFieldNames.isEmpty())
            {
                return;
            }

            final Int2ObjectHashMap<Component> indexToSynthesizedComponent = new Int2ObjectHashMap<>();

            // find shared messages that implement the component in some but not all cases
            sharedMessageNameToMessages.forEach((msgName, messages) ->
            {
                final Int2ObjectHashMap<Message> messagesWithoutComponent = new Int2ObjectHashMap<>();
                final int withComponentCount = findMessagesWithoutComponent(
                    componentName, messages, messagesWithoutComponent);

                // some but not all
                if (withComponentCount > 0 && withComponentCount != messages.size())
                {
                    for (final Map.Entry<Integer, Message> entry : messagesWithoutComponent.entrySet())
                    {
                        final Message message = entry.getValue();
                        // check it contains all the common fields
                        final List<Entry> componentFieldEntries = message
                            .fieldEntries()
                            .filter(e -> sharedFieldNames.contains(e.name()))
                            .collect(toList());

                        if (sharedFieldNames.size() == componentFieldEntries.size())
                        {
                            final int index = entry.getKey();
                            final Component synthesizedComponent = lookupSynthesizedComponent(
                                componentName, indexToSynthesizedComponent, componentFieldEntries, index);

                            final List<Entry> entries = message.entries();
                            entries.removeAll(componentFieldEntries);
                            entries.add(Entry.optional(synthesizedComponent));
                        }
                    }
                }
            });

            synthesizeParentComponent(componentName, sharedFieldNames, indexToSynthesizedComponent);
        });
    }

    private void synthesizeParentComponent(
        final String componentName,
        final Set<String> sharedFieldNames,
        final Int2ObjectHashMap<Component> indexToSynthesizedComponent)
    {
        if (!indexToSynthesizedComponent.isEmpty())
        {
            final Component synthesizedParentComponent = new Component(componentName);
            final List<Entry> entries = synthesizedParentComponent.entries();
            for (final String fieldName : sharedFieldNames)
            {
                entries.add(Entry.optional(sharedNameToField.get(fieldName)));
            }
            entries.sort(Entry.BY_NAME);
            final int entrySize = entries.size();
            for (int i = 0; i < entrySize; i++)
            {
                final Entry parentEntry = entries.get(i);
                final int index = i;
                parentEntry.sharedChildEntries(indexToSynthesizedComponent
                    .values()
                    .stream()
                    .map(comp -> comp.entries().get(index))
                    .collect(toList()));
            }
            sharedNameToComponent.put(componentName, synthesizedParentComponent);
        }
    }

    private Set<String> findFieldsInAllInstancesOfComponent(final List<Component> components)
    {
        final Component component = components.get(0);
        final Set<String> sharedFieldNames = component
            .fieldEntries()
            .map(Entry::name)
            .filter(sharedNameToField::containsKey)
            .collect(toSet());

        final int size = components.size();
        for (int i = 1; i < size; i++)
        {
            final Set<String> fieldNames = components.get(i).fieldEntries().map(Entry::name).collect(toSet());
            sharedFieldNames.retainAll(fieldNames);
        }
        return sharedFieldNames;
    }

    private int findMessagesWithoutComponent(
        final String componentName,
        final List<Message> messages,
        final Int2ObjectHashMap<Message> messagesWithoutComponent)
    {
        int withComponentCount = 0;
        final int messagesSize = messages.size();
        for (int i = 0; i < messagesSize; i++)
        {
            final Message msg = messages.get(i);
            if (msg.hasComponent(componentName))
            {
                withComponentCount++;
            }
            else
            {
                messagesWithoutComponent.put(i, msg);
            }
        }
        return withComponentCount;
    }

    private Component lookupSynthesizedComponent(
        final String componentName,
        final Int2ObjectHashMap<Component> indexToComponent,
        final List<Entry> componentFieldEntries,
        final int index)
    {
        Component synthesizedComponent = indexToComponent.get(index);
        if (synthesizedComponent == null)
        {
            synthesizedComponent = new Component(componentName);
            synthesizedComponent.isInParent(true);
            componentFieldEntries.sort(Entry.BY_NAME);
            componentFieldEntries.forEach(fieldEntry -> fieldEntry.isInParent(true));
            synthesizedComponent.entries().addAll(componentFieldEntries);
            indexToComponent.put(index, synthesizedComponent);

            inputDictionaries.get(index).components().put(componentName, synthesizedComponent);
        }
        return synthesizedComponent;
    }

    private void findCommonAnyFields()
    {
        final Map<String, MutableInteger> counts = new HashMap<>();

        for (final Dictionary inputDictionary : inputDictionaries)
        {
            for (final Message message : inputDictionary.messages())
            {
                message.anyFieldsEntries().forEach(entry ->
                {
                    final String id = anyFieldsId(Collections.singletonList(message), (AnyFields)entry.element());
                    counts.computeIfAbsent(id, k -> new MutableInteger()).increment();
                });
            }
        }

        counts.values().removeIf(count -> count.get() < inputDictionaries.size());

        commonAnyFields.addAll(counts.keySet());
    }

    private String anyFieldsId(final List<Aggregate> parents, final AnyFields anyFields)
    {
        return parents.stream().map(Aggregate::name).collect(joining(".")) + "." + anyFields.name();
    }

    private Map<String, Message> findSharedMessages()
    {
        return findSharedAggregates(
            new HashMap<>(),
            null,
            dict -> addFakeParents(dict.messages()),
            (msg, sharedMessage) -> msg.packedType() == sharedMessage.packedType(),
            this::copyOf,
            pair -> pair.agg().name());
    }

    private static final class AggPath<T extends Aggregate>
    {
        private final List<Aggregate> parents;
        private final T agg;

        private AggPath(final List<Aggregate> parents, final T agg)
        {
            this.parents = parents;
            this.agg = agg;
        }

        public List<Aggregate> parents()
        {
            return parents;
        }

        public T agg()
        {
            return agg;
        }
    }

    private <T extends Aggregate> Collection<AggPath<T>> addFakeParents(final Collection<T> aggregates)
    {
        return aggregates
            .stream()
            .map(agg -> new AggPath<T>(Collections.emptyList(), agg))
            .collect(toList());
    }

    // Groups existing within a Message or Component, so we put the message or component name into the
    // name map for the groups, so we can lookup the precise group later on.
    // Thus all the getName functions need parent objects
    private <T extends Aggregate> Map<String, T> findSharedAggregates(
        final Map<String, T> nameToAggregate,
        final Set<String> commonAggregateNamesCopy,
        final Function<Dictionary, Collection<AggPath<T>>> dictToAggParentPair,
        final BiPredicate<T, T> check,
        final CopyOf<T> copyOf,
        final GetName<T> getName)
    {
        final Set<String> commonAggregateNames = findCommonNames(dict ->
            aggregateNames(dictToAggParentPair.apply(dict), getName));
        if (commonAggregateNamesCopy != null)
        {
            commonAggregateNamesCopy.addAll(commonAggregateNames);
        }
        for (final Dictionary dictionary : inputDictionaries)
        {
            dictToAggParentPair.apply(dictionary).forEach(e ->
            {
                final T agg = e.agg();
                final String name = getName.apply(e);
                if (commonAggregateNames.contains(name))
                {
                    final T sharedAggregate = nameToAggregate.get(name);
                    if (sharedAggregate == null)
                    {
                        final T copy = copyOf.apply(e.parents(), agg);
                        if (copy != null)
                        {
                            nameToAggregate.put(name, copy);
                            agg.isInParent(true);
                        }
                    }
                    else
                    {
                        // merge fields within aggregate
                        if (!check.test(agg, sharedAggregate))
                        {
                            // TODO: if it happens then push the type down into the implementations
                            System.err.println("Invalid types: ");
                            System.err.println(agg);
                            System.err.println(sharedAggregate);
                        }
                        else
                        {
                            // Still need to merge aggregates even though we have merged fields
                            // As some fields may be common to different dictionaries but not messages
                            mergeAggregate(agg, sharedAggregate);
                        }
                    }
                }
            });
        }

        identifyAggregateEntriesInParent(nameToAggregate, dictToAggParentPair, getName);

        return nameToAggregate;
    }

    private <T extends Aggregate> void identifyAggregateEntriesInParent(
        final Map<String, T> nameToAggregate,
        final Function<Dictionary, Collection<AggPath<T>>> dictToAggParentPair,
        final GetName<T> getName)
    {
        inputDictionaries.forEach(dictionary ->
        {
            dictToAggParentPair.apply(dictionary).forEach(e ->
            {
                final T agg = e.agg();
                final String name = getName.apply(e);
                final Aggregate sharedAggregate = nameToAggregate.get(name);
                if (sharedAggregate != null)
                {
                    identifyAggregateEntriesInParent(agg, sharedAggregate);
                }
            });
        });
    }

    private void identifyAggregateEntriesInParent(final Aggregate agg, final Aggregate sharedAgg)
    {
        sharedAgg.entries().forEach(sharedEntry ->
        {
            agg.entries().forEach(entry ->
            {
                if (entry.name().equals(sharedEntry.name()))
                {
                    entry.isInParent(true);
                }
            });
        });
    }

    private void connectToSharedDictionary(final Dictionary sharedDictionary, final Dictionary dict)
    {
        dict.sharedParent(sharedDictionary);
    }

    private void findSharedFields()
    {
        final Set<String> commonNonEnumFieldNames = findCommonNonEnumFieldNames();
        final Set<String> allEnumFieldNames = allEnumFieldNames();

        for (final Dictionary dictionary : inputDictionaries)
        {
            final Map<String, Field> fields = dictionary.fields();
            commonNonEnumFieldNames.forEach(fieldName -> mergeField(fields, fieldName));
            allEnumFieldNames.forEach(enumName -> mergeField(fields, enumName));
        }

        updateAssociatedLengthFields();
        updateFieldTypeWidening();
        updateSometimesEnumClashes();
        formUnionEnums();
    }

    private void updateAssociatedLengthFields()
    {
        sharedNameToField.values().forEach(field ->
            DictionaryParser.checkAssociatedLengthField(sharedNameToField, field, "CodecSharer"));
    }

    private void updateFieldTypeWidening()
    {
        sharedNameToField.values().removeIf(field -> field == CLASH_SENTINEL);

        widenedFields.forEach((name, widenedType) ->
        {
            inputDictionaries.forEach(dict ->
            {
                final Field field = dict.fields().get(name);
                if (field != null)
                {
                    field.type(widenedType);
                }
            });
        });
    }

    private void updateSometimesEnumClashes()
    {
        sharedNameToField.values().forEach(sharedField ->
        {
            if (sharedField.hasSharedSometimesEnumClash())
            {
                inputDictionaries.forEach(dict ->
                {
                    final Field dictField = dict.fields().get(sharedField.name());
                    if (dictField != null)
                    {
                        dictField.hasSharedSometimesEnumClash(true);
                    }
                });
            }
        });
    }

    private void formUnionEnums()
    {
        sharedNameToField.values().forEach(field ->
        {
            if (field.isEnum())
            {
                final List<Value> fieldValues = field.values();

                // Rename collisions by name
                final Map<String, Map<String, Long>> nameToReprToCount = fieldValues.stream().collect(
                    groupingBy(Value::description,
                    groupingBy(Value::representation, Collectors.counting())));

                // Make a new unique Value for every input with the name collisions fixed
                final List<Value> values = nameToReprToCount
                    .entrySet().stream().flatMap(e ->
                    {
                        final String name = e.getKey();
                        final Map<String, Long> reprToCount = e.getValue();
                        final String commonRepr = findCommonName(reprToCount);
                        final Stream<Value> commonValues =
                            LongStream.range(0, reprToCount.get(commonRepr)).mapToObj(i ->
                                new Value(commonRepr, name));

                        final HashSet<String> otherReprs = new HashSet<>(reprToCount.keySet());
                        otherReprs.remove(commonRepr);

                        final Stream<Value> otherValues = otherReprs.stream().flatMap(repr ->
                        {
                            final String newName = name + "_" + DictionaryParser.enumDescriptionToJavaName(repr);
                            return LongStream.range(0, reprToCount.get(repr))
                                .mapToObj(i -> new Value(repr, newName));
                        });

                        return concat(commonValues, otherValues);
                    })
                    .collect(toList());

                // Add name collisions by representation to Javadoc
                final Map<String, Map<String, Long>> reprToNameToCount = values.stream().collect(
                    groupingBy(Value::representation,
                    groupingBy(Value::description, Collectors.counting())));

                final List<Value> finalValues = reprToNameToCount
                    .entrySet().stream().map(e ->
                    {
                        final String repr = e.getKey();
                        final Map<String, Long> nameToCount = e.getValue();
                        final String commonName = findCommonName(nameToCount);
                        final Value value = new Value(repr, commonName);
                        if (nameToCount.size() > 1)
                        {
                            final Set<String> otherNames = nameToCount.keySet();
                            otherNames.remove(commonName);
                            value.alternativeNames(new ArrayList<>(otherNames));
                        }
                        return value;
                    })
                    .collect(toList());

                fieldValues.clear();
                fieldValues.addAll(finalValues);
            }
        });
    }

    private String findCommonName(final Map<String, Long> nameToCount)
    {
        return nameToCount
            .entrySet()
            .stream()
            .max(ENUM_NAME_ORDER)
            .get()
            .getKey();
    }

    private void mergeField(final Map<String, Field> fields, final String fieldName)
    {
        sharedNameToField.compute(fieldName, (name, sharedField) ->
        {
            final Field field = fields.get(name);
            if (field == null)
            {
                return sharedField;
            }

            field.isInParent(true);

            if (sharedField == null)
            {
                return copyOf(field);
            }
            else
            {
                final Field.Type sharedType = sharedField.type();
                final Field.Type type = field.type();

                final BaseType sharedBaseType = BaseType.from(sharedType);
                final BaseType baseType = BaseType.from(type);

                if (sharedType != type && sharedBaseType != baseType)
                {
                    final BaseType widenedBaseType = attemptWidenField(sharedBaseType, baseType);
                    if (widenedBaseType == null)
                    {
                        // No need to widen if we later find a clash.
                        widenedFields.remove(name);
                        if (sharedField.isEnum())
                        {
                            System.err.println("Clash error for enum: " + sharedField);
                            System.out.println(field);
                        }

                        // Remove sharing of previous instances:
                        inputDictionaries.forEach(dict ->
                        {
                            final Field alreadySharedField = dict.fields().get(name);
                            alreadySharedField.isInParent(false);
                        });
                        return CLASH_SENTINEL;
                    }
                    else
                    {
                        final Field.Type widenedType = BaseType.to(widenedBaseType);
                        sharedField.type(widenedType);
                        widenedFields.put(name, widenedType);
                    }
                }

                mergeEnumValues(sharedField, field);

                return sharedField;
            }
        });
    }

    private BaseType attemptWidenField(final BaseType left, final BaseType right)
    {
        final BaseType widenedType = attemptWidenFieldOrdered(left, right);
        if (widenedType != null)
        {
            return widenedType;
        }

        return attemptWidenFieldOrdered(right, left);
    }

    private BaseType attemptWidenFieldOrdered(final BaseType left, final BaseType right)
    {
        // Not super-happy about widening char + int to String
        final boolean leftChar = left == BaseType.CHAR;
        final boolean leftInt = left == BaseType.INT;
        final boolean leftTimestamp = left == BaseType.TIMESTAMP;
        if (((leftChar || leftInt || leftTimestamp) && right == BaseType.STRING) || leftChar && right == BaseType.INT)
        {
            return BaseType.STRING;
        }

        if (leftInt && right == BaseType.TIMESTAMP)
        {
            return BaseType.STRING;
        }

        // widen timestamp + string to string and int + timestamp to String

        return null;
    }

    private void mergeEnumValues(final Field sharedField, final Field field)
    {
        final List<Value> sharedValues = sharedField.values();
        final List<Value> values = field.values();

        final boolean sharedIsEnum = sharedValues.isEmpty();
        final boolean isEnum = values.isEmpty();

        if (sharedIsEnum != isEnum)
        {
            sharedField.hasSharedSometimesEnumClash(true);
        }

        sharedValues.addAll(values);
    }

    private Set<String> allEnumFieldNames()
    {
        return inputDictionaries
            .stream()
            .flatMap(dict -> fieldNames(dict, true))
            .collect(Collectors.toSet());
    }

    private Field copyOf(final Field field)
    {
        final Field newField = new Field(field.number(), field.name(), field.type());
        newField.values().addAll(field.values());
        return newField;
    }

    private Component findSharedComponent(final Function<Dictionary, Component> getter)
    {
        final Dictionary firstDictionary = inputDictionaries.get(0);
        final Component sharedComponent = copyOf(Collections.emptyList(), getter.apply(firstDictionary));
        inputDictionaries.forEach(dict ->
        {
            final Component component = getter.apply(dict);
            mergeAggregate(component, sharedComponent);
        });

        inputDictionaries.forEach(dict ->
        {
            final Component component = getter.apply(dict);
            identifyAggregateEntriesInParent(component, sharedComponent);
        });

        return sharedComponent;
    }

    private void mergeAggregate(final Aggregate aggregate, final Aggregate sharedAggregate)
    {
        aggregate.isInParent(true);

        final Map<String, Entry> nameToEntry = nameToEntry(aggregate.entries());
        final Iterator<Entry> it = sharedAggregate.entries().iterator();
        while (it.hasNext())
        {
            final Entry sharedEntry = it.next();
            if (sharedEntry.element() == null)
            {
                it.remove();
                continue;
            }

            final Entry entry = nameToEntry.get(sharedEntry.name());
            if (entry == null)
            {
                it.remove();
            }
            else
            {
                // Only required if all are required
                sharedEntry.required(sharedEntry.required() && entry.required());
                sharedEntry.sharedChildEntries().add(entry);
            }
        }
    }

    private Set<String> findCommonNonEnumFieldNames()
    {
        return findCommonNames(dictionary -> fieldNames(dictionary, false).collect(Collectors.toSet()));
    }

    private Set<String> findCommonNames(final Function<Dictionary, Set<String>> getAllNames)
    {
        final Set<String> messageNames = new HashSet<>();
        inputDictionaries.forEach(dict ->
        {
            final Set<String> namesInDictionary = getAllNames.apply(dict);
            if (messageNames.isEmpty())
            {
                messageNames.addAll(namesInDictionary);
            }
            else
            {
                messageNames.retainAll(namesInDictionary);
            }
        });

        return messageNames;
    }

    private <T extends Aggregate> Set<String> aggregateNames(
        final Collection<AggPath<T>> aggregates,
        final GetName<T> getName)
    {
        return aggregates.stream().map(getName).collect(Collectors.toSet());
    }

    private Stream<String> fieldNames(final Dictionary dictionary, final boolean isEnum)
    {
        return dictionary.fields().entrySet()
            .stream()
            .filter(e -> e.getValue().isEnum() == isEnum)
            .map(Map.Entry::getKey);
    }

    private Map<String, Entry> nameToEntry(final List<Entry> entries)
    {
        return entries.stream().collect(Collectors.toMap(Entry::name, x -> x));
    }

    // pre: shared fields calculated
    private Entry copyOf(final List<Aggregate> parents, final Entry entry)
    {
        Entry.Element element = entry.element();

        // Remap fields to calculated shared values
        if (element instanceof Field)
        {
            final Field field = (Field)element;
            final String name = field.name();
            element = sharedNameToField.get(name);
            if (element == null)
            {
                return null;
            }
        }
        else if (element instanceof Component)
        {
            final Component component = (Component)element;
            final String name = component.name();
            element = sharedNameToComponent.get(name);
            if (element == null)
            {
                // Component in message but not shared
                element = copyOf(parents, component);
            }
        }
        else if (element instanceof Group)
        {
            final Group group = (Group)element;
            final String id = sharedGroupId(new AggPath<>(parents, group));
            // Nested Groups need to update this shared id map in order to copy the outer element of a nested group
            Group sharedGroup = sharedIdToGroup.get(id);
            if (sharedGroup == null)
            {
                if (!commonGroupIds.contains(id))
                {
                    // Just remove the inner nested group as it isn't in the shared dictionary anyway
                    return null;
                }

                sharedGroup = copyOf(parents, group);
                sharedIdToGroup.put(id, sharedGroup);
            }

            element = sharedGroup;
        }
        else if (element instanceof AnyFields)
        {
            final String id = anyFieldsId(parents, (AnyFields)element);
            if (!commonAnyFields.contains(id))
            {
                return null;
            }
        }
        else
        {
            throw new IllegalArgumentException("Unknown element type: " + element);
        }

        final Entry newEntry = new Entry(entry.required(), element);

        final ArrayList<Entry> sharedChildEntries = new ArrayList<>();
        newEntry.sharedChildEntries(sharedChildEntries);
        sharedChildEntries.add(entry);

        return newEntry;
    }

    private Component copyOf(final List<Aggregate> prefix, final Component component)
    {
        final Component newComponent = new Component(component.name());
        copyTo(prefix, component, newComponent);
        return newComponent;
    }

    private Group copyOf(final List<Aggregate> prefix, final Group group)
    {
        final List<Aggregate> parents = path(prefix, group);

        final Entry numberField = group.numberField();
        final Entry copiedNumberField = copyOf(parents, numberField);
        // if number field isn't shared then the group won't be
        if (copiedNumberField == null)
        {
            return null;
        }

        final Group newGroup = new Group(group.name(), copiedNumberField);
        copyTo(prefix, group, newGroup);
        return newGroup;
    }

    private Message copyOf(final List<Aggregate> prefix, final Message message)
    {
        final Message newMessage = new Message(message.name(), message.fullType(), message.category());
        copyTo(prefix, message, newMessage);
        return newMessage;
    }

    private void copyTo(final List<Aggregate> prefix, final Aggregate aggregate, final Aggregate newAggregate)
    {
        final List<Aggregate> parents = path(prefix, aggregate);

        for (final Entry entry : aggregate.entries())
        {
            final Entry newEntry = copyOf(parents, entry);
            if (newEntry != null)
            {
                newAggregate.entries().add(newEntry);
            }
        }
    }

}
