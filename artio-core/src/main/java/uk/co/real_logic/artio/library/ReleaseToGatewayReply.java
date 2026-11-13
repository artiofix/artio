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
import uk.co.real_logic.artio.session.InternalSession;

/**
 * .
 */
class ReleaseToGatewayReply extends LibraryReply<SessionReplyStatus>
{
    private final InternalSession session;

    ReleaseToGatewayReply(
        final LibraryPoller libraryPoller, final long latestReplyArrivalTime, final InternalSession session)
    {
        super(libraryPoller, latestReplyArrivalTime);
        this.session = session;
        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveReleaseSession(session, correlationId);

        requiresResend = position < 0;
    }

    @Override
    void onComplete(final SessionReplyStatus result)
    {
        if (result == SessionReplyStatus.OK)
        {
            libraryPoller.disableSession(session);
        }

        super.onComplete(result);
    }
}
