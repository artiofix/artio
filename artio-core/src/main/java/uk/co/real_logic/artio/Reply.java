/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio;

import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.FixLibrary;

import java.util.concurrent.locks.LockSupport;

public interface Reply<T>
{
    int DEFAULT_POLL_FRAGMENT_LIMIT = 10;

    /**
     * Performs a blocking wait on a reply. This can be used for waiting for the reply of administrative operations
     * within Artio. This shouldn't be used on a normal duty cycle as it will stop you from performing different
     * operation concurrently, but can be a useful convenience for operations like {@link FixEngine#bind()} which
     * don't normally need to compose with other replies.
     *
     * @param reply the reply to wait for.
     * @param <T> the type of the object returned by the reply.
     * @return the reply
     */
    static <T> Reply<T> await(final Reply<T> reply)
    {
        while (reply.isExecuting())
        {
            LockSupport.parkNanos(1_000_000);
        }
        return reply;
    }

    /**
     * Performs a blocking wait on a reply, whilst polling a <code>FixLibrary</code>. This can be used for waiting for
     * the reply of administrative operations within Artio on a thread that should normally also poll the FixLibrary.
     * This shouldn't be used on a normal duty cycle as it will stop you from performing different operation
     * concurrently, but can be convenient for administrative operations that you want to await.
     *
     * @param reply the reply to wait for.
     * @param library the FixLibrary to poll whilst awaiting the reply.
     * @param idleStrategy the {@link IdleStrategy} to use when polling.
     * @param <T> the type of the object returned by the reply.
     * @return the reply
     */
    static <T> Reply<T> await(
        final Reply<T> reply,
        final FixLibrary library,
        final IdleStrategy idleStrategy)
    {
        while (reply.isExecuting())
        {
            idleStrategy.idle(library.poll(DEFAULT_POLL_FRAGMENT_LIMIT));
        }
        return reply;
    }

    default boolean isExecuting()
    {
        return state() == State.EXECUTING;
    }

    default boolean hasTimedOut()
    {
        return state() == State.TIMED_OUT;
    }

    default boolean hasErrored()
    {
        return state() == State.ERRORED;
    }

    default boolean hasCompleted()
    {
        return state() == State.COMPLETED;
    }

    /**
     * Gets the error iff <code>hasErrored() == true</code> or null otherwise.
     *
     * @return the error iff <code>hasErrored() == true</code> or null otherwise.
     */
    Throwable error();

    /**
     * Gets the result if the operation has completed successfully or null.
     *
     * @return the result if the operation has completed successfully or null.
     */
    T resultIfPresent();

    /**
     * Gets the current state of the Reply.
     *
     * @return the current state of the Reply.
     */
    State state();

    enum State
    {
        /** The operation is currently being executed and its result is unknown. */
        EXECUTING,
        /** The operation has timed out without a result. */
        TIMED_OUT,
        /** The operation has completed with an error. */
        ERRORED,
        /** The operation has completed successfully. */
        COMPLETED
    }
}
