package uk.co.real_logic.artio.test.reproduce;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.ExclusivePublication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.status.RecordingPos;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.CountersReader;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.ExecutionReportEncoder;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.storage.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.PreviousRecordingEncoder;
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class Acceptor
{
    public static void main(String[] args) throws Exception
    {
        final AtomicBoolean running = new AtomicBoolean(true);
        SigInt.register(() -> running.set(false));

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName("/dev/shm/acceptor")
            .printConfigurationOnStart(true)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .deleteArchiveOnStart(false)
            .threadingMode(ArchiveThreadingMode.DEDICATED)
            .idleStrategySupplier(NoOpIdleStrategy::new)
            .replayerIdleStrategySupplier(NoOpIdleStrategy::new)
            .recorderIdleStrategySupplier(NoOpIdleStrategy::new)
            .aeronDirectoryName("/dev/shm/acceptor")
            .archiveDirectoryName("acceptor")
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .controlChannel("aeron:udp?endpoint=localhost:0");

        final EngineConfiguration engineConfiguration = new EngineConfiguration()
            .archiverIdleStrategy(NoOpIdleStrategy.INSTANCE)
            .framerIdleStrategy(NoOpIdleStrategy.INSTANCE)
            .bindTo("localhost", 9999)
            .logFileDir("acceptor-logs")
            .initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY)
            .deleteLogFileDirOnStart(false)
            .libraryAeronChannel("aeron:ipc")
            .sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysPersistent())
            .inboundMaxClaimAttempts(5);

        engineConfiguration.aeronContext()
            .aeronDirectoryName("/dev/shm/acceptor");

        engineConfiguration.aeronArchiveContext()
            .controlRequestChannel("aeron:ipc")
            .controlResponseChannel("aeron:ipc");

        final TestSessionHandler testSessionHandler = new TestSessionHandler(false);
        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
            .libraryId(777)
            .libraryIdleStrategy(new NoOpIdleStrategy())
            .libraryAeronChannels(Collections.singletonList("aeron:ipc"))
            .sessionExistsHandler(new AcquiringSessionExistsHandler(true))
            .sessionAcquireHandler(testSessionHandler)
            .libraryConnectHandler(testSessionHandler);

        libraryConfiguration.aeronContext()
            .aeronDirectoryName("/dev/shm/acceptor");

        try (final ArchivingMediaDriver archivingMediaDriver = ArchivingMediaDriver.launch(mediaDriverContext.clone(), archiveContext.clone().deleteArchiveOnStart(true));
            final AeronArchive archive = AeronArchive.connect(new AeronArchive.Context().aeronDirectoryName(archivingMediaDriver.mediaDriver().context().aeronDirectoryName()).controlRequestChannel("aeron:ipc").controlResponseChannel("aeron:ipc")))
        {
            final CountersReader counters = archive.context().aeron().countersReader();

            final ChannelUri framerInboundChannel = ChannelUri.parse("aeron:ipc?ssc=true");
            framerInboundChannel.initialPosition(258188L * 64 * 1024 * 1024, 0, 64 * 1024 * 1024);
            final ExclusivePublication framerInbound = archive.addRecordedExclusivePublication(framerInboundChannel.toString(), 1);

            long framerInboundCounterId = RecordingPos.findCounterIdBySession(counters, framerInbound.sessionId(), archive.archiveId());
            while (CountersReader.NULL_COUNTER_ID == framerInboundCounterId) {
                framerInboundCounterId = RecordingPos.findCounterIdBySession(counters, framerInbound.sessionId(), archive.archiveId());
            }

            final ChannelUri framerOutboundChannel = ChannelUri.parse("aeron:ipc?ssc=true");
            framerOutboundChannel.initialPosition(4 * 64 * 1024 * 1024, 0, 64 * 1024 * 1024);
            final ExclusivePublication framerOutbound = archive.addRecordedExclusivePublication(framerOutboundChannel.toString(), 2);

            long framerOutboundCounterId = RecordingPos.findCounterIdBySession(counters, framerOutbound.sessionId(), archive.archiveId());
            while (CountersReader.NULL_COUNTER_ID == framerOutboundCounterId) {
                framerOutboundCounterId = RecordingPos.findCounterIdBySession(counters, framerOutbound.sessionId(), archive.archiveId());
            }

            final ChannelUri libraryOutboundChannel = ChannelUri.parse("aeron:ipc?ssc=true");
            libraryOutboundChannel.initialPosition(385042L * 64 * 1024 * 1024, 0, 64 * 1024 * 1024);
            final ExclusivePublication libraryOutbound = archive.addRecordedExclusivePublication(libraryOutboundChannel.toString(), 2);

            long libraryOutboundCounterId = RecordingPos.findCounterIdBySession(counters, libraryOutbound.sessionId(), archive.archiveId());
            while (CountersReader.NULL_COUNTER_ID == libraryOutboundCounterId) {
                libraryOutboundCounterId = RecordingPos.findCounterIdBySession(counters, libraryOutbound.sessionId(), archive.archiveId());
            }
        }

        final File logFileDir = new File(engineConfiguration.logFileDir());
        if (logFileDir.exists())
        {
            IoUtil.delete(logFileDir, false);
        }

        logFileDir.mkdirs();

        final File file = new File(logFileDir, "recording_coordinator");
        final int requiredLength = MessageHeaderEncoder.ENCODED_LENGTH +
                PreviousRecordingEncoder.BLOCK_LENGTH +
                PreviousRecordingEncoder.InboundRecordingsEncoder.HEADER_SIZE + PreviousRecordingEncoder.InboundRecordingsEncoder.sbeBlockLength() * 1 +
                PreviousRecordingEncoder.OutboundRecordingsEncoder.HEADER_SIZE + PreviousRecordingEncoder.OutboundRecordingsEncoder.sbeBlockLength() * 2;
        final MappedByteBuffer mappedBuffer = IoUtil.mapNewFile(file, requiredLength, true);
        try
        {
            final UnsafeBuffer buffer = new UnsafeBuffer(mappedBuffer);
            final MessageHeaderEncoder header = new MessageHeaderEncoder();
            final PreviousRecordingEncoder previousRecording = new PreviousRecordingEncoder();

            previousRecording.wrapAndApplyHeader(buffer, 0, header);
            previousRecording.reproductionRecordingId(Aeron.NULL_VALUE);

            final PreviousRecordingEncoder.InboundRecordingsEncoder inbound = previousRecording.inboundRecordingsCount(1);
            inbound.next().recordingId(0).libraryId(0);

            final PreviousRecordingEncoder.OutboundRecordingsEncoder outbound = previousRecording.outboundRecordingsCount(2);
            outbound.next().recordingId(1).libraryId(0);
            outbound.next().recordingId(2).libraryId(777);
            mappedBuffer.force();
        }
        finally
        {
            IoUtil.unmap(mappedBuffer);
        }


        try (final ArchivingMediaDriver archivingMediaDriver = ArchivingMediaDriver.launch(mediaDriverContext.clone(), archiveContext.clone());
             final FixEngine fixEngine = FixEngine.launch(engineConfiguration);
             final FixLibrary fixLibrary = FixLibrary.connect(libraryConfiguration))
        {
            final IdleStrategy idleStrategy = new NoOpIdleStrategy();

            while (running.get())
            {
                idleStrategy.idle(fixLibrary.poll(10));
                testSessionHandler.sendErsToSession(0);
            }
        }
    }

    public static class TestSessionHandler implements SessionHandler, SessionAcquireHandler, LibraryConnectHandler
    {
        private final ExecutionReportEncoder encoder = new ExecutionReportEncoder();
        private final UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();

        private final boolean blocking;
        private Session session;

        public TestSessionHandler(final boolean blocking)
        {
            this.blocking = blocking;
        }

        @Override
        public ControlledFragmentHandler.Action onMessage(DirectBuffer directBuffer, int i, int i1, int i2, Session session, int i3, long l, long l1, long l2, OnMessageInfo onMessageInfo)
        {
            if (NewOrderSingleDecoder.MESSAGE_TYPE == l)
            {
                sendEr(session);
            }
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        @Override
        public void onTimeout(int i, Session session)
        {
            System.out.println("onTimeout: " + session);
        }

        @Override
        public void onSlowStatus(int i, Session session, boolean b)
        {
            System.out.println("onSlowStatus: " + session);
        }

        @Override
        public ControlledFragmentHandler.Action onDisconnect(int i, Session session, DisconnectReason disconnectReason)
        {
            System.out.println("onDisconnect: " + session);
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        @Override
        public void onSessionStart(Session session)
        {
            System.out.println("onSessionStart: " + session);
        }

        @Override
        public SessionHandler onSessionAcquired(Session session, SessionAcquiredInfo acquiredInfo)
        {
            this.session = session;
            System.out.println("onSessionAcquired: " + session);
            return this;
        }

        @Override
        public void onConnect(FixLibrary library)
        {
            System.err.println(System.currentTimeMillis() + "onLibraryConnect");
        }

        @Override
        public void onDisconnect(FixLibrary library)
        {
            System.err.println(System.currentTimeMillis() + "onLibrayDisconnect");
        }

        public void sendErsToSession(final int count)
        {
            if (null != session)
            {
                for (int i = 0; i < count; i++)
                {
                    sendEr(session);
                }
            }
        }

        private void sendEr(final Session session)
        {
            final int length = timestampEncoder.encode(System.currentTimeMillis());
            encoder
                    .orderID("test")
                    .execID("test")
                    .execType(ExecType.CANCELED)
                    .ordStatus(OrdStatus.CANCELED)
                    .side(Side.BUY)
                    .transactTime(timestampEncoder.buffer(), length);

            encoder.instrument().symbol("test");

            do
            {
                final long result = session.trySend(encoder);
                if (result > 0 || !blocking)
                {
                    break;
                }
            }
            while (true);
        }
    }
}