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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.ilink.ILink3Connection;
import uk.co.real_logic.artio.ilink.ILink3ConnectionConfiguration;
import uk.co.real_logic.artio.messages.GatewayError;

class InitiateILink3ConnectionReply extends LibraryReply<ILink3Connection>
{
    private final ILink3ConnectionConfiguration configuration;
    private boolean onTcpConnected = false;

    InitiateILink3ConnectionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTime,
        final ILink3ConnectionConfiguration configuration)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.configuration = configuration;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveInitiateILink(correlationId, configuration);

        requiresResend = position < 0;
    }

    @Override
    void onComplete(final ILink3Connection result)
    {
        libraryPoller.deregister(correlationId);
        super.onComplete(result);
    }

    void onTcpConnected()
    {
        onTcpConnected = true;
    }

    @Override
    protected boolean onTimeout()
    {
        // In the iLink3 case - the reply timeout should only be for the connection itself.
        // According to the iLink3 spec we should start a new countdown for the keepalive when
        // waiting for the negotiate and establish messages separately.

        if (!onTcpConnected)
        {
            libraryPoller.onTimeoutWaitingForConnection(correlationId);

            super.onTimeout();
        }

        return true;
    }

    @Override
    void onError(final GatewayError errorType, final String errorMessage)
    {
        onError(new FixGatewayException(String.format("%s: %s", errorType, errorMessage)));
    }

    ILink3ConnectionConfiguration configuration()
    {
        return configuration;
    }
}
