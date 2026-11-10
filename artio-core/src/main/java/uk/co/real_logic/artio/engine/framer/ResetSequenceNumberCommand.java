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

import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.messages.GatewayError;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.session.Session;

import java.util.concurrent.TimeUnit;
import java.util.function.LongToIntFunction;

import static uk.co.real_logic.artio.Reply.State.COMPLETED;
import static uk.co.real_logic.artio.Reply.State.ERRORED;
import static uk.co.real_logic.artio.Reply.State.TIMED_OUT;

class ResetSequenceNumberCommand implements Reply<Void>, AdminCommand
{
    private volatile State state = State.EXECUTING;
    // write to error only when updating state
    private Exception error;

    private final long sessionId;

    // State to only be accessed on the Framer thread.
    private final GatewaySessions gatewaySessions;
    private final SessionContexts sessionContexts;
    private final SequenceNumberIndexReader receivedSequenceNumberIndex;
    private final SequenceNumberIndexReader sentSequenceNumberIndex;
    private final GatewayPublication inboundPublication;
    private final GatewayPublication outboundPublication;
    private final EpochNanoClock clock;
    private final long resetTimeInNs;
    private final long timeoutInNs;
    private Session session;
    private LongToIntFunction libraryLookup;
    private long awaitSequenceNumber = 1;

    private boolean isAdminReset = false;
    private long adminCorrelationId;
    private AdminReplyPublication adminReplyPublication;

    void libraryLookup(final LongToIntFunction libraryLookup)
    {
        this.libraryLookup = libraryLookup;
    }

    void setupAdminReset(final long correlationId, final AdminReplyPublication adminReplyPublication)
    {
        isAdminReset = true;
        this.adminCorrelationId = correlationId;
        this.adminReplyPublication = adminReplyPublication;
    }

    private enum Step
    {
        START,

        // Sending the reset session message - used if engine managed session
        RESET_ENGINE_SESSION,

        // Sending the reset session message - used if library managed session
        RESET_LIBRARY_SESSION,

        // Send the message to reset sent seq num - used if not logged in
        RESET_SENT,

        // Send the message to reset recv seq num - used if not logged in
        RESET_RECV,

        // await reset of sent seq num - jumped to from RESET_ENGINE_SESSION and RESET_LIBRARY_SESSION
        // since this will be updated when the other end of the session acknowledges the sequence reset.
        AWAIT_RECV,

        // await reset of recv seq num
        AWAIT_SENT,

        // Send a message to notify the Admin API that the reset has completed.
        NOTIFY_ADMIN_API,

        DONE,
    }

    private Step step = Step.START;

    // Variables initialised on any thread, but objects only executed on the Framer thread
    // so they don't all have to be thread safe
    ResetSequenceNumberCommand(
        final long sessionId,
        final GatewaySessions gatewaySessions,
        final SessionContexts sessionContexts,
        final SequenceNumberIndexReader receivedSequenceNumberIndex,
        final SequenceNumberIndexReader sentSequenceNumberIndex,
        final GatewayPublication inboundPublication,
        final GatewayPublication outboundPublication,
        final EpochNanoClock clock,
        final long timeoutInMs)
    {
        this.sessionId = sessionId;
        this.gatewaySessions = gatewaySessions;
        this.sessionContexts = sessionContexts;
        this.receivedSequenceNumberIndex = receivedSequenceNumberIndex;
        this.sentSequenceNumberIndex = sentSequenceNumberIndex;
        this.inboundPublication = inboundPublication;
        this.outboundPublication = outboundPublication;
        this.clock = clock;
        this.resetTimeInNs = clock.nanoTime();
        this.timeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
    }

    @Override
    public Exception error()
    {
        return error;
    }

    private void onError(final Exception error)
    {
        this.error = error;
        state = ERRORED;
    }

