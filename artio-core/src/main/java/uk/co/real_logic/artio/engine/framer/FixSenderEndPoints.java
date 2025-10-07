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

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.util.CharFormatter;

import java.util.function.LongToIntFunction;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.DebugLogger.IS_REPLAY_LOG_TAG_ENABLED;

class FixSenderEndPoints implements AutoCloseable
{
    final CharFormatter missReplayComplete = new CharFormatter(
        "SEPs.missReplayComplete, connId=%s, corrId=%s, slow=%s");

    private final Long2ObjectHashMap<FixSenderEndPoint> connectionIdToSenderEndpoint = new Long2ObjectHashMap<>();
    private final LongToIntFunction libraryLookup = this::libraryLookup;

    private int libraryLookup(final long sessionId)
    {
        for (final FixSenderEndPoint senderEndPoint : connectionIdToSenderEndpoint.values())
        {
            if (senderEndPoint.sessionId() == sessionId)
            {
                return senderEndPoint.libraryId();
            }
        }

        return FixEngine.ENGINE_LIBRARY_ID;
    }

    private long timeInMs;

    public void add(final FixSenderEndPoint senderEndPoint)
    {
        connectionIdToSenderEndpoint.put(senderEndPoint.connectionId(), senderEndPoint);
    }

    void removeConnection(final long connectionId)
    {
        final FixSenderEndPoint senderEndPoint = connectionIdToSenderEndpoint.remove(connectionId);
        if (senderEndPoint != null)
        {
            senderEndPoint.close();
        }
    }

    boolean onMessage(
        final int libraryId,
        final long connectionId,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int sequenceNumber,
        final int sequenceIndex,
        final long messageType,
        final int metaDataLength)
    {
        final FixSenderEndPoint endPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (endPoint != null)
        {
            endPoint.onOutboundMessage(
                libraryId,
                buffer,
                offset,
                length,
                sequenceNumber,
                sequenceIndex,
                messageType,
                timeInMs,
                metaDataLength);
            return true;
        }

        return false;
    }

    Action onThrottleReject(
        final int libraryId,
        final long connectionId,
        final long refMsgType,
        final int refSeqNum,
        final int sequenceNumber,
        final int sequenceIndex,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength)
    {
        final FixSenderEndPoint endPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (endPoint != null)
        {
            endPoint.onThrottleReject(
                libraryId, refMsgType, refSeqNum, sequenceNumber, sequenceIndex,
                businessRejectRefIDBuffer, businessRejectRefIDOffset, businessRejectRefIDLength,
                timeInMs);
        }

        return null;
    }

    Action onReplayMessage(
        final long connectionId, final DirectBuffer buffer, final int offset, final int length,
        final int sequenceNumber)
    {
        final FixSenderEndPoint endPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (endPoint != null)
        {
            return endPoint.onReplayMessage(buffer, offset, length, timeInMs, sequenceNumber);
        }
        return CONTINUE;

    }

    Action onReplayComplete(final long connectionId, final long correlationId, final boolean slow)
    {
        final FixSenderEndPoint senderEndPoint = connectionIdToSenderEndpoint.get(connectionId);
        if (senderEndPoint != null)
        {
            return senderEndPoint.onReplayComplete(correlationId);
        }
        else
        {
            if (IS_REPLAY_LOG_TAG_ENABLED)
            {
                DebugLogger.log(LogTag.REPLAY, missReplayComplete.clear().with(connectionId).with(correlationId)
                    .with(slow));
            }
        }
        return CONTINUE;
    }

    public void close()
    {
        connectionIdToSenderEndpoint
            .values()
            .forEach(FixSenderEndPoint::close);
    }

    public boolean isSlowConsumer(final long connectionId)
    {
        final FixSenderEndPoint fixSenderEndPoint = connectionIdToSenderEndpoint.get(connectionId);
        return fixSenderEndPoint != null && fixSenderEndPoint.isSlowConsumer();
    }

    void timeInMs(final long timeInMs)
    {
        this.timeInMs = timeInMs;
    }

    int poll(final long timeInMs)
    {
        int count = 0;
        for (final FixSenderEndPoint senderEndPoint : connectionIdToSenderEndpoint.values())
        {
            if (senderEndPoint.poll(timeInMs))
            {
                count++;
            }
        }

        return count;
    }

    LongToIntFunction libraryLookup()
    {
        return libraryLookup;
    }

    public void onValidResendRequest(final long connection, final long correlationId)
    {
        final FixSenderEndPoint fixSenderEndPoint = connectionIdToSenderEndpoint.get(connection);
        if (fixSenderEndPoint != null)
        {
            fixSenderEndPoint.onValidResendRequest(correlationId);
        }
    }

    public void onStartReplay(
        final long connection, final long correlationId, final boolean slow)
    {
        final FixSenderEndPoint fixSenderEndPoint = connectionIdToSenderEndpoint.get(connection);
        if (fixSenderEndPoint != null)
        {
            fixSenderEndPoint.onStartReplay(correlationId);
        }
    }
}
