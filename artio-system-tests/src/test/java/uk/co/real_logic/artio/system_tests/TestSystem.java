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
package uk.co.real_logic.artio.system_tests;

import io.aeron.exceptions.TimeoutException;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.status.ReadablePosition;
import org.hamcrest.Matcher;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Timing;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.LockStepFramerEngineScheduler;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.ilink.ILink3Connection;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.session.Session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.co.real_logic.artio.Reply.State.COMPLETED;
import static uk.co.real_logic.artio.Timing.DEFAULT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.LIBRARY_LIMIT;

public class TestSystem
{
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static final long LONG_AWAIT_TIMEOUT_IN_MS = 600_000_000;

    private final List<FixLibrary> libraries;
    private final List<Runnable> operations;
    private final LockStepFramerEngineScheduler scheduler;

    private long awaitTimeoutInMs = SystemTestUtil.AWAIT_TIMEOUT_IN_MS;

    public TestSystem(final LockStepFramerEngineScheduler scheduler, final FixLibrary... libraries)
    {
        this.scheduler = scheduler;
        this.libraries = new ArrayList<>();
        this.operations = new ArrayList<>();
        Collections.addAll(this.libraries, libraries);
    }

    public TestSystem(final FixLibrary... libraries)
    {
        this(null, libraries);
    }

    public TestSystem awaitTimeoutInMs(final long awaitTimeoutInMs)
    {
        this.awaitTimeoutInMs = awaitTimeoutInMs;
        return this;
    }

    public int poll()
    {
        int result = 0;
        if (scheduler != null)
        {
            result = scheduler.invokeFramer();
        }
        for (final FixLibrary library : libraries)
        {
            result += library.poll(LIBRARY_LIMIT);
        }
        operations.forEach(Runnable::run);
        return result;
    }

    public void addOperation(final Runnable operation)
    {
        operations.add(operation);
    }

    public void removeOperation(final Runnable operation)
    {
        operations.remove(operation);
    }

    public void close(final FixLibrary library)
    {
        CloseHelper.close(library);
        remove(library);
    }

    public void remove(final FixLibrary library)
    {
        libraries.remove(library);
    }

    public FixLibrary add(final FixLibrary library)
    {
        libraries.add(library);
        return library;
    }

    public FixLibrary connect(final LibraryConfiguration configuration)
    {
        final FixLibrary library = FixLibrary.connect(configuration);
        try
        {
            add(library);
            awaitConnected(library);

            return library;
        }
        catch (final Exception e)
        {
            library.close();
            LangUtil.rethrowUnchecked(e);
            return library;
        }
    }

    public void awaitConnected(final FixLibrary library)
    {
        assertThat(libraries, hasItem(library));
        assertEventuallyTrue(
            () -> "Unable to connect to engine",
            () ->
            {
                poll();

                return library.isConnected();
            },
            DEFAULT_TIMEOUT_IN_MS,
            () -> close(library));
    }

    public void awaitCompletedReplies(final Reply<?>... replies)
    {
        for (final Reply<?> reply : replies)
        {
            assertNotNull(reply);
            awaitReply(reply);
            assertEquals(COMPLETED, reply.state(), reply.toString());
        }
    }

    public <T> Reply<T> awaitReply(final Reply<T> reply)
    {
        assertNotNull(reply);
        assertEventuallyTrue(
            () -> "No reply from: " + reply,
            () ->
            {
                poll();

                return !reply.isExecuting();
            },
            DEFAULT_TIMEOUT_IN_MS,
            Exceptions::printStackTracesForAllThreads);

        return reply;
    }

    public void awaitErroredReply(final Reply<?> reply, final Matcher<String> messageMatcher)
    {
        awaitReply(reply);
        assertEquals(Reply.State.ERRORED, reply.state());
        assertThat(reply.error().getMessage(), messageMatcher);
    }

    public <T> Reply<T> awaitCompletedReply(final Reply<T> reply)
    {
        awaitReply(reply);

        if (reply.error() != null)
        {
            reply.error().printStackTrace();
        }

        if (reply.hasTimedOut())
        {
            Exceptions.printStackTracesForAllThreads();
        }

        assertEquals(COMPLETED, reply.state(), reply.toString());
        return reply;
    }

    public ReadablePosition libraryPosition(final FixEngine engine, final FixLibrary library)
    {
        final int libraryId = library.libraryId();
        return awaitCompletedReply(
            engine.libraryIndexedPosition(libraryId)).resultIfPresent();
    }

    public FixMessage awaitMessageOf(final FakeOtfAcceptor otfAcceptor, final String messageType)
    {
        return awaitMessageOf(otfAcceptor, messageType, msg -> true);
    }

    public FixMessage awaitMessageOf(
        final FakeOtfAcceptor otfAcceptor, final String messageType, final Predicate<FixMessage> predicate)
    {
        return Timing.withTimeout(() -> "Never received " + messageType + " only: " + otfAcceptor.messages(), () ->
        {
            poll();

            return otfAcceptor.receivedMessage(messageType).filter(predicate).findFirst();
        },
        Timing.DEFAULT_TIMEOUT_IN_MS);
    }