    @Override
    public Void resultIfPresent()
    {
        return null;
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public void execute(final Framer framer)
    {
        framer.onResetSequenceNumber(this);
    }

    // Only to be called on the Framer thread.
    boolean poll()
    {
        if (clock.nanoTime() - resetTimeInNs >= timeoutInNs)
        {
            return onTimeout();
        }

        switch (step)
        {
            case START:
            {
                if (sessionIsUnknown())
                {
                    onError(new IllegalArgumentException(
                        String.format("Unknown sessionId: %d", sessionId)));

                    return true;
                }

                final GatewaySession gatewaySession = gatewaySessions.sessionById(sessionId);
                if (gatewaySession instanceof FixGatewaySession)
                {
                    session = ((FixGatewaySession)gatewaySession).session();
                }

                // Engine Managed
                if (session != null)
                {
                    step = Step.RESET_ENGINE_SESSION;
                }
                // Library Managed
                else if (isAuthenticated())
                {
                    step = Step.RESET_LIBRARY_SESSION;
                }
                // Not logged in
                else
                {
                    sessionContexts.sequenceReset(sessionId, resetTimeInNs);
                    step = Step.RESET_RECV;
                }

                return false;
            }

            case RESET_ENGINE_SESSION:
            {
                final long position = session.tryResetSequenceNumbers();
                if (!Pressure.isBackPressured(position))
                {
                    awaitSequenceNumber = 1;
                    step = Step.AWAIT_RECV;
                }
                return false;
            }

            case RESET_LIBRARY_SESSION:
            {
                if (isAuthenticated())
                {
                    final int libraryId = libraryLookup.applyAsInt(sessionId);
                    if (!Pressure.isBackPressured(
                        inboundPublication.saveResetLibrarySequenceNumber(libraryId, sessionId)))
                    {
                        awaitSequenceNumber = 1;
                        step = Step.AWAIT_RECV;
                    }
                }
                else
                {
                    // The session disconnects whilst you're trying to talk to reset it
                    step = Step.START;
                }

                return false;
            }

            case RESET_RECV:
                awaitSequenceNumber = 0;
                return reset(inboundPublication, Step.RESET_SENT);

            case RESET_SENT:
                awaitSequenceNumber = 0;
                return reset(outboundPublication, Step.AWAIT_RECV);

            case AWAIT_RECV:
                return await(receivedSequenceNumberIndex, Step.AWAIT_SENT);

            case AWAIT_SENT:
                return await(sentSequenceNumberIndex, isAdminReset ? Step.NOTIFY_ADMIN_API : Step.DONE);

            case NOTIFY_ADMIN_API:
                return notifyAdminApi();

            case DONE:
                state = COMPLETED;
                return true;
        }

        return false;
    }

    private boolean notifyAdminApi()
    {
        if (adminReplyPublication.saveGenericAdminReply(adminCorrelationId, GatewayError.NULL_VAL, "") > 0)
        {
            step = Step.DONE;
        }

        return false;
    }

    private boolean isAuthenticated()
    {
        return sessionContexts.isAuthenticated(sessionId);
    }

    private boolean reset(final GatewayPublication publication, final Step nextStep)
    {
        if (!Pressure.isBackPressured(publication.saveResetSequenceNumber(sessionId)))
        {
            step = nextStep;
        }

        return false;
    }

    private boolean await(final SequenceNumberIndexReader sequenceNumberIndex, final Step nextStep)
    {
        final int lastKnownSequenceNumber = sequenceNumberIndex.lastKnownSequenceNumber(sessionId);
        final boolean done = lastKnownSequenceNumber <= awaitSequenceNumber;
        if (done)
        {
            step = nextStep;
        }
        return false;
    }

    private boolean sessionIsUnknown()
    {
        return !sessionContexts.isKnownSessionId(sessionId);
    }

    private boolean onTimeout()
    {
        if (isAdminReset)
        {
            if (adminReplyPublication.saveGenericAdminReply(adminCorrelationId, GatewayError.EXCEPTION,
                sessionId + " sequence numbers not reset in " + timeoutInNs + "ns") > 0)
            {
                state = TIMED_OUT;
                return true;
            }

            return false;
        }

        else
        {
            state = TIMED_OUT;
            return true;
        }
    }

    @Override
    public String toString()
    {
        return "ResetSequenceNumberReply{" +
            "state=" + state +
            ", error=" + error +
            ", sessionId=" + sessionId +
            ", step=" + step +
            '}';
    }
}
