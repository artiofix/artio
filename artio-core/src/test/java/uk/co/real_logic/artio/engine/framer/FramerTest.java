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
package uk.co.real_logic.artio.engine.framer;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.QueuedPipe;
import org.agrona.concurrent.status.CountersReader;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.CloseChecker;
import uk.co.real_logic.artio.FixCounters;
import uk.co.real_logic.artio.LivenessDetector;
import uk.co.real_logic.artio.Timing;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.*;
import uk.co.real_logic.artio.engine.logger.ReplayQuery;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.CompositeKey;
import uk.co.real_logic.artio.session.InternalSession;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.timing.Timer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_NAME_PREFIX;
import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.artio.library.SessionConfiguration.*;
import static uk.co.real_logic.artio.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.artio.messages.DisconnectReason.APPLICATION_DISCONNECT;
import static uk.co.real_logic.artio.messages.DisconnectReason.REMOTE_DISCONNECT;
import static uk.co.real_logic.artio.messages.GatewayError.*;
import static uk.co.real_logic.artio.messages.SequenceNumberType.TRANSIENT;
import static uk.co.real_logic.artio.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.artio.messages.SessionState.ACTIVE;
import static uk.co.real_logic.artio.messages.SessionState.CONNECTED;

public class FramerTest
{
    private static final InetSocketAddress TEST_ADDRESS = new InetSocketAddress("localhost", 9998);
    private static final InetSocketAddress FRAMER_ADDRESS = new InetSocketAddress("localhost", 9999);
    private static final int LIBRARY_ID = 3;
    private static final int REPLY_TIMEOUT_IN_MS = 10;
    private static final int HEARTBEAT_INTERVAL_IN_S = 10;
    private static final long HEARTBEAT_INTERVAL_IN_MS = TimeUnit.SECONDS.toMillis(HEARTBEAT_INTERVAL_IN_S);
    private static final int CORR_ID = 1;
    private static final long POSITION = 1024;
    private static final int AERON_SESSION_ID = 234;
    private static final long SESSION_ID = 123;
    private static final String LIBRARY_NAME = "library";

    private ServerSocketChannel server;

    private SocketChannel client;
    private final ByteBuffer clientBuffer = ByteBuffer.allocate(1024);

    private final FixSenderEndPoint mockSenderEndPoint = mock(FixSenderEndPoint.class);
    private final FixReceiverEndPoint mockReceiverEndPoint = mock(FixReceiverEndPoint.class);
    private final FixEndPointFactory mockEndPointFactory = mock(FixEndPointFactory.class);
    private final GatewayPublication inboundPublication = mock(GatewayPublication.class);
    private final SessionIdStrategy mockSessionIdStrategy = mock(SessionIdStrategy.class);
    private final Header header = mock(Header.class);
    private final FakeEpochClock mockClock = new FakeEpochClock();
    private final SequenceNumberIndexReader sentSequenceNumberIndex = mock(SequenceNumberIndexReader.class);
    private final SequenceNumberIndexReader receivedSequenceNumberIndex = mock(SequenceNumberIndexReader.class);
    private final ReplayQuery replayQuery = mock(ReplayQuery.class);
    private final FixCounters fixCounters = mock(FixCounters.class);
    private final FixContexts fixContexts = mock(FixContexts.class);
    private final FixGatewaySessions gatewaySessions = mock(FixGatewaySessions.class);
    private final FixGatewaySession gatewaySession = mock(FixGatewaySession.class);
    private final InternalSession session = mock(InternalSession.class);
    private final Subscription outboundLibrarySubscription = mock(Subscription.class);
    private final Image replayImage = mock(Image.class);
    private final Image normalImage = mock(Image.class);
    private final CompositeKey sessionKey = SessionIdStrategy
        .senderAndTarget()
        .onInitiateLogon("local", "", "", "remote", "", "");
    private final FixDictionary fixDictionary = FixDictionary.of(FixDictionary.findDefault());

    private final FinalImagePositions finalImagePositions = mock(FinalImagePositions.class);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<List<ConnectedSessionInfo>> sessionCaptor = ArgumentCaptor.forClass(List.class);

