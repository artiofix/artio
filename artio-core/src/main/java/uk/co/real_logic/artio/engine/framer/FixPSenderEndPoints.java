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

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.collections.ArrayUtil;
import org.agrona.collections.Long2ObjectHashMap;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class FixPSenderEndPoints implements AutoCloseable
{
    private final Long2ObjectHashMap<FixPSenderEndPoint> connectionIdToSenderEndpoint = new Long2ObjectHashMap<>();
    private FixPSenderEndPoint[] backPressuredEndpoints = new FixPSenderEndPoint[0];

    public Action onMessage(
        final long connectionId, final DirectBuffer buffer, final int offset, final boolean retransmit)
    {
        final FixPSenderEndPoint senderEndPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (senderEndPoint != null)
        {
            return senderEndPoint.onMessage(buffer, offset, retransmit);
        }
        return CONTINUE;
    }

    public int reattempt()
    {
        FixPSenderEndPoint[] backpressuredEndpoints = this.backPressuredEndpoints;
        int size = backpressuredEndpoints.length;
        for (int i = 0; i < size; i++)
        {
            final FixPSenderEndPoint endpoint = backpressuredEndpoints[i];
            if (endpoint.reattempt())
            {
                backpressuredEndpoints = ArrayUtil.remove(backpressuredEndpoints, i);
                size--;
            }
        }
        this.backPressuredEndpoints = backpressuredEndpoints;
        return size;
    }

    public void add(final FixPSenderEndPoint senderEndPoint)
    {
        connectionIdToSenderEndpoint.put(senderEndPoint.connectionId(), senderEndPoint);
    }

    void removeConnection(final long connectionId)
    {
        final FixPSenderEndPoint endPoint = connectionIdToSenderEndpoint.remove(connectionId);
        if (endPoint != null)
        {
            endPoint.close();
            this.backPressuredEndpoints = ArrayUtil.remove(backPressuredEndpoints, endPoint);
        }
    }

    public Action onReplayComplete(final long connectionId, final long correlationId, final boolean slow)
    {
        final FixPSenderEndPoint senderEndPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (senderEndPoint != null)
        {
            return senderEndPoint.onReplayComplete(correlationId);
        }
        return CONTINUE;
    }

    public String toString()
    {
        return "ILink3SenderEndPoints{" +
            "connectionIdToSenderEndpoint=" + connectionIdToSenderEndpoint +
            '}';
    }

    void backPressured(final FixPSenderEndPoint endPoint)
    {
        backPressuredEndpoints = ArrayUtil.add(backPressuredEndpoints, endPoint);
    }

    public void onValidResendRequest(final long connection, final long correlationId)
    {
        final FixPSenderEndPoint fixSenderEndPoint = connectionIdToSenderEndpoint.get(connection);
        if (fixSenderEndPoint != null)
        {
            fixSenderEndPoint.onValidResendRequest(correlationId);
        }
    }

    public void close()
    {
        connectionIdToSenderEndpoint
            .values()
            .forEach(FixPSenderEndPoint::close);
    }
}
