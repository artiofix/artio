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
package uk.co.real_logic.artio.admin;

import io.aeron.Aeron;
import io.aeron.Counter;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.exceptions.TimeoutException;
import org.agrona.CloseHelper;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.FixGatewayException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static uk.co.real_logic.artio.FixCounters.FixCountersId.FAILED_ADMIN_TYPE_ID;

/**
 * Provides a blocking wrapper for Artio API operations that can be used from a different process. Every API operation
 * in this class blocks its invoking thread until the operation is complete and only one API operation can be performed
 * at a time.
 * <p>
 * This can be used for integration into commandline tools (such as FixAdminTool) or external processes, such as
 * web services APIs or custom GUI tools. It is designed to provide functionality for tasks that operational personnel
 * may want to perform on a FIX Gateway.
 */
public final class ArtioAdmin implements AutoCloseable
{
    private static final int MAX_CLAIM_ATTEMPTS = 10_000;
    private static final int FRAGMENT_LIMIT = 10;

    private final Lock lock = new ReentrantLock();
    private final AdminEndPointHandler handler = new AdminEndPointHandler();
    private final AdminApiProtocolSubscription protocolSubscription = new AdminApiProtocolSubscription(handler);

    private final AdminPublication outboundPublication;
    private final Subscription inboundSubscription;
    private final Aeron aeron;
    private final IdleStrategy idleStrategy;
    private final EpochNanoClock epochNanoClock;
    private final Counter failCounter;
    private final long replyTimeoutInNs;
    private final BooleanSupplier checkReplyFunc = this::checkReply;
    private final BooleanSupplier saveRequestAllFixSessionsFunc = this::saveRequestAllFixSessionsFunc;
    private final Supplier<List<FixAdminSession>> allFixSessionsResultFunc = handler::allFixSessions;

    private volatile boolean closed = false;

    private long correlationId;

    // ----------------------------------------------------
    // Public API
    // ----------------------------------------------------

    /**
     * This starts the ArtioAdmin instance. This allows users to perform Admin API operations by calling the public
     * methods on this class. Once the instance is finished with it should be closed by calling the {@link #close()}
     * method.
     *
     * @param config the configuration object to start your admin instance.
     * @return the new ArtioAdmin instance
     */
    public static ArtioAdmin launch(final ArtioAdminConfiguration config)
    {
        return new ArtioAdmin(config);
    }

    /**
     * Queries the current list of all FIX sessions associated with this FixEngine. This includes both connected and
     * offline FIX sessions. It is worth noting that the information provided by this API is a snapshot in time. For
     * example the received and sent sequence numbers are the numbers that the Engine is aware of when the API
     * operation is processed. It might be the case that a FIX session with a high volume of traffic that sends or
     * receives many messages in a short time period will have advanced to new sequence numbers by the time that the
     * API operation returns. Similarly a client could connect or disconnect immediately after the Engine replies
     * to this API operation, leaving its data stale.
     *
     * @return the list of FIX sessions.
     * @throws TimeoutException      if the operation times out.
     * @throws IllegalStateException if the instance has been closed.
     */
    public List<FixAdminSession> allFixSessions()
    {
        return exchangeMessage(saveRequestAllFixSessionsFunc, allFixSessionsResultFunc);
    }

    /**
     * Disconnects a currently connected FIX session.
     *
     * @param sessionId the id of the session to disconnect.
     * @throws FixGatewayException   if the session id is unknown or if the session is not currently connected.
     * @throws TimeoutException      if the operation times out.
     * @throws IllegalStateException if the instance has been closed.
     */
    public void disconnectSession(final long sessionId)
    {
        exchangeMessage(
            () -> outboundPublication.saveDisconnectSession(correlationId, sessionId) > 0,
            handler::checkError);
    }

