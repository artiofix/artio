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
package uk.co.real_logic.artio.library;

import uk.co.real_logic.artio.FixGatewayException;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.messages.GatewayError;

/**
 * Represents a reply from an asynchronous method. Methods can complete successfully, in error
 * or they can timeout.
 *
 * This class isn't threadsafe and should be used on the same thread as the FixLibrary instance.
 *
 * @param <T> the return type of the method in question.
 */
abstract class LibraryReply<T> implements Reply<T>
{
    final LibraryPoller libraryPoller;

    final long latestReplyArrivalTimeInMs;

    boolean requiresResend;
    long correlationId;

    private Throwable error;
    private T result;
    private State state = State.EXECUTING;

    LibraryReply(final LibraryPoller libraryPoller, final long latestReplyArrivalTimeInMs)
    {
        this.libraryPoller = libraryPoller;
        this.latestReplyArrivalTimeInMs = latestReplyArrivalTimeInMs;

        if (libraryPoller.isConnected())
        {
            register();
        }
        else
        {
            onError(new FixGatewayException("Not connected to the Gateway"));
        }
    }

    protected abstract void sendMessage();

    protected void register()
    {
        correlationId = libraryPoller.register(this);
    }

    @Override
    public Throwable error()
    {
        return error;
    }

    @Override
    public T resultIfPresent()
    {
        return result;
    }

    @Override
    public State state()
    {
        return state;
    }

    void onComplete(final T result)
    {
        this.result = result;
        state = State.COMPLETED;
    }

    void onError(final Throwable error)
    {
        this.error = error;
        state = State.ERRORED;
    }

    void onError(final GatewayError errorType, final String errorMessage)
    {
    }

    protected boolean onTimeout()
    {
        state = State.TIMED_OUT;

        return true;
    }

    /**
     * Poll the reply's duty cycle.
     *
     * @param timeInMs current time in milliseconds
     *
     * @return true if this reply should be removed from the lookup map.
     */
    boolean poll(final long timeInMs)
    {
        if (!isExecuting())
        {
            return true;
        }

        if (requiresResend)
        {
            sendMessage();
        }

        if (timeInMs >= latestReplyArrivalTimeInMs)
        {
            return onTimeout();
        }

        return false;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "{" +
            "latestReplyArrivalTimeInMs=" + latestReplyArrivalTimeInMs +
            ", error=" + error +
            ", result=" + result +
            ", state=" + state +
            '}';
    }
}
