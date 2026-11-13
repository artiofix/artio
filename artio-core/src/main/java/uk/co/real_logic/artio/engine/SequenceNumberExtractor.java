/*
 * Copyright 2019 Monotonic Ltd.
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

import io.aeron.Publication;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.ValidationError;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.fields.AsciiFieldFlyweight;
import uk.co.real_logic.artio.otf.MessageControl;
import uk.co.real_logic.artio.otf.OtfMessageAcceptor;
import uk.co.real_logic.artio.otf.OtfParser;
import uk.co.real_logic.artio.util.AsciiBuffer;

import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.otf.MessageControl.CONTINUE;
import static uk.co.real_logic.artio.otf.MessageControl.STOP;

public class SequenceNumberExtractor
{
    public static final int NO_SEQUENCE_NUMBER = -1;

    private boolean isSequenceReset;
    private int sequenceNumber;
    private int newSequenceNumber;

    private long aeronSessionId;
    private long position = Publication.NOT_CONNECTED;

    public SequenceNumberExtractor()
    {
    }

    public int extract(
        final DirectBuffer buffer, final int offset, final int length)
    {
        sequenceNumber = NO_SEQUENCE_NUMBER;
        newSequenceNumber = NO_SEQUENCE_NUMBER;
        isSequenceReset = false;

        parser.onMessage(buffer, offset, length);

        return pickSequenceNumber();
    }

    public int extractCached(
        final DirectBuffer buffer, final int offset, final int length, final long aeronSessId, final long position)
    {
        if (aeronSessId == this.aeronSessionId && position == this.position)
        {
            return pickSequenceNumber();
        }
        else
        {
            this.aeronSessionId = aeronSessId;
            this.position = position;
            return extract(buffer, offset, length);
        }
    }

    private int pickSequenceNumber()
    {
        return newSequenceNumber != NO_SEQUENCE_NUMBER ? newSequenceNumber - 1 : sequenceNumber;
    }

    public int sequenceNumber()
    {
        return sequenceNumber;
    }

    public int newSequenceNumber()
    {
        return newSequenceNumber;
    }

    private final OtfMessageAcceptor extractor = new OtfMessageAcceptor()
    {
        @Override
        public MessageControl onNext()
        {
            return CONTINUE;
        }

        @Override
        public MessageControl onComplete()
        {
            return CONTINUE;
        }

        @Override
        public MessageControl onField(
            final int tag, final AsciiBuffer buffer, final int offset, final int length)
        {
            if (tag == MESSAGE_TYPE)
            {
                isSequenceReset = length == 1 && buffer.getByte(offset) == SEQUENCE_RESET_TYPE_BYTE;
            }
            else if (tag == MSG_SEQ_NO)
            {
                sequenceNumber = buffer.getInt(offset, offset + length);
                if (!isSequenceReset)
                {
                    return STOP;
                }
            }
            else if (tag == NEW_SEQ_NO && isSequenceReset)
            {
                newSequenceNumber = buffer.getInt(offset, offset + length);
                return STOP;
            }

            return CONTINUE;
        }

        @Override
        public MessageControl onGroupHeader(final int tag, final int numInGroup)
        {
            return CONTINUE;
        }

        @Override
        public MessageControl onGroupBegin(final int tag, final int numInGroup, final int index)
        {
            return CONTINUE;
        }

        @Override
        public MessageControl onGroupEnd(final int tag, final int numInGroup, final int index)
        {
            return CONTINUE;
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
    };

    private final OtfParser parser = new OtfParser(extractor, new LongDictionary());
}
