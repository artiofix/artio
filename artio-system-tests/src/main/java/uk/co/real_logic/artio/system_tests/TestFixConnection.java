/*
 * Copyright 2015-2021 Real Logic Limited.
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

import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.ExecType;
import uk.co.real_logic.artio.OrdStatus;
import uk.co.real_logic.artio.Side;
import uk.co.real_logic.artio.builder.*;
import uk.co.real_logic.artio.decoder.*;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static uk.co.real_logic.artio.LogTag.FIX_TEST;

public final class TestFixConnection implements AutoCloseable
{
    public static final int BUFFER_SIZE = 8 * 1024;
    private static final int OFFSET = 0;

    public static final String PROXY_SOURCE_IP = "192.168.0.1";
    public static final int PROXY_SOURCE_PORT = 56324;

    public static final String LARGEST_PROXY_SOURCE_IP = "ffff:f...f:ffff";
    public static final int LARGEST_PROXY_SOURCE_PORT = 65535;

    public static final int PROXY_V2_SOURCE_PORT = 56546;

    public static final String PROXY_V2_IPV6_SOURCE_IP = "fdaa:bbcc:ddee:0:5e8:349b:d23d:f168";
    public static final int PROXY_V2_IPV6_SOURCE_PORT = 44858;

    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final MutableAsciiBuffer writeAsciiBuffer = new MutableAsciiBuffer(writeBuffer);

    private final UtcTimestampEncoder sendingTimeEncoder = new UtcTimestampEncoder();
    private final UtcTimestampEncoder origSendingTimeEncoder = new UtcTimestampEncoder();
    private final LogonEncoder logon = new LogonEncoder();
    private final LogoutEncoder logout = new LogoutEncoder();
    private final TestRequestEncoder testRequestEncoder = new TestRequestEncoder();

    private final SocketChannel socket;
    private final String senderCompID;
    private final String targetCompID;

    private int msgSeqNum = 1;

    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final MutableAsciiBuffer asciiReadBuffer = new MutableAsciiBuffer(readBuffer);
    private int endOfMessage;
    private int bytesRemaining = 0;
    private String ascii;
    private String password;

    public TestFixConnection(final SocketChannel socket, final String senderCompID, final String targetCompID)
    {
        this.socket = socket;
        this.senderCompID = senderCompID;
        this.targetCompID = targetCompID;
    }

    // Can read data
    public boolean isConnected()
    {
        try
        {
            final int read = socket.read(readBuffer);
            final boolean isConnected = read != -1;

            if (isConnected)
            {
                final String ascii = asciiReadBuffer.getAscii(readBuffer.position() - read, read);
                DebugLogger.log(FIX_TEST, "< [" + ascii + "] for isConnected()");
            }

            return isConnected;
        }
        catch (final IOException e)
        {
            return false;
        }
    }

    void sendProxyV1Line()
    {
        final int length = writeAsciiBuffer.putAscii(
            0, "PROXY TCP4 " + PROXY_SOURCE_IP + " 192.168.0.11 " + PROXY_SOURCE_PORT + " 443\r\n");
        send(0, length);
    }

    void sendProxyV1LargestLine()
    {
        final int length = writeAsciiBuffer.putAscii(
            0, "PROXY UNKNOWN " + LARGEST_PROXY_SOURCE_IP +
            " ffff:f...f:ffff " + LARGEST_PROXY_SOURCE_PORT + " 65535\r\n");
        send(0, length);
    }

    void sendProxyV2LineTcpV4()
    {
        final byte[] bytes =
        {
            13, 10, 13, 10,
            0, 13, 10, 81,
            85, 73, 84, 10,
            33, 17, 0, 12,

            -64, -88, 0, 1,
            -64, -88, 0, 1,
            -36, -30,
            19, -120,
        };

        sendBytes(bytes);
    }

    void sendProxyV2LineTcpV6()
    {
        final byte[] bytes =
        {
            13, 10, 13, 10,
            0, 13, 10, 81,
            85, 73, 84, 10,
            33, 33, 0, 36,

            // ipv6 source addr
            -3, -86, -69, -52,
            -35, -18, 0, 0,
            5, -24, 52, -101,
            -46, 61, -15, 104,

            // ipv6 dest addr
            -3, -86, -69, -52,
            -35, -18, 0, 0,
            5, -24, 52, -101,
            -46, 61, -15, 104,

            // ipv6 source port
            -81, 58,

            // ipv6 dest port
            19, -120
        };

        sendBytes(bytes);
    }

    void sendProxyV2LineTcpV6Localhost()
    {
        final byte[] bytes =
        {
            13, 10, 13, 10,
            0, 13, 10, 81,
            85, 73, 84, 10,
            33, 33, 0, 36,

            // ipv6 source addr
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 1,

            // ipv6 dest addr
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 1,

            // ipv6 source port
            -81, 58,

            // ipv6 dest port
            19, -120
        };

        sendBytes(bytes);
    }

    public void sendBytes(final byte[] bytes)
    {
        final int length = bytes.length;
        writeAsciiBuffer.putBytes(0, bytes);

        send(0, length);
    }

    public void logon(final boolean resetSeqNumFlag)
    {
        logon(resetSeqNumFlag, 30);
    }

    public void logon(final boolean resetSeqNumFlag, final int heartBtInt)
    {
        logon(resetSeqNumFlag, heartBtInt, false);
    }

    public void logon(final boolean resetSeqNumFlag, final int heartBtInt, final boolean possDupFlag)
    {
        setupHeader(logon.header(), msgSeqNum++, possDupFlag);

        logon
            .resetSeqNumFlag(resetSeqNumFlag)
            .encryptMethod(0)
            .heartBtInt(heartBtInt)
            .maxMessageSize(9999);

        if (password != null)
        {
            logon.password(password);
        }

        send(logon);
    }

    public TestFixConnection msgSeqNum(final int msgSeqNum)
    {
        this.msgSeqNum = msgSeqNum;
        return this;
    }

    public int acquireMsgSeqNum()
    {
        return this.msgSeqNum++;
    }

    public void logout()
    {
        setupHeader(logout.header(), msgSeqNum++, false);

        send(logout);
    }

    public void password(final String password)
    {
        this.password = password;
    }

    public void setupHeader(final SessionHeaderEncoder header, final int msgSeqNum, final boolean possDupFlag)
    {
        final long timestamp = System.currentTimeMillis();
        final int timestampLength = sendingTimeEncoder.encode(timestamp);

        header
            .senderCompID(senderCompID)
            .targetCompID(targetCompID)
            .msgSeqNum(msgSeqNum)
            .sendingTime(sendingTimeEncoder.buffer(), timestampLength);

        if (possDupFlag)
        {
            final int origSendingTimeLength = origSendingTimeEncoder.encode(timestamp - 1000);

            header
                .possDupFlag(true)
                .origSendingTime(origSendingTimeEncoder.buffer(), origSendingTimeLength);
        }
    }

    public ExecutionReportDecoder readExecutionReport()
    {
        return readMessage(new ExecutionReportDecoder());
    }

    public ExecutionReportDecoder readExecutionReport(final int msgSeqNum)
    {
        final ExecutionReportDecoder executionReport = readExecutionReport();
        assertSeqNum(msgSeqNum, executionReport);
        return executionReport;
    }

    public <T extends Decoder> T readMessage(final T decoder)
    {
        try
        {
            final int bytesToParse = bytesRemaining == 0 ? socket.read(readBuffer) : bytesRemaining;
            ascii = asciiReadBuffer.getAscii(OFFSET, bytesToParse);

            DebugLogger.log(FIX_TEST,
                "< [" + ascii + "] for attempted: " + decoder.getClass());

            endOfMessage = ascii.indexOf("8=FIX.4.4", 9);
            if (endOfMessage == -1)
            {
                endOfMessage = bytesToParse;
            }

            decoder.decode(asciiReadBuffer, OFFSET, endOfMessage);

            if (!decoder.validate())
            {
                // TODO: fail("Failed: " + RejectReason.decode(decoder.rejectReason()) + " for " +
                //  decoder.invalidTagId());
            }

            // check MsgType in case we read an unexpected message, but with a compatible structure
            try
            {
                final Field messageTypeAsStringField = decoder.getClass().getDeclaredField("MESSAGE_TYPE_AS_STRING");
                final String expectedMsgType = (String)messageTypeAsStringField.get(null);
                final SessionHeaderDecoder header = decoder.header();
                final String actualMsgType = new String(header.msgType(), 0, header.msgTypeLength());
                // TODO: assertEquals("MsgType", expectedMsgType, actualMsgType);
            }
            catch (final NoSuchFieldException | IllegalAccessException e)
            {
                LangUtil.rethrowUnchecked(e);
            }

            readBuffer.clear();
            if (endOfMessage != -1)
            {
                bytesRemaining = bytesToParse - endOfMessage;
                asciiReadBuffer.putBytes(0, asciiReadBuffer, endOfMessage, bytesRemaining);
            }
            else
            {
                bytesRemaining = 0;
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return decoder;
    }

    int pollData() throws IOException
    {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        final MutableAsciiBuffer asciiBuffer = new MutableAsciiBuffer(buffer);

        socket.configureBlocking(false);
        final int read = socket.read(buffer);
        socket.configureBlocking(true);
        if (read > 0)
        {
            DebugLogger.log(FIX_TEST, "< [" + asciiBuffer.getAscii(OFFSET, read) + "] for poll");
        }
        return read;
    }

    public void send(final Encoder encoder)
    {
        final long result = encoder.encode(writeAsciiBuffer, OFFSET);
        final int offset = Encoder.offset(result);
        final int length = Encoder.length(result);
        encoder.reset();

        send(offset, length);
    }

    public void awaitDisconnect()
    {
        try
        {
            final int read = socket.read(readBuffer);
            if (read > 0)
            {
                awaitDisconnect();
            }
        }
        catch (final IOException e)
        {
            if (socket.isConnected())
            {
                LangUtil.rethrowUnchecked(e);
            }
        }
    }

    private void send(final int offset, final int length)
    {
        try
        {
            writeBuffer.position(offset).limit(offset + length);
            final int written = socket.write(writeBuffer);
            if (length != written)
            {
                throw new IllegalStateException("TODO");
            }
            DebugLogger.log(FIX_TEST, "> [" + writeAsciiBuffer.getAscii(offset, length) + "]");
            writeBuffer.clear();
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public LogonDecoder readLogon()
    {
        return readMessage(new LogonDecoder());
    }

    public LogonDecoder readLogon(final int msgSeqNum)
    {
        final LogonDecoder logonReply = readLogon();
        assertSeqNum(msgSeqNum, logonReply);
        return logonReply;
    }

    private void assertSeqNum(final int msgSeqNum, final Decoder decoder)
    {
        // TODO; assertEquals(decoder.toString(), msgSeqNum, decoder.header().msgSeqNum());
    }

    public RejectDecoder readReject()
    {
        return readMessage(new RejectDecoder());
    }

    public BusinessMessageRejectDecoder readBusinessReject()
    {
        return readMessage(new BusinessMessageRejectDecoder());
    }

    public NewOrderSingleDecoder readOrder()
    {
        return readMessage(new NewOrderSingleDecoder());
    }

    public void sendTestRequest(final String testReqID)
    {
        setupHeader(testRequestEncoder.header(), msgSeqNum++, false);
        testRequestEncoder.testReqID(testReqID);
        send(testRequestEncoder);
    }

    public String lastMessageAsString()
    {
        return asciiReadBuffer.getAscii(OFFSET, endOfMessage);
    }

    public String lastTotalBytesRead()
    {
        return ascii;
    }

    public HeartbeatDecoder readHeartbeat()
    {
        return readMessage(new HeartbeatDecoder());
    }

    @Override
    public void close()
    {
        CloseHelper.close(socket);
    }

    public LogoutDecoder readLogout()
    {
        return readMessage(new LogoutDecoder());
    }

    public void sendGapFill(final int msgSeqNum, final int newMsgSeqNum)
    {
        final SequenceResetEncoder sequenceResetEncoder = new SequenceResetEncoder();
        final HeaderEncoder headerEncoder = sequenceResetEncoder.header();

        setupHeader(headerEncoder, msgSeqNum, true);
        sequenceResetEncoder.newSeqNo(newMsgSeqNum)
            .gapFillFlag(true);

        send(sequenceResetEncoder);
    }

    public void sendExecutionReport(final int msgSeqNum, final boolean possDupFlag)
    {
        final ExecutionReportEncoder executionReportEncoder = new ExecutionReportEncoder();
        final HeaderEncoder header = executionReportEncoder.header();

        setupHeader(header, msgSeqNum, possDupFlag);

        executionReportEncoder
            .orderID("order")
            .execID("exec")
            .execType(ExecType.FILL)
            .ordStatus(OrdStatus.FILLED)
            .side(Side.BUY);

        executionReportEncoder.instrument().symbol("IBM");

        send(executionReportEncoder);
    }

    public ResendRequestEncoder sendResendRequest(final int beginSeqNo, final int endSeqNo)
    {
        final ResendRequestEncoder resendRequest = new ResendRequestEncoder();

        resendRequest.beginSeqNo(beginSeqNo).endSeqNo(endSeqNo);
        setupHeader(resendRequest.header(), msgSeqNum++, false);
        send(resendRequest);

        return resendRequest;
    }

    public int msgSeqNum()
    {
        return msgSeqNum;
    }
}
