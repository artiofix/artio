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

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.Timing;
import uk.co.real_logic.artio.builder.HeartbeatEncoder;
import uk.co.real_logic.artio.dictionary.LongDictionary;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.MetaDataStatus;
import uk.co.real_logic.artio.otf.OtfParser;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.*;
import java.util.function.BiConsumer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;

public class FakeHandler
    implements SessionHandler, SessionAcquireHandler, SessionExistsHandler
{
    public static final String SESSION_START_ERROR = "Unexpected Error";

    private final OtfParser parser;
    private final FakeOtfAcceptor acceptor;

    private final List<Session> sessions = new ArrayList<>();
    private final Set<Session> slowSessions = new HashSet<>();
    private final Deque<SessionExistsInfo> sessionExistsInfos = new ArrayDeque<>();

    private Session lastSession;
    private boolean hasDisconnected = false;
    private boolean lastSessionWasSlow;
    private UnsafeBuffer lastSessionMetaData;
    private MetaDataStatus lastSessionMetaDataStatus;

    private final ExpandableArrayBuffer lastMessageBuffer = new ExpandableArrayBuffer();
    private final MutableAsciiBuffer lastMessage = new MutableAsciiBuffer();
    private final HeartbeatEncoder heartbeatEncoder = new HeartbeatEncoder();

    private boolean throwInOnSessionStart = false;
    private boolean onSessionStartCalled = false;

    {
        heartbeatEncoder.testReqID("abc");
    }

    private int lastMessageLength = 0;

    private boolean hasTimedOut = false;
    private int timedOutLibraryId;
    private Session timedOutSession;
    private boolean spamLogonMessages = false;
    private BiConsumer<Session, DisconnectReason> onDisconnectCallback;
    private BiConsumer<Session, FixMessage> onMessageCallback;

    public FakeHandler(final FakeOtfAcceptor acceptor)
    {
        this.acceptor = acceptor;
        parser = new OtfParser(acceptor, new LongDictionary());
    }

    private boolean copyMessages = false;

    public void copyMessages(final boolean copyMessages)
    {
        this.copyMessages = copyMessages;
    }

    public int lastMessageLength()
    {
        return lastMessageLength;
    }

    public MutableAsciiBuffer lastMessage()
    {
        return lastMessage;
    }

    public void spamLogonMessages()
    {
        spamLogonMessages = true;
    }

    public void onDisconnectCallback(final BiConsumer<Session, DisconnectReason> onDisconnectCallback)
    {
        this.onDisconnectCallback = onDisconnectCallback;
    }

    public void onMessageCallback(final BiConsumer<Session, FixMessage> onMessageCallback)
    {
        this.onMessageCallback = onMessageCallback;
    }

    // ----------- EVENTS -----------

    @Override
    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final Session session,
        final int sequenceIndex,
        final long messageType,
        final long timestampInNs,
        final long position,
        final OnMessageInfo messageInfo)
    {
        parser.onMessage(buffer, offset, length);
        final FixMessage parsedMessage = acceptor.lastReceivedMessage();
        parsedMessage.sequenceIndex(sequenceIndex);
        parsedMessage.messageType(messageType);
        parsedMessage.status(messageInfo.status());
        parsedMessage.isValid(messageInfo.isValid());
        acceptor.forSession(session);

        if (copyMessages)
        {
            lastMessageBuffer.putBytes(0, buffer, offset, length);
            lastMessage.wrap(lastMessageBuffer);
            lastMessageLength = length;
        }

        if (onMessageCallback != null)
        {
            onMessageCallback.accept(session, parsedMessage);
        }

        return CONTINUE;
    }

    @Override
    public void onTimeout(final int libraryId, final Session session)
    {
        hasTimedOut = true;
        timedOutLibraryId = libraryId;
        timedOutSession = session;
    }

    public boolean hasTimedOut()
    {
        return hasTimedOut;
    }

    public int timedOutLibraryId()
    {
        return timedOutLibraryId;
    }

    public Session timedOutSession()
    {
        return timedOutSession;
    }

    public void resetTimedOut()
    {
        hasTimedOut = false;
        timedOutLibraryId = ENGINE_LIBRARY_ID;
        timedOutSession = null;
    }

    @Override
    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
    {
        if (hasBecomeSlow)
        {
            slowSessions.add(session);
        }
        else
        {
            slowSessions.remove(session);
        }
    }

    @Override
    public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
    {
        sessions.remove(session);
        hasDisconnected = true;
        if (onDisconnectCallback != null)
        {
            onDisconnectCallback.accept(session, reason);
        }
        return CONTINUE;
    }

    @Override
    public void onSessionStart(final Session session)
    {
        onSessionStartCalled = true;

        if (throwInOnSessionStart)
        {
            throw new RuntimeException(SESSION_START_ERROR);
        }
    }

    @Override
    public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo acquiredInfo)
    {
        if (spamLogonMessages && session.isActive())
        {
            for (int i = 0; i < 1000; i++)
            {
                while (session.trySend(heartbeatEncoder) < 0)
                {
                    Thread.yield();
                }
            }
        }

        assertNotEquals(Session.UNKNOWN, session.id());
        sessions.add(session);
        this.lastSession = session;
        this.lastSessionWasSlow = acquiredInfo.isSlow();

        final DirectBuffer metaDataBuffer = acquiredInfo.metaDataBuffer();
        this.lastSessionMetaDataStatus = acquiredInfo.metaDataStatus();
        this.lastSessionMetaData = new UnsafeBuffer(new byte[metaDataBuffer.capacity()]);
        this.lastSessionMetaData.putBytes(0, metaDataBuffer, 0, metaDataBuffer.capacity());
        return this;
    }

    @Override
    public void onSessionExists(
        final FixLibrary library,
        final long sessionId,
        final String localCompId,
        final String localSubId,
        final String localLocationId,
        final String remoteCompId,
        final String remoteSubId,
        final String remoteLocationId,
        final int logonReceivedSequenceNumber,
        final int logonSequenceIndex)
    {
        if (spamLogonMessages)
        {
            library.requestSession(sessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, 10_000);
        }

        sessionExistsInfos.add(new SessionExistsInfo(
            localCompId, remoteCompId, sessionId, logonReceivedSequenceNumber, logonSequenceIndex));
    }

    // ----------- END EVENTS -----------

    public void resetSession()
    {
        lastSession = null;
    }

    public List<Session> sessions()
    {
        return sessions;
    }

    public boolean hasDisconnected()
    {
        return hasDisconnected;
    }

    public long awaitSessionId(final Runnable poller)
    {
        return awaitCompleteSessionId(poller).surrogateId();
    }

    public SessionExistsInfo awaitCompleteSessionId(final Runnable poller)
    {
        Timing.assertEventuallyTrue(
            "Couldn't find session Id",
            () ->
            {
                poller.run();
                return hasSeenSession();
            });

        return lastSessionExistsInfo();
    }

    public boolean hasSeenSession()
    {
        return !sessionExistsInfos.isEmpty();
    }

    public void clearSessionExistsInfos()
    {
        sessionExistsInfos.clear();
    }

    public SessionExistsInfo lastSessionExists()
    {
        return sessionExistsInfos.pollFirst();
    }

    long awaitSessionIdFor(
        final String initiatorId,
        final String acceptorId,
        final Runnable poller,
        final int timeoutInMs)
    {
        return Timing.withTimeout(
            "Unable to get session id for: " + initiatorId + " - " + acceptorId,
            () ->
            {
                poller.run();

                return sessionExistsInfos
                    .stream()
                    .filter((sid) ->
                        sid.remoteCompId().equals(initiatorId) && sid.localCompId().equals(acceptorId))
                    .findFirst();
            },
            timeoutInMs).surrogateId();
    }

    public String lastAcceptorCompId()
    {
        return lastSessionExistsInfo().localCompId();
    }

    public String lastInitiatorCompId()
    {
        return lastSessionExistsInfo().remoteCompId();
    }

    public int lastLogonReceivedSequenceNumber()
    {
        return lastSessionExistsInfo().logonReceivedSequenceNumber();
    }

    public int lastLogonSequenceIndex()
    {
        return lastSessionExistsInfo().logonSequenceIndex();
    }

    public Session lastSession()
    {
        return lastSession;
    }

    private SessionExistsInfo lastSessionExistsInfo()
    {
        return sessionExistsInfos.peekLast();
    }

    public boolean isSlow(final Session session)
    {
        return slowSessions.contains(session);
    }

    public boolean lastSessionWasSlow()
    {
        return lastSessionWasSlow;
    }

    public DirectBuffer lastSessionMetaData()
    {
        return lastSessionMetaData;
    }

    public MetaDataStatus lastSessionMetaDataStatus()
    {
        return lastSessionMetaDataStatus;
    }

    public void shouldThrowInOnSessionStart(final boolean throwInOnSessionStart)
    {
        this.throwInOnSessionStart = throwInOnSessionStart;
    }

    public boolean onSessionStartCalled()
    {
        return onSessionStartCalled;
    }
}
