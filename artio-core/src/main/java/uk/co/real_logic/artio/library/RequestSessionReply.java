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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.messages.SessionReplyStatus;

/**
 * .
 */
class RequestSessionReply extends LibraryReply<SessionReplyStatus>
{
    private final long sessionId;
    private final int resendFromSequenceNumber;
    private final int resendFromSequenceIndex;

    RequestSessionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTime,
        final long sessionId,
        final int resendFromSequenceNumber,
        final int resendFromSequenceIndex)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.sessionId = sessionId;
        this.resendFromSequenceNumber = resendFromSequenceNumber;
        this.resendFromSequenceIndex = resendFromSequenceIndex;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveRequestSession(
            sessionId, correlationId, resendFromSequenceNumber, resendFromSequenceIndex);

        requiresResend = position < 0;
    }

    @Override
    void onComplete(final SessionReplyStatus result)
    {
        super.onComplete(result);
    }
}
