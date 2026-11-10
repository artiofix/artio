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
package uk.co.real_logic.artio.message_examples;

import uk.co.real_logic.artio.ValidationError;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.otf.MessageControl;
import uk.co.real_logic.artio.otf.OtfMessageAcceptor;
import uk.co.real_logic.artio.util.AsciiBuffer;

// You register the acceptor - which is your custom application hook
// Your generic acceptor then gets callbacks for each field of the tag or tags that it
// gets registered for
public class SampleOtfAcceptor implements OtfMessageAcceptor
{
    private boolean wantsToSell;
    private String symbol;

    @Override
    public MessageControl onNext()
    {
        System.out.println("a NewOrderSingle has arrived");
        return MessageControl.CONTINUE;
    }

    @Override
    public MessageControl onField(final int tag, final AsciiBuffer buffer, final int offset, final int length)
    {
        switch (tag)
        {
            // You switch on the tag in order to decode what field data has arrived.
            case 54:
                wantsToSell = buffer.getByte(offset) == '2';
                break;

            case 55:
                symbol = buffer.getStringUtf8(offset, length);
                break;

            // Implement other tags in order to
            // Optional fields will either generate callbacks or not depending upon whether they
            // are present in the message.
        }
        return MessageControl.CONTINUE;
    }

    @Override
    public MessageControl onGroupHeader(final int tag, final int numInGroup)
    {
        // Some FIX fields consist of repeating groups, you get callbacks
        // when these start and end.
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
        // Message has been parsed and passed its checksum check.
        // Now we can make a decision about what to do with the message.

        if (wantsToSell && "USD".equals(symbol))
        {
            System.out.println("Our client wants to sell dollars");
        }
        return MessageControl.CONTINUE;
    }

    @Override
    public boolean onError(
        final ValidationError error, final long messageType, final int tagNumber, final AsciiFieldFlyweight value)
    {
        return false;
    }
}
