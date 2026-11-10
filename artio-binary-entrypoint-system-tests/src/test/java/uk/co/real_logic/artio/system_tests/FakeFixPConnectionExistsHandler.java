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
package uk.co.real_logic.artio.system_tests;

import io.aeron.logbuffer.ControlledFragmentHandler;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.FixPConnectionExistsHandler;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.SessionReplyStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FakeFixPConnectionExistsHandler implements FixPConnectionExistsHandler
{
    private static final int REQUEST_TIMEOUT_IN_MS = 10_000;

    private long lastSurrogateSessionId;
    private FixPContext lastIdentification;
    private Reply<SessionReplyStatus> lastReply;

    private boolean request = true;

    public void request(final boolean request)
    {
        this.request = request;
    }

    @Override
    public ControlledFragmentHandler.Action onConnectionExists(
        final FixLibrary library,
        final long surrogateSessionId,
        final FixPProtocolType protocol,
        final FixPContext context)
    {
        assertNotNull(library);
        assertEquals(FixPProtocolType.BINARY_ENTRYPOINT, protocol);

        this.lastSurrogateSessionId = surrogateSessionId;
        this.lastIdentification = context;

        if (request)
        {
            lastReply = requestSession(library, surrogateSessionId);
        }

        return ControlledFragmentHandler.Action.CONTINUE;
    }

    static Reply<SessionReplyStatus> requestSession(final FixLibrary library, final long surrogateSessionId)
    {
        return library.requestSession(surrogateSessionId,
            FixLibrary.NO_MESSAGE_REPLAY,
            FixLibrary.NO_MESSAGE_REPLAY,
            REQUEST_TIMEOUT_IN_MS);
    }

    public long lastSurrogateSessionId()
    {
        return lastSurrogateSessionId;
    }

    public FixPContext lastIdentification()
    {
        return lastIdentification;
    }

    public Reply<SessionReplyStatus> lastReply()
    {
        return lastReply;
    }

    public boolean invoked()
    {
        return lastIdentification != null;
    }

    public void reset()
    {
        lastSurrogateSessionId = 0;
        lastReply = null;
        lastIdentification = null;
    }
}
