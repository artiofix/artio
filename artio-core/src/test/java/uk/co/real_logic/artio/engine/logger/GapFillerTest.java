package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.driver.DutyCycleTracker;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.artio.builder.Encoder;
import uk.co.real_logic.artio.builder.ResendRequestEncoder;
import uk.co.real_logic.artio.decoder.HeaderDecoder;
import uk.co.real_logic.artio.decoder.SequenceResetDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.engine.ReplayerCommandQueue;
import uk.co.real_logic.artio.engine.SenderSequenceNumber;
import uk.co.real_logic.artio.engine.SenderSequenceNumbers;
import uk.co.real_logic.artio.fields.EpochFractionFormat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.messages.MessageStatus;
import uk.co.real_logic.artio.messages.ReplayCompleteDecoder;
import uk.co.real_logic.artio.messages.StartReplayDecoder;
import uk.co.real_logic.artio.messages.ValidResendRequestEncoder;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.util.List;

import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_FLAG;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SEQUENCE_RESET_MESSAGE_TYPE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.artio.engine.logger.AbstractReplayer.REPLAY_COMPLETE_LEN;
import static uk.co.real_logic.artio.engine.logger.AbstractReplayer.START_REPLAY_LENGTH;

class GapFillerTest
{
    private static final long SESSION_ID = 1;
    private static final long SESSION_ID_2 = 2;
    private static final long CONNECTION_ID = 1;
    private static final long CONNECTION_ID_2 = 2;
    private static final int BEGIN_SEQ_NO = 1;
    private static final int END_SEQ_NO = 10;
    private static final int BEGIN_SEQ_NO_2 = 10;
    private static final int END_SEQ_NO_2 = 20;
    private static final int SEQUENCE_INDEX = 1;
    private static final long CORRELATION_ID = 12325236;
    private static final long CORRELATION_ID_2 = 987432932;
    private static final String SENDER = "sender";
    private static final String TARGET = "target";
    private static final String SENDER2 = "sender2";
    private static final String TARGET2 = "target2";

    private final UnsafeBuffer inboundBuffer = new UnsafeBuffer(new byte[4096]);
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final ValidResendRequestEncoder validResendRequestEncoder = new ValidResendRequestEncoder();
    private final MutableAsciiBuffer inboundFixBuffer = new MutableAsciiBuffer(new byte[4096]);
    private final ResendRequestEncoder resendRequestEncoder = new ResendRequestEncoder();

    private final UnsafeBuffer startReplayBuffer = new UnsafeBuffer(new byte[START_REPLAY_LENGTH]);
    private final UnsafeBuffer sendCompleteBuffer = new UnsafeBuffer(new byte[REPLAY_COMPLETE_LEN]);
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final StartReplayDecoder startReplayDecoder = new StartReplayDecoder();
    private final ReplayCompleteDecoder replayCompleteDecoder = new ReplayCompleteDecoder();
    private final ArgumentCaptor<DirectBuffer> gapFillBufferCaptor = ArgumentCaptor.forClass(DirectBuffer.class);
    private final ArgumentCaptor<Integer> gapFillBufferOffsetCaptor = ArgumentCaptor.forClass(Integer.class);
    private final ArgumentCaptor<Integer> gapFillBufferLengthCaptor = ArgumentCaptor.forClass(Integer.class);
    private final MutableAsciiBuffer outboundFixBuffer = new MutableAsciiBuffer();
    private final SequenceResetDecoder sequenceResetDecoder = new SequenceResetDecoder();

    private GapFiller gapFiller;