    public List<FixMessage> awaitMessageCount(
        final FakeOtfAcceptor otfAcceptor, final int count)
    {
        Timing.assertEventuallyTrue("Never received " + count + " messages: " + otfAcceptor.messages(), () ->
        {
            poll();

            return otfAcceptor.messages().size() >= count;
        },
            Timing.DEFAULT_TIMEOUT_IN_MS);

        return otfAcceptor.messages();
    }

    public void awaitReceivedSequenceNumber(final Session session, final int sequenceNumber)
    {
        Timing.assertEventuallyTrue(session + " Never get to " + sequenceNumber, () ->
        {
            poll();

            return session.lastReceivedMsgSeqNum() == sequenceNumber;
        });
    }

    public void send(final Session session, final Encoder encoder)
    {
        awaitSend("Unable to send " + encoder.getClass().getSimpleName(), () -> session.trySend(encoder));
    }

    public long awaitSend(final LongSupplier operation)
    {
        return awaitSend("Failed to send", operation);
    }

    public long awaitSend(final String message, final LongSupplier operation)
    {
        final long[] position = new long[1];
        await(message, () -> (position[0] = operation.getAsLong()) > 0);
        return position[0];
    }

    @SuppressWarnings("unchecked")
    public <T> T awaitNotNull(final String message, final Supplier<T> operation)
    {
        final Object[] value = new Object[1];
        await(message, () -> (value[0] = operation.get()) != null);
        return (T)value[0];
    }

    public void await(final String message, final BooleanSupplier predicate)
    {
        assertEventuallyTrue(
            message,
            () ->
            {
                poll();
                return predicate.getAsBoolean();
            });
    }

    public void awaitRequestDisconnect(final Session session)
    {
        await("Failed to disconnect: " + session, () -> session.requestDisconnect() > 0);
    }

    public void awaitBlocking(final Runnable operation)
    {
        awaitBlocking(() ->
        {
            operation.run();
            return null;
        });
    }

    public void awaitLongBlocking(final Runnable operation)
    {
        final long awaitTimeoutInMs = this.awaitTimeoutInMs;
        awaitTimeoutInMs(LONG_AWAIT_TIMEOUT_IN_MS);
        awaitBlocking(operation);
        awaitTimeoutInMs(awaitTimeoutInMs);
    }

    public <T> T awaitBlocking(final Callable<T> operation)
    {
        final Future<T> future = executor.submit(operation);

        final long deadlineInMs = System.currentTimeMillis() + awaitTimeoutInMs;

        while (!future.isDone())
        {
            poll();

            Thread.yield();

            if (System.currentTimeMillis() > deadlineInMs)
            {
                Exceptions.printStackTracesForAllThreads();

                throw new TimeoutException(String.format(" %s failed: timed out after [%s]ms",
                    operation,
                    awaitTimeoutInMs));
            }
        }

        try
        {
            return future.get();
        }
        catch (final InterruptedException | ExecutionException e)
        {
            if (e.getCause() instanceof TimeoutException ||
                e.getCause() instanceof java.util.concurrent.TimeoutException)
            {
                Exceptions.printStackTracesForAllThreads();
            }

            LangUtil.rethrowUnchecked(e);
        }

        return null;
    }

    public void awaitUnbind(final ILink3Connection session)
    {
        await("Failed to unbind session", () -> session.state() == ILink3Connection.State.UNBOUND);
    }

    public Long2LongHashMap pruneArchive(
        final Long2LongHashMap minimumPosition, final FixEngine engine)
    {
        final Reply<Long2LongHashMap> pruneReply = engine.pruneArchive(minimumPosition);
        assertNotNull(pruneReply);
        awaitCompletedReplies(pruneReply);
        return pruneReply.resultIfPresent();
    }

    public void awaitPosition(final ReadablePosition positionCounter, final long position)
    {
        await("Failed to complete index", () -> positionCounter.getVolatile() >= position);
    }

    public List<LibraryInfo> libraries(final FixEngine engine)
    {
        return awaitCompletedReply(engine.libraries()).resultIfPresent();
    }

    public void awaitIsReplaying(final Session session)
    {
        await("Failed to start replaying", session::isReplaying);
    }

    public void awaitNotReplaying(final Session session)
    {
        await("Failed to stop replaying", () -> !session.isReplaying());
    }

    public Reply<?> resetSequenceNumber(final FixEngine engine, final long sessionId)
    {
        final Reply<?> reply = startResetSequenceNumber(engine, sessionId);
        awaitCompletedReply(reply);
        return reply;
    }

    public Reply<?> startResetSequenceNumber(final FixEngine engine, final long sessionId)
    {
        return awaitNotNull(
            "Timed out whilst attempting resetSequenceNumber",
            () -> engine.resetSequenceNumber(sessionId));
    }

    @Override
    public String toString()
    {
        return "TestSystem{" +
            "libraries=" + libraries +
            ", operations=" + operations +
            ", awaitTimeoutInMs=" + awaitTimeoutInMs +
            '}';
    }
}
