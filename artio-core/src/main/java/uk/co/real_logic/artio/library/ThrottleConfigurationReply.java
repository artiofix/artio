/*
 * Copyright 2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.messages.ThrottleConfigurationStatus;

/**
 * .
 */
class ThrottleConfigurationReply extends LibraryReply<ThrottleConfigurationStatus>
{
    private final long sessionId;
    private final int throttleWindowInMs;
    private final int throttleLimitOfMessages;

    ThrottleConfigurationReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTime,
        final long sessionId,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.sessionId = sessionId;
        this.throttleWindowInMs = throttleWindowInMs;
        this.throttleLimitOfMessages = throttleLimitOfMessages;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveThrottleConfiguration(
            correlationId, sessionId, throttleWindowInMs, throttleLimitOfMessages);

        requiresResend = position < 0;
    }
}
