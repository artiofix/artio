/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.GatewayError;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

class FixPFollowerSessionReply extends LibraryReply<Long>
{
    private final FixPProtocolType protocolType;
    private final MutableAsciiBuffer buffer;

    FixPFollowerSessionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTimeInMs,
        final byte[] firstMessage,
        final FixPProtocolType protocolType)
    {
        super(libraryPoller, latestReplyArrivalTimeInMs);
        this.buffer = new MutableAsciiBuffer(firstMessage);
        this.protocolType = protocolType;

        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveFollowerSessionRequest(
            correlationId, protocolType, buffer, 0, buffer.capacity());

        requiresResend = position < 0;
    }

    @Override
    void onError(final GatewayError errorType, final String errorMessage)
    {
        this.onError(new IllegalArgumentException(errorMessage));
    }
}
