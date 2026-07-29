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
package uk.co.real_logic.artio.system_tests;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.status.CountersReader;

import static io.aeron.AeronCounters.DRIVER_SUBSCRIBER_POSITION_TYPE_ID;
import static io.aeron.driver.status.StreamCounter.SESSION_ID_OFFSET;

final class SubPosMatcher
{
    private final CountersReader countersReader;
    private final long registrationId;
    private final int sessionId;
    private final long expectedPosition;
    private int counterId = -1;

    SubPosMatcher(
        final CountersReader countersReader,
        final long registrationId,
        final int sessionId,
        final long expectedPosition)
    {
        this.countersReader = countersReader;
        this.registrationId = registrationId;
        this.sessionId = sessionId;
        this.expectedPosition = expectedPosition;
    }

    public void tryMatch(final int counterId, final int typeId, final DirectBuffer keyBuffer)
    {
        if (typeId == DRIVER_SUBSCRIBER_POSITION_TYPE_ID &&
            countersReader.getCounterRegistrationId(counterId) == registrationId &&
            keyBuffer.getInt(SESSION_ID_OFFSET) == sessionId)
        {
            if (hasCounterId())
            {
                throw new IllegalStateException();
            }
            this.counterId = counterId;
        }
    }

    public boolean hasCounterId()
    {
        return counterId != -1;
    }

    public boolean isCaughtUp()
    {
        final long counterValue = countersReader.getCounterValue(counterId);
        return counterValue >= expectedPosition;
    }

    public String toString()
    {
        return "SubPosMatcher{" +
            "registrationId=" + registrationId +
            ", sessionId=" + sessionId +
            ", expectedPosition=" + expectedPosition +
            ", counterId=" + counterId +
            '}';
    }
}