    private final EngineConfiguration engineConfiguration = new EngineConfiguration()
        .bindTo(FRAMER_ADDRESS.getHostName(), FRAMER_ADDRESS.getPort())
        .replyTimeoutInMs(REPLY_TIMEOUT_IN_MS)
        .libraryAeronChannel(IPC_CHANNEL)
        .conclude();

    private Framer framer;

    private final MutableLong connectionId = new MutableLong(NO_CONNECTION_ID);
    private final ErrorHandler errorHandler = mock(ErrorHandler.class);
    private final LivenessDetector livenessDetector = mock(LivenessDetector.class);

    private final LiveLibraryInfo libraryInfo = new LiveLibraryInfo(
        errorHandler,
        LIBRARY_ID, LIBRARY_NAME, livenessDetector, 1,
        false);

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws IOException
    {
        server = ServerSocketChannel.open().bind(TEST_ADDRESS);
        server.configureBlocking(false);

        clientBuffer.putInt(10, 5);

        when(fixCounters.getFramerDutyCycleTracker(anyLong())).thenReturn(mock(DutyCycleTracker.class));
        when(fixCounters.getIndexerDutyCycleTracker(anyLong())).thenReturn(mock(DutyCycleTracker.class));

        when(outboundLibrarySubscription.imageBySessionId(anyInt())).thenReturn(normalImage);

        when(mockEndPointFactory.receiverEndPoint(
            any(), anyLong(), anyLong(), anyInt(), anyInt(), any()))
            .thenAnswer((Answer<FixReceiverEndPoint>)invocationOnMock ->
            {
                connectionId.set(invocationOnMock.getArgument(1));
                return mockReceiverEndPoint;
            });

        when(mockEndPointFactory.senderEndPoint(any(), anyLong(), anyInt(), any(), any()))
            .thenReturn(mockSenderEndPoint);

        when(mockReceiverEndPoint.connectionId()).then((inv) -> connectionId.get());

        when(mockSenderEndPoint.connectionId()).then((inv) -> connectionId.get());

        when(gatewaySession.session()).thenReturn(session);
        when(gatewaySession.fixDictionary()).thenReturn(fixDictionary);
        when(gatewaySession.isOffline()).thenReturn(false);

        when(session.lastLogonTimeInNs()).thenReturn(-1L);
        when(session.compositeKey()).thenReturn(sessionKey);

        framer = new Framer(
            mockClock,
            mock(Timer.class),
            mock(Timer.class),
            engineConfiguration,
            mock(Subscription.class),
            mock(AdminReplyPublication.class),
            mockEndPointFactory,
            outboundLibrarySubscription,
            replayImage,
            replayQuery,
            mock(GatewayPublication.class),
            inboundPublication,
            mock(QueuedPipe.class),
            mockSessionIdStrategy,
            fixContexts,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex,
            gatewaySessions,
            errorHandler,
            DEFAULT_NAME_PREFIX,
            mock(CompletionPosition.class),
            mock(CompletionPosition.class),
            finalImagePositions,
            mock(RecordingCoordinator.class),
            mock(FixPContexts.class),
            mock(CountersReader.class),
            2,
            1,
            fixCounters,
            mock(SenderSequenceNumbers.class),
            mock(AgentInvoker.class),
            null);

        when(fixContexts.onLogon(any(), any(fixDictionary.getClass()))).thenReturn(new SessionContext(
            sessionKey,
            SESSION_ID,
            SessionInfo.UNKNOWN_SEQUENCE_INDEX,
            Session.UNKNOWN_TIME,
            System.currentTimeMillis(),
            fixContexts,
            0,
            EngineConfiguration.DEFAULT_INITIAL_SEQUENCE_INDEX,
            fixDictionary, false));
    }

    @AfterEach
    public void tearDown() throws IOException
    {
        framer.onClose();
        server.close();
        if (client != null)
        {
            client.close();
        }
        engineConfiguration.close();

        Mockito.framework().clearInlineMocks();
        CloseChecker.validateAll();
    }

    @Test
    public void shouldListenOnSpecifiedPort() throws IOException
    {
        aClientConnects();

        assertTrue(client.finishConnect(), "Client has failed to connect");
    }

    @Test
    public void shouldCreateEndPointWhenClientConnects() throws Exception
    {
        aClientConnects();

        awaitEndpointCreation();
    }

