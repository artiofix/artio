/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.session.SessionWriter;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static uk.co.real_logic.artio.dictionary.SessionConstants.LOGON_MESSAGE_TYPE_STR;

class FollowerSessionReply extends LibraryReply<SessionWriter>
{
    private static final int INITIAL_BUFFER_SIZE = 4096;

    private final MutableAsciiBuffer buffer = new MutableAsciiBuffer(new byte[INITIAL_BUFFER_SIZE]);
    private final int offset;
    private final int length;

    FollowerSessionReply(
        final LibraryPoller libraryPoller,
        final long latestReplyArrivalTimeInMs,
        final SessionHeaderEncoder headerEncoder)
    {
        super(libraryPoller, latestReplyArrivalTimeInMs);
        headerEncoder
            .msgType(LOGON_MESSAGE_TYPE_STR)
            .sendingTime(new byte[1]);
        final long result = headerEncoder.startMessage(buffer, 0);
        length = Encoder.length(result);
        offset = Encoder.offset(result);

        if (libraryPoller.isConnected())
        {
            sendMessage();
        }
    }

    @Override
    protected void sendMessage()
    {
        final long position = libraryPoller.saveFollowerSessionRequest(
            correlationId, FixPProtocolType.NULL_VAL, buffer, offset, length);

        requiresResend = position < 0;
    }
}