    private final BufferClaim claim = mock(BufferClaim.class);
    private final ExclusivePublication publication = mock(ExclusivePublication.class);
    private final GatewayPublication gatewayPublication = mock(GatewayPublication.class);
    private final SenderSequenceNumbers senderSequenceNumbers = mock(SenderSequenceNumbers.class);
    private final ReplayerCommandQueue replayerCommandQueue = mock(ReplayerCommandQueue.class);
    private final FixSessionCodecsFactory sessionCodecsFactory = mock(FixSessionCodecsFactory.class);
    private final AtomicCounter gapfillerSessionCounter =
        new AtomicCounter(new UnsafeBuffer(new byte[SIZE_OF_LONG]), 0);
    private final EpochNanoClock epochNanoClock = mock(EpochNanoClock.class);
    private final DutyCycleTracker dutyCycleTracker = mock(DutyCycleTracker.class);
    private final Header header = mock(Header.class);

    @BeforeEach
    void setup()
    {
        when(gatewayPublication.dataPublication()).thenReturn(publication);
        when(header.flags()).thenReturn((byte)BEGIN_FLAG);

        this.gapFiller = new GapFiller(
            gatewayPublication,
            claim,
            "",
            senderSequenceNumbers,
            replayerCommandQueue,
            sessionCodecsFactory,
            gapfillerSessionCounter,
            epochNanoClock,
            dutyCycleTracker
        );
    }

    @Test
    void shouldGapfill()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillWaitingForSenderSequenceNumber()
    {
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        for (int i = 0; i < 20; ++i)
        {
            gapFiller.doWork();
        }
        verifyInteractionsWithPublication(times(0));

        setupSenderSequenceNumber(false);

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldNotGapfillFixP()
    {
        setupSenderSequenceNumber(true);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillWaitingForSessionCodecs()
    {
        setupSenderSequenceNumber(false);

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        for (int i = 0; i < 20; ++i)
        {
            gapFiller.doWork();
        }
        verifyInteractionsWithPublication(times(0));

        setupSessionCodecs();

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillWithStartReplayBackPressure()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        for (int i = 0; i < 20; ++i)
        {
            gapFiller.doWork();
        }
        verifyInteractionsWithPublication(times(20));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(21));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillWithGapFillBackPressure()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        backPressureGapfill();
        for (int i = 0; i < 20; ++i)
        {
            gapFiller.doWork();
        }
        verify(gatewayPublication, times(20)).saveMessage(
            any(), anyInt(), anyInt(),
            anyInt(), anyLong(), anyLong(), anyInt(), anyLong(),
            eq(MessageStatus.OK), anyInt());
        assertEquals(1, gapfillerSessionCounter.get());

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(21));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillWithSendCompleteBackPressure()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        for (int i = 0; i < 20; ++i)
        {
            gapFiller.doWork();
        }
        verifyInteractionsWithPublication(times(21));
        assertEquals(1, gapfillerSessionCounter.get());

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(21));

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldAbortGapfillIfConnectionDisconnects()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        when(senderSequenceNumbers.hasDisconnected(CONNECTION_ID)).thenReturn(true);

