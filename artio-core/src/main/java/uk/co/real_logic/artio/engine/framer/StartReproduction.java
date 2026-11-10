/*
 * Copyright 2022 Monotonic Ltd.
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

class StartReproduction implements AdminCommand, Reply<Void>
{
    private volatile State state = State.EXECUTING;

    private Throwable error;

    @Override
    public void execute(final Framer framer)
    {
        framer.onStartReproduction(this);
    }

    @Override
    public Throwable error()
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

    public void onComplete()
    {
        state = State.COMPLETED;
    }

    public void onError(final Throwable e)
    {
        this.error = e;
        state = State.ERRORED;
    }
}
