/*
 * Copyright 2022 Monotonic Ltd.
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
package uk.co.real_logic.artio.session;

import uk.co.real_logic.artio.builder.AbstractRejectEncoder;

public class ResendRequestResponse
{
    private boolean result;

    private int overriddenBeginSequenceNUmber;
    private int refTagId;
    private AbstractRejectEncoder rejectEncoder;

    /**
     * Invoke when you want to apply normal behaviour and respond to the resend request.
     */
    public void resend()
    {
        result = true;
    }

    /**
     * Invoke when you want to respond to the resend request, from an overridden beginSeqNum, gapfilling prior messages
     * @param overriddenBeginSequenceNumber indicates messages before this are gapfilled
     */
    public void resendFrom(final int overriddenBeginSequenceNumber)
    {
        this.overriddenBeginSequenceNUmber = overriddenBeginSequenceNumber;
        this.result = true;
    }

    /**
     * Invoke when you want to reject the resend request.
     *
     * @param refTagId the tag id that has motivated the reject.
     */
    public void reject(final int refTagId)
    {
        this.refTagId = refTagId;

        result = false;
    }

    public void reject(final AbstractRejectEncoder rejectEncoder)
    {
        this.rejectEncoder = rejectEncoder;

        result = false;
    }

    void reset()
    {
        result = false;
        overriddenBeginSequenceNUmber = Session.UNKNOWN;
        refTagId = Session.UNKNOWN;
        rejectEncoder = null;
    }

    AbstractRejectEncoder rejectEncoder()
    {
        return rejectEncoder;
    }

    boolean result()
    {
        return result;
    }

    int overriddenBeginSequenceNumber()
    {
        return overriddenBeginSequenceNUmber;
    }

    int refTagId()
    {
        return refTagId;
    }
}
