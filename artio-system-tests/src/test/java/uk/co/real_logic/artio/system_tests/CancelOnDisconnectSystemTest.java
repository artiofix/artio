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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.CancelOnDisconnectType;
import uk.co.real_logic.artio.Constants;
import uk.co.real_logic.artio.builder.AbstractLogonEncoder;
import uk.co.real_logic.artio.builder.AbstractLogoutEncoder;
import uk.co.real_logic.artio.builder.LogonEncoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.CancelOnDisconnectOption;
import uk.co.real_logic.artio.other.FixDictionaryImpl;
import uk.co.real_logic.artio.session.CancelOnDisconnectTimeoutHandler;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.SessionCustomisationStrategy;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static uk.co.real_logic.artio.CancelOnDisconnectType.*;
import static uk.co.real_logic.artio.CommonConfiguration.RUNNING_ON_WINDOWS;
import static uk.co.real_logic.artio.Constants.LOGON_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class CancelOnDisconnectSystemTest extends AbstractGatewayToGatewaySystemTest
{
    public static final int COD_TEST_TIMEOUT_IN_MS = 500;
    public static final int LONG_COD_TEST_TIMEOUT_IN_MS = RUNNING_ON_WINDOWS ? 3_000 : COD_TEST_TIMEOUT_IN_MS;
    public static final Class<FixDictionaryImpl> FIX_DICTIONARY_WITHOUT_COD = FixDictionaryImpl.class;
    private long now;

    private final FakeTimeoutHandler timeoutHandler = new FakeTimeoutHandler();

    public void launch()
    {
        launch(null, null);
    }

    public void launch(final CancelOnDisconnectOption acceptorCancelOnDisconnectOption,
        final Class<? extends FixDictionary> fixDictionary)
    {
        launch(acceptorCancelOnDisconnectOption, CodecUtil.MISSING_INT, fixDictionary);
    }

    public void launch(final CancelOnDisconnectOption acceptorCancelOnDisconnectOption,
        final int cancelOnDisconnectTimeoutWindowInMs, final Class<? extends FixDictionary> fixDictionary)
    {
        mediaDriver = launchMediaDriver();

        final EngineConfiguration configuration = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .deleteLogFileDirOnStart(true)
            .cancelOnDisconnectTimeoutHandler(timeoutHandler);
        if (cancelOnDisconnectTimeoutWindowInMs != CodecUtil.MISSING_INT)
        {
            configuration.cancelOnDisconnectTimeoutWindowInMs(cancelOnDisconnectTimeoutWindowInMs);
        }
        if (acceptorCancelOnDisconnectOption != null)
        {
            configuration.cancelOnDisconnectOption(acceptorCancelOnDisconnectOption);
        }
        if (fixDictionary != null)
        {
            configuration.acceptorfixDictionary(fixDictionary);
        }
        acceptingEngine = FixEngine.launch(configuration);
        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);

        acceptingLibrary = connect(acceptingLibraryConfig(acceptingHandler, nanoClock));
    }

    private void setup(final int cancelOnDisconnectType, final int cODTimeoutWindow)
    {
        final CancelOnDisconnectSessionCustomisationStrategy customisationStrategy =
            new CancelOnDisconnectSessionCustomisationStrategy(cancelOnDisconnectType, cODTimeoutWindow);
        setup(customisationStrategy);
    }

    private void setup(final SessionCustomisationStrategy customisationStrategy)
    {
        setupWithoutConnecting(customisationStrategy);

        connectSessions();
    }

    private void setupWithoutConnecting(final SessionCustomisationStrategy customisationStrategy)
    {
        final LibraryConfiguration config = initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock);
        config.sessionCustomisationStrategy(customisationStrategy);
        initiatingLibrary = connect(config);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogout()
    {
        launch();
        setup(CANCEL_ON_LOGOUT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        now = nanoClock.nanoTime();
        logoutSession(initiatingSession);

        assertTriggersCancelOnDisconnect(CANCEL_ON_LOGOUT_ONLY, now);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForDisconnect()
    {
        launch();
        setup(CANCEL_ON_DISCONNECT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        now = nanoClock.nanoTime();
        testSystem.awaitRequestDisconnect(initiatingSession);

        assertTriggersCancelOnDisconnect(CANCEL_ON_DISCONNECT_ONLY, now);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogoutLibrary()
    {
        launch();
        setup(CANCEL_ON_LOGOUT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        logoutSession(initiatingSession);

        assertTriggersCancelOnDisconnect(CANCEL_ON_LOGOUT_ONLY, now);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForDisconnectLibrary()
    {
        launch();
        setup(CANCEL_ON_DISCONNECT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        now = nanoClock.nanoTime();
        testSystem.awaitRequestDisconnect(initiatingSession);

        assertTriggersCancelOnDisconnect(CANCEL_ON_DISCONNECT_ONLY, now);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutWhenConfiguredNotTo()
    {
        launch();
        setup(DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT.representation(), 0);

        testSystem.awaitRequestDisconnect(initiatingSession);
        assertDisconnectWithHandlerNotInvoked();

        assertInitiatorCodState(DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT, 0, 0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutWithNoLogonOptions()
    {
        launch();
        setup(SessionCustomisationStrategy.none());

        testSystem.awaitRequestDisconnect(initiatingSession);
        assertDisconnectWithHandlerNotInvoked();

        assertInitiatorCodState(DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT, 0, 0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutWithNoLogonOptionsButServerOption()
    {
        launch(CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY, FIX_DICTIONARY_WITHOUT_COD);
        setupWithoutConnecting(SessionCustomisationStrategy.none());
        connectSessions(FIX_DICTIONARY_WITHOUT_COD);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        disconnectSession(initiatingSession);

        assertTriggersCancelOnDisconnectFromDefaults(CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY, 0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutWithNoLogonOptionsButServerOptionAndTimeout()
    {
        launch(CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY, COD_TEST_TIMEOUT_IN_MS, FIX_DICTIONARY_WITHOUT_COD);
        setupWithoutConnecting(SessionCustomisationStrategy.none());
        connectSessions(FIX_DICTIONARY_WITHOUT_COD);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        disconnectSession(initiatingSession);

        assertTriggersCancelOnDisconnectFromDefaults(CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY,
            COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogoutWithServerOption()
    {
        launch(CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY, FIX_DICTIONARY_WITHOUT_COD);
        setupWithoutConnecting(SessionCustomisationStrategy.none());
        connectSessions(FIX_DICTIONARY_WITHOUT_COD);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        logoutSession(initiatingSession);

        assertTriggersCancelOnDisconnectFromDefaults(CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY, 0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogoutWithServerOptionAndTimeout()
    {
        launch(CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY, COD_TEST_TIMEOUT_IN_MS, FIX_DICTIONARY_WITHOUT_COD);
        setupWithoutConnecting(SessionCustomisationStrategy.none());
        connectSessions(FIX_DICTIONARY_WITHOUT_COD);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        logoutSession(initiatingSession);

        assertTriggersCancelOnDisconnectFromDefaults(CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY,
            COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldTriggerCancelOnDisconnectTimeoutForLogoutWithOptionsInsteadOfServerOptionAndTimeout()
    {
        launch(CancelOnDisconnectOption.CANCEL_ON_DISCONNECT_ONLY, 0, null);
        setup(CANCEL_ON_LOGOUT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        acquireAcceptingSession();

        now = nanoClock.nanoTime();
        logoutSession(initiatingSession);

        assertTriggersCancelOnDisconnectFromDefaults(CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY,
            CancelOnDisconnectOption.CANCEL_ON_LOGOUT_ONLY, COD_TEST_TIMEOUT_IN_MS, COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldCorrectTimeoutsOverLimit()
    {
        launch();
        setup(CANCEL_ON_DISCONNECT_OR_LOGOUT.representation(), 100_000_000);

        acquireAcceptingSession();

        final long maxTimeoutInNs = 60_000_000_000L;
        assertEquals(maxTimeoutInNs, acceptingSession.cancelOnDisconnectTimeoutWindowInNs());
        assertEquals(maxTimeoutInNs, initiatingSession.cancelOnDisconnectTimeoutWindowInNs());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotTriggerCancelOnDisconnectTimeoutIfReconnectOccurs()
    {
        launch();
        setup(CANCEL_ON_DISCONNECT_OR_LOGOUT.representation(), LONG_COD_TEST_TIMEOUT_IN_MS);

        testSystem.awaitRequestDisconnect(initiatingSession);

        assertSessionDisconnected(initiatingSession);

        connectSessions();

        assertHandlerNotInvoked(LONG_COD_TEST_TIMEOUT_IN_MS);
    }

    @Test
    public void shouldTriggerCancelOnDisconnectFromGatewayAfterReacquiring()
    {
        launch();
        setup(CANCEL_ON_DISCONNECT_ONLY.representation(), COD_TEST_TIMEOUT_IN_MS);

        acquireAcceptingSession();
        testSystem.awaitCompletedReply(acceptingLibrary.releaseToGateway(acceptingSession, 5_000));

        now = nanoClock.nanoTime();
        testSystem.awaitRequestDisconnect(initiatingSession);
        assertTriggersCancelOnDisconnect(CANCEL_ON_DISCONNECT_ONLY, now);
    }

    private void assertDisconnectWithHandlerNotInvoked()
    {
        assertSessionDisconnected(initiatingSession);

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

    private void assertTriggersCancelOnDisconnect(final CancelOnDisconnectType type, final long initiatorLogoutTime)
    {
        assertTriggersCancelOnDisconnect(type, COD_TEST_TIMEOUT_IN_MS, initiatorLogoutTime);
    }

    private void assertTriggersCancelOnDisconnect(final CancelOnDisconnectType type,
        final int codTestTimeoutInMs,
        final long initiatorLogoutTime)
    {
        final long codTimeoutInNs = MILLISECONDS.toNanos(codTestTimeoutInMs);

        assertAcceptorCodTriggered(codTimeoutInNs, initiatorLogoutTime);

        assertInitiatorCodState(type, codTimeoutInNs, codTestTimeoutInMs);
    }

    private void assertTriggersCancelOnDisconnectFromDefaults(final CancelOnDisconnectOption acceptorOption,
        final int acceptorCodTestTimeoutInMs)
    {
        assertTriggersCancelOnDisconnectFromDefaults(acceptorOption,
            CancelOnDisconnectOption.DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT, acceptorCodTestTimeoutInMs, 0);
    }

    private void assertTriggersCancelOnDisconnectFromDefaults(final CancelOnDisconnectOption acceptorOption,
        final CancelOnDisconnectOption initiatorOption, final int acceptorCodTestTimeoutInMs,
        final int initiatorCodTestTimeoutInMs)
    {
        final long acceptorCodTimeoutInNs = MILLISECONDS.toNanos(acceptorCodTestTimeoutInMs);
        assertEquals(acceptorOption, acceptingSession.cancelOnDisconnectOption());
        assertEquals(acceptorCodTimeoutInNs, acceptingSession.cancelOnDisconnectTimeoutWindowInNs());

        final long initiatorCodTestTimeoutInNs = MILLISECONDS.toNanos(initiatorCodTestTimeoutInMs);
        assertEquals(initiatorOption, initiatingSession.cancelOnDisconnectOption());
        assertEquals(initiatorCodTestTimeoutInNs, initiatingSession.cancelOnDisconnectTimeoutWindowInNs());

        assertAcceptorCodTriggered(acceptorCodTimeoutInNs, now);
    }

    private void assertAcceptorCodTriggered(final long codTimeoutInNs, final long initiatorLogoutTime)
    {
        assertSessionDisconnected(initiatingSession);

        testSystem.await("timeout not triggered", () -> timeoutHandler.result() != null);

        final SessionInfo onlySession = acceptingEngine.allSessions().get(0);
        final TimeoutResult result = timeoutHandler.result();
        assertEquals(onlySession.sessionId(), result.surrogateId);
        assertEquals(onlySession.sessionKey(), result.compositeId);
        final long timeoutTakenInNs = result.timeInNs - initiatorLogoutTime;
        assertThat(timeoutTakenInNs, greaterThanOrEqualTo(codTimeoutInNs));
        assertEquals(1, timeoutHandler.invokeCount());
    }

    private void assertInitiatorCodState(
        final CancelOnDisconnectType type, final long codTimeoutInNs, final int codTestTimeoutInMs)
    {
        final FixMessage echodLogon = initiatingOtfAcceptor.messages().get(0);
        assertEquals(LOGON_MESSAGE_AS_STR, echodLogon.msgType());
        assertEquals(type.representation(), echodLogon.cancelOnDisconnectType(), echodLogon.toString());
        assertEquals(codTestTimeoutInMs, echodLogon.getInt(Constants.C_O_D_TIMEOUT_WINDOW), echodLogon.toString());

        final CancelOnDisconnectOption option = CancelOnDisconnectOption.get(type.representation());
        assertEquals(option, initiatingSession.cancelOnDisconnectOption());
        assertEquals(codTimeoutInNs, initiatingSession.cancelOnDisconnectTimeoutWindowInNs());
    }

    static class CancelOnDisconnectSessionCustomisationStrategy implements SessionCustomisationStrategy
    {
        private final int cancelOnDisconnectType;
        private final int cODTimeoutWindow;

        CancelOnDisconnectSessionCustomisationStrategy(
            final int cancelOnDisconnectType, final int cODTimeoutWindow)
        {
            this.cancelOnDisconnectType = cancelOnDisconnectType;
            this.cODTimeoutWindow = cODTimeoutWindow;
        }

        @Override
        public void configureLogon(final AbstractLogonEncoder abstractLogon, final long sessionId)
        {
            final LogonEncoder logon = (LogonEncoder)abstractLogon;
            logon.cancelOnDisconnectType(cancelOnDisconnectType);
            logon.cODTimeoutWindow(cODTimeoutWindow);
        }

        @Override
        public void configureLogout(final AbstractLogoutEncoder logout, final long sessionId)
        {
        }
    }

    class FakeTimeoutHandler implements CancelOnDisconnectTimeoutHandler
    {
        private final AtomicInteger invokeCount = new AtomicInteger(0);
        private volatile TimeoutResult result;

        @Override
        public void onCancelOnDisconnectTimeout(final long sessionId, final CompositeKey fixSessionKey)
        {
            result = new TimeoutResult(sessionId, fixSessionKey, nanoClock.nanoTime());
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
        private final CompositeKey compositeId;
        private final long timeInNs;

        private TimeoutResult(final long surrogateId, final CompositeKey compositeId, final long nowNs)
        {
            this.surrogateId = surrogateId;
            this.compositeId = compositeId;
            timeInNs = nowNs;
        }
    }
}
