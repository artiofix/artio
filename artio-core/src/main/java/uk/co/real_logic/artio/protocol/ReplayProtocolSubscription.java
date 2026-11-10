/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.protocol;

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.ReplayCompleteDecoder;
import uk.co.real_logic.artio.messages.StartReplayDecoder;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public final class ReplayProtocolSubscription implements ControlledFragmentHandler
{
    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final ReplayCompleteDecoder replayComplete = new ReplayCompleteDecoder();
    private final StartReplayDecoder startReplay = new StartReplayDecoder();
    private final ReplayProtocolHandler handler;

    public ReplayProtocolSubscription(final ReplayProtocolHandler handler)
    {
        this.handler = handler;
    }

    @Override
    public Action onFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        return onFragment(buffer, offset, length, header.position());
    }

    @SuppressWarnings("FinalParameters")
    private Action onFragment(final DirectBuffer buffer, int offset, int length, final long position)
    {
        messageHeader.wrap(buffer, offset);

        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();
        offset += MessageHeaderDecoder.ENCODED_LENGTH;

        switch (messageHeader.templateId())
        {
            case ReplayCompleteDecoder.TEMPLATE_ID:
            {
                return onReplayComplete(buffer, offset, blockLength, version);
            }

            case StartReplayDecoder.TEMPLATE_ID:
            {
                return onStartReplay(buffer, offset, blockLength, version, position);
            }
        }

        return CONTINUE;
    }

    private Action onReplayComplete(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version)
    {
        final ReplayCompleteDecoder replayComplete = this.replayComplete;
        replayComplete.wrap(buffer, offset, blockLength, version);
        return handler.onReplayComplete(
            replayComplete.connection(),
            replayComplete.correlationId());
    }

    private Action onStartReplay(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final long position)
    {
        final StartReplayDecoder startReplay = this.startReplay;
        startReplay.wrap(buffer, offset, blockLength, version);
        return handler.onStartReplay(
            startReplay.session(),
            startReplay.connection(),
            startReplay.correlationId(),
            position);
    }
}