    /**
     * Resets the sequence numbers of a session back to 1. This operation has the same semantics as
     * {@link uk.co.real_logic.artio.engine.FixEngine#resetSequenceNumber(long)} and will work for both the case where
     * a session is connected or offline. If the session is currently connected then a Logon message with a
     * resetSeqNum=Y flag will be sent to the counter-party.
     *
     * @param sessionId the id of the session to perform the reset operation on.
     * @throws FixGatewayException   if the session id is unknown.
     * @throws TimeoutException      if the operation times out.
     * @throws IllegalStateException if the instance has been closed.
     */
    public void resetSequenceNumbers(final long sessionId)
    {
        exchangeMessage(
            () -> outboundPublication.saveResetSequenceNumbers(correlationId, sessionId) > 0,
            handler::checkError);
    }

    /**
     * Close the Admin API instance, releasing underlying resources.
     */
    @Override
    public void close()
    {
        lock.lock();
        try
        {
            if (!closed)
            {
                CloseHelper.closeAll(failCounter, aeron);
                closed = true;
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Returns whether the class has been closed or not.
     *
     * @return true if {@link #close()} has been called, false otherwise.
     */
    public boolean isClosed()
    {
        return closed;
    }

    // ----------------------------------------------------
    // End of Public API
    // ----------------------------------------------------

    private ArtioAdmin(final ArtioAdminConfiguration config)
    {
        try
        {
            config.conclude();

            aeron = Aeron.connect(config.aeronContext());
            idleStrategy = config.idleStrategy();
            epochNanoClock = config.epochNanoClock();
            failCounter = aeron.addCounter(FAILED_ADMIN_TYPE_ID.id(), "Failed offer for admin publication");
            replyTimeoutInNs = config.replyTimeoutInNs();

            final String channel = config.aeronChannel();
            final ExclusivePublication publication =
                aeron.addExclusivePublication(channel, config.outboundAdminStream());
            outboundPublication = new AdminPublication(
                publication,
                failCounter,
                idleStrategy,
                MAX_CLAIM_ATTEMPTS);
            inboundSubscription = aeron.addSubscription(channel, config.inboundAdminStream());

            final long connectDeadlineNs = nanoTime() + config.connectTimeoutNs();
            idleStrategy.reset();
            while (!inboundSubscription.isConnected() || !publication.isConnected())
            {
                if (nanoTime() > connectDeadlineNs)
                {
                    throw new TimeoutException("Failed to connect to FixEngine using channel=" + channel +
                        " outboundAdminStreamId=" + config.outboundAdminStream() +
                        " inboundAdminStreamId=" + config.inboundAdminStream() +
                        " subscription.isConnected=" + inboundSubscription.isConnected() +
                        " publication.isConnected=" + publication.isConnected() +
                        " after " + config.connectTimeoutNs() + " ns");
                }
                idleStrategy.idle();
            }
        }
        catch (final RuntimeException ex)
        {
            close();
            throw ex;
        }
    }

    private boolean saveRequestAllFixSessionsFunc()
    {
        return outboundPublication.saveRequestAllFixSessions(correlationId) > 0;
    }

    private <T> T exchangeMessage(final BooleanSupplier sendMessage, final Supplier<T> getResult)
    {
        lock.lock();
        try
        {
            checkOpen();

            final long deadlineInNs = nanoTime() + replyTimeoutInNs;
            correlationId = newCorrelationId();
            handler.expectedCorrelationId(correlationId);
            until(deadlineInNs, sendMessage);
            until(deadlineInNs, checkReplyFunc);

            return getResult.get();
        }
        finally
        {
            lock.unlock();
        }
    }

    private boolean checkReply()
    {
        inboundSubscription.poll(protocolSubscription, FRAGMENT_LIMIT);
        return handler.hasReceivedReply();
    }

    private long newCorrelationId()
    {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private void until(final long deadlineInNs, final BooleanSupplier attempt)
    {
        while (true)
        {
            if (nanoTime() > deadlineInNs)
            {
                throw new TimeoutException("Operation timed out after " + replyTimeoutInNs + " ns");
            }

            if (attempt.getAsBoolean())
            {
                break;
            }

            idleStrategy.idle();
        }
    }

    private long nanoTime()
    {
        return epochNanoClock.nanoTime();
    }

    private void checkOpen()
    {
        if (closed)
        {
            throw new IllegalStateException("client is closed");
        }
    }
}
