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

import uk.co.real_logic.artio.Reply;

final class EngineStreamInfoRequestCommand implements AdminCommand, Reply<EngineStreamInfo>
{
    private volatile State state = State.EXECUTING;

    private EngineStreamInfo engineStreamInfo;

    public Throwable error()
    {
        return null;
    }

    public EngineStreamInfo resultIfPresent()
    {
        return engineStreamInfo;
    }

    public State state()
    {
        return state;
    }

    public void execute(final Framer framer)
    {
        framer.onEngineStreamInfoRequest(this);
    }

    public void complete(final EngineStreamInfo engineStreamInfo)
    {
        this.engineStreamInfo = engineStreamInfo;
        state = State.COMPLETED;
    }
}
