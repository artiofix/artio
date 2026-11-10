/*
 * Copyright 2021 Monotonic Ltd.
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
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.sbe.MessageDecoderFlyweight;
import org.agrona.sbe.MessageEncoderFlyweight;
import org.junit.jupiter.api.Assertions;

import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointProtocol;
import uk.co.real_logic.sbe.json.JsonPrinter;
import uk.co.real_logic.sbe.otf.OtfHeaderDecoder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static b3.entrypoint.fixp.sbe.CancelOnDisconnectType.DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.LogTag.FIX_TEST;
import static uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointProxy.BINARY_ENTRYPOINT_HEADER_LENGTH;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.*;

public final class BinaryEntryPointClient implements AutoCloseable
{
    private static final int NOT_SKIPPING = -1;

    public static final int BUFFER_SIZE = 8 * 1024;
    public static final int SESSION_ID = 123;
    public static final int SESSION_ID_2 = SESSION_ID + 1;
    public static final int FIRM_ID = 456;
    public static final int CL_ORD_ID = 1;

    private static final long KEEP_ALIVE_INTERVAL_IN_MS = 10_000L;

    public static final long SECURITY_ID = 2;
    public static final int INITIAL_SESSION_VER_ID = 1;
    public static final String CREDENTIALS = "ABC123";
    public static final String CLIENT_IP = "clientIP";
    public static final String CLIENT_APP_NAME = "clientAppName";
    public static final String CLIENT_APP_VERSION = "clientAppVersion";
    public static final int OUT_OF_RANGE_TEMPLATE_ID = 1000;

    private final JsonPrinter jsonPrinter = new JsonPrinter(BinaryEntryPointProtocol.loadSbeIr());

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();

    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final UnsafeBuffer unsafeWriteBuffer = new UnsafeBuffer(writeBuffer);

    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final UnsafeBuffer unsafeReadBuffer = new UnsafeBuffer(readBuffer);

    private final EpochNanoClock epochNanoClock = new SystemEpochNanoClock();

    private final SocketChannel socket;
    private final TestSystem testSystem;
    private final long serverAliveIntervalInMs;

    private int skipTemplateId = NOT_SKIPPING;
    private int sessionId = SESSION_ID;
    private long sessionVerID = INITIAL_SESSION_VER_ID;
    private long negotiateTimestampInNs;
    private long establishTimestampInNs;
    private long retransmitRequestTimestampInNs;

    private long keepAliveIntervalInMs = KEEP_ALIVE_INTERVAL_IN_MS;
    private CancelOnDisconnectType cancelOnDisconnectType = DO_NOT_CANCEL_ON_DISCONNECT_OR_TERMINATE;
    private long codTimeoutWindow = DeltaInMillisEncoder.timeNullValue();
    private static final OtfHeaderDecoder OTF_HEADER_DECODER = new OtfHeaderDecoder(
        BinaryEntryPointProtocol.loadSbeIr().headerStructure());

    public BinaryEntryPointClient(final int port, final TestSystem testSystem, final long serverAliveIntervalInMs)
        throws IOException
    {
        socket = SocketChannel.open(new InetSocketAddress("localhost", port));
        this.testSystem = testSystem;
        this.serverAliveIntervalInMs = serverAliveIntervalInMs;

        headerDecoder.wrap(unsafeReadBuffer, SOFH_LENGTH);
    }

    public void sessionVerID(final long sessionVerID)
    {
        this.sessionVerID = sessionVerID;
    }

    public void sessionId(final int sessionId)
    {
        this.sessionId = sessionId;
    }

    public void skipTemplateId(final int skipTemplateId)
    {
        this.skipTemplateId = skipTemplateId;
    }

    public void codTimeout(final CancelOnDisconnectType cancelOnDisconnectType, final long codTimeoutWindow)
    {
        this.cancelOnDisconnectType = cancelOnDisconnectType;
        this.codTimeoutWindow = codTimeoutWindow;
    }

    public InetSocketAddress remoteAddress()
    {
        try
        {
            return (InetSocketAddress)socket.getLocalAddress();
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    public void skipSequence()
    {
        skipTemplateId(SequenceDecoder.TEMPLATE_ID);
    }

    public void dontSkip()
    {
        skipTemplateId(NOT_SKIPPING);
    }

    public long sessionVerID()
    {
        return sessionVerID;
    }

    public int sessionId()
    {
        return sessionId;
    }

    public void keepAliveIntervalInMs(final long keepAliveIntervalInMs)
    {
        this.keepAliveIntervalInMs = keepAliveIntervalInMs;
    }

    public NegotiateResponseDecoder readNegotiateResponse()
    {
        final NegotiateResponseDecoder response = read(new NegotiateResponseDecoder(), 0);
        assertEquals(sessionId, response.sessionID());
        assertEquals(sessionVerID, response.sessionVerID());
        assertEquals(BinaryEntryPointClient.FIRM_ID, response.enteringFirm());
        return response;
    }

    public NegotiateRejectDecoder readNegotiateReject(final NegotiationRejectCode negotiationRejectCode)
    {
        final NegotiateRejectDecoder reject = read(new NegotiateRejectDecoder(), 0);
        assertEquals(sessionId, reject.sessionID());
        assertEquals(sessionVerID, reject.sessionVerID());
        assertEquals(negotiateTimestampInNs, reject.requestTimestamp().time());
        assertEquals(FIRM_ID, reject.enteringFirm());
        assertEquals(negotiationRejectCode, reject.negotiationRejectCode());
        return reject;
    }

    public void readEstablishReject(final EstablishRejectCode rejectCode)
    {
        final EstablishRejectDecoder reject = read(new EstablishRejectDecoder(), 0);
        assertEquals(sessionId, reject.sessionID());
        assertEquals(sessionVerID, reject.sessionVerID());
        assertEquals(establishTimestampInNs, reject.requestTimestamp().time());
        assertEquals(rejectCode, reject.establishmentRejectCode());
    }

    public <T extends MessageDecoderFlyweight> T read(final T messageDecoder, final int nonBlockLength)
    {
        return testSystem.awaitBlocking(() -> readInternal(messageDecoder, nonBlockLength));
    }

    private <T extends MessageDecoderFlyweight> T readInternal(final T messageDecoder, final int nonBlockLength)
    {
        try
        {
            final int headerLength = MessageHeaderDecoder.ENCODED_LENGTH + SOFH_LENGTH;
            readBuffer.limit(headerLength);
            final int readHeader = readSocket();
            if (readHeader != headerLength)
            {
                throw new IllegalStateException("readHeader=" + readHeader + ",headerLength" + headerLength);
            }

            final int totalLength = readSofh(unsafeReadBuffer, 0, BINARY_ENTRYPOINT_TYPE);
            final int templateId = headerDecoder.templateId();
            final int blockLength = headerDecoder.blockLength();
            final int version = headerDecoder.version();

            if (skipTemplateId != NOT_SKIPPING && templateId == skipTemplateId)
            {
                readBuffer.limit(totalLength);
                int readSkip = 0;
                do
                {
                    readSkip += readSocket();
                }
                while (readSkip < (totalLength - readHeader));

                readBuffer.clear();

                return readInternal(messageDecoder, nonBlockLength);
            }

            final int messageOffset = headerLength;
            final int expectedLength = messageOffset + messageDecoder.sbeBlockLength() + nonBlockLength;
            readBuffer.limit(expectedLength);

            final int readBody = readSocket();
            final int read = readHeader + readBody;

            print(unsafeReadBuffer, "> ");

            final int expectedDecodeTemplateId = messageDecoder.sbeTemplateId();
            if (expectedDecodeTemplateId != templateId)
            {
                final StringBuilder sb = new StringBuilder("invalid template id: ");
                jsonPrinter.print(sb, unsafeReadBuffer, SOFH_LENGTH);
                assertEquals(expectedDecodeTemplateId, templateId, sb.toString());
            }

            if (totalLength != read)
            {
                throw new IllegalArgumentException("totalLength=" + totalLength + ",read=" + read);
            }

            messageDecoder.wrap(
                unsafeReadBuffer,
                messageOffset,
                blockLength,
                version);

            assertThat(read, greaterThanOrEqualTo(messageOffset + blockLength));

            readBuffer.clear();

            return messageDecoder;
        }
        catch (final IOException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    private int readSocket() throws IOException
    {
        final int read = socket.read(readBuffer);
        if (read < 0)
        {
            throw new IllegalStateException("SOCKET CLOSED");
        }
        return read;
    }

    private void write()
    {
        final int messageSize = readSofhMessageSize(unsafeWriteBuffer, 0);
        writeWithLength(messageSize);
    }

    private void writeWithLength(final int messageSize)
    {
        writeBuffer.position(0).limit(messageSize);

        testSystem.awaitBlocking(() ->
        {
            try
            {
                print(unsafeWriteBuffer, "< ");

                final int written = socket.write(writeBuffer);
                assertEquals(messageSize, written);
            }
            catch (final IOException e)
            {
                e.printStackTrace();
            }
            finally
            {
                writeBuffer.clear();
            }
        });
    }

    private void wrap(final MessageEncoderFlyweight messageEncoder, final int length)
    {
        wrap(messageEncoder, length, messageEncoder.sbeTemplateId());
    }

    private void wrap(final MessageEncoderFlyweight messageEncoder, final int length, final int templateId)
    {
        final int messageSize = BINARY_ENTRYPOINT_HEADER_LENGTH + length;
        writeBinaryEntryPointSofh(unsafeWriteBuffer, 0, messageSize);
        DebugLogger.log(FIX_TEST, "wrap messageSize=", String.valueOf(messageSize));

        headerEncoder
            .wrap(unsafeWriteBuffer, SOFH_LENGTH)
            .blockLength(messageEncoder.sbeBlockLength())
            .templateId(templateId)
            .schemaId(messageEncoder.sbeSchemaId())
            .version(messageEncoder.sbeSchemaVersion());

        messageEncoder.wrap(unsafeWriteBuffer, BINARY_ENTRYPOINT_HEADER_LENGTH);
    }

    private void print(final UnsafeBuffer unsafeReadBuffer, final String prefixString)
    {
        if (DebugLogger.isEnabled(FIX_TEST))
        {
            // when templateId == 1000 the call to jsonPrinter.print throws an exception as it does not recognize
            // this as a valid templateId
            final int templateId = OTF_HEADER_DECODER.getTemplateId(unsafeReadBuffer, SOFH_LENGTH);
            if (templateId != OUT_OF_RANGE_TEMPLATE_ID)
            {
                final StringBuilder sb = new StringBuilder();
                jsonPrinter.print(sb, unsafeReadBuffer, SOFH_LENGTH);
                DebugLogger.log(FIX_TEST, prefixString, sb.toString());
            }
        }
    }

    @Override
    public void close()
    {
        CloseHelper.close(socket);
    }

    public void writeOutOfRangeTemplateIdMessage()
    {
        writeNegotiateInternal(0, timeInNs(), OUT_OF_RANGE_TEMPLATE_ID);
    }

    public void writeNegotiate()
    {
        writeNegotiateInternal(0, timeInNs(), NegotiateEncoder.TEMPLATE_ID);
    }

    public void writeNegotiateWithLargeSofh()
    {
        writeNegotiateInternal(5, timeInNs(), NegotiateEncoder.TEMPLATE_ID);
    }

    public void writeNegotiateWithShortSofh()
    {
        writeNegotiateInternal(-15, timeInNs(), NegotiateEncoder.TEMPLATE_ID);
    }

    public void writeNegotiateWithTimestamp(final long negotiateTimestampInNs)
    {
        writeNegotiateInternal(0, negotiateTimestampInNs, NegotiateEncoder.TEMPLATE_ID);
    }

    private void writeNegotiateInternal(final int extraLength, final long negotiateTimestampInNs, final int templateId)
    {
        final NegotiateEncoder negotiate = new NegotiateEncoder();
        final int actualLength = NegotiateEncoder.BLOCK_LENGTH + NegotiateEncoder.credentialsHeaderLength() +
            CREDENTIALS.length() + NegotiateEncoder.clientIPHeaderLength() + CLIENT_IP.length() +
            NegotiateEncoder.clientAppNameHeaderLength() + CLIENT_APP_NAME.length() +
            NegotiateEncoder.clientAppVersionHeaderLength() + CLIENT_APP_VERSION.length();
        wrap(negotiate, actualLength + extraLength, templateId);

        this.negotiateTimestampInNs = negotiateTimestampInNs;

        negotiate
            .sessionID(sessionId)
            .sessionVerID(sessionVerID)
            .timestamp().time(negotiateTimestampInNs);
        negotiate
            .enteringFirm(FIRM_ID)
            .onbehalfFirm(NegotiateEncoder.onbehalfFirmNullValue())
            .credentials(CREDENTIALS)
            .clientIP(CLIENT_IP)
            .clientAppName(CLIENT_APP_NAME)
            .clientAppVersion(CLIENT_APP_VERSION);

        writeWithLength(BINARY_ENTRYPOINT_HEADER_LENGTH + actualLength);
    }

    public void writeEstablish()
    {
        writeEstablish(1);
    }

    public void writeEstablish(final int nextSeqNo)
    {
        final EstablishEncoder establish = new EstablishEncoder();
        wrap(establish, EstablishEncoder.BLOCK_LENGTH + EstablishEncoder.credentialsHeaderLength() +
            CREDENTIALS.length());

        establishTimestampInNs = timeInNs();
        establish
            .sessionID(sessionId)
            .sessionVerID(sessionVerID)
            .timestamp().time(establishTimestampInNs);
        establish.keepAliveInterval().time(keepAliveIntervalInMs);
        establish
            .nextSeqNo(nextSeqNo)
            .cancelOnDisconnectType(cancelOnDisconnectType)
            .codTimeoutWindow().time(codTimeoutWindow);
        establish.credentials(CREDENTIALS);

        write();
    }

    public EstablishAckDecoder readFirstEstablishAck()
    {
        return readEstablishAck(1, 0);
    }

    public EstablishAckDecoder readEstablishAck(final int nextSeqNo, final int lastIncomingSeqNo)
    {
        final EstablishAckDecoder establishAck = read(new EstablishAckDecoder(), 0);
        assertEquals(sessionId, establishAck.sessionID(), "sessionID");
        assertEquals(sessionVerID, establishAck.sessionVerID(), "sessionVerID");
        assertEquals(nextSeqNo, establishAck.nextSeqNo(), "nextSeqNo");
        assertEquals(lastIncomingSeqNo, establishAck.lastIncomingSeqNo(), "lastIncomingSeqNo");
        assertEquals(serverAliveIntervalInMs, establishAck.keepAliveInterval().time());
        return establishAck;
    }

    public TerminateDecoder readTerminate()
    {
        return readTerminate(TerminationCode.FINISHED);
    }

    public TerminateDecoder readTerminate(final TerminationCode terminationCode)
    {
        final TerminateDecoder terminate = read(new TerminateDecoder(), 0);
        assertEquals(sessionId, terminate.sessionID());
        assertEquals(sessionVerID, terminate.sessionVerID());
        assertEquals(terminationCode, terminate.terminationCode());
        return terminate;
    }

    public void readExecutionReportNew()
    {
        readExecutionReportNew(CL_ORD_ID);
    }

    public ExecutionReport_NewDecoder readExecutionReportNew(final int clOrdId)
    {
        final ExecutionReport_NewDecoder report = read(
            new ExecutionReport_NewDecoder(), 0);
        assertEquals(clOrdId, report.clOrdID());
        return report;
    }

    public void writeTerminate()
    {
        writeTerminate(sessionId);
    }

    public void writeTerminate(final int sessionId)
    {
        final TerminateEncoder terminate = new TerminateEncoder();
        wrap(terminate, TerminateEncoder.BLOCK_LENGTH);

        terminate
            .sessionID(sessionId)
            .sessionVerID(sessionVerID)
            .terminationCode(TerminationCode.FINISHED);

        write();
    }

    public void writeNewOrderSingle()
    {
        writeNewOrderSingle(CL_ORD_ID);
    }

    public void writeNewOrderSingle(final int clOrdId)
    {
        final NewOrderSingleEncoder newOrderSingle = new NewOrderSingleEncoder();
        wrap(newOrderSingle, NewOrderSingleEncoder.BLOCK_LENGTH);

        newOrderSingle
            .clOrdID(clOrdId)
            .selfTradePreventionInstruction(SelfTradePreventionInstruction.CANCEL_BOTH_ORDERS)
            .securityID(SECURITY_ID)
            .price().mantissa(3);
        newOrderSingle
            .account(5)
            .marketSegmentID(NewOrderSingleEncoder.marketSegmentIDNullValue())
            .side(Side.BUY)
            .ordType(OrdType.MARKET)
            .timeInForce(TimeInForce.FILL_OR_KILL)
            .stopPx().mantissa(PriceOptionalEncoder.mantissaNullValue());
        newOrderSingle
            .enteringTrader("Maria")
            .ordTagID((short)1)
            .mmProtectionReset(Boolean.TRUE_VALUE)
            .routingInstruction(RoutingInstruction.NULL_VAL)
            .investorID(123)
            .custodianInfo()
                .custodian(1)
                .custodyAccount(2)
                .custodyAllocationType(3);

        write();
    }

    public void assertDisconnected()
    {
        try
        {
            testSystem.awaitBlocking(() ->
            {
                try
                {
                    final int read = socket.read(readBuffer);
                    if (read == -1)
                    {
                        return;
                    }

                    final int totalLength = readSofh(unsafeReadBuffer, 0, BINARY_ENTRYPOINT_TYPE);
                    final int templateId = headerDecoder.templateId();
                    final int blockLength = headerDecoder.blockLength();
                    final int version = headerDecoder.version();

                    Assertions.fail("read = " + read + ", totalLength = " + totalLength + ", templateId = " +
                        templateId + ", blockLength = " + blockLength + ", version = " + version);
                }
                catch (final IOException e)
                {
                    // Deliberately blank - if it throws an exception due to being disconnected that's ok
                }
            });
        }
        finally
        {
            try
            {
                socket.close();
            }
            catch (final IOException e)
            {
                LangUtil.rethrowUnchecked(e);
            }
        }
    }

    public void writeFinishedSending(final long lastSeqNo)
    {
        final FinishedSendingEncoder finishedSending = new FinishedSendingEncoder();
        wrap(finishedSending, FinishedSendingEncoder.BLOCK_LENGTH);

        finishedSending
            .sessionID(sessionId)
            .sessionVerID(sessionVerID)
            .lastSeqNo(lastSeqNo);

        write();
    }

    public void readFinishedReceiving()
    {
        final FinishedReceivingDecoder finishedReceiving = read(new FinishedReceivingDecoder(), 0);
        assertEquals(sessionId, finishedReceiving.sessionID());
        assertEquals(sessionVerID, finishedReceiving.sessionVerID());
    }

    public void readFinishedSending(final int lastSeqNo)
    {
        final FinishedSendingDecoder finishedSending = read(new FinishedSendingDecoder(), 0);
        assertEquals(sessionId, finishedSending.sessionID());
        assertEquals(sessionVerID, finishedSending.sessionVerID());
        assertEquals(lastSeqNo, finishedSending.lastSeqNo());
    }

    public void readSequence(final long nextSeqNo)
    {
        final SequenceDecoder sequence = read(new SequenceDecoder(), 0);
        assertEquals(nextSeqNo, sequence.nextSeqNo());
    }

    public void writeFinishedReceiving()
    {
        final FinishedReceivingEncoder finishedReceiving = new FinishedReceivingEncoder();
        wrap(finishedReceiving, FinishedReceivingEncoder.BLOCK_LENGTH);

        finishedReceiving
            .sessionID(sessionId)
            .sessionVerID(sessionVerID);

        write();
    }

    public void writeRetransmitRequest(final long fromSeqNo, final long count)
    {
        writeRetransmitRequest(sessionId, fromSeqNo, count);
    }

    public void writeRetransmitRequest(final int sessionId, final long fromSeqNo, final long count)
    {
        writeRetransmitRequest(sessionId, fromSeqNo, count, timeInNs());
    }

    public void writeRetransmitRequest(final int sessionId, final long fromSeqNo, final long count, final long timeInNs)
    {
        final RetransmitRequestEncoder retransmitRequest = new RetransmitRequestEncoder();
        wrap(retransmitRequest, RetransmitRequestEncoder.BLOCK_LENGTH);

        retransmitRequestTimestampInNs = timeInNs;

        retransmitRequest
            .sessionID(sessionId)
            .timestamp().time(retransmitRequestTimestampInNs);
        retransmitRequest
            .fromSeqNo(fromSeqNo)
            .count(count);

        write();
    }

    public void writeSequence(final int nextSeqNo)
    {
        final SequenceEncoder sequence = new SequenceEncoder();
        wrap(sequence, SequenceEncoder.BLOCK_LENGTH);

        sequence.nextSeqNo(nextSeqNo);

        write();
    }

    public void readNotApplied(final int fromSeqNo, final int count)
    {
        final NotAppliedDecoder notApplied = read(new NotAppliedDecoder(), 0);
        assertEquals(fromSeqNo, notApplied.fromSeqNo());
        assertEquals(count, notApplied.count());
    }

    public void readRetransmitReject(final RetransmitRejectCode rejectCode)
    {
        final RetransmitRejectDecoder retransmitReject = read(new RetransmitRejectDecoder(), 0);
        assertEquals(sessionId, retransmitReject.sessionID());
        assertEquals(retransmitRequestTimestampInNs, retransmitReject.requestTimestamp().time());
        assertEquals(rejectCode, retransmitReject.retransmitRejectCode());
    }

    public void readRetransmission(final long nextSeqNo, final long count)
    {
        final RetransmissionDecoder retransmission = read(new RetransmissionDecoder(), 0);
        assertEquals(sessionId, retransmission.sessionID());
        assertEquals(nextSeqNo, retransmission.nextSeqNo());
        assertEquals(count, retransmission.count());
        assertEquals(retransmitRequestTimestampInNs, retransmission.requestTimestamp().time());
    }

    public long timeInNs()
    {
        return epochNanoClock.nanoTime();
    }

    public void readBusinessReject(final long refSeqNum, final long rejectRefID)
    {
        final BusinessMessageRejectDecoder businessReject = read(new BusinessMessageRejectDecoder(),
            BusinessMessageRejectDecoder.textHeaderLength() +
            BusinessMessageRejectDecoder.memoHeaderLength());
        assertEquals(refSeqNum, businessReject.refSeqNum());
        assertEquals(MessageType.NewOrderSingle, businessReject.refMsgType());
        assertEquals(rejectRefID, businessReject.businessRejectRefID());
        assertEquals(33000, businessReject.businessRejectReason());
    }
}
