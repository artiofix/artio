/*
 * Copyright 2015-2024 Real Logic Limited., Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.fields;

import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.util.PowerOf10;

import static uk.co.real_logic.artio.util.PowerOf10.HIGHEST_POWER_OF_TEN;
import static uk.co.real_logic.artio.util.PowerOf10.POWERS_OF_TEN;

/**
 * The main purpose of this class is to prevent accidental mutation of predefined constant values such as ZERO.
 * An additional benefit is that it can be used to specify an argument of a function that promises not to
 * modify the input value.
 *
 * The class is prefixed with ReadOnly, not with Immutable. It can still reference a mutable DecimalFloat subclass.
 * This should be sufficient, as the ReadOnlyDecimalFloat type guarantees the following:
 *
 * - holders of the reference to the ReadOnlyDecimalFloat type do not modify its value
 *   even if the underlying implementation is mutable
 *
 * - immutability, when using a predefined constants or when obtained using {@link DecimalFloat#immutableCopy()}
 *
 */
public class ReadOnlyDecimalFloat implements Comparable<ReadOnlyDecimalFloat>
{
    private static final int SCALE_NAN_VALUE = -128;
    private static final long VALUE_NAN_VALUE = Long.MIN_VALUE;
    private static final double DOUBLE_NAN_VALUE = Double.NaN;

    public static final long VALUE_MAX_VAL = 999_999_999_999_999_999L;
    private static final long VALUE_MIN_VAL = -VALUE_MAX_VAL;
    private static final int SCALE_MAX_VAL = 127;
    private static final int SCALE_MIN_VAL = 0;

    public static final ReadOnlyDecimalFloat MIN_VALUE = new ReadOnlyDecimalFloat(VALUE_MIN_VAL, 0);
    public static final ReadOnlyDecimalFloat MAX_VALUE = new ReadOnlyDecimalFloat(VALUE_MAX_VAL, 0);
    public static final ReadOnlyDecimalFloat ZERO = new ReadOnlyDecimalFloat(0, 0);
    public static final ReadOnlyDecimalFloat NAN = newReadOnlyNanValue();
    public static final ReadOnlyDecimalFloat MISSING_FLOAT = NAN;

    protected long value;
    protected int scale;

    /**
     * Used for values created internally that would not pass the normalization, such as NaN.
     */
    private ReadOnlyDecimalFloat()
    {

    }

    /**
     * Standard way of constructing decimal float.
     *
     * Making this constructor package-private prevents from subclassing it outside this package.
     * The class is not final only because it needs to be extended by the DecimalFloat in the same package.

     * @param value significant digits
     * @param scale location of the decimal point
     */
    ReadOnlyDecimalFloat(final long value, final int scale)
    {
        setAndNormalise(value, scale);
    }

    /**
     * @return The same value, but with the type that allows mutations.
     */
    public DecimalFloat mutableCopy()
    {
        // There is no need to use the public normalizing constructor as the values were already normalized
        // or created internally without the normalization.
        // If the public constructor was used, we would not be able to create copies of some
        // internally created values such as NaN

        final DecimalFloat mutableCopy = new DecimalFloat();
        mutableCopy.value = this.value;
        mutableCopy.scale = this.scale;
        return mutableCopy;
    }

    public boolean isNaNValue()
    {
        return isNaNValue(value, scale);
    }

    public double toDouble()
    {
        if (isNaNValue())
        {
            return DOUBLE_NAN_VALUE;
        }
        return toDouble(value, scale);
    }


    public static boolean isNaNValue(final long value, final int scale)
    {
        return value == ReadOnlyDecimalFloat.VALUE_NAN_VALUE && scale == ReadOnlyDecimalFloat.SCALE_NAN_VALUE;
    }

    public long value()
    {
        return this.value;
    }

    /**
     * Get the number of digits to the right of the decimal point.
     *
     * @return the number of digits to the right of the decimal point.
     */
    public int scale()
    {
        return this.scale;
    }

    public void appendTo(final StringBuilder builder)
    {
        CodecUtil.appendFloat(builder, this);
    }

    public String toString()
    {
        final StringBuilder builder = new StringBuilder();
        appendTo(builder);
        return builder.toString();
    }

