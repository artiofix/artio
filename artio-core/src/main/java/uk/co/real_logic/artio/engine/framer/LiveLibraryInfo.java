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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.LivenessDetector;
import uk.co.real_logic.artio.engine.ConnectedSessionInfo;
import uk.co.real_logic.artio.engine.FixPConnectedSessionInfo;
import org.agrona.ErrorHandler;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

final class LiveLibraryInfo implements LibraryInfo
{
    private final ErrorHandler errorHandler;
    private final int libraryId;
    private final String libraryName;
    private final LivenessDetector livenessDetector;
    private final int aeronSessionId;
    private final List<GatewaySession> allSessions = new CopyOnWriteArrayList<>();
    private final List<ConnectedSessionInfo> unmodifiableFixSessions;
    private final List<FixPConnectedSessionInfo> unmodifiableFixPConnections;
    private final Long2ObjectHashMap<ConnectingSession> correlationIdToConnectingSession = new Long2ObjectHashMap<>();

    private long acquireAtPosition;

    @SuppressWarnings("unchecked")
    LiveLibraryInfo(
        final ErrorHandler errorHandler,
        final int libraryId,
        final String libraryName,
        final LivenessDetector livenessDetector,
        final int aeronSessionId,
        final boolean isFixP)
    {
        this.errorHandler = errorHandler;
        this.libraryId = libraryId;
        this.libraryName = libraryName;
        this.livenessDetector = livenessDetector;
        this.aeronSessionId = aeronSessionId;

        if (isFixP)
        {
            unmodifiableFixSessions = emptyList();
            unmodifiableFixPConnections = unmodifiableList(
                (List<? extends FixPConnectedSessionInfo>)(List<?>)allSessions);
        }
        else
        {
            unmodifiableFixSessions = unmodifiableList((List<? extends ConnectedSessionInfo>)(List<?>)allSessions);
            unmodifiableFixPConnections = emptyList();
        }
    }

    @Override
    public int libraryId()
    {
        return libraryId;
    }

    @Override
    public String libraryName()
    {
        return libraryName;
    }

    @Override
    public List<ConnectedSessionInfo> sessions()
    {
        return unmodifiableFixSessions;
    }

    @Override
    public List<FixPConnectedSessionInfo> fixPConnections()
    {
        return unmodifiableFixPConnections;
    }

    @Override
    public String toString()
    {
        return "LibraryInfo{" +
            "libraryId=" + libraryId +
            ", allSessions=" + allSessions +
            '}';
    }

    int aeronSessionId()
    {
        return aeronSessionId;
    }

    List<GatewaySession> gatewaySessions()
    {
        return allSessions;
    }

    void onHeartbeat(final long timeInMs)
    {
        livenessDetector.onHeartbeat(timeInMs);
    }

    int poll(final long timeInMs)
    {
        return livenessDetector.poll(timeInMs);
    }

    boolean isConnected()
    {
        return livenessDetector.isConnected();
    }

    void addSession(final GatewaySession session)
    {
        final GatewaySession existingSession = removeSessionBySessionId(session.sessionId());
        if (existingSession != null && !existingSession.isOffline())
        {
            final IllegalStateException exception = new IllegalStateException(
                "Session already exists for session id: " + session.sessionId() + " and it is not offline."
            );
            errorHandler.onError(exception);
        }
        allSessions.add(session);
    }

    void removeSessionByConnectionId(final long connectionId)
    {
        GatewaySessions.removeSessionByConnectionId(connectionId, allSessions);
    }

    void offlineSession(final long connectionId)
    {
        final List<GatewaySession> sessions = this.allSessions;
        for (int i = 0, size = sessions.size(); i < size; i++)
        {
            final GatewaySession session = sessions.get(i);
            if (session.connectionId() == connectionId)
            {
                ((FixGatewaySession)session).goOffline();
            }
        }
    }

    GatewaySession removeSessionBySessionId(final long sessionId)
    {
        final int index = GatewaySessions.indexBySessionId(sessionId, allSessions);
        return index == -1 ? null : allSessions.remove(index);
    }

    GatewaySession lookupSessionById(final long sessionId)
    {
        final int index = GatewaySessions.indexBySessionId(sessionId, allSessions);
        return index == -1 ? null : allSessions.get(index);
    }

    void removeSession(final GatewaySession gatewaySession)
    {
        allSessions.remove(gatewaySession);
    }

    void acquireAtPosition(final long libraryPosition)
    {
        acquireAtPosition = libraryPosition;
    }

    long acquireAtPosition()
    {
        return acquireAtPosition;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final LiveLibraryInfo that = (LiveLibraryInfo)o;

        return libraryId == that.libraryId;
    }

    @Override
    public int hashCode()
    {
        return libraryId;
    }

    void connectionStartsConnecting(final long correlationId, final ConnectingSession connectingSession)
    {
        correlationIdToConnectingSession.put(correlationId, connectingSession);
    }

    ConnectingSession connectionFinishesConnecting(final long correlationId)
    {
        return correlationIdToConnectingSession.remove(correlationId);
    }
}