    private void awaitEndpointCreation()
    {
        Timing.assertEventuallyTrue(
            "endpoints never created",
            () ->
            {
                framer.doWork();

                verifyEndpointsCreated();
            });
    }

    @Test
    public void shouldPassDataToEndPointWhenSent() throws Exception
    {
        aClientConnects();
        framer.doWork();

        aClientSendsData();

        assertEventuallyTrue("Receiver end point never polled",
            () ->
            {
                doWork();
                verify(mockReceiverEndPoint).poll();
            });
    }

    @Test
    public void shouldCloseSocketUponDisconnect() throws Exception
    {
        aClientConnects();
        framer.doWork();

        framer.onDisconnect(LIBRARY_ID, connectionId.get(), APPLICATION_DISCONNECT);
        framer.doWork();

        verifyEndPointsDisconnected(APPLICATION_DISCONNECT);
    }

    @Test
    public void shouldConnectToAddress() throws Exception
    {
        initiateConnection();
    }

    @Test
    public void shouldNotConnectIfLibraryUnknown() throws Exception
    {
        onInitiateConnection();

        framer.doWork();

        assertNull(server.accept(), "Sender has connected to server");
        assertEquals(NO_CONNECTION_ID, connectionId.get());
        verifyErrorPublished(UNKNOWN_LIBRARY);
    }

    @Test
    public void shouldNotifyLibraryOfInitiatedConnection() throws Exception
    {
        initiateConnection();

        framer.doWork();

        notifyLibraryOfConnection();
    }

    @Test
    public void shouldReplyWithSocketConnectionError() throws Exception
    {
        server.close();

        libraryConnects();

        assertEquals(CONTINUE, onInitiateConnection());

        assertEventuallyTrue("Never sends UNABLE_TO_CONNECT message",
            () ->
            {
                doWork();
                verifyErrorPublished(UNABLE_TO_CONNECT);
            });
    }

