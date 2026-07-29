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

public final class EngineStreamInfo
{
    private final long inboundIndexSubscriptionRegistrationId;
    private final long outboundIndexSubscriptionRegistrationId;
    private final long librarySubscriptionRegistrationId;
    private final int inboundPublicationSessionId;
    private final long inboundPublicationPosition;
    private final int outboundPublicationSessionId;
    private final long outboundPublicationPosition;

    EngineStreamInfo(
        final long inboundIndexSubscriptionRegistrationId,
        final long outboundIndexSubscriptionRegistrationId,
        final long librarySubscriptionRegistrationId,
        final int inboundPublicationSessionId,
        final long inboundPublicationPosition,
        final int outboundPublicationSessionId,
        final long outboundPublicationPosition)
    {
        this.inboundIndexSubscriptionRegistrationId = inboundIndexSubscriptionRegistrationId;
        this.outboundIndexSubscriptionRegistrationId = outboundIndexSubscriptionRegistrationId;
        this.librarySubscriptionRegistrationId = librarySubscriptionRegistrationId;
        this.inboundPublicationSessionId = inboundPublicationSessionId;
        this.inboundPublicationPosition = inboundPublicationPosition;
        this.outboundPublicationSessionId = outboundPublicationSessionId;
        this.outboundPublicationPosition = outboundPublicationPosition;
    }

    public long inboundIndexSubscriptionRegistrationId()
    {
        return inboundIndexSubscriptionRegistrationId;
    }

    public long outboundIndexSubscriptionRegistrationId()
    {
        return outboundIndexSubscriptionRegistrationId;
    }

    public long librarySubscriptionRegistrationId()
    {
        return librarySubscriptionRegistrationId;
    }

    public int inboundPublicationSessionId()
    {
        return inboundPublicationSessionId;
    }

    public long inboundPublicationPosition()
    {
        return inboundPublicationPosition;
    }

    public int outboundPublicationSessionId()
    {
        return outboundPublicationSessionId;
    }

    public long outboundPublicationPosition()
    {
        return outboundPublicationPosition;
    }

    public String toString()
    {
        return "EngineStreamInfo{" +
            "inboundIndexSubscriptionRegistrationId=" + inboundIndexSubscriptionRegistrationId +
            ", outboundIndexSubscriptionRegistrationId=" + outboundIndexSubscriptionRegistrationId +
            ", librarySubscriptionRegistrationId=" + librarySubscriptionRegistrationId +
            ", inboundPublicationSessionId=" + inboundPublicationSessionId +
            ", inboundPublicationPosition=" + inboundPublicationPosition +
            ", outboundPublicationSessionId=" + outboundPublicationSessionId +
            ", outboundPublicationPosition=" + outboundPublicationPosition +
            '}';
    }
}