        gapFiller.doWork();
        verify(gatewayPublication, times(0)).saveMessage(
            any(), anyInt(), anyInt(),
            anyInt(), anyLong(), anyLong(), anyInt(), anyLong(),
            any(), anyInt());

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillMultipleRequests()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);

        setupValidResendRequest(
            SESSION_ID, CONNECTION_ID, BEGIN_SEQ_NO_2, END_SEQ_NO_2, CORRELATION_ID_2, SENDER, TARGET);
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(1, gapfillerSessionCounter.get());

        /* First gapfill */

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(1));

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(1));

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(1));

        /* Second gapfill */

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(2));

        captureStartReplay();
        gapFiller.doWork();
        verifyStartReplay(times(2), SESSION_ID, CONNECTION_ID, CORRELATION_ID_2);

        captureGapfill();
        gapFiller.doWork();
        verifyGapfill(times(2), BEGIN_SEQ_NO_2, END_SEQ_NO_2 + 1);

        captureSendComplete();
        gapFiller.doWork();
        verifySendComplete(times(2), CONNECTION_ID, CORRELATION_ID_2);

        assertEquals(0, gapfillerSessionCounter.get());
    }

    @Test
    void shouldGapfillMultipleConnections()
    {
        setupSenderSequenceNumber(false);
        setupSessionCodecs();

        setupValidResendRequest();
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);

        setupValidResendRequest(
            SESSION_ID_2, CONNECTION_ID_2, BEGIN_SEQ_NO_2, END_SEQ_NO_2, CORRELATION_ID_2, SENDER2, TARGET2);
        gapFiller.onFragment(inboundBuffer, 0, validResendRequestEncoder.limit(), header);
        assertEquals(2, gapfillerSessionCounter.get());

        gapFiller.doWork();
        verifyInteractionsWithPublication(times(0));

        captureStartReplay();
        gapFiller.doWork();
        verify(publication, times(2)).tryClaim(START_REPLAY_LENGTH, claim);

        captureGapfill();
        gapFiller.doWork();
        verifyMultipleGapfills(
            SENDER, TARGET, BEGIN_SEQ_NO, END_SEQ_NO + 1,
            SENDER2, TARGET2, BEGIN_SEQ_NO_2, END_SEQ_NO_2 + 1);

        captureSendComplete();
        gapFiller.doWork();
        verify(publication, times(2)).tryClaim(REPLAY_COMPLETE_LEN, claim);

        assertEquals(0, gapfillerSessionCounter.get());
    }

    private void setupSenderSequenceNumber(final boolean fixP)
    {
        final AtomicCounter bytesInBufferCounter = mock(AtomicCounter.class);
        final SenderSequenceNumber senderSequenceNumber = mock(SenderSequenceNumber.class);
        when(senderSequenceNumber.fixP()).thenReturn(fixP);
        when(senderSequenceNumber.bytesInBuffer()).thenReturn(bytesInBufferCounter);
        when(senderSequenceNumbers.senderSequenceNumber(anyLong())).thenReturn(senderSequenceNumber);
        when(senderSequenceNumbers.hasDisconnected(anyLong())).thenReturn(false);
    }

    private void setupSessionCodecs()
    {
        final Class<? extends FixDictionary> fixDictionaryClass = FixDictionary.findDefault();
        final UtcTimestampEncoder utcTimestampEncoder = new UtcTimestampEncoder(EpochFractionFormat.NANOSECONDS);
        final SystemEpochNanoClock clock = new SystemEpochNanoClock();
        when(sessionCodecsFactory.get(anyLong()))
            .thenReturn(new FixReplayerCodecs(fixDictionaryClass, utcTimestampEncoder, clock));
    }

    private void setupValidResendRequest()
    {
        setupValidResendRequest(SESSION_ID, CONNECTION_ID, BEGIN_SEQ_NO, END_SEQ_NO, CORRELATION_ID, SENDER, TARGET);
    }

    private void setupValidResendRequest(
        final long sessionId,
        final long connectionId,
        final int beginSeqNo,
        final int endSeqNo,
        final long correlationId,
        final String localCompId,
        final String remoteCompId)
    {
        validResendRequestEncoder.wrapAndApplyHeader(inboundBuffer, 0, headerEncoder)
            .session(sessionId)
            .connection(connectionId)
            .beginSequenceNumber(beginSeqNo)
            .endSequenceNumber(endSeqNo)
            .sequenceIndex(SEQUENCE_INDEX)
            .correlationId(correlationId)
            .overriddenBeginSequenceNumber(ValidResendRequestEncoder.overriddenBeginSequenceNumberNullValue());

        final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
        timestampEncoder.encode(System.currentTimeMillis());

        resendRequestEncoder.header()
            .senderCompID(remoteCompId)
            .targetCompID(localCompId)
            .msgSeqNum(beginSeqNo)
            .sendingTime(timestampEncoder.buffer());

        resendRequestEncoder
            .beginSeqNo(beginSeqNo)
            .endSeqNo(endSeqNo);

        final long result = resendRequestEncoder.encode(inboundFixBuffer, 0);
        final int offset = Encoder.offset(result);
        final int length = Encoder.length(result);

        validResendRequestEncoder.putBody(inboundFixBuffer, offset, length);
    }

    private void captureStartReplay()
    {
        when(publication.tryClaim(START_REPLAY_LENGTH, claim)).thenReturn((long)START_REPLAY_LENGTH);
        when(claim.buffer()).thenReturn(startReplayBuffer);
        when(claim.offset()).thenReturn(0);
        when(claim.length()).thenReturn(START_REPLAY_LENGTH);
    }

    private void verifyStartReplay(final VerificationMode verificationMode)
    {
        verifyStartReplay(verificationMode, SESSION_ID, CONNECTION_ID, CORRELATION_ID);
    }

    private void verifyStartReplay(
        final VerificationMode verificationMode,
        final long sessionId,
        final long connectionId,
        final long correlationId)
    {
        verify(publication, verificationMode).tryClaim(START_REPLAY_LENGTH, claim);

        startReplayDecoder.wrapAndApplyHeader(startReplayBuffer, 0, headerDecoder);
        assertEquals(sessionId, startReplayDecoder.session(), startReplayDecoder.toString());
        assertEquals(connectionId, startReplayDecoder.connection(), startReplayDecoder.toString());
        assertEquals(correlationId, startReplayDecoder.correlationId(), startReplayDecoder.toString());
    }

    private void captureGapfill()
    {
        when(gatewayPublication.saveMessage(
            gapFillBufferCaptor.capture(), gapFillBufferOffsetCaptor.capture(), gapFillBufferLengthCaptor.capture(),
            eq(ENGINE_LIBRARY_ID), eq(SEQUENCE_RESET_MESSAGE_TYPE), anyLong(), anyInt(), anyLong(),
            eq(MessageStatus.OK), anyInt())).thenReturn(100L);
    }

    private void backPressureGapfill()
    {
        when(gatewayPublication.saveMessage(
            any(), anyInt(), anyInt(),
            eq(ENGINE_LIBRARY_ID), eq(SEQUENCE_RESET_MESSAGE_TYPE), anyLong(), anyInt(), anyLong(),
            eq(MessageStatus.OK), anyInt())).thenReturn(0L);
    }

    private void verifyGapfill(final VerificationMode verificationMode)
    {
        verifyGapfill(verificationMode, BEGIN_SEQ_NO, END_SEQ_NO + 1);
    }

    private void verifyGapfill(final VerificationMode verificationMode, final int beginSeqNo, final int newSeqNo)
    {
        verify(gatewayPublication, verificationMode).saveMessage(
            any(), anyInt(), anyInt(),
            eq(ENGINE_LIBRARY_ID), eq(SEQUENCE_RESET_MESSAGE_TYPE), anyLong(), anyInt(), anyLong(),
            eq(MessageStatus.OK), anyInt());

        final DirectBuffer gapFillBuffer = gapFillBufferCaptor.getValue();
        final Integer offset = gapFillBufferOffsetCaptor.getValue();
        final Integer length = gapFillBufferLengthCaptor.getValue();
        outboundFixBuffer.wrap(gapFillBuffer);

        sequenceResetDecoder.decode(outboundFixBuffer, offset, length);

        final HeaderDecoder fixHeader = sequenceResetDecoder.header();
        assertEquals(SENDER, fixHeader.senderCompIDAsString(), sequenceResetDecoder.toString());
        assertEquals(TARGET, fixHeader.targetCompIDAsString(), sequenceResetDecoder.toString());
        assertEquals(beginSeqNo, fixHeader.msgSeqNum(), sequenceResetDecoder.toString());

        assertEquals(newSeqNo, sequenceResetDecoder.newSeqNo(), sequenceResetDecoder.toString());
        assertTrue(sequenceResetDecoder.gapFillFlag(), sequenceResetDecoder.toString());
    }

    private void verifyMultipleGapfills(
        final String localCompId, final String remoteCompId, final int beginSeqNo, final int newSeqNo,
        final String localCompId2, final String remoteCompId2, final int beginSeqNo2, final int newSeqNo2
    )
    {
        final List<DirectBuffer> gapFillBuffers = gapFillBufferCaptor.getAllValues();
        final List<Integer> offsets = gapFillBufferOffsetCaptor.getAllValues();
        final List<Integer> lengths = gapFillBufferLengthCaptor.getAllValues();

        assertEquals(2, gapFillBuffers.size());
        assertEquals(2, offsets.size());
        assertEquals(2, lengths.size());

        for (int i = 0; i < gapFillBuffers.size(); i++)
        {
            outboundFixBuffer.wrap(gapFillBuffers.get(i));
            sequenceResetDecoder.decode(outboundFixBuffer, offsets.get(i), lengths.get(i));

            final HeaderDecoder fixHeader = sequenceResetDecoder.header();
            final String expectedLocalCompId;
            final String expectedRemoteCompId;
            final int expectedBeginSeqNo;
            final int expectedNewSeqNo;
            if (localCompId.equals(fixHeader.senderCompIDAsString()) &&
                remoteCompId.equals(fixHeader.targetCompIDAsString()))
            {
                expectedLocalCompId = localCompId;
                expectedRemoteCompId = remoteCompId;
                expectedBeginSeqNo = beginSeqNo;
                expectedNewSeqNo = newSeqNo;
            }
            else if (localCompId2.equals(fixHeader.senderCompIDAsString()) &&
                remoteCompId2.equals(fixHeader.targetCompIDAsString()))
            {
                expectedLocalCompId = localCompId2;
                expectedRemoteCompId = remoteCompId2;
                expectedBeginSeqNo = beginSeqNo2;
                expectedNewSeqNo = newSeqNo2;
            }
            else
            {
                expectedLocalCompId = "";
                expectedRemoteCompId = "";
                expectedBeginSeqNo = Aeron.NULL_VALUE;
                expectedNewSeqNo = Aeron.NULL_VALUE;
            }


            assertEquals(expectedLocalCompId, fixHeader.senderCompIDAsString(), sequenceResetDecoder.toString());
            assertEquals(expectedRemoteCompId, fixHeader.targetCompIDAsString(), sequenceResetDecoder.toString());
            assertEquals(expectedBeginSeqNo, fixHeader.msgSeqNum(), sequenceResetDecoder.toString());

            assertEquals(expectedNewSeqNo, sequenceResetDecoder.newSeqNo(), sequenceResetDecoder.toString());
            assertTrue(sequenceResetDecoder.gapFillFlag(), sequenceResetDecoder.toString());
        }
    }


    private void captureSendComplete()
    {
        when(publication.tryClaim(REPLAY_COMPLETE_LEN, claim)).thenReturn((long)REPLAY_COMPLETE_LEN);
        when(claim.buffer()).thenReturn(sendCompleteBuffer);
        when(claim.offset()).thenReturn(0);
        when(claim.length()).thenReturn(REPLAY_COMPLETE_LEN);
    }

    private void verifySendComplete(final VerificationMode verificationMode)
    {
        verifySendComplete(verificationMode, CONNECTION_ID, CORRELATION_ID);
    }

    private void verifySendComplete(
        final VerificationMode verificationMode,
        final long connectionId,
        final long correlationId)
    {
        verify(publication, verificationMode).tryClaim(REPLAY_COMPLETE_LEN, claim);

        replayCompleteDecoder.wrapAndApplyHeader(sendCompleteBuffer, 0, headerDecoder);
        assertEquals(ENGINE_LIBRARY_ID, replayCompleteDecoder.libraryId(), replayCompleteDecoder.toString());
        assertEquals(connectionId, replayCompleteDecoder.connection(), replayCompleteDecoder.toString());
        assertEquals(correlationId, replayCompleteDecoder.correlationId(), replayCompleteDecoder.toString());
    }

    private void verifyInteractionsWithPublication(final VerificationMode verificationMode)
    {
        verify(publication, verificationMode).tryClaim(anyInt(), any());
    }
}