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
package uk.co.real_logic.artio.util;

import org.agrona.LangUtil;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.fields.DecimalFloat;

import org.agrona.AsciiSequenceView;
import uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;

public final class Reflection
{
    private Reflection()
    {
    }

    public static void setInt(final Object object, final String setter, final int value)
        throws Exception
    {
        set(object, setter, int.class, value);
    }

    public static void setLong(final Object object, final String setter, final long value)
        throws Exception
    {
        set(object, setter, long.class, value);
    }

    public static void setChar(final Object object, final String setter, final char value)
        throws Exception
    {
        set(object, setter, char.class, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void setEnum(final Object object, final String setter, final String enumClassName, final String value)
        throws Exception
    {
        final Class<?> enumClass = object.getClass().getClassLoader().loadClass(enumClassName);
        final Object enumValue = Enum.valueOf((Class<Enum>)enumClass, value);
        set(object, setter, enumClass, enumValue);
    }

    public static void setFloat(final Object object, final String setter, final DecimalFloat value)
        throws Exception
    {
        set(object, setter, ReadOnlyDecimalFloat.class, value);
    }

    public static void setFloat(final Object object, final String setter, final long value, final int scale)
        throws Exception
    {
        set(object, setter, long.class, int.class, value, scale);
    }

    public static void setCharSequence(final Object object, final String setter, final CharSequence value)
        throws Exception
    {
        set(object, setter, CharSequence.class, value);
    }

    public static void setBoolean(final Object object, final String setter, final boolean value)
        throws Exception
    {
        set(object, setter, boolean.class, value);
    }

    public static void setByteArray(final Object object, final String setter, final byte[] value)
        throws Exception
    {
        set(object, setter, byte[].class, value);
    }

    public static void setByteArrayAsCopy(
        final Object object,
        final String setter,
        final byte[] value,
        final int offset,
        final int length
    )
        throws Exception
    {
        try
        {
            object.getClass()
                    .getMethod(setter + "AsCopy", byte[].class, int.class, int.class)
                    .invoke(object, value, offset, length);
        }
        catch (final InvocationTargetException e)
        {
            LangUtil.rethrowUnchecked(e.getCause());
        }
    }

    private static void set(
        final Object object,
        final String setterName,
        final Class<?> type,
        final Object value) throws Exception
    {
        try
        {
            object.getClass()
                .getMethod(setterName, type)
                .invoke(object, value);
        }
        catch (final InvocationTargetException e)
        {
            LangUtil.rethrowUnchecked(e.getCause());
        }
    }

    private static void set(
        final Object object,
        final String setterName,
        final Class<?> type1,
        final Class<?> type2,
        final Object value1,
        final Object value2) throws Exception
    {
        object.getClass()
            .getMethod(setterName, type1, type2)
            .invoke(object, value1, value2);
    }

    public static void setField(
        final Object object,
        final String fieldName,
        final Object value) throws Exception
    {
        field(object, fieldName).set(object, value);
    }

    public static Object get(final Object value, final String name) throws Exception
    {
        return value.getClass()
            .getMethod(name)
            .invoke(value);
    }


    public static Object get(final Object value, final String name, final int parameter) throws Exception
    {
        return value.getClass()
            .getMethod(name, int.class)
            .invoke(value, parameter);
    }

    public static Object getField(final Object object, final String fieldName) throws Exception
    {
        return field(object, fieldName).get(object);
    }

    public static Field field(final Object object, final String fieldName) throws NoSuchFieldException
    {
        final Field field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }

    public static Object call(final Object value, final String methodName) throws Exception
    {
        return value.getClass()
            .getMethod(methodName)
            .invoke(value);
    }

    public static void reset(final Encoder encoder) throws Exception
    {
        call(encoder, "reset");
    }

    public static Object next(final Object stub) throws Exception
    {
        return call(stub, "next");
    }

    public static Object getEgGroup(final Object stub) throws Exception
    {
        return get(stub, "egGroupGroup");
    }

    public static Object getEgGroup(final Object stub, final int numberOfElements) throws Exception
    {
        return get(stub, "egGroupGroup", numberOfElements);
    }

    public static Iterator<?> getEgGroupIterator(final Decoder decoder) throws Exception
    {
        return (Iterator<?>)get(decoder, "egGroupGroupIterator");
    }

    public static Iterable<?> getEgGroupIterable(final Decoder decoder) throws Exception
    {
        return (Iterable<?>)get(decoder, "egGroupGroupIterator");
    }

    public static Object getComponentGroup(final Object stub, final int numberOfElements) throws Exception
    {
        return get(stub, "componentGroupGroup", numberOfElements);
    }

    public static Object getNestedGroup(final Object group) throws Exception
    {
        return get(group, "nestedGroupGroup");
    }

    public static Object getNestedGroup(final Object group, final int numberOfElements) throws Exception
    {
        return get(group, "nestedGroupGroup", numberOfElements);
    }

    public static Object getEgComponent(final Object object) throws Exception
    {
        return get(object, "egComponent");
    }

    public static Object getRepresentation(final Object object) throws Exception
    {
        return get(object, "representation");
    }

    public static byte[] getBytes(final Decoder decoder, final String field) throws Exception
    {
        return (byte[])get(decoder, field);
    }

    public static char[] getChars(final Decoder decoder, final String field) throws Exception
    {
        return (char[])get(decoder, field);
    }

    public static char getChar(final Object object, final String field) throws Exception
    {
        return (char)get(object, field);
    }

    public static int getInt(final Object object, final String field) throws Exception
    {
        return (int)get(object, field);
    }

    public static boolean getBoolean(final Object object, final String field) throws Exception
    {
        return (boolean)get(object, field);
    }

    public static String getString(final Object decoder, final String field) throws Exception
    {
        return (String)get(decoder, field);
    }

    public static AsciiSequenceView getAsciiSequenceView(final Object value, final String name) throws Exception
    {
        final AsciiSequenceView view = new AsciiSequenceView();
        return (AsciiSequenceView)value.getClass()
                .getMethod(name, AsciiSequenceView.class)
                .invoke(value, view);
    }
}
