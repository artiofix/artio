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
package uk.co.real_logic.artio.system_tests;

import b3.entrypoint.fixp.sbe.Boolean;
import b3.entrypoint.fixp.sbe.*;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntArrayList;
import org.agrona.collections.LongArrayList;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.fixp.FixPMessageHeader;
import uk.co.real_logic.artio.fixp.RetransmissionInfo;
import uk.co.real_logic.artio.library.NotAppliedResponse;
import uk.co.real_logic.artio.messages.DisconnectReason;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class FakeBinaryEntrypointConnectionHandler implements FixPConnectionHandler
{
    private final IntArrayList messageIds = new IntArrayList();
    private final LongArrayList sessionIds = new LongArrayList();
    private final List<Exception> exceptions = new ArrayList<>();
    private final Consumer<NotAppliedResponse> notAppliedResponse;

    private DisconnectReason disconnectReason;
    private boolean replyToOrder = true;
    private boolean abortReport;
    private boolean finishedSending = false;
    private long lastPosition;
    private RetransmissionInfo retransmissionInfo;
    private RetransmitRejectCode retransmitRejectCode;
    private final AtomicInteger retransmissionCallbacks = new AtomicInteger(0);
    private int retransmissionBackpressureAttempts = 0;

    public void retransmissionBackpressureAttempts(final int retransmissionBackpressureAttempts)
    {
        this.retransmissionBackpressureAttempts = retransmissionBackpressureAttempts;
    }

    public void retransmitRejectCode(final RetransmitRejectCode retransmitRejectCode)
    {
        this.retransmitRejectCode = retransmitRejectCode;
    }

    public int retransmissionCallbacks()
    {
        return retransmissionCallbacks.get();
    }

    public FakeBinaryEntrypointConnectionHandler()
    {
        this.notAppliedResponse = ignore -> {};
    }

    public void abortReport(final boolean abortReport)
    {
        this.abortReport = abortReport;
    }

    public void replyToOrder(final boolean replyToOrder)
    {
        this.replyToOrder = replyToOrder;
    }

    public boolean hasFinishedSending()
    {
        return finishedSending;
    }

    @Override
    public ControlledFragmentHandler.Action onBusinessMessage(
        final FixPConnection connection,
        final int templateId,
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final boolean possRetrans,
        final FixPMessageHeader header)
    {
        messageIds.add(templateId);
        sessionIds.add(connection.key().sessionIdIfExists());

        assertThat(header.messageSize(), greaterThanOrEqualTo(blockLength));

        if (replyToOrder && templateId == NewOrderSingleDecoder.TEMPLATE_ID)
        {
            final NewOrderSingleDecoder newOrderSingle = new NewOrderSingleDecoder();
            newOrderSingle.wrap(buffer, offset, blockLength, version);

            final long clOrderID = newOrderSingle.clOrdID();
            final long securityID = newOrderSingle.securityID();

            lastPosition = sendExecutionReportNew(connection, clOrderID, securityID, abortReport);
        }

        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onFinishedSending(final FixPConnection connection)
    {
        finishedSending = true;

        return CONTINUE;
    }

    static long sendExecutionReportNew(
        final FixPConnection connection, final long clOrderID, final long securityID, final boolean abortReport)
    {
        final ExecutionReport_NewEncoder executionReport = new ExecutionReport_NewEncoder();

        long position;

        while (true)
        {
            position = connection.tryClaim(executionReport, 0);
            if (position >= 0)
            {
                break;
            }
            else
            {
                Thread.yield();
            }
        }

        executionReport
            .orderID(clOrderID)
            .clOrdID(clOrderID)
            .securityID(securityID)
            .secondaryOrderID(ExecutionReport_NewEncoder.secondaryOrderIDNullValue())
            .ordStatus(OrdStatus.NEW)
            .execRestatementReason(ExecRestatementReason.NULL_VAL)
            .multiLegReportingType(MultiLegReportingType.NULL_VAL)
            .workingIndicator(Boolean.NULL_VAL)
            .transactTime().time(System.nanoTime());
        executionReport
            .protectionPrice().mantissa(1234);
        executionReport.marketSegmentReceivedTime().time(System.nanoTime());

        if (abortReport)
        {
            connection.abort();
        }
        else
        {
            connection.commit();
        }

        return position;
    }

    @Override
    public ControlledFragmentHandler.Action onNotApplied(
        final FixPConnection connection,
        final long fromSequenceNumber,
        final long msgCount,
        final NotAppliedResponse response)
    {
        notAppliedResponse.accept(response);

        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onRetransmitReject(
        final FixPConnection connection,
        final String reason,
        final long requestTimestamp,
        final int errorCodes)
    {
        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onRetransmitTimeout(final FixPConnection connection)
    {
        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onSequence(final FixPConnection connection, final long nextSeqNo)
    {
        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onError(final FixPConnection connection, final Exception ex)
    {
        exceptions.add(ex);

        return CONTINUE;
    }

    @Override
    public ControlledFragmentHandler.Action onDisconnect(final FixPConnection connection, final DisconnectReason reason)
    {
        this.disconnectReason = reason;

        return CONTINUE;
    }

    public DisconnectReason disconnectReason()
    {
        return disconnectReason;
    }

    public IntArrayList templateIds()
    {
        return messageIds;
    }

    public LongArrayList sessionIds()
    {
        return sessionIds;
    }

    public List<Exception> exceptions()
    {
        return exceptions;
    }

    public long lastPosition()
    {
        return lastPosition;
    }

    public void reset()
    {
        lastPosition = 0;
        disconnectReason = null;
        retransmissionInfo = null;
        messageIds.clear();
        exceptions.clear();
        sessionIds.clear();
    }

    @Override
    public ControlledFragmentHandler.Action onRetransmitRequest(
        final FixPConnection connection, final RetransmissionInfo retransmissionInfo)
    {
        this.retransmissionInfo = retransmissionInfo;

        retransmissionCallbacks.incrementAndGet();

        if (retransmissionBackpressureAttempts <= 0)
        {
            if (retransmitRejectCode != null)
            {
                retransmissionInfo.reject(retransmitRejectCode);
            }

            return CONTINUE;
        }
        else
        {
            retransmissionBackpressureAttempts--;
            return ABORT;
        }
    }

    public RetransmissionInfo retransmissionInfo()
    {
        return retransmissionInfo;
    }

    public boolean hasRetransmissionInfo()
    {
        return retransmissionInfo != null;
    }
}
