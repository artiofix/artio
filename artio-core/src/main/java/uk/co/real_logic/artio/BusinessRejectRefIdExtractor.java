/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio;

import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.dictionary.SessionConstants;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.otf.MessageControl;
import uk.co.real_logic.artio.otf.OtfMessageAcceptor;
import uk.co.real_logic.artio.otf.OtfParser;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static uk.co.real_logic.artio.dictionary.generation.CodecUtil.MISSING_LONG;
import static uk.co.real_logic.artio.util.MessageTypeEncoding.packMessageType;

public class BusinessRejectRefIdExtractor implements OtfMessageAcceptor
{
    private static final MutableAsciiBuffer NO_RESULT = new MutableAsciiBuffer(new byte[0]);

    private static final Long2LongHashMap PACKED_MESSAGE_TYPE_TO_REF_ID_TAG = new Long2LongHashMap(MISSING_LONG);

    private static void putReferenceTag(final String messageType, final int searchTag)
    {
        PACKED_MESSAGE_TYPE_TO_REF_ID_TAG.put(packMessageType(messageType), searchTag);
    }

    static
    {
        putReferenceTag("6", 23);
        putReferenceTag("7", 2);
        putReferenceTag("B", 148);
        putReferenceTag("C", 164);
        putReferenceTag("9", 11);
        putReferenceTag("P", 70);
        putReferenceTag("AT", 70);
        putReferenceTag("N", 66);
        putReferenceTag("T", 162);
        putReferenceTag("W", 262);
        putReferenceTag("X", 262);
        putReferenceTag("Y", 262);
        putReferenceTag("b", 117);
        putReferenceTag("d", 322);
        putReferenceTag("f", 324);
        putReferenceTag("h", 335);
        putReferenceTag("r", 37);
        putReferenceTag("w", 322);
        putReferenceTag("y", 322);
        putReferenceTag("AA", 322);
        putReferenceTag("AG", 131);
        putReferenceTag("AH", 644);
        putReferenceTag("AI", 117);
        putReferenceTag("p", 513);
        putReferenceTag("AE", 571);
        putReferenceTag("AU", 664);
        putReferenceTag("l", 390);
        putReferenceTag("m", 66);
        putReferenceTag("T", 777);
        putReferenceTag("AQ", 568);
        putReferenceTag("AR", 571);
        putReferenceTag("AM", 721);
        putReferenceTag("AO", 721);
        putReferenceTag("AP", 721);
        putReferenceTag("AW", 833);
        putReferenceTag("AZ", 904);
        putReferenceTag("BG", 909);
    }

    private final OtfParser parser = new OtfParser(this, new LongDictionary());

    private AsciiBuffer buffer;
    private int offset;
    private int length;
    private int sequenceNumber;

    private int searchTag;

    public void search(final long msgType, final DirectBuffer msgBuffer, final int msgOffset, final int msgLength)
    {
        buffer = NO_RESULT;
        offset = 0;
        length = 0;
        sequenceNumber = 0;

        searchTag = (int)PACKED_MESSAGE_TYPE_TO_REF_ID_TAG.get(msgType);

        parser.onMessage(msgBuffer, msgOffset, msgLength);
    }

    @Override
    public MessageControl onNext()
    {
        return MessageControl.CONTINUE;
    }

    @Override
    public MessageControl onComplete()
    {
        return MessageControl.CONTINUE;
    }

    @Override
    public MessageControl onField(final int tag, final AsciiBuffer buffer, final int offset, final int length)
    {
        if (tag == SessionConstants.MSG_SEQ_NO)
        {
            sequenceNumber = buffer.getInt(offset, offset + length);
        }

        if (tag == searchTag)
        {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
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
    public boolean onError(
        final ValidationError error, final long messageType, final int tagNumber, final AsciiFieldFlyweight value)
    {
        return false;
    }

    public AsciiBuffer buffer()
    {
        return buffer;
    }

    public int offset()
    {
        return offset;
    }

    public int length()
    {
        return length;
    }

    public int sequenceNumber()
    {
        return sequenceNumber;
    }
}
