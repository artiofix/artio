/*
 * Copyright 2020 Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.engine.logger;

import uk.co.real_logic.artio.util.AsciiBuffer;

public class EnqueuedReplay
{
    private final long sessionId;
    private final long connectionId;
    private final long correlationId;
    private final long beginSeqNo;
    private final long endSeqNo;
    private final int sequenceIndex;
    private final long overriddenBeginSeqNo;
    private final AsciiBuffer asciiBuffer;

    public EnqueuedReplay(
        final long sessionId,
        final long connectionId,
        final long correlationId, final long beginSeqNo,
        final long endSeqNo,
        final int sequenceIndex,
        final long overriddenBeginSeqNo,
        final AsciiBuffer asciiBuffer)
    {

        this.sessionId = sessionId;
        this.connectionId = connectionId;
        this.correlationId = correlationId;
        this.beginSeqNo = beginSeqNo;
        this.endSeqNo = endSeqNo;
        this.sequenceIndex = sequenceIndex;
        this.overriddenBeginSeqNo = overriddenBeginSeqNo;
        this.asciiBuffer = asciiBuffer;
    }

    public long sessionId()
    {
        return sessionId;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public long beginSeqNo()
    {
        return beginSeqNo;
    }

    public long endSeqNo()
    {
        return endSeqNo;
    }

    public int sequenceIndex()
    {
        return sequenceIndex;
    }

    public long correlationId()
    {
        return correlationId;
    }

    public long overriddenBeginSeqNo()
    {
        return overriddenBeginSeqNo;
    }

    public String toString()
    {
        return "EnqueuedReplay{" +
            "sessionId=" + sessionId +
            ", connectionId=" + connectionId +
            ", correlationId=" + correlationId +
            ", beginSeqNo=" + beginSeqNo +
            ", endSeqNo=" + endSeqNo +
            ", sequenceIndex=" + sequenceIndex +
            ", overriddenBeginSeqNo=" + overriddenBeginSeqNo +
            ", asciiBuffer=" + asciiBuffer.getAscii(0, asciiBuffer.capacity()) +
            '}';
    }

    public AsciiBuffer asciiBuffer()
    {
        return asciiBuffer;
    }
}
