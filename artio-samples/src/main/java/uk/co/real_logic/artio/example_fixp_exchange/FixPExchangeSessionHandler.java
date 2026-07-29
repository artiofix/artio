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
package uk.co.real_logic.artio.example_fixp_exchange;

import b3.entrypoint.fixp.sbe.Boolean;
import b3.entrypoint.fixp.sbe.*;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointConnection;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.fixp.FixPMessageHeader;
import uk.co.real_logic.artio.library.NotAppliedResponse;
import uk.co.real_logic.artio.messages.DisconnectReason;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class FixPExchangeSessionHandler implements FixPConnectionHandler
{
    private final NewOrderSingleDecoder newOrderSingle = new NewOrderSingleDecoder();
    private final ExecutionReport_NewEncoder executionReport = new ExecutionReport_NewEncoder();

    private long orderId = ExecutionReport_NewEncoder.orderIDMinValue();

    FixPExchangeSessionHandler(final BinaryEntryPointConnection connection)
    {
    }

    public Action onBusinessMessage(
        final FixPConnection connection,
        final int templateId,
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final boolean possRetrans,
        final FixPMessageHeader header)
    {
        System.out.println("Received Business Message");
        if (templateId == NewOrderSingleDecoder.TEMPLATE_ID)
        {
            System.out.println("Type=NewOrderSingle");
            final NewOrderSingleDecoder newOrderSingle = this.newOrderSingle;
            final ExecutionReport_NewEncoder executionReport = this.executionReport;

            newOrderSingle.wrap(buffer, offset, blockLength, version);

            final long position = connection.tryClaim(
                executionReport, 0);

            if (position < 0)
            {
                // handle back-pressure here
                return ABORT;
            }

            executionReport
                .orderID(orderId++)
                .clOrdID(newOrderSingle.clOrdID())
                .securityID(newOrderSingle.securityID())
                .secondaryOrderID(ExecutionReport_NewEncoder.secondaryOrderIDNullValue())
                .ordStatus(OrdStatus.NEW)
                .execRestatementReason(ExecRestatementReason.NULL_VAL)
                .multiLegReportingType(MultiLegReportingType.NULL_VAL)
                .workingIndicator(Boolean.NULL_VAL)
                .transactTime().time(System.nanoTime());
            executionReport
                .protectionPrice().mantissa(1234);
            executionReport.marketSegmentReceivedTime().time(System.nanoTime());

            connection.commit();
            System.out.println("Sent Execution Report New");
        }

        return CONTINUE;
    }

    public Action onNotApplied(
        final FixPConnection connection,
        final long fromSequenceNumber,
        final long msgCount,
        final NotAppliedResponse response)
    {
        return CONTINUE;
    }

    public Action onRetransmitReject(
        final FixPConnection connection, final String reason, final long requestTimestamp, final int errorCodes)
    {
        return CONTINUE;
    }

    public Action onRetransmitTimeout(final FixPConnection connection)
    {
        return CONTINUE;
    }

    public Action onSequence(final FixPConnection connection, final long nextSeqNo)
    {
        return CONTINUE;
    }

    public Action onError(final FixPConnection connection, final Exception ex)
    {
        ex.printStackTrace();

        return CONTINUE;
    }

    public Action onDisconnect(final FixPConnection connection, final DisconnectReason reason)
    {
        System.out.println("onDisconnect conn=" + connection.key() + ",reason=" + reason);

        return CONTINUE;
    }
}
