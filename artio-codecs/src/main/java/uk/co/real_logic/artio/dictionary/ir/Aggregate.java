/*
 * Copyright 2015-2025 Real Logic Limited.
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
package uk.co.real_logic.artio.dictionary.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * An Aggregate is either a group, a message or a component.
 */
public abstract class Aggregate
{
    private final String name;
    private final List<Entry> entries;

    private boolean isInParent;

    protected Aggregate(final String name)
    {
        this.name = name;
        entries = new ArrayList<>();
    }

    public String name()
    {
        return name;
    }

    public List<Entry> entries()
    {
        return entries;
    }

    public Stream<Entry> entriesWith(final Predicate<Entry.Element> predicate)
    {
        return entries.stream().filter((entry) -> predicate.test(entry.element()));
    }

    public Stream<Entry> fieldEntries()
    {
        return entries().stream().filter(Entry::isField);
    }

    /**
     * @return all entries including those of nested components
     */
    public Stream<Entry> allFieldsIncludingComponents()
    {
        return entries.stream()
            .flatMap(
                (entry) -> entry.match(
                    (ele, field) -> Stream.of(ele),
                    (ele, group) -> Stream.empty(),
                    (ele, component) -> component.allFieldsIncludingComponents(),
                    (ele, anyFields) -> Stream.empty()
                ));
    }

    /**
     * @return all entries including those of nested components
     */
    public Stream<Entry> allGroupsIncludingComponents()
    {
        return entries.stream()
            .flatMap(
                (entry) -> entry.match(
                    (ele, field) -> Stream.empty(),
                    (ele, group) -> Stream.of(ele),
                    (ele, component) -> component.allGroupsIncludingComponents(),
                    (ele, anyFields) -> Stream.empty()
                ));
    }

    public Stream<Entry> allComponents()
    {
        return entries.stream()
            .flatMap(
                (entry) -> entry.match(
                    (e, field) -> Stream.empty(),
                    (e, group) -> Stream.empty(),
                    (e, component) -> Stream.concat(Stream.of(e), component.allComponents()),
                    (e, anyFields) -> Stream.empty()
                ));
    }

    public Aggregate optionalEntry(final Entry.Element element)
    {
        entries().add(Entry.optional(element));
        return this;
    }

    public Aggregate requiredEntry(final Entry.Element element)
    {
        entries().add(Entry.required(element));
        return this;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "{" +
            "name='" + name + '\'' +
            ", entries=" + entries +
            ", isInParent=" + isInParent +
            '}';
    }

    public boolean hasField(final String msgType)
    {
        return entries().stream().anyMatch(e -> msgType.equals(e.element().name()));
    }

    public boolean containsGroup()
    {
        return entries.stream()
            .anyMatch(
                (entry) -> entry.match(
                    (ele, field) -> false,
                    (ele, group) -> true,
                    (ele, component) -> component.containsGroup(),
                    (ele, anyFields) -> false
                ));
    }

    public boolean isInParent()
    {
        return isInParent;
    }

    public void isInParent(final boolean isInParent)
    {
        this.isInParent = isInParent;
    }

    public Stream<Entry> componentEntries()
    {
        return entriesWith((element) -> element instanceof Component);
    }

    public boolean hasComponent(final String componentName)
    {
        return componentEntries().anyMatch(e -> componentName.equals(e.name()));
    }

    public Stream<Entry> anyFieldsEntries()
    {
        return entries().stream().filter(Entry::isAnyFields);
    }
}
