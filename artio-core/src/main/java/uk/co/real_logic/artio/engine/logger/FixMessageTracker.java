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
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.messages.FixMessageDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.ThrottleNotificationDecoder;
import uk.co.real_logic.artio.messages.ThrottleRejectDecoder;
import uk.co.real_logic.artio.util.CharFormatter;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.engine.SessionInfo.UNK_SESSION;

public class FixMessageTracker extends MessageTracker
{
    private final ThrottleNotificationDecoder throttleNotification = new ThrottleNotificationDecoder();
    private final ThrottleRejectDecoder throttleReject = new ThrottleRejectDecoder();
    private final FixMessageDecoder messageDecoder = new FixMessageDecoder();
    private final long sessionId;

    public FixMessageTracker(final LogTag logTag, final ControlledFragmentHandler messageHandler, final long sessionId)
    {
        super(logTag, messageHandler);
        this.sessionId = sessionId;
    }

    @Override
    public Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final MessageHeaderDecoder messageHeaderDecoder = this.messageHeaderDecoder;
        messageHeaderDecoder.wrap(buffer, offset);

        final int messageOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int templateId = messageHeaderDecoder.templateId();
        final int blockLength = messageHeaderDecoder.blockLength();
        final int version = messageHeaderDecoder.version();

        if (templateId == FixMessageDecoder.TEMPLATE_ID)
        {
            if (sessionId != UNK_SESSION)
            {

                messageDecoder.wrap(
                    buffer,
                    messageOffset,
                    blockLength,
                    version);

                if (messageDecoder.session() != sessionId)
                {
                    return CONTINUE;
                }
            }

            if (DebugLogger.isEnabled(logTag))
            {
                messageDecoder.skipMetaData();
                final int bodyLength = messageDecoder.bodyLength();
                final int bodyOffset = messageDecoder.limit() + FixMessageDecoder.bodyHeaderLength();
                final CharFormatter formatter = FOUND_REPLAY_MESSAGE.get();
                formatter.clear();
                DebugLogger.log(logTag, formatter, buffer, bodyOffset, bodyLength);
            }

            return processFragment(buffer, offset, length, header);
        }
        else if (templateId == ThrottleNotificationDecoder.TEMPLATE_ID)
        {
            throttleNotification.wrap(buffer, messageOffset, blockLength, version);
            return onThrottle(throttleNotification.session(), buffer, offset, length, header);
        }
        else if (templateId == ThrottleRejectDecoder.TEMPLATE_ID)
        {
            throttleReject.wrap(buffer, messageOffset, blockLength, version);
            return onThrottle(throttleReject.session(), buffer, offset, length, header);
        }

        return CONTINUE;
    }

    private Action onThrottle(
        final long sessionId,
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        if (sessionId != this.sessionId)
        {
            return CONTINUE;
        }

        return processFragment(buffer, offset, length, header);
    }

    private Action processFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final Action action = messageHandler.onFragment(buffer, offset, length, header);
        if (action != ABORT)
        {
            count++;
        }
        return action;
    }
}
