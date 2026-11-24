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

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.MonitoringAgentFactory;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.builder.TestRequestEncoder;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.messages.MetaDataStatus;
import uk.co.real_logic.artio.messages.SessionReplyStatus;

import java.util.concurrent.locks.LockSupport;

import static org.agrona.BitUtil.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.TestFixtures.largeTestReqId;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class MetaDataTest extends AbstractGatewayToGatewaySystemTest
{
    private static final int UPDATE_OFFSET = 1;
    private static final short UPDATE_VALUE = 128;

    @BeforeEach
    public void launch()
    {
        mediaDriver = launchMediaDriver();
        testSystem = new TestSystem();

        launchAcceptingArtio(true);

        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);
        initiatingLibrary = testSystem.connect(initiatingLibraryConfig(libraryAeronPort, initiatingHandler, nanoClock));
    }

    void launchAcceptingArtio(final boolean deleteLogFileDirOnStart)
    {
        final EngineConfiguration acceptingConfig = acceptingConfig(port, ACCEPTOR_ID, INITIATOR_ID, nanoClock)
            .deleteLogFileDirOnStart(deleteLogFileDirOnStart);
        acceptingConfig.monitoringAgentFactory(MonitoringAgentFactory.none());
        acceptingConfig.messageTimingHandler(messageTimingHandler);
        acceptingEngine = FixEngine.launch(acceptingConfig);
        acceptingLibrary = testSystem.connect(acceptingLibraryConfig(acceptingHandler, nanoClock));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReadWrittenSessionMetaData()
    {
        connectSessions();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);

        writeMetaData(writeBuffer);

        final UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReadWrittenSessionMetaDataForFollowerSession()
    {
        createFollowerSession(TEST_REPLY_TIMEOUT_IN_MS);

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);

        retryableWriteMetadata(writeBuffer);

        final UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReadWrittenSessionMetaDataForFollowerSessionAfterRestart()
    {
        createFollowerSession(TEST_REPLY_TIMEOUT_IN_MS);

        final SessionReplyStatus status = requestSession(acceptingLibrary, META_DATA_SESSION_ID, testSystem);
        assertEquals(SessionReplyStatus.OK, status);

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);

        retryableWriteMetadata(writeBuffer);

        restartArtio();

        final UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReadWrittenSessionSendMetaData()
    {
        connectSessions();

        acquireAcceptingSession();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);

        final TestRequestEncoder testRequest = new TestRequestEncoder();
        testRequest.testReqID(testReqId());
        send(writeBuffer, testRequest, 0);

        readMetaDataEqualTo(writeBuffer);

        testSystem.await("Metadata not called", () -> messageTimingHandler.count() >= 2);
        messageTimingHandler.verifyConsecutiveSequenceNumbers(acceptingSession.lastSentMsgSeqNum());
        final DirectBuffer timingBuffer = messageTimingHandler.getMetaData(1);
        assertEquals(writeBuffer, timingBuffer);

        final UnsafeBuffer updateBuffer = updateBuffer();

        send(updateBuffer, testRequest, UPDATE_OFFSET);

        updateExpectedBuffer(writeBuffer, updateBuffer);

        assertEventuallyTrue("Failed to read meta data", () ->
        {
            final UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);

            assertEquals(writeBuffer, readBuffer);

            LockSupport.parkNanos(10_000L);
        });
    }

    @Test
    public void shouldWriteMessageMetaDataWithFragmentedMessage()
    {
        connectSessions();

        acquireAcceptingSession();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);

        final String testReqID = largeTestReqId();
        final TestRequestEncoder testRequest = new TestRequestEncoder();
        testRequest.testReqID(testReqID);
        send(writeBuffer, testRequest, 0);

        readMetaDataEqualTo(writeBuffer);

        assertReceivedSingleHeartbeat(testSystem, acceptingOtfAcceptor, testReqID);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveSessionMetaDataWhenSessionAcquired()
    {
        connectSessions();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_VALUE);
        writeMetaData(writeBuffer);

        acquireAcceptingSession();
        assertEquals(MetaDataStatus.OK, acceptingHandler.lastSessionMetaDataStatus());
        final DirectBuffer readBuffer = acceptingHandler.lastSessionMetaData();
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));
        assertEquals(SIZE_OF_INT, readBuffer.capacity());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldNotReceiveSessionMetaDataWhenSessionAcquiredWithNoMetaData()
    {
        connectSessions();

        acquireAcceptingSession();
        assertEquals(MetaDataStatus.NO_META_DATA, acceptingHandler.lastSessionMetaDataStatus());
        final DirectBuffer readBuffer = acceptingHandler.lastSessionMetaData();
        assertEquals(0, readBuffer.capacity());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldUpdateWrittenSessionMetaDataFittingWithinSlot()
    {
        connectSessions();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_WRONG_VALUE);
        writeMetaData(writeBuffer);

        writeBuffer.putInt(0, META_DATA_VALUE);
        writeMetaData(writeBuffer);

        UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));

        final UnsafeBuffer updateBuffer = updateBuffer();

        final Reply<MetaDataStatus> reply = writeMetaData(updateBuffer, META_DATA_SESSION_ID, UPDATE_OFFSET);
        assertEquals(MetaDataStatus.OK, reply.resultIfPresent());

        updateExpectedBuffer(writeBuffer, updateBuffer);

        readBuffer = readSuccessfulMetaData(writeBuffer);
        assertEquals(writeBuffer, readBuffer);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldUpdateWrittenSessionMetaDataTooBigForOldSlot()
    {
        connectSessions();

        final UnsafeBuffer writeBuffer = writeBuffer(META_DATA_WRONG_VALUE);
        writeMetaData(writeBuffer);

        final UnsafeBuffer bigWriteBuffer = new UnsafeBuffer(new byte[SIZE_OF_LONG]);
        bigWriteBuffer.putLong(0, META_DATA_VALUE);
        writeMetaData(bigWriteBuffer);

        UnsafeBuffer readBuffer = readSuccessfulMetaData(bigWriteBuffer);
        assertEquals(META_DATA_VALUE, readBuffer.getInt(0));

        final UnsafeBuffer updateBuffer = updateBuffer();

        final int bigUpdateOffset = SIZE_OF_LONG;
        final Reply<MetaDataStatus> reply = writeMetaData(updateBuffer, META_DATA_SESSION_ID, bigUpdateOffset);
        assertEquals(MetaDataStatus.OK, reply.resultIfPresent());

        final UnsafeBuffer aggregatedBuffer = new UnsafeBuffer(new byte[bigUpdateOffset + SIZE_OF_SHORT]);
        aggregatedBuffer.putLong(0, META_DATA_VALUE);
        aggregatedBuffer.putShort(bigUpdateOffset, UPDATE_VALUE);

        readBuffer = readSuccessfulMetaData(aggregatedBuffer);
        assertEquals(aggregatedBuffer, readBuffer);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveReadErrorForUnwrittenSessionMetaData()
    {
        connectSessions();

        assertNoMetaData();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveReadErrorForMetaDataWithUnknownSession()
    {
        connectSessions();

        assertUnknownSessionMetaData(META_DATA_WRONG_SESSION_ID);
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldReceiveWriteErrorForMetaDataWithUnknownSession()
    {
        connectSessions();

        final UnsafeBuffer writeBuffer = new UnsafeBuffer(new byte[SIZE_OF_INT]);
        final Reply<?> reply = writeMetaData(writeBuffer, META_DATA_WRONG_SESSION_ID);
        assertEquals(MetaDataStatus.UNKNOWN_SESSION, reply.resultIfPresent());
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldResetMetaDataWhenSequenceNumberResetsWithLogon()
    {
        connectSessions();

        writeMetaDataThenDisconnect();

        // Support reading meta data after logout, but before sequence number reset
        final FakeMetadataHandler handler = readMetaData(META_DATA_SESSION_ID);
        assertEquals(MetaDataStatus.OK, handler.status());

        connectSessions();

        assertNoMetaData();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldResetMetaDataWhenSequenceNumberResetsWhenSessionIdExplicitlyReset()
    {
        connectSessions();

        writeMetaDataThenDisconnect();

        testSystem.resetSequenceNumber(acceptingEngine, META_DATA_SESSION_ID);

        assertNoMetaData();

        // sequence index reset so no need to validate it
        acceptingSession = null;
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldResetMetaDataWhenSequenceNumberResetsWithExplicitResetSessionIds()
    {
        connectSessions();

        writeMetaDataThenDisconnect();

        // Tests the deprecated resetSessionIds on purpose, since it's still in use.
        @SuppressWarnings("deprecation")
        // codeql[java/deprecated-call]
        final Reply<?> reply = acceptingEngine.resetSessionIds(null);
        testSystem.awaitCompletedReplies(reply);

        assertUnknownSessionMetaData(META_DATA_SESSION_ID);

        connectSessions();
        acquireAcceptingSession();

        assertNoMetaData();
    }

    private void restartArtio()
    {
        Exceptions.closeAll(
            this::closeAcceptingEngine,
            this::closeAcceptingLibrary);

        launchAcceptingArtio(false);
    }

    private void retryableWriteMetadata(final UnsafeBuffer writeBuffer)
    {
        while (true)
        {
            final Reply<MetaDataStatus> reply = writeMetaData(writeBuffer, META_DATA_SESSION_ID);
            final MetaDataStatus status = reply.resultIfPresent();
            if (status != MetaDataStatus.UNKNOWN_SESSION)
            {
                assertEquals(MetaDataStatus.OK, status);
                break;
            }
        }
    }

    private void assertUnknownSessionMetaData(final long sessionId)
    {
        final FakeMetadataHandler handler = readMetaData(sessionId);
        assertEquals(MetaDataStatus.UNKNOWN_SESSION, handler.status());
    }

    private void assertNoMetaData()
    {
        final FakeMetadataHandler handler = readMetaData(META_DATA_SESSION_ID);
        assertEquals(MetaDataStatus.NO_META_DATA, handler.status());
    }

    private void writeMetaDataThenDisconnect()
    {
        writeMetaData();

        acquireAcceptingSession();
        disconnectSessions();
    }

    private void send(
        final UnsafeBuffer writeBuffer, final TestRequestEncoder testRequest, final int metaDataUpdateOffset)
    {
        assertThat(acceptingSession.trySend(testRequest, writeBuffer, metaDataUpdateOffset), greaterThan(0L));
    }

    private UnsafeBuffer updateBuffer()
    {
        final UnsafeBuffer updateBuffer = new UnsafeBuffer(new byte[SIZE_OF_SHORT]);
        updateBuffer.putShort(0, UPDATE_VALUE);
        return updateBuffer;
    }

    private void updateExpectedBuffer(final UnsafeBuffer writeBuffer, final UnsafeBuffer updateBuffer)
    {
        writeBuffer.putBytes(UPDATE_OFFSET, updateBuffer, 0, SIZE_OF_SHORT);
    }

    private void readMetaDataEqualTo(final UnsafeBuffer writeBuffer)
    {
        assertEventuallyTrue("Failed to read meta data", () ->
        {
            final UnsafeBuffer readBuffer = readSuccessfulMetaData(writeBuffer);
            assertEquals(META_DATA_VALUE, readBuffer.getInt(0));

            LockSupport.parkNanos(10_000L);
        });
    }

    private UnsafeBuffer writeBuffer(final int metaDataValue)
    {
        final UnsafeBuffer writeBuffer = new UnsafeBuffer(new byte[SIZE_OF_INT]);
        writeBuffer.putInt(0, metaDataValue);
        return writeBuffer;
    }
}
