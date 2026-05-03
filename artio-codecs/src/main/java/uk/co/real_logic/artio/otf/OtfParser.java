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
package uk.co.real_logic.artio.otf;

import org.agrona.DirectBuffer;
import org.agrona.collections.IntHashSet;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static uk.co.real_logic.artio.ValidationError.INVALID_CHECKSUM;
import static uk.co.real_logic.artio.ValidationError.PARSE_ERROR;
import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.otf.MessageControl.STOP;
import static uk.co.real_logic.artio.util.AsciiBuffer.UNKNOWN_INDEX;

/**
 * Zero allocation generic parser for fix messages.
 *
 * Take care when refactoring:
 *
 * There are a lot of places where values are passed as parameters and not assigned to fields in order to
 * allow stack allocated primitives and avoid allocation.
 */
public final class OtfParser
{
    private static final int NO_CHECKSUM = -2;
    private static final int UNKNOWN = -1;

    private final AsciiBuffer string = new MutableAsciiBuffer();
    private final AsciiFieldFlyweight stringField = new AsciiFieldFlyweight();

    private final OtfMessageAcceptor acceptor;
    private final LongDictionary groupToField;

    private int checksum;
    private int checksumOffset;
    private long messageType;
    private int tag;

    public OtfParser(final OtfMessageAcceptor acceptor, final LongDictionary groupToField)
    {
        this.acceptor = acceptor;
        this.groupToField = groupToField;
    }

    public void onMessage(final DirectBuffer buffer, final int offset, final int length)
    {
        string.wrap(buffer);
        if (acceptor.onNext() == STOP)
        {
            return;
        }

        tag = UNKNOWN;
        this.messageType = UNKNOWN;

        checksum = NO_CHECKSUM;
        checksumOffset = 0;

        try
        {
            if (parseFields(offset, offset + length, UNKNOWN, null, 0) < 0)
            {
                return;
            }

            if (validChecksum(offset, checksum))
            {
                acceptor.onComplete();
            }
            else
            {
                invalidChecksum(this.messageType);
            }
        }
        catch (final NumberFormatException ex)
        {
            parseError(this.messageType, tag);
        }
    }

    private int parseFields(
        final int offset,
        final int end,
        final int groupTag,
        final IntHashSet groupFields,
        final int numberOfElementsInGroup)
    {
        int firstFieldInGroup = UNKNOWN;
        int indexOfGroupElement = 0;

        int position = offset;

        while (position < end)
        {
            final int equalsPosition = string.scan(position, end, '=');
            if (!validatePosition(equalsPosition, acceptor))
            {
                return position;
            }

            tag = string.getNatural(position, equalsPosition);
            final int valueOffset = equalsPosition + 1;
            final int endOfField = string.scan(valueOffset, end, START_OF_HEADER);
            if (!validatePosition(endOfField, acceptor))
            {
                return position;
            }

            final int valueLength = endOfField - valueOffset;

            final IntHashSet newGroupFields = groupToField.values(tag);
            if (newGroupFields == null)
            {

                if (insideAGroup(groupTag))
                {
                    if (isEndOfGroup(groupFields))
                    {
                        groupEnd(groupTag, numberOfElementsInGroup, indexOfGroupElement);
                        return position;
                    }
                    else
                    {
                        // First field first iteration
                        if (firstFieldInGroup == UNKNOWN)
                        {
                            firstFieldInGroup = tag;
                        }
                        // We've seen the first field again - its a new group iteration
                        else if (tag == firstFieldInGroup)
                        {
                            if (groupEnd(groupTag, numberOfElementsInGroup, indexOfGroupElement) == STOP)
                            {
                                return position;
                            }
                            indexOfGroupElement++;
                            if (groupBegin(groupTag, numberOfElementsInGroup, indexOfGroupElement) == STOP)
                            {
                                return position;
                            }
                        }
                    }
                }
                final MessageControl control = acceptor.onField(tag, string, valueOffset, valueLength);

                collectImportantFields(equalsPosition, valueOffset, endOfField, valueLength);

                position = endOfField + 1;

                if (control == STOP)
                {
                    return ~position;
                }
            }
            else
            {
                if (insideAGroup(groupTag) && isEndOfGroup(groupFields))
                {
                    groupEnd(groupTag, numberOfElementsInGroup, indexOfGroupElement);
                    return position;
                }
                else
                {
                    position = parseGroup(tag, valueOffset, endOfField, end, newGroupFields);

                    if (position < 0)
                    {
                        return position;
                    }
                }
            }
        }

        return position;
    }

    private int parseGroup(
        final int tag,
        final int valueOffset,
        final int endOfField,
        final int end,
        final IntHashSet groupFields)
    {
        final int numberOfElements = string.getNatural(valueOffset, endOfField);

        acceptor.onGroupHeader(tag, numberOfElements);

        if (numberOfElements > 0)
        {
            if (groupBegin(tag, numberOfElements, 0) == STOP)
            {
                return ~endOfField;
            }

            final int position = parseFields(endOfField + 1, end, tag, groupFields, numberOfElements);
            if (position == end)
            {
                if (groupEnd(tag, numberOfElements, numberOfElements - 1) == STOP)
                {
                    return ~position;
                }
            }
            return position;
        }

        return endOfField + 1;
    }

    private boolean isEndOfGroup(final IntHashSet groupFields)
    {
        return !groupFields.contains(tag);
    }

    private void collectImportantFields(
        final int equalsPosition,
        final int valueOffset,
        final int endOfField,
        final int valueLength)
    {
        if (tag == CHECKSUM)
        {
            checksum = string.getNatural(valueOffset, endOfField);
            checksumOffset = equalsPosition - 2;
        }
        else if (tag == MESSAGE_TYPE)
        {
            messageType = string.getMessageType(valueOffset, valueLength);
        }
    }

    private boolean insideAGroup(final int tag)
    {
        return tag != UNKNOWN;
    }

    private MessageControl groupBegin(final int tag, final int numberOfElements, final int index)
    {
        return acceptor.onGroupBegin(tag, numberOfElements, index);
    }

    private MessageControl groupEnd(final int tag, final int numberOfElements, final int index)
    {
        return acceptor.onGroupEnd(tag, numberOfElements, index);
    }

    private boolean parseError(final long messageType, final int tag)
    {
        return acceptor.onError(PARSE_ERROR, messageType, tag, stringField);
    }

    private boolean invalidChecksum(final long messageType)
    {
        return acceptor.onError(INVALID_CHECKSUM, messageType, CHECKSUM, stringField);
    }

    private boolean validatePosition(final int position, final OtfMessageAcceptor acceptor)
    {
        if (position == UNKNOWN_INDEX)
        {
            // null because there's no actual field data at this point.
            acceptor.onError(PARSE_ERROR, messageType, tag, null);

            return false;
        }

        return true;
    }

    private boolean validChecksum(final int offset, final int messageChecksum)
    {
        if (messageChecksum == NO_CHECKSUM)
        {
            return false;
        }

        final int correctChecksum = string.computeChecksum(offset, checksumOffset);
        return correctChecksum == messageChecksum;
    }
}
