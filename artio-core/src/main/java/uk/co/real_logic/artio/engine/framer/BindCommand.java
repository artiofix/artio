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

class BindCommand implements AdminCommand, Reply<Void>
{
    private volatile State state = State.EXECUTING;

    // thread-safe publication by writes to state after, and reads of state before its read.
    private Exception error;

    public void execute(final Framer framer)
    {
        framer.onBind(this);
    }

    void success()
    {
        state = State.COMPLETED;
    }

    void onError(final Exception error)
    {
        this.error = error;
        state = State.ERRORED;
    }

    public Exception error()
    {
        return error;
    }

    public Void resultIfPresent()
    {
        return null;
    }

    public State state()
    {
        return state;
    }


    public String toString()
    {
        return "BindCommand{" +
            ", state=" + state +
            ", error=" + error +
            '}';
    }
}