    public final int compareTo(final ReadOnlyDecimalFloat other)
    {
        final long value = this.value;
        final int scale = this.scale;

        final long otherValue = other.value;
        final int otherScale = other.scale;

        final long decimalPointDivisor = PowerOf10.pow10(scale);
        final long otherDecimalPointDivisor = PowerOf10.pow10(otherScale);

        final long valueBeforeDecimalPoint = value / decimalPointDivisor;
        final long otherValueBeforeDecimalPoint = otherValue / otherDecimalPointDivisor;

        final int beforeDecimalPointComparison = Long.compare(valueBeforeDecimalPoint, otherValueBeforeDecimalPoint);

        if (beforeDecimalPointComparison != 0)
        {
            // Can be determined using just the long value before decimal point
            return beforeDecimalPointComparison;
        }

        // values after decimal point, but has removed scale entirely
        long valueAfterDecimalPoint = (value % decimalPointDivisor);
        long otherValueAfterDecimalPoint = (otherValue % otherDecimalPointDivisor);

        // re-normalise with scales by multiplying the lower scale number up
        if (scale > otherScale)
        {
            final int differenceInScale = scale - otherScale;
            otherValueAfterDecimalPoint *= PowerOf10.pow10(differenceInScale);
        }
        else
        {
            final int differenceInScale = otherScale - scale;
            valueAfterDecimalPoint *= PowerOf10.pow10(differenceInScale);
        }

        return Long.compare(valueAfterDecimalPoint, otherValueAfterDecimalPoint);
    }

    /**
     * This method should satisfy the Effective Java - 3rd Edition - Methods Common to All Objects Item 10
     * "Obey the general contract when overriding equals",
     * and it should do it for every x and y where x and y can be an instance of
     * ReadOnlyDecimalFloat or DecimalFloat, in any order, this method is:
     * Reflexive : x.equal(x) return true
     * Symmetric : if x.equals(y) return true, y.equals(x) return true
     * Transitive : if x.equals(y) return true and y.equals(z) return true, x.equals(z) return true
     * Consistent : invocations of x.equals(y) return the same value
     * For any x != null, x.equals(null) returns false
     *
     * Support for DecimalFloat was done to provide a backward compatibility when some constants such as ZERO or NAN
     * were made read only to avoid subtle bugs. By making DecimalFloats equal the corresponding ReadOnlyDecimalFloat
     * any code that used the predefined constants when they were mutable should still yield the same results,
     * thus avoiding a different class of subtle errors if the client code was not thoroughly tested
     *
     * @param o object to compare with
     * @return true if any combination of ReadOnlyDecimalFloat or DecimalFloat is equal each other
     */
    public final boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        //
        if (o == null || (ReadOnlyDecimalFloat.class != o.getClass() && DecimalFloat.class != o.getClass()))
        {
            return false;
        }

        final ReadOnlyDecimalFloat that = (ReadOnlyDecimalFloat)o;
        return scale == that.scale && value == that.value;
    }

    public final int hashCode()
    {
        final int result = (int)(value ^ (value >>> 32));
        return 31 * result + scale;
    }

    protected final void setAndNormalise(final long value, final int scale)
    {
        this.value = value;
        this.scale = scale;
        normalise();
    }

    private void normalise()
    {
        long value = this.value;
        int scale = this.scale;
        if (value == 0)
        {
            scale = 0;
        }
        else if (0 < scale)
        {
            while (value % 10 == 0 && 0 < scale)
            {
                value /= 10;
                --scale;
            }
        }
        else if (scale < 0)
        {
            while (!isOutsideLimits(value, VALUE_MIN_VAL, VALUE_MAX_VAL) && scale < 0)
            {
                value *= 10;
                ++scale;
            }
        }
        if (isOutsideLimits(scale, SCALE_MIN_VAL, SCALE_MAX_VAL) ||
            isOutsideLimits(value, VALUE_MIN_VAL, VALUE_MAX_VAL))
        {
            throw new ArithmeticException("Out of range: value: " + this.value + ", scale: " + this.scale);
        }
        this.value = value;
        this.scale = scale;
    }

    private static ReadOnlyDecimalFloat newReadOnlyNanValue()
    {
        final ReadOnlyDecimalFloat nanFloat = new ReadOnlyDecimalFloat();
        nanFloat.value = VALUE_NAN_VALUE;
        nanFloat.scale = SCALE_NAN_VALUE;
        return nanFloat;
    }

    private static boolean isOutsideLimits(final long value, final long lowerBound, final long upperBound)
    {
        return value < lowerBound || upperBound < value;
    }

    private static double toDouble(final long value, final int scale)
    {
        int remainingPowersOfTen = scale;
        double divisor = 1.0;
        while (remainingPowersOfTen >= HIGHEST_POWER_OF_TEN)
        {
            divisor *= POWERS_OF_TEN[HIGHEST_POWER_OF_TEN];
            remainingPowersOfTen -= HIGHEST_POWER_OF_TEN;
        }
        divisor *= POWERS_OF_TEN[remainingPowersOfTen];
        return value / divisor;
    }
}
