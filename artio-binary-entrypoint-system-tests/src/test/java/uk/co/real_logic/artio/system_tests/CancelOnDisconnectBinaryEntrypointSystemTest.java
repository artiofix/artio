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
package uk.co.real_logic.artio.system_tests;

import b3.entrypoint.fixp.sbe.CancelOnDisconnectType;
import b3.entrypoint.fixp.sbe.DeltaInMillisEncoder;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixPSessionInfo;
import uk.co.real_logic.artio.fixp.FixPCancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.fixp.FixPContext;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static b3.entrypoint.fixp.sbe.CancelOnDisconnectType.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.co.real_logic.artio.CommonConfiguration.NO_FIXP_MAX_RETRANSMISSION_RANGE;
import static uk.co.real_logic.artio.engine.EngineConfiguration.DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.system_tests.CancelOnDisconnectSystemTest.COD_TEST_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.system_tests.CancelOnDisconnectSystemTest.LONG_COD_TEST_TIMEOUT_IN_MS;

public class CancelOnDisconnectBinaryEntrypointSystemTest extends AbstractBinaryEntryPointSystemTest
{

    private final FakeTimeoutHandler timeoutHandler = new FakeTimeoutHandler();
    private BinaryEntryPointClient client;

    @AfterEach
    public void shutdown()
    {
        Exceptions.closeAll(client);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogout() throws IOException
    {
        setup(CANCEL_ON_TERMINATE_ONLY, COD_TEST_TIMEOUT_IN_MS);

        final long logoutTimeInNs = nanoClock.nanoTime();
        clientTerminatesConnection(client);

        assertTriggersCancelOnDisconnect(logoutTimeInNs);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutWhenClientDisconnectForLogoutOnly() throws IOException
    {
        setup(CANCEL_ON_TERMINATE_ONLY, COD_TEST_TIMEOUT_IN_MS);

        disconnect();

        assertHandlerNotInvoked(LONG_COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForDisconnect() throws IOException
    {
        setup(CANCEL_ON_DISCONNECT_ONLY, COD_TEST_TIMEOUT_IN_MS);

        final long logoutTimeInNs = nanoClock.nanoTime();
        disconnect();

        assertTriggersCancelOnDisconnect(logoutTimeInNs);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutWhenClientTerminateForDisconnectOnly() throws IOException
    {
        setup(CANCEL_ON_DISCONNECT_ONLY, COD_TEST_TIMEOUT_IN_MS);

        clientTerminatesConnection(client);

        assertHandlerNotInvoked(LONG_COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutWhenConfiguredNotTo() throws IOException
    {
        setup(DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE, DeltaInMillisEncoder.timeNullValue());

        disconnect();
        assertDisconnectWithHandlerNotInvoked();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldCorrectTimeoutsOverLimit() throws IOException
    {
        setup(
            DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE,
            DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE,
            100_000,
            60_000);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldCorrectNullTimeouts() throws IOException
    {
        setup(
            CANCEL_ON_DISCONNECT_OR_TERMINATE,
            DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE,
            DeltaInMillisEncoder.timeNullValue(),
            DeltaInMillisEncoder.timeNullValue());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutIfReconnectOccurs() throws IOException
    {
        setup(CANCEL_ON_DISCONNECT_OR_TERMINATE, LONG_COD_TEST_TIMEOUT_IN_MS);

        disconnect();

        client.assertDisconnected();

        reEstablishClient();

        assertHandlerNotInvoked(LONG_COD_TEST_TIMEOUT_IN_MS);
    }

    private void reEstablishClient() throws IOException
    {
        client = newClient();
        client.writeEstablish(1);
        libraryAcquiresConnection(client);
        client.readEstablishAck(1, 0);
    }

    private void setup(
        final CancelOnDisconnectType cancelOnDisconnectType, final long codTestTimeoutInMs) throws IOException
    {
        setup(cancelOnDisconnectType, cancelOnDisconnectType, codTestTimeoutInMs, codTestTimeoutInMs);
    }

    private void setup(
        final CancelOnDisconnectType cancelOnDisconnectType,
        final CancelOnDisconnectType calculatedCancelOnDisconnectType,
        final long codTestTimeoutInMs,
        final long calculatedCodTestTimeoutInMs) throws IOException
    {
        setup();
        setupJustArtio(
            true,
            DEFAULT_NO_LOGON_DISCONNECT_TIMEOUT_IN_MS,
            NO_FIXP_MAX_RETRANSMISSION_RANGE,
            timeoutHandler,
            false, EngineConfiguration.DEFAULT_SENDER_MAX_BYTES_IN_BUFFER);
        client = newClient();
        client.codTimeout(cancelOnDisconnectType, codTestTimeoutInMs);
        establishNewConnection(client);
        acquireConnection(connectionAcquiredHandler);

        assertEquals(calculatedCancelOnDisconnectType, connection.cancelOnDisconnectType());
        assertEquals(calculatedCodTestTimeoutInMs, connection.codTimeoutWindow());
    }

    private void disconnect()
    {
        client.close();
    }

    private void assertDisconnectWithHandlerNotInvoked()
    {
        client.assertDisconnected();

        assertHandlerNotInvoked(COD_TEST_TIMEOUT_IN_MS);
    }

    private void assertHandlerNotInvoked(final int codTestTimeoutInMs)
    {
        testSystem.awaitBlocking(() ->
        {
            try
            {
                Thread.sleep(codTestTimeoutInMs);
            }
            catch (final InterruptedException e)
            {
                e.printStackTrace();
            }
        });

        assertNull(timeoutHandler.result);
        assertEquals(0, timeoutHandler.invokeCount());
    }

    private void assertTriggersCancelOnDisconnect(final long logoutTimeInNs)
    {
        final long codTimeoutInNs = MILLISECONDS.toNanos(COD_TEST_TIMEOUT_IN_MS);

        client.assertDisconnected();

        testSystem.await("timeout not triggered", () -> timeoutHandler.result() != null);

        final FixPSessionInfo onlySession = engine.allFixPSessions().get(0);
        final TimeoutResult result = timeoutHandler.result();
        assertEquals(onlySession.key().sessionIdIfExists(), result.surrogateId);
        assertEquals(onlySession.key(), result.context.key());
        final long timeoutTakenInNs = result.timeInNs - logoutTimeInNs;
        assertThat(timeoutTakenInNs, greaterThanOrEqualTo(codTimeoutInNs));
        testSystem.await("timeoutHandler.invokeCount() is not 1", () -> 1 == timeoutHandler.invokeCount());
    }

    class FakeTimeoutHandler implements FixPCancelOnDisconnectTimeoutHandler
    {
        private final AtomicInteger invokeCount = new AtomicInteger(0);
        private volatile TimeoutResult result;

        @Override
        public void onCancelOnDisconnectTimeout(final long sessionId, final FixPContext context)
        {
            this.result = new TimeoutResult(sessionId, context, nanoClock.nanoTime());
            invokeCount.incrementAndGet();
        }

        public TimeoutResult result()
        {
            return result;
        }

        public int invokeCount()
        {
            return invokeCount.get();
        }
    }

    static final class TimeoutResult
    {
        private final long surrogateId;
        private final FixPContext context;
        private final long timeInNs;

        private TimeoutResult(final long surrogateId, final FixPContext context, final long nowNs)
        {
            this.surrogateId = surrogateId;
            this.context = context;
            timeInNs = nowNs;
        }
    }
}
