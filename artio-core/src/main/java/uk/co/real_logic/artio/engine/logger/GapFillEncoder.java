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
package uk.co.real_logic.artio.engine.logger;

import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.builder.AbstractSequenceResetEncoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.engine.HeaderSetup;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.concurrent.TimeUnit;

class GapFillEncoder
{
    private static final int ENCODE_BUFFER_SIZE = 1024;

    private final AbstractSequenceResetEncoder sequenceResetEncoder;
    private final UtcTimestampEncoder timestampEncoder;
    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[ENCODE_BUFFER_SIZE]);
    private final EpochNanoClock nanoClock;

    GapFillEncoder(
        final AbstractSequenceResetEncoder sequenceResetEncoder,
        final UtcTimestampEncoder timestampEncoder,
        final EpochNanoClock nanoClock)
    {
        this.sequenceResetEncoder = sequenceResetEncoder;
        this.timestampEncoder = timestampEncoder;
        this.nanoClock = nanoClock;
        this.sequenceResetEncoder.header().possDupFlag(true);
        this.sequenceResetEncoder.gapFillFlag(true);
    }

    long encode(final int msgSeqNum, final int newSeqNo)
    {
        final SessionHeaderEncoder respHeader = sequenceResetEncoder.header();
        respHeader.sendingTime(timestampEncoder.buffer(),
            timestampEncoder.encodeFrom(nanoClock.nanoTime(), TimeUnit.NANOSECONDS));
        respHeader.msgSeqNum(msgSeqNum);
        sequenceResetEncoder.newSeqNo(newSeqNo);

        return sequenceResetEncoder.encode(buffer, 0);
    }

    void setupMessage(final SessionHeaderDecoder requestHeader)
    {
        HeaderSetup.setup(requestHeader, sequenceResetEncoder.header());
    }

    MutableAsciiBuffer buffer()
    {
        return buffer;
    }
}
