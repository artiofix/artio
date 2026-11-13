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

import org.agrona.Verify;
import uk.co.real_logic.artio.dictionary.ir.Entry.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Field implements Element
{
    private final int number;
    private final String name;
    private boolean isInParent;
    private Type type;
    private final List<Value> values;

    // true iff you've got a shared dictionary and this field represents a type which is sometimes an enum and
    // sometimes not.
    private boolean hasSharedSometimesEnumClash;

    private Field associatedLengthField;

    public static Field registerField(
        final Map<String, Field> nameToField,
        final int number,
        final String name,
        final Type type)
    {
        final Field field = new Field(number, name, type);
        nameToField.put(name, field);
        return field;
    }

    public Field(final int number, final String name, final Type type)
    {
        this.number = number;
        this.name = name;
        this.type = type;
        this.values = new ArrayList<>();
    }

    public Type type()
    {
        return type;
    }

    public Field associatedLengthField()
    {
        return associatedLengthField;
    }

    public void associatedLengthField(final Field associatedLengthField)
    {
        this.associatedLengthField = associatedLengthField;
    }

    public void type(final Type type)
    {
        Verify.notNull(type, "type");
        this.type = type;
    }

    @Override
    public String name()
    {
        return name;
    }

    public int number()
    {
        return number;
    }

    public List<Value> values()
    {
        return values;
    }

    public Field addValue(final String representation, final String description)
    {
        values().add(new Value(representation, description));
        return this;
    }

    public boolean isEnum()
    {
        return !values.isEmpty();
    }

    @Override
    public boolean isCharOrBooleanBasedField()
    {
        return type.isCharBased || type.isBooleanBased;
    }

    public boolean hasSharedSometimesEnumClash()
    {
        return hasSharedSometimesEnumClash;
    }

    public void hasSharedSometimesEnumClash(final boolean hasSharedSometimesEnumClash)
    {
        this.hasSharedSometimesEnumClash = hasSharedSometimesEnumClash;
    }

    @Override
    public String toString()
    {
        return "Field{" +
            "number=" + number +
            ", name='" + name + '\'' +
            ", type=" + type +
            ", hasSharedSometimesEnumClash=" + hasSharedSometimesEnumClash +
            ", associatedLengthField=" + associatedLengthField +
            ", values=" + values +
            '}';
    }

    public boolean isInParent()
    {
        return isInParent;
    }

    public void isInParent(final boolean isInParent)
    {
        this.isInParent = isInParent;
    }

    public enum Type
    {
        // int types
        INT(false, true, false, false, false),
        LENGTH(false, true, false, false, false),
        SEQNUM(false, true, false, false, false),
        NUMINGROUP(false, true, false, false, false),
        DAYOFMONTH(false, true, false, false, false),

        // Custom LONG integer type. Not supported elsewhere by FIX AFAIK
        LONG(false, true, false, false, false),

        // float types
        FLOAT(false, false, true, false, false),
        PRICE(false, false, true, false, false),
        PRICEOFFSET(false, false, true, false, false),
        QTY(false, false, true, false, false),
        // Not standard FIX but was observed in the wild in an IB's FIX 4.2 dictonary
        QUANTITY(false, false, true, false, false),
        // Percentage represented as a float
        PERCENTAGE(false, false, true, false, false),
        // Float amount, not to be confused with boolean Y/N AMT
        AMT(false, false, true, false, false),

        CHAR(false, false, false, false, true),

        MULTIPLECHARVALUE(true, false, false, true, false),
        STRING(true, false, false, false, false),
        MULTIPLEVALUESTRING(true, false, false, true, false),
        MULTIPLESTRINGVALUE(true, false, false, true, false),
        // Technically has extra validation but we'll just make it a String for now.
        TENOR(true, false, false, true, false),

        CURRENCY(true, false, false, false, false), // String using ISO 4217 (3 chars)
        EXCHANGE(true, false, false, false, false), // String using ISO 10383 (2 chars)
        COUNTRY(true, false, false, false, false), // String using ISO 3166
        LANGUAGE(true, false, false, false, false), // String using ISO 639-1 standard

        // NB: data doesn't have a length field because in specified
        // XML files it often comes along with a length field.
        DATA(false, false, false, false, false),
        // Only used in 5.0sp1 or later.
        XMLDATA(false, false, false, false, false),

        // Boolean types
        BOOLEAN(false, false, false, false, false, true),

        UTCTIMESTAMP(true, false, false, false, false), // YYYYMMDD-HH:MM:SS or YYYYMMDD-HH:MM:SS.sss
        UTCTIMEONLY(true, false, false, false, false), // HH:MM:SS or HH:MM:SS.sss
        UTCDATEONLY(true, false, false, false, false), // YYYYMMDD
        LOCALMKTDATE(true, false, false, false, false), // YYYYMMDD
        MONTHYEAR(true, false, false, false, false), // YYYYMM or YYYYMMDD or YYYYMMWW
        TZTIMEONLY(true, false, false, false, false), // HH:MM[:SS][Z [ + - hh[:mm]]]
        TZTIMESTAMP(true, false, false, false, false); // YYYYMMDD-HH:MM:SS.sss*[Z [ + - hh[:mm]]]

        private final boolean isStringBased;
        private final boolean isIntBased;
        private final boolean isFloatBased;
        private final boolean isMultiValue;
        private final boolean isCharBased;
        private final boolean isBooleanBased;

        Type(
            final boolean isStringBased,
            final boolean isIntBased,
            final boolean isFloatBased,
            final boolean isMultiValue,
            final boolean isCharBased,
            final boolean isBooleanBased
        )
        {
            this.isStringBased = isStringBased;
            this.isIntBased = isIntBased;
            this.isFloatBased = isFloatBased;
            this.isMultiValue = isMultiValue;
            this.isCharBased = isCharBased;
            this.isBooleanBased = isBooleanBased;
        }

        Type(
            final boolean isStringBased,
            final boolean isIntBased,
            final boolean isFloatBased,
            final boolean isMultiValue,
            final boolean isCharBased
        )
        {
            this(isStringBased, isIntBased, isFloatBased, isMultiValue, isCharBased, false);
        }

        public boolean isStringBased()
        {
            return isStringBased;
        }

        public boolean isIntBased()
        {
            return isIntBased;
        }

        public boolean isFloatBased()
        {
            return isFloatBased;
        }

        public boolean isCharBased()
        {
            return isCharBased;
        }

        public boolean isCharOrBooleanBased()
        {
            return isCharBased || isBooleanBased;
        }

        public boolean isDataBased()
        {
            return this == DATA || this == XMLDATA;
        }

        public boolean hasOffsetField(final boolean flyweightsEnabled)
        {
            return hasLengthField(flyweightsEnabled) || (flyweightsEnabled && isDataBased());
        }

        public boolean hasLengthField(final boolean flyweightsEnabled)
        {
            return flyweightsEnabled ?
                isStringBased() || isIntBased() || isFloatBased() :
                isStringBased();
        }

        public boolean isMultiValue()
        {
            return isMultiValue;
        }

        public static Type lookup(final String name)
        {
            // Renamed from FIX 4.2 to Fix 4.4 spec
            if ("UTCDATE".equals(name))
            {
                return UTCDATEONLY;
            }

            // Fix typo in bad dictionary
            if ("STIRNG".equals(name))
            {
                return STRING;
            }

            // Standardise naming from bad dictionaries
            if ("MONTH-YEAR".equals(name))
            {
                return MONTHYEAR;
            }
            if ("RATE".equals(name))
            {
                return PRICE;
            }

            return valueOf(name.trim());
        }
    }

    public static class Value
    {
        private final String representation;
        private final String description;

        private List<String> alternativeNames;

        public Value(final String representation, final String description)
        {
            this.representation = representation;
            this.description = description;
        }

        public String representation()
        {
            return representation;
        }

        public String description()
        {
            return description;
        }

        @Override
        public boolean equals(final Object o)
        {
            if (o == null || getClass() != o.getClass())
            {
                return false;
            }

            final Value value = (Value)o;
            return Objects.equals(representation, value.representation) &&
                Objects.equals(description, value.description);
        }

        @Override
        public int hashCode()
        {
            int result = representation.hashCode();
            result = 31 * result + (description != null ? description.hashCode() : 0);
            return result;
        }

        public List<String> alternativeNames()
        {
            return alternativeNames;
        }

        public void alternativeNames(final List<String> alternativeNames)
        {
            this.alternativeNames = alternativeNames;
        }

        @Override
        public String toString()
        {
            return "Value{" +
                "representation=" + representation +
                ", description='" + description + '\'' +
                ", alternativeNames='" + alternativeNames + '\'' +
                '}';
        }
    }
}
