/*
 * Copyright 2019 Adaptive Financial Consulting Ltd.
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

import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import java.util.List;

import static io.aeron.Publication.BACK_PRESSURED;

class DisconnectAllOperation implements Continuation
{
    private final GatewayPublication inboundPublication;
    private final List<LiveLibraryInfo> libraries;
    private final List<GatewaySession> gatewaySessions;
    private final ReceiverEndPoints receiverEndPoints;
    private final Runnable onSuccess;

    private Step step = Step.CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS;
    private int libraryIndex = 0;
    private int gatewaySessionIndex = 0;

    DisconnectAllOperation(
        final GatewayPublication inboundPublication,
        final List<LiveLibraryInfo> libraries,
        final List<GatewaySession> gatewaySessions,
        final ReceiverEndPoints receiverEndPoints,
        final Runnable onSuccess)
    {
        this.inboundPublication = inboundPublication;
        this.libraries = libraries;
        this.gatewaySessions = gatewaySessions;
        this.receiverEndPoints = receiverEndPoints;
        this.onSuccess = onSuccess;
    }

    @Override
    public long attempt()
    {
        switch (step)
        {
            case CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS:
            {
                receiverEndPoints.closeRequiredPollingEndPoints();

                DebugLogger.log(LogTag.CLOSE, "Completed CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS");
                step = Step.LOGGING_OUT_LIBRARIES;
                return BACK_PRESSURED;
            }

            case LOGGING_OUT_LIBRARIES:
            {
                return logOutLibraries();
            }

            case LOGGING_OUT_GATEWAY_SESSIONS:
            {
                return logOutGatewaySessions();
            }

            case AWAITING_DISCONNECTS:
            {
                return awaitDisconnects();
            }

            default:
                // Impossible
                return 1;
        }
    }

    private long logOutLibraries()
    {
        final GatewayPublication inboundPublication = this.inboundPublication;
        final List<LiveLibraryInfo> libraries = this.libraries;
        final int libraryCount = libraries.size();

        while (libraryIndex < libraryCount)
        {
            final LiveLibraryInfo library = libraries.get(libraryIndex);
            final long position = inboundPublication.saveEndOfDay(library.libraryId());
            if (position < 0)
            {
                return position;
            }

            libraryIndex++;
        }

        DebugLogger.log(LogTag.CLOSE, "Completed LOGGING_OUT_LIBRARIES");
        step = Step.LOGGING_OUT_GATEWAY_SESSIONS;
        return BACK_PRESSURED;
    }

    // NB: if library is in the process of being acquired during an end of day operation then its sessions
    // Will be logged out at the point of acquisition.
    private long logOutGatewaySessions()
    {
        final List<GatewaySession> gatewaySessions = this.gatewaySessions;
        final int gatewaySessionCount = gatewaySessions.size();

        while (gatewaySessionIndex < gatewaySessionCount)
        {
            final GatewaySession gatewaySession = gatewaySessions.get(gatewaySessionIndex);
            final long position = gatewaySession.startEndOfDay();
            if (position < 0)
            {
                return position;
            }
            gatewaySessionIndex++;
        }

        DebugLogger.log(LogTag.CLOSE, "Completed LOGGING_OUT_GATEWAY_SESSIONS");
        step = Step.AWAITING_DISCONNECTS;
        return BACK_PRESSURED;
    }

    private long awaitDisconnects()
    {
        if (receiverEndPoints.size() > 0)
        {
            return BACK_PRESSURED;
        }

        DebugLogger.log(LogTag.CLOSE, "Completed AWAITING_DISCONNECTS");

        onSuccess.run();
        return 1;
    }

    private enum Step
    {
        CLOSING_NOT_LOGGED_ON_RECEIVER_END_POINTS,
        LOGGING_OUT_LIBRARIES,
        LOGGING_OUT_GATEWAY_SESSIONS,
        AWAITING_DISCONNECTS
    }
}
