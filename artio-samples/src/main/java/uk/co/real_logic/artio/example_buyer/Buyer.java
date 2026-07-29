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
package uk.co.real_logic.artio.example_buyer;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.OrdType;
import uk.co.real_logic.artio.Pressure;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder;
import uk.co.real_logic.artio.decoder.ExecutionReportDecoder;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.example_exchange.ExchangeApplication.ACCEPTOR_COMP_ID;
import static uk.co.real_logic.artio.example_exchange.ExchangeApplication.INITIATOR_COMP_ID;

public class Buyer implements LibraryConnectHandler, SessionHandler, SessionAcquireHandler
{
    private enum State
    {
        LIBRARY_DISCONNECTED,
        LIBRARY_CONNECTED,
        SESSION_CONNECTING,
        SESSION_CONNECTED,
        AWAITING_FILL
    }

    private final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final NewOrderSingleEncoder newOrderSingle = new NewOrderSingleEncoder();
    private final ExecutionReportDecoder executionReport = new ExecutionReportDecoder();
    private final DecimalFloat price = new DecimalFloat(100);
    private final DecimalFloat orderQty = new DecimalFloat(2);
    private final UtcTimestampEncoder transactTime = new UtcTimestampEncoder();

    private State state = State.LIBRARY_DISCONNECTED;

    private FixLibrary library;
    private Reply<Session> initiateReply;
    private Session session;

    public void onConnect(final FixLibrary library)
    {
        System.out.println("Library Connected");
        state = State.LIBRARY_CONNECTED;
        this.library = library;
    }

    public void onDisconnect(final FixLibrary library)
    {
        System.out.println("Library Disconnected");
        state = State.LIBRARY_DISCONNECTED;
        this.library = null;
    }

    public int poll()
    {
        checkReplies();

        switch (state)
        {
            case LIBRARY_CONNECTED:
                connectSession();
                return 1;

            case SESSION_CONNECTED:
                sendOrder();
                return 1;

            default:
                break;
        }

        return 0;
    }

    private void checkReplies()
    {
        if (initiateReply != null && !initiateReply.isExecuting())
        {
            if (initiateReply.hasCompleted())
            {
                System.out.println("Session Connected");
                session = initiateReply.resultIfPresent();
                state = State.SESSION_CONNECTED;
            }
            else
            {
                System.err.printf("Session connect failed %s%n", initiateReply.state());
                final Throwable error = initiateReply.error();
                if (error != null)
                {
                    error.printStackTrace();
                }
                System.exit(-1);
            }

            initiateReply = null;
        }
    }

    private void connectSession()
    {
        // Each outbound session with an Exchange or broker is represented by
        // a Session object. Each session object can be configured with connection
        // details and credentials.
        final SessionConfiguration sessionConfig = SessionConfiguration.builder()
            .address("localhost", 9999)
            .targetCompId(ACCEPTOR_COMP_ID)
            .senderCompId(INITIATOR_COMP_ID)
            .build();

        initiateReply = library.initiate(sessionConfig);

        state = State.SESSION_CONNECTING;

        System.out.println("Attempting to connect to exchange");
    }

    private void sendOrder()
    {
        final int transactTimeLength = transactTime.encode(System.currentTimeMillis());

        newOrderSingle
            .clOrdID("A")
            .side(Side.BUY)
            .transactTime(transactTime.buffer(), transactTimeLength)
            .ordType(OrdType.MARKET)
            .price(price);

        newOrderSingle.instrument().symbol("MSFT");
        newOrderSingle.orderQtyData().orderQty(orderQty);

        final long position = session.trySend(newOrderSingle);
        if (!Pressure.isBackPressured(position))
        {
            state = State.AWAITING_FILL;
        }
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
        if (messageType == ExecutionReportDecoder.MESSAGE_TYPE)
        {
            asciiBuffer.wrap(buffer, offset, length);
            System.out.println("Received report: " + asciiBuffer.getAscii(0, length));

            executionReport.decode(asciiBuffer, 0, length);

            System.out.println("Parsed report: " + executionReport);

            state = State.SESSION_CONNECTED;
        }

        return CONTINUE;
    }

    public void onTimeout(final int libraryId, final Session session)
    {
    }

    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
    {
    }

    public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
    {
        state = State.LIBRARY_CONNECTED;

        return CONTINUE;
    }

    public void onSessionStart(final Session session)
    {
    }

    public SessionHandler onSessionAcquired(final Session session, final SessionAcquiredInfo isSlow)
    {
        return this;
    }
}
