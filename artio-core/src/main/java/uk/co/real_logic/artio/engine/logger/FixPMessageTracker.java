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
package uk.co.real_logic.artio.engine.logger;

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.fixp.AbstractFixPParser;
import uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader;
import uk.co.real_logic.artio.messages.FixPMessageDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class FixPMessageTracker extends MessageTracker
{
    private final AbstractFixPParser fixPParser;
    private int totalMessages;

    public FixPMessageTracker(
        final ControlledFragmentHandler messageHandler,
        final AbstractFixPParser fixPParser,
        final int totalMessages)
    {
        super(LogTag.REPLAY, messageHandler);
        this.fixPParser = fixPParser;
        this.totalMessages = totalMessages;
    }

    @Override
    public Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        messageHeaderDecoder.wrap(buffer, offset);

        final int templateId = messageHeaderDecoder.templateId();
        if (templateId == FixPMessageDecoder.TEMPLATE_ID)
        {
            // NB: we can aggregate contiguous ranges of business messages and we ignore session messages when indexing
            // So we need to filter out the session messages here on replay.

            final int encoderOffset = offset + MessageHeaderEncoder.ENCODED_LENGTH;
            final int headerOffset = encoderOffset + SimpleOpenFramingHeader.SOFH_LENGTH +
                FixPMessageDecoder.BLOCK_LENGTH;
            final boolean retransmittedMessage = fixPParser.isRetransmittedMessage(buffer, headerOffset);
            if (!retransmittedMessage || count >= maxCount || totalMessages <= 0)
            {
                return CONTINUE;
            }

            final Action action = messageHandler.onFragment(buffer, offset, length, header);
            if (action != ABORT)
            {
                totalMessages--;
                count++;
            }

            return action;
        }

        return CONTINUE;
    }
}