    private void doWork()
    {
        try
        {
            framer.doWork();
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    @Test
    public void shouldIdentifyDuplicateInitiatedSessions() throws Exception
    {
        initiateConnection();

        framer.doWork();

        notifyLibraryOfConnection();

        when(fixContexts.onLogon(any(), any(fixDictionary.getClass())))
            .thenReturn(FixContexts.DUPLICATE_SESSION);

        // Don't wait for connection of duplicated session because it should not connect.
        libraryConnects();
        assertEquals(CONTINUE, onInitiateConnection());

        verifyErrorPublished(DUPLICATE_SESSION);
    }

    @Test
    public void shouldAcquireInitiatedClientsWhenLibraryDisconnects() throws Exception
    {
        initiateConnection();

        timeoutLibrary();

        framer.doWork();

        verifySessionsAcquired(ACTIVE);
        verifyLibraryTimeout();
    }

    @Test
    public void shouldAcquireAcceptedClientsWhenLibraryDisconnects() throws Exception
    {
        libraryHasAcceptedClient();

        timeoutLibrary();

        framer.doWork();

        verifySessionsAcquired(ACTIVE);
        verifyLibraryTimeout();
    }

    @Test
    public void shouldAcquireAcceptedClientsWhenLibraryDisconnectsAndIndexerCaughtUp() throws Exception
    {
        sentIndexedToPosition(-100L);

        libraryHasAcceptedClient();

        timeoutLibrary();

        framer.doWork();

        verifySessionsAcquired(ACTIVE, never());

        sentIndexedToPosition(100L);

        framer.doWork();

        verifySessionsAcquired(ACTIVE);

        removesPosition();
    }

    private void removesPosition()
    {
        verify(finalImagePositions).removePosition(anyInt());
    }

    @Test
    public void shouldRetryNotifyingLibraryOfInitiateWhenBackPressured() throws Exception
    {
        backPressureFirstSaveAttempts();

        libraryConnects();

        assertEquals(CONTINUE, onInitiateConnection());

        // Requires 4 steps to complete
        framer.doWork();
        framer.doWork();
        framer.doWork();
        framer.doWork();

        notifyLibraryOfConnection(times(2));
    }

    @Test
    public void shouldWaitForSequenceNumberIndexingProcessToUpdate() throws Exception
    {
        setupHeader();

        sentIndexedToPosition(0, POSITION + 1);

        libraryConnects();

        initiateConnection();

        framer.doWork();
        framer.doWork();

        notifyLibraryOfConnection(times(1));
    }

    @Test
    public void shouldNotHandoverInitiatedConnectionDisconnectedWhilstWaitingForIndexer() throws Exception
    {
        setupHeader();

        sentIndexedToPosition(0);

        initiateConnection();

        framer.doWork();
        framer.doWork();

        when(mockReceiverEndPoint.hasDisconnected()).thenReturn(true);
        assertEquals(CONTINUE, framer.onDisconnect(LIBRARY_ID, connectionId.get(), REMOTE_DISCONNECT));

        sentIndexedToPosition(POSITION + 1);

        framer.doWork();
        framer.doWork();

        notifyLibraryOfConnection(never());
        verifyErrorPublished(UNABLE_TO_CONNECT);
    }

    private void setupHeader()
    {
        when(header.sessionId()).thenReturn(AERON_SESSION_ID);
        when(header.position()).thenReturn(POSITION);
    }

    @Test
    public void shouldManageGatewaySessions() throws Exception
    {
        openSocket();

        awaitEndpointCreation();

        framer.onFixLogonMessageReceived(gatewaySession, SESSION_ID);

        verifySessionsAcquired(CONNECTED);
    }

    @Test
    public void shouldNotifyLibraryOfAuthenticatedGatewaySessions() throws Exception
    {
        shouldManageGatewaySessions();

        givenAGatewayToManage();

        libraryConnects();

        verifySessionExistsSaved(times(1), SessionStatus.LIBRARY_NOTIFICATION);
    }

    @Test
    public void shouldRetryNotifyingLibraryOfAuthenticatedGatewaySessionsWhenBackPressured() throws Exception
    {
        shouldManageGatewaySessions();

        givenAGatewayToManage();

        backPressureSaveSessionExists();

        final Action actual = onLibraryConnect();

        assertEquals(ABORT, actual);

        libraryConnects();

        verifySessionExistsSaved(times(2), SessionStatus.LIBRARY_NOTIFICATION);
    }

    @Test
    public void shouldAcquireInitiatedClientsUponReleased() throws Exception
    {
        initiateConnection();

        releaseConnection(CONTINUE);

        verifySessionsAcquired(ACTIVE);
    }

    @Test
    public void shouldRetryAcquiringInitiatedClientsUponReleasedWhenBackPressured() throws Exception
    {
        initiateConnection();

        when(inboundPublication.saveReleaseSessionReply(LIBRARY_ID, OK, CORR_ID))
            .thenReturn(BACK_PRESSURED, POSITION);

        releaseConnection(ABORT);

        releaseConnection(CONTINUE);

        verifySessionsAcquired(ACTIVE);
    }

    @Test
    public void shouldHandoverSessionToLibraryUponRequest() throws IOException
    {
        aClientConnects();

        handoverSessionToLibrary();
    }

    @Test
    public void shouldHandoverSessionToLibraryUponRequestWhenBackPressured() throws IOException
    {
        when(inboundPublication.saveManageSession(anyInt(),
            anyLong(),
            anyLong(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyLong(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyInt(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(DirectBuffer.class),
            any(),
            anyLong())).thenReturn(BACK_PRESSURED, POSITION);

        aClientConnects();

        sessionIsActive();

        assertEquals(CONTINUE, onRequestSession());

        doWork();

        doWork();

        verify(inboundPublication, times(2)).saveManageSession(eq(LIBRARY_ID),
            anyLong(),
            anyLong(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyLong(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyInt(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(DirectBuffer.class),
            any(),
            anyLong());
        saveRequestSessionReply();

        neverSavesUnknownSession();
    }

    private void neverSavesUnknownSession()
    {
        verify(inboundPublication, never())
            .saveRequestSessionReply(LIBRARY_ID, SessionReplyStatus.UNKNOWN_SESSION, CORR_ID);
    }

    @Test
    public void shouldNotifyLibraryOfControlledSessionsUponDuplicateConnect() throws IOException
    {
        aClientConnects();

        handoverSessionToLibrary();

        duplicateLibraryConnect();

        verifyLibraryControlNotified(Matchers.contains(gatewaySession));
    }

    @Test
    public void shouldNotifyLibraryOnlyOfControlledSessionsUponDuplicateConnect() throws IOException
    {
        aClientConnects();

        duplicateLibraryConnect();

        verifyLibraryControlNotified(hasSize(0));
    }

    @Test
    public void shouldNotifyLibraryOfControlledSessionsUponDuplicateConnectAfterTimeout() throws Exception
    {
        aClientConnects();

        handoverSessionToLibrary();

        timeoutLibrary();

        framer.doWork();

        reset(inboundPublication);

        duplicateLibraryConnect();

        saveControlNotification(times(1));
    }

    private void duplicateLibraryConnect()
    {
        framer.onLibraryConnect(LIBRARY_ID, LIBRARY_NAME, CORR_ID + 1, AERON_SESSION_ID);
    }

    private void verifyLibraryControlNotified(final Matcher<? super Collection<?>> sessionMatcher)
    {
        verify(inboundPublication).saveApplicationHeartbeat(eq(LIBRARY_ID), anyLong());
        saveControlNotification(times(2));

        final List<ConnectedSessionInfo> sessions = sessionCaptor.getValue();
        assertThat(sessions, sessionMatcher);
    }

    private void saveControlNotification(final VerificationMode times)
    {
        verify(inboundPublication, times).saveControlNotification(eq(LIBRARY_ID), any(), sessionCaptor.capture(),
            any());
    }

    private void handoverSessionToLibrary()
    {
        sessionIsActive();

        assertEquals(CONTINUE, onRequestSession());

        doWork();

        saveRequestSessionReply();
    }

    private void saveRequestSessionReply()
    {
        verify(inboundPublication).saveRequestSessionReply(LIBRARY_ID, OK, CORR_ID);
    }

    private Action onRequestSession()
    {
        return framer.onRequestSession(LIBRARY_ID, SESSION_ID, CORR_ID, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY);
    }

    private void sessionIsActive()
    {
        when(gatewaySessions.releaseBySessionId(SESSION_ID)).thenReturn(gatewaySession, (GatewaySession)null);
        when(gatewaySession.session()).thenReturn(session);
        when(gatewaySession.heartbeatIntervalInS()).thenReturn(HEARTBEAT_INTERVAL_IN_S);
        when(session.isActive()).thenReturn(true);
    }

    private void verifyErrorPublished(final GatewayError error)
    {
        verify(inboundPublication).saveError(eq(error), eq(LIBRARY_ID), anyLong(), anyString());
    }

    private void releaseConnection(final Action expectedResult)
    {
        assertEquals(expectedResult, framer.onReleaseSession(
            LIBRARY_ID,
            connectionId.get(),
            SESSION_ID,
            CORR_ID,
            ACTIVE,
            false,
            HEARTBEAT_INTERVAL_IN_MS,
            0,
            0,
            "",
            "", header));
    }

    private Action onLibraryConnect()
    {
        return framer.onLibraryConnect(LIBRARY_ID, LIBRARY_NAME, CORR_ID, AERON_SESSION_ID);
    }

    private void givenAGatewayToManage()
    {
        when(gatewaySession.connectionId()).thenReturn(connectionId.get());
        when(gatewaySession.sessionKey()).thenReturn(mock(CompositeKey.class));
        when(gatewaySessions.sessions()).thenReturn(singletonList(gatewaySession));
    }

    private void backPressureFirstSaveAttempts()
    {
        backPressureSaveSessionExists();
    }

    private void backPressureSaveSessionExists()
    {
        when(inboundPublication.saveManageSession(eq(LIBRARY_ID),
            anyLong(),
            anyLong(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyLong(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyInt(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(DirectBuffer.class),
            any(),
            anyLong())).thenReturn(BACK_PRESSURED, POSITION);
    }

    private void verifySessionsAcquired(final SessionState state)
    {
        verifySessionsAcquired(state, times(1));
    }

    private void verifySessionsAcquired(final SessionState state, final VerificationMode times)
    {
        verify(gatewaySessions, times).acquire(
            any(),
            eq(state),
            eq(false),
            eq(HEARTBEAT_INTERVAL_IN_S),
            anyInt(),
            anyInt(),
            any(),
            any());
    }

    private void verifyEndPointsDisconnected(final DisconnectReason reason)
    {
        verify(mockReceiverEndPoint).close(reason);
        verify(mockSenderEndPoint).close();
    }

    private void timeoutLibrary()
    {
        mockClock.advanceMilliSeconds(REPLY_TIMEOUT_IN_MS * 2);
    }

    private void libraryConnects()
    {
        when(outboundLibrarySubscription.imageBySessionId(anyInt())).thenReturn(mock(Image.class));
        assertEquals(CONTINUE, onLibraryConnect());
    }

    private void initiateConnection() throws Exception
    {
        libraryConnects();

        assertEquals(CONTINUE, onInitiateConnection());

        while (NO_CONNECTION_ID == connectionId.get())
        {
            framer.doWork();
        }
    }

    private Action onInitiateConnection()
    {
        return framer.onInitiateConnection(
            LIBRARY_ID,
            TEST_ADDRESS.getPort(),
            TEST_ADDRESS.getHostName(),
            "LEH_LZJ02",
            null,
            null,
            "CCG",
            null,
            null,
            TRANSIENT,
            AUTOMATIC_INITIAL_SEQUENCE_NUMBER,
            AUTOMATIC_INITIAL_SEQUENCE_NUMBER,
            false,
            DEFAULT_CLOSED_RESEND_INTERVAL,
            NO_RESEND_REQUEST_CHUNK_SIZE,
            DEFAULT_SEND_REDUNDANT_RESEND_REQUESTS,
            false,
            "",
            "",
            FixDictionary.findDefault(),
            HEARTBEAT_INTERVAL_IN_S,
            CORR_ID,
            header);
    }

    private void aClientConnects() throws IOException
    {
        libraryConnects();

        openSocket();
    }

    private void openSocket() throws IOException
    {
        client = SocketChannel.open(FRAMER_ADDRESS);
    }

    private void notifyLibraryOfConnection()
    {
        notifyLibraryOfConnection(times(1));
    }

    private void notifyLibraryOfConnection(final VerificationMode times)
    {
        verify(inboundPublication, times).saveManageSession(eq(LIBRARY_ID),
            eq(connectionId.get()),
            anyLong(),
            anyInt(),
            anyInt(),
            eq(SessionStatus.SESSION_HANDOVER),
            eq(SlowStatus.NOT_SLOW),
            eq(INITIATOR),
            any(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyLong(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyInt(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(DirectBuffer.class),
            any(),
            anyLong());
    }

    private void verifySessionExistsSaved(final VerificationMode times, final SessionStatus status)
    {
        verify(inboundPublication, times).saveManageSession(eq(LIBRARY_ID),
            eq(connectionId.get()),
            anyLong(),
            anyInt(),
            anyInt(),
            eq(status),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyBoolean(),
            anyBoolean(),
            anyLong(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyInt(),
            anyBoolean(),
            anyInt(),
            anyInt(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(DirectBuffer.class),
            any(),
            anyLong());
    }

    private void aClientSendsData() throws IOException
    {
        clientBuffer.position(0);
        assertEquals(clientBuffer.remaining(), client.write(clientBuffer), "Has written bytes");
    }

    private void verifyEndpointsCreated()
    {
        verify(mockEndPointFactory).receiverEndPoint(
            notNull(), anyLong(), anyLong(), anyInt(), eq(ENGINE_LIBRARY_ID), eq(framer));

        verify(mockEndPointFactory).senderEndPoint(
            notNull(), anyLong(), eq(ENGINE_LIBRARY_ID), eq(framer), any());
    }

    private void verifyLibraryTimeout()
    {
        verify(inboundPublication).saveLibraryTimeout(libraryInfo, 0);
    }

    private void libraryHasAcceptedClient() throws IOException
    {
        aClientConnects();
        sessionIsActive();
        assertEquals(CONTINUE, onRequestSession());
        when(receivedSequenceNumberIndex.lastKnownSequenceNumber(anyInt())).thenReturn(1);
    }

    private void sentIndexedToPosition(final long position, final Long... positions)
    {
        when(sentSequenceNumberIndex.indexedPosition(anyInt())).thenReturn(position, positions);
    }
}
