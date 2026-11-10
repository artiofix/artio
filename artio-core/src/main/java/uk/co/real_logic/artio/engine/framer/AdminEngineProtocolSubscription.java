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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.messages.AdminResetSequenceNumbersRequestDecoder;
import uk.co.real_logic.artio.messages.AllFixSessionsRequestDecoder;
import uk.co.real_logic.artio.messages.DisconnectSessionRequestDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;

class AdminEngineProtocolSubscription implements FragmentHandler
{
    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final AllFixSessionsRequestDecoder allFixSessionsRequest = new AllFixSessionsRequestDecoder();
    private final DisconnectSessionRequestDecoder disconnectSessionRequest = new DisconnectSessionRequestDecoder();
    private final AdminResetSequenceNumbersRequestDecoder adminResetSequenceNumbersRequest =
        new AdminResetSequenceNumbersRequestDecoder();

    private final Framer handler;

    AdminEngineProtocolSubscription(final Framer handler)
    {
        this.handler = handler;
    }

    @Override
    @SuppressWarnings("FinalParameters")
    public void onFragment(final DirectBuffer buffer, int offset, final int length, final Header header)
    {
        final MessageHeaderDecoder messageHeader = this.messageHeader;
        messageHeader.wrap(buffer, offset);

        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();
        offset += messageHeader.encodedLength();

        switch (messageHeader.templateId())
        {
            case AllFixSessionsRequestDecoder.TEMPLATE_ID:
            {
                onAllFixSessions(buffer, offset, blockLength, version);
                return;
            }

            case DisconnectSessionRequestDecoder.TEMPLATE_ID:
            {
                onDisconnectSession(buffer, offset, blockLength, version);
                return;
            }

            case AdminResetSequenceNumbersRequestDecoder.TEMPLATE_ID:
            {
                onAdminResetSequenceNumbersRequest(buffer, offset, blockLength, version);
                return;
            }
        }
    }

    private void onAllFixSessions(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version)
    {
        final AllFixSessionsRequestDecoder allFixSessionsRequest = this.allFixSessionsRequest;
        allFixSessionsRequest.wrap(buffer, offset, blockLength, version);
        handler.onAllFixSessions(allFixSessionsRequest.correlationId());
    }

    private void onDisconnectSession(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version)
    {
        final DisconnectSessionRequestDecoder disconnectSessionRequest = this.disconnectSessionRequest;
        disconnectSessionRequest.wrap(buffer, offset, blockLength, version);
        handler.onDisconnectSession(
            disconnectSessionRequest.correlationId(),
            disconnectSessionRequest.sessionId());
    }

    private void onAdminResetSequenceNumbersRequest(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version)
    {
        final AdminResetSequenceNumbersRequestDecoder adminResetSequenceNumbersRequest =
            this.adminResetSequenceNumbersRequest;
        adminResetSequenceNumbersRequest.wrap(buffer, offset, blockLength, version);
        handler.onAdminResetSequenceNumbersRequest(
            adminResetSequenceNumbersRequest.correlationId(),
            adminResetSequenceNumbersRequest.sessionId());
    }
}
