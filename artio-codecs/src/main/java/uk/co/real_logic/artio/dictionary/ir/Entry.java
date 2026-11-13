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

import org.agrona.LangUtil;
import org.agrona.Verify;
import org.agrona.generation.ResourceConsumer;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Entry
{
    public static final Comparator<Entry> BY_NAME = Comparator.comparing(Entry::name);

    private boolean required;
    private Element element;
    private boolean isInParent;

    // Only used in shared parent entry in order to store references to child entries
    private List<Entry> sharedChildEntries;

    public static Entry required(final Element element)
    {
        return new Entry(true, element);
    }

    public static Entry optional(final Element element)
    {
        return new Entry(false, element);
    }

    public <T> T match(
        final BiFunction<Entry, Field, ? extends T> withField,
        final BiFunction<Entry, Group, ? extends T> withGroup,
        final BiFunction<Entry, Component, ? extends T> withComponent,
        final BiFunction<Entry, AnyFields, ? extends T> withAnyFields)
    {
        if (element instanceof Field)
        {
            return withField.apply(this, (Field)element);
        }
        else if (element instanceof Group)
        {
            return withGroup.apply(this, (Group)element);
        }
        else if (element instanceof Component)
        {
            return withComponent.apply(this, (Component)element);
        }
        else if (element instanceof AnyFields)
        {
            return withAnyFields.apply(this, (AnyFields)element);
        }

        throw new IllegalStateException("Unknown element type: " + element);
    }

    public void forEach(
        final ResourceConsumer<Field> withField,
        final ResourceConsumer<Group> withGroup,
        final ResourceConsumer<Component> withComponent,
        final ResourceConsumer<AnyFields> withAnyFields)
    {
        try
        {
            if (element instanceof Field)
            {
                withField.accept((Field)element);
            }
            else if (element instanceof Group)
            {
                withGroup.accept((Group)element);
            }
            else if (element instanceof Component)
            {
                withComponent.accept((Component)element);
            }
            else if (element instanceof AnyFields)
            {
                withAnyFields.accept((AnyFields)element);
            }
            else
            {
                throw new IllegalStateException("Unknown element type: " + element);
            }
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    public <T> T matchEntry(
        final Function<Entry, ? extends T> withField,
        final Function<Entry, ? extends T> withGroup,
        final Function<Entry, ? extends T> withComponent,
        final Function<Entry, ? extends T> withAnyFields)
    {
        return match(
            (entry, field) -> withField.apply(entry),
            (entry, group) -> withGroup.apply(entry),
            (entry, component) -> withComponent.apply(entry),
            (entry, anyFields) -> withAnyFields.apply(entry));
    }

    /**
     * @param required whether this is a required or optional element
     * @param element nullable in the case of forward references
     */
    public Entry(final boolean required, final Element element)
    {
        this.required = required;
        this.element = element;
    }

    public boolean required()
    {
        return required;
    }

    public void required(final boolean required)
    {
        this.required = required;
    }

    public Element element()
    {
        return this.element;
    }

    public Entry element(final Element element)
    {
        Verify.notNull(element, "element");

        this.element = element;
        return this;
    }

    public boolean isField()
    {
        return element() instanceof Field;
    }

    public boolean isComponent()
    {
        return element() instanceof Component;
    }

    public boolean isGroup()
    {
        return element() instanceof Group;
    }

    public boolean isAnyFields()
    {
        return element() instanceof AnyFields;
    }

    @Override
    public String toString()
    {
        return "Entry{" +
            "required=" + required +
            ", element=" + element +
            ", isInParent=" + isInParent +
            '}';
    }

    public void sharedChildEntries(final List<Entry> sharedChildEntries)
    {
        this.sharedChildEntries = sharedChildEntries;
    }

    public List<Entry> sharedChildEntries()
    {
        return sharedChildEntries;
    }

    public String name()
    {
        return element().name();
    }

    public int number()
    {
        return ((Field)element()).number();
    }

    public boolean isInParent()
    {
        return isInParent;
    }

    public void isInParent(final boolean isInParent)
    {
        this.isInParent = isInParent;
    }

    public interface Element
    {
        default boolean isEnumField()
        {
            return this instanceof Field && ((Field)this).isEnum();
        }

        default boolean isCharOrBooleanBasedField()
        {
            return this instanceof Field && ((Field)this).isCharOrBooleanBasedField();
        }

        String name();
    }
}
