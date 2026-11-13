/*
 * Copyright 2015-2020 Real Logic Limited.
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

import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.session.*;
import uk.co.real_logic.artio.util.AsciiBuffer;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static uk.co.real_logic.artio.GatewayProcess.NO_CONNECTION_ID;
import static uk.co.real_logic.artio.LogTag.FIX_MESSAGE;
import static uk.co.real_logic.artio.LogTag.GATEWAY_MESSAGE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexWriter.NO_REQUIRED_POSITION;
import static uk.co.real_logic.artio.messages.DisconnectReason.ENGINE_SHUTDOWN;

class FixGatewaySession extends GatewaySession implements ConnectedSessionInfo, FixSessionOwner
{
    private final boolean closedResendInterval;
    private final int resendRequestChunkSize;
    private final boolean sendRedundantResendRequests;
    private final boolean enableLastMsgSeqNumProcessed;
    private final EngineConfiguration configuration;

    private FixDictionary fixDictionary;
    // receiverEndPoint & senderEndPoint null iff session is offline.
    private FixReceiverEndPoint receiverEndPoint;
    private FixSenderEndPoint senderEndPoint;

    private SessionContext context;
    private SessionParser sessionParser;
    private InternalSession session;
    private DirectSessionProxy proxy;
    private CompositeKey sessionKey;
    private String username;
    private String password;
    private int heartbeatIntervalInS;

    private Consumer<FixGatewaySession> onGatewaySessionLogon;
    private boolean initialResetSeqNum;
    private int logonReceivedSequenceNumber;
    private int logonSequenceIndex;
    // lastLogonTime is set when the logon message is processed
    // when we process the logon, the lastSequenceResetTime is set if it does reset the sequence.
    // Otherwise this is updated when we handover the session.
    private long lastSequenceResetTime = Session.UNKNOWN_TIME;
    private long lastLogonTime = Session.UNKNOWN_TIME;
    private CancelOnDisconnectOption cancelOnDisconnectOption;
    private long cancelOnDisconnectTimeoutWindowInNs;

    FixGatewaySession(
        final long connectionId,
        final SessionContext context,
        final String address,
        final ConnectionType connectionType,
        final CompositeKey sessionKey,
        final FixReceiverEndPoint receiverEndPoint,
        final FixSenderEndPoint senderEndPoint,
        final Consumer<FixGatewaySession> onGatewaySessionLogon,
        final boolean closedResendInterval,
        final int resendRequestChunkSize,
        final boolean sendRedundantResendRequests,
        final boolean enableLastMsgSeqNumProcessed,
        final FixDictionary fixDictionary,
        final EngineConfiguration configuration)
    {
        super(connectionId, context.sessionId(), address, connectionType, configuration.authenticationTimeoutInMs(),
            receiverEndPoint);
        this.context = context;
        this.sessionKey = sessionKey;
        this.receiverEndPoint = receiverEndPoint;
        this.senderEndPoint = senderEndPoint;
        this.onGatewaySessionLogon = onGatewaySessionLogon;
        this.closedResendInterval = closedResendInterval;
        this.resendRequestChunkSize = resendRequestChunkSize;
        this.sendRedundantResendRequests = sendRedundantResendRequests;
        this.enableLastMsgSeqNumProcessed = enableLastMsgSeqNumProcessed;
        this.configuration = configuration;
        fixDictionary(fixDictionary);
    }

    @Override
    public String address()
    {
        if (receiverEndPoint != null)
        {
            return receiverEndPoint.address();
        }

        return address;
    }

    @Override
    public CompositeKey sessionKey()
    {
        return sessionKey;
    }

    void manage(
        final SessionParser sessionParser,
        final InternalSession session,
        final DirectSessionProxy proxy)
    {
        lastLibraryId = libraryId;
        libraryId = ENGINE_LIBRARY_ID;
        this.sessionParser = sessionParser;
        this.session = session;
        this.proxy = proxy;
        this.session.sessionProcessHandler(this);
        if (receiverEndPoint != null)
        {
            receiverEndPoint.libraryId(ENGINE_LIBRARY_ID);
            senderEndPoint.libraryId(ENGINE_LIBRARY_ID);
        }
    }

    // sets management to a library and also cleans up locally associated session.
    void handoverManagementTo(
        final int libraryId)
    {
        setManagementTo(libraryId);

        sessionParser = null;
        context.updateAndSaveFrom(session);
        session.close();
        session = null;
        proxy = null;
    }

    void setManagementTo(final int libraryId)
    {
        libraryId(libraryId);
        if (receiverEndPoint != null)
        {
            receiverEndPoint.libraryId(libraryId);
            receiverEndPoint.pause();
            senderEndPoint.libraryId(libraryId);
        }
    }

    void play()
    {
        if (receiverEndPoint != null)
        {
            receiverEndPoint.play();
        }
    }

    @Override
    int poll(final long timeInMs, final long timeInNs)
    {
        final int events = session != null ? session.poll(timeInNs) : 0;
        return events + checkNoLogonDisconnect(timeInMs);
    }

    @Override
    public void onLogon(final Session session)
    {
        context.updateFrom(session);
        onGatewaySessionLogon.accept(this);
    }

    @Override
    public Reply<ReplayMessagesStatus> replayReceivedMessages(
        final long sessionId,
        final int replayFromSequenceNumber,
        final int replayFromSequenceIndex,
        final int replayToSequenceNumber,
        final int replayToSequenceIndex,
        final long timeout)
    {
        return unsupported();
    }

    @Override
    public void enqueueTask(final BooleanSupplier task)
    {
        unsupported();
    }

    @Override
    public Reply<ThrottleConfigurationStatus> messageThrottle(
        final long sessionId, final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        return unsupported();
    }

    @Override
    public long inboundMessagePosition()
    {
        return NO_REQUIRED_POSITION;
    }

    private <T> T unsupported()
    {
        throw new UnsupportedOperationException("Should never be invoked inside the Engine.");
    }

    InternalSession session()
    {
        return session;
    }

    public void onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long messageType,
        final long position)
    {
        if (sessionParser != null)
        {
            DebugLogger.logFixMessage(FIX_MESSAGE, messageType, "Gateway Received ", buffer, offset, length);

            session.messageInfo().isValid(true);

            sessionParser.onMessage(buffer, offset, length, messageType, position);
        }
    }

    void onLogon(
        final String username,
        final String password,
        final int heartbeatIntervalInS)
    {
        this.username = username;
        this.password = password;
        this.heartbeatIntervalInS = heartbeatIntervalInS;
        if (session != null)
        {
            session.setupSession(sessionId, sessionKey, null);
            sessionParser.sessionKey(sessionKey);
            sessionParser.sequenceIndex(context.sequenceIndex());
            DebugLogger.log(GATEWAY_MESSAGE, "Setup Session As: ", sessionKey.localCompId());
        }
        if (senderEndPoint != null)
        {
            senderEndPoint.sessionId(sessionId);
        }
    }

    public void onLogon(
        final long sessionId,
        final SessionContext context,
        final CompositeKey sessionKey,
        final String username,
        final String password,
        final int heartbeatIntervalInS,
        final int logonReceivedSequenceNumber,
        final CancelOnDisconnectOption cancelOnDisconnectOption,
        final long cancelOnDisconnectTimeoutWindowInNs)
    {
        this.sessionId = sessionId;
        this.context = context;
        this.sessionKey = sessionKey;
        this.logonReceivedSequenceNumber = logonReceivedSequenceNumber;
        this.logonSequenceIndex = context.sequenceIndex();
        this.cancelOnDisconnectOption = cancelOnDisconnectOption;
        this.cancelOnDisconnectTimeoutWindowInNs = cancelOnDisconnectTimeoutWindowInNs;

        senderEndPoint.onLogon(sessionKey, configuration);

        onLogon(username, password, heartbeatIntervalInS);
    }

    public String username()
    {
        return username;
    }

    public String password()
    {
        return password;
    }

    int heartbeatIntervalInS()
    {
        return heartbeatIntervalInS;
    }

    @Override
    void acceptorSequenceNumbers(final int retrievedSentSequenceNumber, final int retrievedReceivedSequenceNumber)
    {
        if (session != null)
        {
            session.lastSentMsgSeqNum(adjustLastSequenceNumber(retrievedSentSequenceNumber));
            final int lastReceivedMsgSeqNum = adjustLastSequenceNumber(retrievedReceivedSequenceNumber);
            session.initialLastReceivedMsgSeqNum(lastReceivedMsgSeqNum);
        }
    }

    void lastLogonWasSequenceReset()
    {
        lastSequenceResetTime(lastLogonTime);
    }

    static int adjustLastSequenceNumber(final int lastSequenceNumber)
    {
        return (lastSequenceNumber == UNK_SESSION) ? 0 : lastSequenceNumber;
    }

    @Override
    public String toString()
    {
        return "GatewaySession{" +
            "sessionId=" + sessionId +
            ", sessionKey=" + sessionKey +
            '}';
    }

    @Override
    public long bytesInBuffer()
    {
        return senderEndPoint.bytesInBuffer();
    }

    @Override
    void close()
    {
        CloseHelper.close(session);
    }

    @Override
    public int sequenceIndex()
    {
        return context.sequenceIndex();
    }

    SlowStatus slowStatus()
    {
        if (isOffline())
        {
            return SlowStatus.NOT_SLOW;
        }

        return senderEndPoint.isSlowConsumer() ? SlowStatus.SLOW : SlowStatus.NOT_SLOW;
    }

    public boolean closedResendInterval()
    {
        return closedResendInterval;
    }

    public int resendRequestChunkSize()
    {
        return resendRequestChunkSize;
    }

    public boolean sendRedundantResendRequests()
    {
        return sendRedundantResendRequests;
    }

    public boolean enableLastMsgSeqNumProcessed()
    {
        return enableLastMsgSeqNumProcessed;
    }

    public SessionContext context()
    {
        return context;
    }

    void initialResetSeqNum(final boolean resetSeqNum)
    {
        initialResetSeqNum = resetSeqNum;
    }

    boolean initialResetSeqNum()
    {
        return initialResetSeqNum;
    }

    FixDictionary fixDictionary()
    {
        return fixDictionary;
    }

    void fixDictionary(final FixDictionary fixDictionary)
    {
        this.fixDictionary = fixDictionary;
        if (senderEndPoint != null)
        {
            senderEndPoint.fixDictionary(fixDictionary);
        }
    }

    public int logonReceivedSequenceNumber()
    {
        return logonReceivedSequenceNumber;
    }

    public int logonSequenceIndex()
    {
        return logonSequenceIndex;
    }

    public CancelOnDisconnectOption cancelOnDisconnectOption()
    {
        return cancelOnDisconnectOption == null ? configuration.cancelOnDisconnectOption() : cancelOnDisconnectOption;
    }

    public long cancelOnDisconnectTimeoutWindowInNs()
    {
        return cancelOnDisconnectTimeoutWindowInNs;
    }

    void updateSessionDictionary()
    {
        if (session != null)
        {
            session.fixDictionary(fixDictionary);
            sessionParser.fixDictionary(fixDictionary);
        }
    }

    long lastSequenceResetTime()
    {
        return lastSequenceResetTime;
    }

    void lastSequenceResetTime(final long lastSequenceResetTime)
    {
        this.lastSequenceResetTime = lastSequenceResetTime;
        if (session != null)
        {
            session.lastSequenceResetTimeInNs(lastSequenceResetTime);
        }
    }

    @Override
    long lastLogonTime()
    {
        return lastLogonTime;
    }

    void lastLogonTime(final long lastLogonTime)
    {
        this.lastLogonTime = lastLogonTime;
        if (session != null)
        {
            session.lastLogonTimeInNs(lastLogonTime);
        }
    }

    public void goOffline()
    {
        // Library retains ownership of a disconnected session, reset state to that of an offline GatewaySession object
        connectionId = NO_CONNECTION_ID;
        address = ":" + NO_CONNECTION_ID;
        super.receiverEndPoint = null;
        receiverEndPoint = null;
        senderEndPoint = null;
        onGatewaySessionLogon = null;
    }

    public long lastSentPosition()
    {
        return proxy.lastSentPosition();
    }

    @Override
    public void onDisconnectReleasedByOwner()
    {
        if (session != null)
        {
            session.onDisconnect();
        }
    }

    public boolean onThrottleNotification(
        final long messageType,
        final int refSeqNum,
        final AsciiBuffer refIdBuffer,
        final int refIdOffset,
        final int refIdLength)
    {
        if (session != null)
        {
            return session.onThrottleNotification(
                messageType,
                refSeqNum,
                refIdBuffer,
                refIdOffset,
                refIdLength
            );
        }

        return true;
    }

    @Override
    public boolean configureThrottle(final int throttleWindowInMs, final int throttleLimitOfMessages)
    {
        final boolean ok = senderEndPoint.configureThrottle(throttleWindowInMs, throttleLimitOfMessages);
        if (ok)
        {
            receiverEndPoint.configureThrottle(throttleWindowInMs, throttleLimitOfMessages);
        }
        return ok;
    }

    public void onSequenceReset(final long resetTimeInNs)
    {
        context.onSequenceReset(resetTimeInNs);
    }

    @Override
    public long startEndOfDay()
    {
        final InternalSession session = this.session;
        if (session != null)
        {
            final SessionState state = session.state();
            switch (state)
            {
                case SENT_LOGON:
                case ACTIVE:
                case AWAITING_LOGOUT:
                case LOGGING_OUT_AND_DISCONNECTING:
                case LOGGING_OUT:
                {
                    final long position = session.logoutAndDisconnect(ENGINE_SHUTDOWN);
                    if (position < 0)
                    {
                        return position;
                    }

                    break;
                }

                case CONNECTED:
                case CONNECTING:
                case DISCONNECTING:
                {
                    final long position = session.requestDisconnect(ENGINE_SHUTDOWN);
                    if (position < 0)
                    {
                        return position;
                    }

                    break;
                }

                case DISCONNECTED:
                case DISABLED:
                default:
                    // deliberately blank
                    break;
            }
        }

        return 1;
    }
}
