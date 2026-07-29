/*
 * Copyright 2015-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.framer;

import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.builder.AbstractBusinessMessageRejectEncoder;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.util.MessageTypeEncoding;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.nio.ByteBuffer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class FixThrottleRejectBuilder
{
    private static final int BUFFER_CAPACITY = 512;

    private final MutableAsciiBuffer businessRejectBuffer;
    private int offset;
    private int length;

    private final UtcTimestampEncoder timestampEncoder;
    private final EpochNanoClock clock;
    private final AbstractBusinessMessageRejectEncoder businessMessageReject;
    private final byte[] refMsgTypeBuffer = new byte[2];
    private final int businessRejectReason;

    public FixThrottleRejectBuilder(
        final FixDictionary fixDictionary,
        final ErrorHandler errorHandler,
        final long sessionId,
        final long connectionId,
        final UtcTimestampEncoder timestampEncoder,
        final EpochNanoClock clock,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages,
        final int businessRejectReason)
    {
        this.timestampEncoder = timestampEncoder;
        this.clock = clock;
        this.businessRejectBuffer = new MutableAsciiBuffer(ByteBuffer.allocateDirect(BUFFER_CAPACITY));
        this.businessMessageReject = fixDictionary.makeBusinessMessageRejectEncoder();
        this.businessRejectReason = businessRejectReason;

        if (businessMessageReject == null)
        {
            errorHandler.onError(new IllegalStateException(String.format(
                "Unable to create business reject when attempting to provide a throttle based rejection for " +
                "sessId=%d,connId=%d - this is a fatal error for this session / connection",
                sessionId, connectionId)));
        }
        else
        {
            configureThrottle(throttleWindowInMs, throttleLimitOfMessages);
        }
    }

    boolean configureThrottle(final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        if (businessMessageReject != null)
        {
            businessMessageReject.text(String.format("Throttle limit exceeded (%s in %sms)",
                throttleLimitOfMessages,
                throttleWindowInMs));
            return true;
        }
        else
        {
            return false;
        }
    }

    public boolean build(
        final long refMsgType,
        final int refSeqNum,
        final int sequenceNumber,
        final DirectBuffer businessRejectRefIDBuffer,
        final int businessRejectRefIDOffset,
        final int businessRejectRefIDLength,
        final boolean possDup)
    {
        final AbstractBusinessMessageRejectEncoder businessMessageReject = this.businessMessageReject;
        if (businessMessageReject == null)
        {
            // already logged an error for this
            return false;
        }

        final UtcTimestampEncoder timestampEncoder = this.timestampEncoder;
        final byte[] refMsgTypeBuffer = this.refMsgTypeBuffer;
        final MutableAsciiBuffer businessRejectBuffer = this.businessRejectBuffer;

        final long timeInNs = clock.nanoTime();
        final SessionHeaderEncoder header = businessMessageReject.header();
        header
            .sendingTime(timestampEncoder.buffer(), timestampEncoder.encodeFrom(timeInNs, NANOSECONDS))
            .msgSeqNum(sequenceNumber);

        if (possDup)
        {
            header.possDupFlag(true);
        }

        final int refMsgTypeLength = MessageTypeEncoding.unpackMessageType(refMsgType, refMsgTypeBuffer);
        businessMessageReject
            .refMsgType(refMsgTypeBuffer, 0, refMsgTypeLength)
            .refSeqNum(refSeqNum)
            .businessRejectReason(businessRejectReason)
            .businessRejectRefID(businessRejectRefIDBuffer, businessRejectRefIDOffset, businessRejectRefIDLength);

        final long result = businessMessageReject.encode(businessRejectBuffer, 0);
        offset = Encoder.offset(result);
        length = Encoder.length(result);

        return true;
    }

    public SessionHeaderEncoder header()
    {
        return businessMessageReject.header();
    }

    public MutableAsciiBuffer buffer()
    {
        return businessRejectBuffer;
    }

    public int offset()
    {
        return offset;
    }

    public int length()
    {
        return length;
    }

    public long messageType()
    {
        return businessMessageReject.messageType();
    }
}
