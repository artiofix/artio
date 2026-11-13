/*
 * Copyright 2014-2016 Real Logic Limited.
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

import java.io.File;

final class ResetSessionIdsCommand implements AdminCommand, Reply<Void>
{
    private final File backupLocation;

    private volatile State state = State.EXECUTING;

    // thread-safe publication by writes to state after, and reads of state before its read.
    private Exception error;

    ResetSessionIdsCommand(final File backupLocation)
    {
        this.backupLocation = backupLocation;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onResetSessionIds(backupLocation, this);
    }

    void onError(final Exception error)
    {
        this.error = error;
        state = State.ERRORED;
    }

    void success()
    {
        state = State.COMPLETED;
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
}
