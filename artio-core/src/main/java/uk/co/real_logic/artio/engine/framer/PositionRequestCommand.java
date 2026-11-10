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

import org.agrona.concurrent.status.ReadablePosition;
import uk.co.real_logic.artio.Reply;

public class PositionRequestCommand implements AdminCommand, Reply<ReadablePosition>
{
    private volatile State state = State.EXECUTING;
    private final int libraryId;

    private Exception error;
    private ReadablePosition position;

    public PositionRequestCommand(final int libraryId)
    {
        this.libraryId = libraryId;
    }

    @Override
    public Exception error()
    {
        return error;
    }

    @Override
    public ReadablePosition resultIfPresent()
    {
        return position;
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onPositionRequest(this);
    }

    public int libraryId()
    {
        return libraryId;
    }

    void error(final Exception error)
    {
        this.error = error;
        state = State.ERRORED;
    }

    public void position(final ReadablePosition readablePosition)
    {
        position = readablePosition;
        state = State.COMPLETED;
    }
}
