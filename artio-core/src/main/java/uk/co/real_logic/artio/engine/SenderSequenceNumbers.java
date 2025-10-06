/*
 * Copyright 2014-2018 Real Logic Limited.
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
package uk.co.real_logic.artio.engine;

import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.status.AtomicCounter;

/**
 * For publishing the last sent sequence number to the replay system.
 */
public class SenderSequenceNumbers
{
    public static final int UNKNOWN_SESSION = -1;

    // Written on Framer, Read on Indexer
    private final ReplayerCommandQueue queue;

    // Indexer State
    private final Long2ObjectHashMap<SenderSequenceNumber> connectionIdToSequencePosition
        = new Long2ObjectHashMap<>();
    private final LongHashSet oldConnectionIds = new LongHashSet();

    public SenderSequenceNumbers(final ReplayerCommandQueue queue)
    {
        this.queue = queue;
    }

    // Called on Framer Thread
    public SenderSequenceNumber onNewSender(final long connectionId, final AtomicCounter bytesInBuffer)
    {
        final SenderSequenceNumber position = new SenderSequenceNumber(
            connectionId, bytesInBuffer, this);
        enqueue(position);
        return position;
    }

    // Called on Framer Thread
    void onSenderClosed(final SenderSequenceNumber senderSequenceNumber)
    {
        enqueue(senderSequenceNumber);
    }

    // We receive the object to either add or remove it.
    private void enqueue(final SenderSequenceNumber senderSequenceNumber)
    {
        queue.enqueue(senderSequenceNumber);
    }

    // Called on Indexer Thread
    public AtomicCounter bytesInBufferCounter(final long connectionId)
    {
        final SenderSequenceNumber senderSequenceNumber = connectionIdToSequencePosition.get(connectionId);
        return senderSequenceNumber == null ? null : senderSequenceNumber.bytesInBuffer();
    }

    // Called on Indexer Thread
    public boolean hasDisconnected(final long connectionId)
    {
        return oldConnectionIds.contains(connectionId);
    }

    // Called on Indexer Thread
    void onSenderSequenceNumber(final SenderSequenceNumber senderSequenceNumber)
    {
        final long connectionId = senderSequenceNumber.connectionId();
        if (connectionIdToSequencePosition.remove(connectionId) == null)
        {
            connectionIdToSequencePosition.put(connectionId, senderSequenceNumber);
        }
        else
        {
            oldConnectionIds.add(connectionId);
        }
    }
}
