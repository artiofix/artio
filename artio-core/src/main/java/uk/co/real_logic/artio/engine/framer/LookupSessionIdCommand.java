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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.Reply;

class LookupSessionIdCommand implements AdminCommand, Reply<Long>
{
    private volatile State state = State.EXECUTING;

    private Long sessionId;
    private Exception error;

    final String localCompId;
    final String remoteCompId;
    final String localSubId;
    final String remoteSubId;
    final String localLocationId;
    final String remoteLocationId;

    LookupSessionIdCommand(
        final String localCompId,
        final String remoteCompId,
        final String localSubId,
        final String remoteSubId,
        final String localLocationId,
        final String remoteLocationId)
    {
        this.localCompId = localCompId;
        this.remoteCompId = remoteCompId;
        this.localSubId = localSubId;
        this.remoteSubId = remoteSubId;
        this.localLocationId = localLocationId;
        this.remoteLocationId = remoteLocationId;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onLookupSessionId(this);
    }

    @Override
    public Exception error()
    {
        return error;
    }

    @Override
    public Long resultIfPresent()
    {
        return sessionId;
    }

    @Override
    public State state()
    {
        return state;
    }

    void complete(final long sessionId)
    {
        this.sessionId = sessionId;
        state = State.COMPLETED;
    }

    public void error(final Exception error)
    {
        this.error = error;
        state = State.ERRORED;
    }
}
