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
package uk.co.real_logic.artio.example_exchange;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.nio.charset.StandardCharsets.US_ASCII;

public class ExchangeSessionHandler implements SessionHandler
{
    private static final DecimalFloat MINIMUM_PRICE = new DecimalFloat().fromLong(100);
    private static final char[] VALID_SYMBOL = "MSFT".toCharArray();
    private static final byte[] SYMBOL_BYTES = "MSFT".getBytes(US_ASCII);

    private static final int SIZE_OF_ASCII_LONG = String.valueOf(Long.MAX_VALUE).length();

    // static fields below safe due to single threaded nature of the logic.

    // execIds unique to venue.
    private static final byte[] EXEC_ID_BUFFER = new byte[SIZE_OF_ASCII_LONG];
    private static int execIdEncodedLength;
    private static final UnsafeBuffer EXEC_ID_ENCODER = new UnsafeBuffer(EXEC_ID_BUFFER);
    private static long execId = 0;

    private static final byte[] ORDER_ID_BUFFER = new byte[SIZE_OF_ASCII_LONG];
    private static int orderIdEncodedLength;
    private static final UnsafeBuffer ORDER_ID_ENCODER = new UnsafeBuffer(ORDER_ID_BUFFER);

    // orderIds unique per session
    private long orderId = 0;

    private final NewOrderSingleDecoder newOrderSingle = new NewOrderSingleDecoder();
    private final ExecutionReportEncoder executionReport = new ExecutionReportEncoder();
    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();

    ExchangeSessionHandler(final Session session)
    {
    }

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
        asciiBuffer.wrap(buffer, offset, length);

        if (messageType == NewOrderSingleDecoder.MESSAGE_TYPE)
        {
            newOrderSingle.decode(asciiBuffer, 0, length);

            if (!validOrder())
            {
                return cancelOrder(session);
            }

            return fillOrder(session);
        }

        return CONTINUE;
    }

    private Action cancelOrder(final Session session)
    {
        final Side side = newOrderSingle.sideAsEnum();

        newOrderId();

        newExecId();

        executionReport
            .execType(ExecType.CANCELED)
            .ordStatus(OrdStatus.CANCELED)
            .orderID(ORDER_ID_BUFFER, orderIdEncodedLength)
            .execID(EXEC_ID_BUFFER, execIdEncodedLength)
            .side(side);

        executionReport.instrument().symbol(newOrderSingle.symbol(), newOrderSingle.symbolLength());

        return Pressure.apply(session.trySend(executionReport));
    }

    private boolean validOrder()
    {
        if (!CodecUtil.equals(newOrderSingle.symbol(), VALID_SYMBOL, newOrderSingle.symbolLength()))
        {
            return false;
        }

        final OrdType ordType = newOrderSingle.ordTypeAsEnum();
        if (ordType != OrdType.MARKET)
        {
            return false;
        }

        final DecimalFloat price = newOrderSingle.price();
        return MINIMUM_PRICE.compareTo(price) > 0;
    }

    private Action fillOrder(final Session session)
    {
        final Side side = newOrderSingle.sideAsEnum();

        newOrderId();

        newExecId();

        executionReport
            .orderID(ORDER_ID_BUFFER, orderIdEncodedLength)
            .execID(EXEC_ID_BUFFER, execIdEncodedLength)
            .execType(ExecType.FILL)
            .ordStatus(OrdStatus.FILLED)
            .side(side);

        executionReport.instrument().symbol(SYMBOL_BYTES);

        final long sendPosition = session.trySend(executionReport);
        if (Pressure.isBackPressured(sendPosition))
        {
            // Roll back transactional state and indicate that you need to retry.
            orderId--;
            execId--;

            return ABORT;
        }
        else
        {
            return CONTINUE;
        }
    }

    private void newExecId()
    {
        execId++;
        execIdEncodedLength = EXEC_ID_ENCODER.putLongAscii(0, execId);
    }

    private void newOrderId()
    {
        // Generate ids GC free, underlying buffer is associated with the execution report in the constructor
        orderId++;
        orderIdEncodedLength = ORDER_ID_ENCODER.putLongAscii(0, orderId);
    }

    @Override
    public void onSessionStart(final Session session)
    {
    }

    @Override
    public void onTimeout(final int libraryId, final Session session)
    {

    }

    @Override
    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
    {

    }

    @Override
    public Action onDisconnect(
        final int libraryId, final Session session, final DisconnectReason reason)
    {
        return CONTINUE;
    }
}
