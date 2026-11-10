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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.Reply;

class UnbindCommand implements AdminCommand, Reply<Void>
{
    private volatile State state = State.EXECUTING;

    private UnbindCommand concurrentUnbind;

    // thread-safe publication by writes to state after, and reads of state before its read.
    private Exception error;
    private final boolean disconnect;

    UnbindCommand(final boolean disconnect)
    {
        this.disconnect = disconnect;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onUnbind(this);
    }

    void success()
    {
        state = State.COMPLETED;
        if (concurrentUnbind != null)
        {
            concurrentUnbind.success();
        }
    }

    void onError(final Exception error)
    {
        this.error = error;
        state = State.ERRORED;

        if (concurrentUnbind != null)
        {
            concurrentUnbind.onError(error);
        }
    }

    @Override
    public Exception error()
    {
        return error;
    }

    @Override
    public Void resultIfPresent()
    {
        return null;
    }

    @Override
    public State state()
    {
        return state;
    }

    boolean disconnect()
    {
        return disconnect;
    }

    @Override
    public String toString()
    {
        return "UnbindCommand{" +
            "disconnect=" + disconnect +
            ", state=" + state +
            ", error=" + error +
            '}';
    }

    public void addConcurrentUnbind(final UnbindCommand unbindCommand)
    {
        if (concurrentUnbind == null)
        {
            concurrentUnbind = unbindCommand;
        }
        else
        {
            concurrentUnbind.addConcurrentUnbind(unbindCommand);
        }
    }
}
