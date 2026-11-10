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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.engine.AbstractConnectedSessionInfo;
import uk.co.real_logic.artio.messages.ConnectionType;

abstract class GatewaySession implements AbstractConnectedSessionInfo
{
    protected static final int NO_TIMEOUT = -1;

    protected final ConnectionType connectionType;
    protected final long authenticationTimeoutInMs;

    // null iff session is offline.
    protected ReceiverEndPoint receiverEndPoint;
    protected long sessionId;
    protected long connectionId;
    protected String address;
    protected long disconnectTimeInMs = NO_TIMEOUT;

    protected boolean hasStartedAuthentication = false;
    protected int libraryId;
    // Only set when owned by gateway, in case that library reconnects.
    protected int lastLibraryId;

    GatewaySession(
        final long connectionId,
        final long sessionId,
        final String address,
        final ConnectionType connectionType,
        final long authenticationTimeoutInMs,
        final ReceiverEndPoint receiverEndPoint)
    {
        this.connectionId = connectionId;
        this.sessionId = sessionId;
        this.address = address;
        this.connectionType = connectionType;
        this.authenticationTimeoutInMs = authenticationTimeoutInMs;
        this.receiverEndPoint = receiverEndPoint;
    }

    @Override
    public long connectionId()
    {
        return connectionId;
    }

    @Override
    public abstract String address();

    public long sessionId()
    {
        return sessionId;
    }

    abstract int poll(long timeInMs, long timeInNs);

    void startAuthentication(final long timeInMs)
    {
        hasStartedAuthentication = true;
        disconnectTimeInMs = timeInMs + authenticationTimeoutInMs;
    }

    void onAuthenticationResult()
    {
        disconnectTimeInMs = NO_TIMEOUT;
    }

    void disconnectAt(final long disconnectTimeout)
    {
        this.disconnectTimeInMs = disconnectTimeout;
    }

    boolean hasDisconnected()
    {
        return receiverEndPoint == null || receiverEndPoint.hasDisconnected();
    }

    int checkNoLogonDisconnect(final long timeInMs)
    {
        if (disconnectTimeInMs == NO_TIMEOUT)
        {
            return 0;
        }

        if (disconnectTimeInMs <= timeInMs && !hasDisconnected())
        {
            if (hasStartedAuthentication)
            {
                receiverEndPoint.onAuthenticationTimeoutDisconnect();
            }
            else
            {
                receiverEndPoint.onNoLogonDisconnect();
            }
            return 1;
        }

        return 0;
    }

    public boolean isOffline()
    {
        return receiverEndPoint == null;
    }

    ConnectionType connectionType()
    {
        return connectionType;
    }

    public void libraryId(final int libraryId)
    {
        this.libraryId = libraryId;
    }

    public int libraryId()
    {
        return libraryId;
    }

    public void consumeOfflineSession(final GatewaySession oldGatewaySession)
    {
        libraryId(oldGatewaySession.libraryId());
    }

    abstract long lastLogonTime();

    abstract void acceptorSequenceNumbers(
        int lastSentSequenceNumber, int lastReceivedSequenceNumber);

    abstract void onDisconnectReleasedByOwner();

    abstract void close();

    public abstract boolean configureThrottle(int throttleWindowInMs, int throttleLimitOfMessages);

    public abstract long startEndOfDay();
}
