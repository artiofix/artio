/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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

import org.agrona.collections.IntHashSet;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.MonitoringAgentFactory;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.RejectEncoder;
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.AuthenticationProxy;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_INBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_OUTBOUND_LIBRARY_STREAM;
import static uk.co.real_logic.artio.Constants.LOGON_MESSAGE_AS_STR;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class AsyncAuthenticatorTest extends AbstractGatewayToGatewaySystemTest
{
    private static final long LINGER_TIMEOUT_IN_MS = 500L;
    private static final long AUTHENTICATION_TIMEOUT_IN_MS = 500L;
    private final ControllableAuthenticationStrategy auth = new ControllableAuthenticationStrategy();

    private long initiateTimeoutInMs = TEST_REPLY_TIMEOUT_IN_MS;

    @BeforeEach
    public void launch()
    {
        mediaDriver = launchMediaDriver();

        // FixMessageLogger.main(new String[]{});

        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock);
        acceptingConfig.deleteLogFileDirOnStart(true);
        acceptingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        acceptingConfig.authenticationStrategy(auth);
        acceptingConfig.authenticationTimeoutInMs(AUTHENTICATION_TIMEOUT_IN_MS);

        acceptingEngine = FixEngine.launch(acceptingConfig);
        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);

        final LibraryConfiguration acceptingLibraryConfig = acceptingLibraryConfig(acceptingHandler, nanoClock);
        acceptingLibrary = connect(acceptingLibraryConfig);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(acceptingLibrary, initiatingLibrary);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldConnectedAcceptedAuthentications()
    {
        final Reply<Session> reply = acquireExecutingAuthProxy();

        auth.accept();
        completeConnectInitiatingSession(reply);
        messagesCanBeExchanged();
        assertInitiatingSequenceIndexIs(0);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToRejectLogons()
    {
        final Reply<Session> reply = acquireExecutingAuthProxy();

        auth.reject();

        assertDisconnectRejected(reply, true);

        assertOnlyLogonInArchive();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToRejectLogonsWithCustomMessages()
    {
        // We run this test twice to ensure that there's no state that persisted over reconnects
        // Which was an observed bug in Artio 0.117.

        rejectLogonWithCustomReject(LINGER_TIMEOUT_IN_MS);

        auth.reset();

        rejectLogonWithCustomReject(LINGER_TIMEOUT_IN_MS);

        final List<ArchiveEntry> entries = scanArchiveForEntries(4);

        assertContainsRejectedLogon(entries.get(0));
        assertRejectReply(entries.get(1));

        assertContainsRejectedLogon(entries.get(2));
        assertRejectReply(entries.get(3));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldBeAbleToRejectLogonsWithCustomMessagesEarlyDisconnect()
    {
        // Perform an early disconnect from the initiator side

        initiateTimeoutInMs = 200;

        rejectLogonWithCustomReject(LINGER_TIMEOUT_IN_MS * 2);

        assertLogonAndRejectInArchive();
    }

    private void rejectLogonWithCustomReject(final long lingerTimeoutInMs)
    {
        final Reply<Session> reply = acquireExecutingAuthProxy();

        final RejectEncoder rejectEncoder = newRejectEncoder();

        final long startTime = System.currentTimeMillis();
        auth.reject(rejectEncoder, lingerTimeoutInMs);

        final boolean acceptorDisconnect = initiateTimeoutInMs > lingerTimeoutInMs;
        assertDisconnectRejected(reply, acceptorDisconnect);
        if (acceptorDisconnect)
        {
            final long rejectTime = System.currentTimeMillis() - startTime;
            assertThat(rejectTime, greaterThanOrEqualTo(lingerTimeoutInMs));
            assertThat(rejectTime, lessThan(2 * lingerTimeoutInMs));
        }
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void rejectWithEncoderMustProvideAnEncoder()
    {
        assertThrows(NullPointerException.class, () ->
        {
            acquireExecutingAuthProxy();

            try
            {
                auth.reject(null, LINGER_TIMEOUT_IN_MS);
            }
            finally
            {
                // Test optimisation.
                auth.reject();
            }

            assertOnlyLogonInArchive();
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void lingerTimeoutShouldBeValid()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {

            acquireExecutingAuthProxy();

            final RejectEncoder encoder = newRejectEncoder();

            try
            {
                auth.reject(encoder, -1);
            }
            finally
            {
                // Test optimisation.
                auth.reject();
            }

            assertOnlyLogonInArchive();
        });
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void invalidEncoderShouldStillDisconnect()
    {
        final Reply<Session> reply = acquireExecutingAuthProxy();

        final RejectEncoder invalidEncoder = new RejectEncoder();

        auth.reject(invalidEncoder, 0);

        assertDisconnectRejected(reply, true);

        assertOnlyLogonInArchive();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectSessionsWhenAuthStrategyFails()
    {
        auth.throwWhenInvoked = true;

        final Reply<Session> reply = acquireAuthProxy();

        assertDisconnectRejected(reply, true);

        assertOnlyLogonInArchive();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void messagesCanBeSentFromInitiatorToAcceptorAfterRejectedAuthenticationAttempt()
    {
        final Reply<Session> invalidReply = acquireExecutingAuthProxy();

        auth.reject();

        completeFailedSession(invalidReply);

        auth.reset();

        final Reply<Session> validReply = acquireExecutingAuthProxy();

        auth.accept();
        completeConnectInitiatingSession(validReply);
        messagesCanBeExchanged();
        assertInitiatingSequenceIndexIs(1);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldOnlyUseFirstMethodCall()
    {
        final Reply<Session> reply = acquireExecutingAuthProxy();

        auth.accept();

        completeConnectInitiatingSession(reply);

        try
        {
            auth.reject();
            fail("Should not allow a reject after an accept");
        }
        catch (final IllegalStateException e)
        {
            // Deliberately blank
        }

        messagesCanBeExchanged();
        assertInitiatingSequenceIndexIs(0);

        scanArchiveForEntries(4);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldDisconnectPendingAuthenticationAfterTimeout()
    {
        final long start = System.currentTimeMillis();
        final Reply<Session> reply = acquireExecutingAuthProxy();
        assertDisconnectRejected(reply, true);
        final long duration = System.currentTimeMillis() - start;
        assertThat(duration, is(greaterThanOrEqualTo(AUTHENTICATION_TIMEOUT_IN_MS)));
        assertThat(duration, is(lessThan(TEST_REPLY_TIMEOUT_IN_MS)));
    }

    @AfterEach
    public void teardown()
    {
        auth.verifyNoBlockingCalls();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotifyAuthStrategyUponAcceptorLogoff()
    {
        // can be either logout or remote disconnect
        notifyAuthStrategyUpon(this::logoutAcceptingSession, null);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotifyAuthStrategyUponInitiatorLogoff()
    {
        notifyAuthStrategyUpon(this::logoutInitiatingSession, DisconnectReason.LOGOUT);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotifyAuthStrategyUponAcceptorDisconnect()
    {
        notifyAuthStrategyUpon(
            () -> testSystem.awaitRequestDisconnect(acceptingSession), DisconnectReason.APPLICATION_DISCONNECT);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotifyAuthStrategyUponInitiatorDisconnect()
    {
        notifyAuthStrategyUpon(
            () -> testSystem.awaitRequestDisconnect(initiatingSession), DisconnectReason.REMOTE_DISCONNECT);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void rejectMessagesCanBeScannedInLogs()
    {
        final Reply<Session> invalidReply = acquireExecutingAuthProxy();
        final RejectEncoder rejectEncoder = newRejectEncoder();
        auth.reject(rejectEncoder, LINGER_TIMEOUT_IN_MS);

        completeFailedSession(invalidReply);

        assertLogonAndRejectInArchive();
    }

    private void assertOnlyLogonInArchive()
    {
        assertContainsRejectedLogon(scanArchiveForEntries(1).get(0));
    }

    private void assertLogonAndRejectInArchive()
    {
        final List<ArchiveEntry> entries = scanArchiveForEntries(2);
        assertContainsRejectedLogon(entries.get(0));
        assertRejectReply(entries.get(1));
    }

    private List<ArchiveEntry> scanArchiveForEntries(final int size)
    {
        final IntHashSet queryStreamIds = new IntHashSet();
        queryStreamIds.add(DEFAULT_OUTBOUND_LIBRARY_STREAM);
        queryStreamIds.add(DEFAULT_INBOUND_LIBRARY_STREAM);
        final List<ArchiveEntry> entries = getFromArchive(acceptingEngine.configuration(), queryStreamIds);
        assertThat(entries, hasSize(size));
        return entries;
    }

    private void assertRejectReply(final ArchiveEntry reply)
    {
        assertEquals(MessageStatus.OK, reply.status(), reply.toString());
        assertThat(reply.body(), containsString("35=3\00149=acceptor"));
        assertThat(reply.body(), containsString("372=A\00158=Invalid Logon"));
    }

    private void assertContainsRejectedLogon(final ArchiveEntry logon)
    {
        assertEquals(MessageStatus.AUTH_REJECT, logon.status());
        assertThat(logon.body(), containsString("35=A\00149=initiator"));
        assertThat(logon.body(), containsString("554=***")); // password is erased
    }

    private void notifyAuthStrategyUpon(final Runnable disconnector, final DisconnectReason reason)
    {
        shouldConnectedAcceptedAuthentications();
        acquireAcceptingSession();
        final long connectionId = acceptingSession.connectionId();

        disconnector.run();
        assertSessionsDisconnected();

        testSystem.await("Failed to disconnect", () -> auth.hasDisconnected);
        assertEquals(acceptingSession.id(), auth.disconnectSessionId);
        assertEquals(connectionId, auth.disconnectConnectionId);
        assertEquals(connectionId, auth.authConnectionId);
        if (reason != null)
        {
            assertEquals(reason, auth.disconnectReason);
        }
    }

    private RejectEncoder newRejectEncoder()
    {
        final RejectEncoder rejectEncoder = new RejectEncoder();
        rejectEncoder.refMsgType(LOGON_MESSAGE_AS_STR);
        rejectEncoder.refSeqNum(1);
        rejectEncoder.text("Invalid Logon");
        return rejectEncoder;
    }

    private void assertDisconnectRejected(final Reply<Session> reply, final boolean acceptorDisconnect)
    {
        testSystem.awaitReply(reply);
        if (acceptorDisconnect)
        {
            assertEquals(Reply.State.ERRORED, reply.state(), reply.toString());
            assertThat(reply.error().getMessage(),
                containsString("UNABLE_TO_LOGON: Disconnected before session active"));
        }
    }

    private Reply<Session> acquireExecutingAuthProxy()
    {
        final Reply<Session> reply = acquireAuthProxy();

        assertEquals(Reply.State.EXECUTING, reply.state(), reply.toString());

        return reply;
    }

    private Reply<Session> acquireAuthProxy()
    {
        final Reply<Session> reply = initiate(initiatingLibrary, port, INITIATOR_ID, ACCEPTOR_ID,
            initiateTimeoutInMs, false, null);

        assertEventuallyTrue("failed to receive auth proxy", () ->
        {
            testSystem.poll();
            return auth.hasAuthenticateBeenInvoked();
        }, TEST_REPLY_TIMEOUT_IN_MS);

        return reply;
    }

    private static class ControllableAuthenticationStrategy implements AuthenticationStrategy
    {
        private volatile boolean throwWhenInvoked;
        private volatile boolean blockingAuthenticateCalled;
        private volatile AuthenticationProxy authProxy;

        private long authConnectionId;
        private long disconnectSessionId;
        private long disconnectConnectionId;
        private DisconnectReason disconnectReason;
        private volatile boolean hasDisconnected;

        ControllableAuthenticationStrategy()
        {
            reset();
        }

        @Override
        public void authenticateAsync(final AbstractLogonDecoder logon, final AuthenticationProxy authProxy)
        {
            authConnectionId = authProxy.connectionId();
            this.authProxy = authProxy;

            assertThat(authProxy.remoteAddress(), containsString("127.0.0.1"));

            if (throwWhenInvoked)
            {
                throw new RuntimeException("Broken application code");
            }
        }

        @Override
        public boolean authenticate(final AbstractLogonDecoder logon)
        {
            blockingAuthenticateCalled = true;

            throw new UnsupportedOperationException();
        }

        void verifyNoBlockingCalls()
        {
            assertFalse(blockingAuthenticateCalled);
        }

        void accept()
        {
            authProxy.accept();
        }

        void reject()
        {
            authProxy.reject();
        }

        void reject(final Encoder encoder, final long lingerTimeoutInMs)
        {
            authProxy.reject(encoder, lingerTimeoutInMs);
        }

        boolean hasAuthenticateBeenInvoked()
        {
            return authProxy != null;
        }

        void reset()
        {
            authConnectionId = NO_CONNECTION_ID;
            disconnectSessionId = Session.UNKNOWN;
            disconnectConnectionId = NO_CONNECTION_ID;
            disconnectReason = null;
            hasDisconnected = false;
            authProxy = null;
        }

        @Override
        public void onDisconnect(
            final long sessionId,
            final long connectionId,
            final DisconnectReason reason)
        {
            this.disconnectSessionId = sessionId;
            this.disconnectConnectionId = connectionId;
            this.disconnectReason = reason;
            hasDisconnected = true;
        }
    }
}
