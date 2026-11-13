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

import java.util.List;
import java.util.Map;

public final class Dictionary
{
    private final List<Message> messages;
    private final Map<String, Field> fields;
    private final Map<String, Component> components;
    private final Component header;
    private final Component trailer;
    private final String specType;
    private final int majorVersion;
    private final int minorVersion;

    private String name;
    private boolean shared;
    private Dictionary sharedParent;

    public Dictionary(
        final List<Message> messages,
        final Map<String, Field> fields,
        final Map<String, Component> components,
        final Component header,
        final Component trailer,
        final String specType,
        final int majorVersion,
        final int minorVersion)
    {
        this.messages = messages;
        this.fields = fields;
        this.components = components;
        this.header = header;
        this.trailer = trailer;
        this.specType = specType;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
    }

    public List<Message> messages()
    {
        return messages;
    }

    public Map<String, Field> fields()
    {
        return fields;
    }

    public Map<String, Component> components()
    {
        return components;
    }

    public Component header()
    {
        return header;
    }

    public Component trailer()
    {
        return trailer;
    }

    public int minorVersion()
    {
        return minorVersion;
    }

    public int majorVersion()
    {
        return majorVersion;
    }

    public String specType()
    {
        return specType;
    }

    public String name()
    {
        return name;
    }

    public void name(final String name)
    {
        this.name = name;
    }

    public Dictionary sharedParent()
    {
        return sharedParent;
    }

    public boolean hasSharedParent()
    {
        return sharedParent() != null;
    }

    public void sharedParent(final Dictionary sharedParent)
    {
        this.sharedParent = sharedParent;
    }

    public boolean shared()
    {
        return shared;
    }

    public void shared(final boolean shared)
    {
        this.shared = shared;
    }

    @Override
    public String toString()
    {
        return "DataDictionary{" +
                "specType=" + specType +
                ", messages=" + messages +
                ", fields=" + fields +
                ", components=" + components +
                ", header=" + header +
                ", trailer=" + trailer +
                ", name=" + name +
                ", shared=" + shared +
                ", sharedParent=" + sharedParent +
                '}';
    }

    public String beginString()
    {
        return String.format("%s.%d.%d",
            specType(),
            majorVersion(),
            minorVersion());
    }
}
