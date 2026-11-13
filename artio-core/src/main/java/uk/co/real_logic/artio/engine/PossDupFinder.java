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
package uk.co.real_logic.artio.engine;

import uk.co.real_logic.artio.ValidationError;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.otf.MessageControl;
import uk.co.real_logic.artio.otf.OtfMessageAcceptor;
import uk.co.real_logic.artio.util.AsciiBuffer;

class PossDupFinder implements OtfMessageAcceptor
{
    public static final int NO_ENTRY = -1;

    private int possDupOffset;
    private int sendingTimeOffset;
    private int sendingTimeLength;
    private int bodyLength;
    private int bodyLengthOffset;
    private int lengthOfBodyLength;
    private int origSendingTimeOffset;
    private int origSendingTimeLength;
    private int checkSumOffset;

    @Override
    public MessageControl onNext()
    {
        possDupOffset = NO_ENTRY;
        sendingTimeOffset = NO_ENTRY;
        sendingTimeLength = NO_ENTRY;
        origSendingTimeOffset = NO_ENTRY;
        origSendingTimeLength = NO_ENTRY;
        bodyLength = NO_ENTRY;
        bodyLengthOffset = NO_ENTRY;
        lengthOfBodyLength = NO_ENTRY;
        checkSumOffset = NO_ENTRY;
        return MessageControl.CONTINUE;
    }

    @Override
    public MessageControl onField(final int tag, final AsciiBuffer buffer, final int offset, final int length)
    {
        switch (tag)
        {
            case SessionConstants.POSS_DUP_FLAG:
                possDupOffset = offset;
                break;

            case SessionConstants.SENDING_TIME:
                sendingTimeOffset = offset;
                sendingTimeLength = length;
                break;

            case SessionConstants.ORIG_SENDING_TIME:
                origSendingTimeOffset = offset;
                origSendingTimeLength = length;
                break;

            case SessionConstants.BODY_LENGTH:
                bodyLengthOffset = offset;
                lengthOfBodyLength = length;
                bodyLength = buffer.getInt(offset, offset + length);
                break;

            case SessionConstants.CHECKSUM:
                checkSumOffset = offset;
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

    int possDupOffset()
    {
        return possDupOffset;
    }

    public int checkSumOffset()
    {
        return checkSumOffset;
    }

    int sendingTimeEnd()
    {
        return sendingTimeOffset + sendingTimeLength + 1;
    }

    int sendingTimeOffset()
    {
        return sendingTimeOffset;
    }

    int sendingTimeLength()
    {
        return sendingTimeLength;
    }

    int origSendingTimeOffset()
    {
        return origSendingTimeOffset;
    }

    int origSendingTimeLength()
    {
        return origSendingTimeLength;
    }

    int bodyLength()
    {
        return bodyLength;
    }

    int bodyLengthOffset()
    {
        return bodyLengthOffset;
    }

    int lengthOfBodyLength()
    {
        return lengthOfBodyLength;
    }
}
