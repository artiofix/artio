/*
 * Copyright 2019 Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.ValidationError;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.otf.MessageControl;
import uk.co.real_logic.artio.otf.OtfMessageAcceptor;
import uk.co.real_logic.artio.otf.OtfParser;
import uk.co.real_logic.artio.util.AsciiBuffer;

class PasswordCleaner
{
    private static final int NO_ENTRY = -1;
    private static final int REPLACEMENT_LENGTH = 3;
    private static final UnsafeBuffer REPLACEMENT = new UnsafeBuffer(new byte[REPLACEMENT_LENGTH]);
    static
    {
        REPLACEMENT.putStringWithoutLengthAscii(0, "***");
    }

    private int passwordOffset;
    private int passwordLength;
    private int newPasswordOffset;
    private int newPasswordLength;
    private int bodyLength;
    private int bodyLengthOffset;
    private int lengthOfBodyLength;

    private final FieldScanner fieldScanner = new FieldScanner();
    private final OtfParser parser = new OtfParser(fieldScanner, new LongDictionary());
    private final ExpandableArrayBuffer cleanedBuffer = new ExpandableArrayBuffer();
    private int cleanedLength;

    public void clean(final DirectBuffer buffer, final int offset, final int length)
    {
        parser.onMessage(buffer, offset, length);

        final int passwordOffset = this.passwordOffset;
        if (passwordOffset == NO_ENTRY)
        {
            cleanedBuffer.putBytes(0, buffer, offset, length);
            cleanedLength = length;
            return;
        }

        final int newPasswordOffset = this.newPasswordOffset;

        final int firstOffset;
        final int firstLength;
        final int secondOffset;
        final int secondLength;

        if (passwordOffset < newPasswordOffset || newPasswordOffset == NO_ENTRY)
        {
            firstOffset = passwordOffset;
            firstLength = passwordLength;
            secondOffset = newPasswordOffset;
            secondLength = newPasswordLength;
        }
        else
        {
            firstOffset = newPasswordOffset;
            firstLength = newPasswordLength;
            secondOffset = passwordOffset;
            secondLength = passwordLength;
        }

        final int headLength = firstOffset - offset;
        cleanedBuffer.putBytes(0, buffer, offset, headLength);

        putReplacement(headLength);

        final int nextDestOffset = headLength + REPLACEMENT_LENGTH;

        final int nextSrcOffset = firstOffset + firstLength;
        final int newPasswordLengthChange;

        if (secondOffset == NO_ENTRY)
        {
            final int tailLength = length - (headLength + firstLength);
            cleanedBuffer.putBytes(nextDestOffset, buffer, nextSrcOffset, tailLength);

            newPasswordLengthChange = 0;
        }
        else
        {
            final int midLength = secondOffset - nextSrcOffset;
            cleanedBuffer.putBytes(nextDestOffset, buffer, nextSrcOffset, midLength);

            final int destNewPasswordOffset = nextDestOffset + midLength;

            putReplacement(destNewPasswordOffset);

            final int tailSrcOffset = secondOffset + secondLength;
            final int tailLength = (length + offset) - (tailSrcOffset);
            final int tailDestOffset = destNewPasswordOffset + REPLACEMENT_LENGTH;
            cleanedBuffer.putBytes(tailDestOffset, buffer, tailSrcOffset, tailLength);

            newPasswordLengthChange = secondLength - REPLACEMENT_LENGTH;
        }

        int lengthChange = firstLength - REPLACEMENT_LENGTH + newPasswordLengthChange;

        cleanedLength = length - lengthChange;

        if (lengthOfBodyLength == 2)
        {
            final int endOfBodyLength = (bodyLengthOffset - offset) + lengthOfBodyLength;
            shiftRight(endOfBodyLength, cleanedLength - endOfBodyLength);
            lengthOfBodyLength++;
            cleanedLength++;
            lengthChange--;
        }

        updateBodyLengthField(offset, lengthChange);
    }

    private void shiftRight(final int from, final int bytes)
    {
        for (int i = from + bytes; i >= from; i--)
        {
            cleanedBuffer.putByte(i + 1, cleanedBuffer.getByte(i));
        }
    }

    private void putReplacement(final int destNewPasswordOffset)
    {
        cleanedBuffer.putBytes(destNewPasswordOffset, REPLACEMENT, 0, REPLACEMENT_LENGTH);
    }

    private void updateBodyLengthField(final int offset, final int lengthChange)
    {
        final int newBodyLength = bodyLength - lengthChange;
        final int relativeBodyLengthOffset = bodyLengthOffset - offset;
        cleanedBuffer.putNaturalPaddedIntAscii(relativeBodyLengthOffset, lengthOfBodyLength,
            newBodyLength);
    }

    public DirectBuffer cleanedBuffer()
    {
        return cleanedBuffer;
    }

    public int cleanedLength()
    {
        return cleanedLength;
    }

    private final class FieldScanner implements OtfMessageAcceptor
    {
        @Override
        public MessageControl onNext()
        {
            passwordOffset = NO_ENTRY;
            newPasswordOffset = NO_ENTRY;
            bodyLength = NO_ENTRY;
            bodyLengthOffset = NO_ENTRY;
            lengthOfBodyLength = NO_ENTRY;
            return MessageControl.CONTINUE;
        }

        @Override
        public MessageControl onField(final int tag, final AsciiBuffer buffer, final int offset, final int length)
        {
            switch (tag)
            {
                case SessionConstants.PASSWORD:
                    passwordOffset = offset;
                    passwordLength = length;
                    break;

                case SessionConstants.NEW_PASSWORD:
                    newPasswordOffset = offset;
                    newPasswordLength = length;
                    break;

                case SessionConstants.BODY_LENGTH:
                    bodyLengthOffset = offset;
                    lengthOfBodyLength = length;
                    bodyLength = buffer.getInt(offset, offset + length);
                    break;
            }
            return MessageControl.CONTINUE;
        }

        @Override
        public MessageControl onGroupHeader(final int tag, final int numInGroup)
        {
            return MessageControl.CONTINUE;
        }

        @Override
        public MessageControl onGroupBegin(final int tag, final int numInGroup, final int index)
        {
            return MessageControl.CONTINUE;
        }

        @Override
        public MessageControl onGroupEnd(final int tag, final int numInGroup, final int index)
        {
            return MessageControl.CONTINUE;
        }

        @Override
        public MessageControl onComplete()
        {
            return MessageControl.CONTINUE;
        }

        @Override
        public boolean onError(
            final ValidationError error,
            final long messageType,
            final int tagNumber,
            final AsciiFieldFlyweight value)
        {
            return false;
        }

    }
}
