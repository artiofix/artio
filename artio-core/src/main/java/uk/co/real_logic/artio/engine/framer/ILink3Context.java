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

import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.fixp.FixPFirstMessageResponse;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.fixp.InternalFixPContext;
import uk.co.real_logic.artio.messages.FixPProtocolType;

public final class ILink3Context implements InternalFixPContext
{
    private final int offset;

    // holds the last uuid whilst we're connecting but before we're sure that we've connected
    private long connectLastUuid;
    private long connectUuid;

    private final ILink3Key key;
    private final EpochNanoClock clock;
    private long uuid;
    private long lastUuid;
    private boolean newlyAllocated;
    private boolean primaryConnected;
    private boolean backupConnected;

    public ILink3Context(
        final ILink3Key key,
        final EpochNanoClock clock,
        final long uuid,
        final long lastUuid,
        final long connectUuid,
        final long connectLastUuid,
        final boolean newlyAllocated,
        final int offset)
    {
        this.key = key;
        this.clock = clock;
        this.uuid = uuid;
        this.lastUuid = lastUuid;
        this.connectLastUuid = connectLastUuid;
        this.connectUuid = connectUuid;
        this.newlyAllocated = newlyAllocated;
        this.offset = offset;
    }

    public long uuid()
    {
        return uuid;
    }

    void uuid(final long uuid)
    {
        this.uuid = uuid;
    }

    long lastUuid()
    {
        return lastUuid;
    }

    void lastUuid(final long lastUuid)
    {
        this.lastUuid = lastUuid;
    }

    boolean newlyAllocated()
    {
        return newlyAllocated;
    }

    void newlyAllocated(final boolean newlyAllocated)
    {
        this.newlyAllocated = newlyAllocated;
    }

    boolean primaryConnected()
    {
        return primaryConnected;
    }

    public void primaryConnected(final boolean connected)
    {
        this.primaryConnected = connected;
    }

    boolean backupConnected()
    {
        return backupConnected;
    }

    public void backupConnected(final boolean connected)
    {
        this.backupConnected = connected;
    }

    public int offset()
    {
        return offset;
    }

    @Override
    public boolean onInitiatorNegotiateResponse()
    {
        uuid = connectUuid;
        lastUuid = connectLastUuid;

        return lastUuid == 0;
    }

    @Override
    public void onInitiatorDisconnect()
    {
        if (backupConnected)
        {
            backupConnected(false);
        }
        else
        {
            primaryConnected(false);
        }
    }

    public void connectLastUuid(final long uuid)
    {
        this.connectLastUuid = uuid;
    }

    public long connectLastUuid()
    {
        return connectLastUuid;
    }

    public void connectUuid(final long newUuid)
    {
        connectUuid = newUuid;
    }

    public long connectUuid()
    {
        return connectUuid;
    }

    @Override
    public FixPFirstMessageResponse checkAccept(final FixPContext context, final boolean ignoreFromNegotiate)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareVersion(final FixPContext oldContext)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean fromNegotiate()
    {
        return newlyAllocated;
    }

    @Override
    public void initiatorReconnect(final boolean reestablishConnection)
    {
        final long connectLastUuid = uuid();
        connectLastUuid(connectLastUuid);

        // connectLastUuid == 0 implies that we're attempting to re-establish a connection that failed on its
        // last attempt, also its first of the week, so we need to generate a new UUID
        final boolean newlyAllocated = !reestablishConnection || connectLastUuid == 0;

        newlyAllocated(newlyAllocated);
        if (newlyAllocated)
        {
            final long newUuid = clock.nanoTime();
            connectUuid(newUuid);
        }
        else
        {
            // We may have an invalid connect uuid from a failed connection at this point.
            connectUuid(connectLastUuid);
        }
    }

    @Override
    public FixPProtocolType protocolType()
    {
        return FixPProtocolType.ILINK_3;
    }

    @Override
    public void onEndSequence()
    {
        // Never happens in iLink3 as it doesn't support the FinishedSending and FinishedReceiving abstractions.
    }

    @Override
    public ILink3Key key()
    {
        return key;
    }

    @Override
    public String toString()
    {
        return "ILink3Context{connectLastUuid=" + connectLastUuid +
            ", connectUuid=" + connectUuid +
            ", uuid=" + uuid +
            ", lastUuid=" + lastUuid +
            ", newlyAllocated=" + newlyAllocated +
            ", primaryConnected=" + primaryConnected +
            ", backupConnected=" + backupConnected +
            '}';
    }

    @Override
    public long surrogateSessionId()
    {
        return connectUuid;
    }

    @Override
    public boolean hasUnsentMessagesAtNegotiate()
    {
        return false;
    }

    @Override
    public void validate(final Enum<?> rejectCode)
    {
    }
}
