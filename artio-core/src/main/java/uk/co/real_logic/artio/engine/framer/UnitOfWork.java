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

import uk.co.real_logic.artio.Pressure;

import java.util.Arrays;
import java.util.List;

class UnitOfWork implements Continuation
{
    List<Continuation> workList;
    private int index = 0;

    UnitOfWork(final Continuation... work)
    {
        work(work);
    }

    void work(final Continuation... work)
    {
        workList = Arrays.asList(work);
    }

    UnitOfWork(final List<Continuation> workList)
    {
        this.workList = workList;
    }

    @Override
    public long attempt()
    {
        for (final int size = workList.size(); index < size; index++)
        {
            final Continuation continuation = workList.get(index);
            final long position = continuation.attempt();

            if (Pressure.isBackPressured(position))
            {
                return position;
            }
        }

        return COMPLETE;
    }

    void add(final Continuation continuation)
    {
        workList.add(continuation);
    }
}
