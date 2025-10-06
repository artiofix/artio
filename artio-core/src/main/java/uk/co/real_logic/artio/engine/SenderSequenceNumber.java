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

import org.agrona.concurrent.status.AtomicCounter;

/**
 * Class to notify replays and gap fills of the latest sent sequence
 * number.
 *
 * Per Session.
 */
public class SenderSequenceNumber implements ReplayerCommand
{
    private final long connectionId;
    private final AtomicCounter bytesInBuffer;
    private final SenderSequenceNumbers senderSequenceNumbers;

    SenderSequenceNumber(
        final long connectionId, final AtomicCounter bytesInBuffer, final SenderSequenceNumbers senderSequenceNumbers)
    {
        this.connectionId = connectionId;
        this.bytesInBuffer = bytesInBuffer;
        this.senderSequenceNumbers = senderSequenceNumbers;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public AtomicCounter bytesInBuffer()
    {
        return bytesInBuffer;
    }

    public void close()
    {
        senderSequenceNumbers.onSenderClosed(this);
    }

    public void execute()
    {
        senderSequenceNumbers.onSenderSequenceNumber(this);
    }

}
