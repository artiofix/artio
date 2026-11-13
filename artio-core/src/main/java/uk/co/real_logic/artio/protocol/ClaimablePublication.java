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
package uk.co.real_logic.artio.protocol;

import io.aeron.ExclusivePublication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;

import java.util.Objects;

import static io.aeron.Publication.CLOSED;
import static io.aeron.Publication.MAX_POSITION_EXCEEDED;

/**
 * A publication designed for deterministic claiming.
 */
public class ClaimablePublication implements AutoCloseable
{
    protected static final int HEADER_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH;

    private final long maxClaimAttempts;
    private final AtomicCounter fails;
    protected final MessageHeaderEncoder header = new MessageHeaderEncoder();
    protected final BufferClaim bufferClaim = new BufferClaim();
    protected ExclusivePublication dataPublication;
    private long initialPosition;

    protected final IdleStrategy idleStrategy;

    protected ClaimablePublication(
        final int maxClaimAttempts,
        final IdleStrategy idleStrategy,
        final AtomicCounter fails,
        final ExclusivePublication dataPublication)
    {
        this.maxClaimAttempts = maxClaimAttempts;
        this.idleStrategy = idleStrategy;
        this.fails = fails;
        dataPublication(dataPublication);
    }

    protected long claim(final int framedLength)
    {
        return claim(framedLength, bufferClaim);
    }

    public long claim(final int framedLength, final BufferClaim bufferClaim)
    {
        long position;
        long i = 0;
        do
        {
            position = dataPublication.tryClaim(framedLength, bufferClaim);

            if (position > 0L)
            {
                return position;
            }
            else
            {
                idleStrategy.idle();
            }

            if (position == CLOSED || position == MAX_POSITION_EXCEEDED)
            {
                throw new NotConnectedException(position);
            }

            fails.increment();
            i++;
        }
        while (i <= maxClaimAttempts);

        idleStrategy.reset();

        return position;
    }

    public long offer(final DirectBuffer buffer, final int offset, final int length)
    {
        return dataPublication.offer(buffer, offset, length);
    }

    public ExclusivePublication dataPublication()
    {
        return dataPublication;
    }

    public void dataPublication(final ExclusivePublication dataPublication)
    {
        Objects.requireNonNull(dataPublication, "dataPublication");
        CloseHelper.close(this.dataPublication);
        this.dataPublication = dataPublication;
        initialPosition = dataPublication.position();
    }

    public long initialPosition()
    {
        return initialPosition;
    }

    @Override
    public void close()
    {
        dataPublication.close();
    }
}
