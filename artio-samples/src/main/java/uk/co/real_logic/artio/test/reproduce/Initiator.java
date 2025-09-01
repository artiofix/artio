package uk.co.real_logic.artio.test.reproduce;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.*;
import uk.co.real_logic.artio.*;
import uk.co.real_logic.artio.builder.NewOrderSingleEncoder;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.fields.DecimalFloat;
import uk.co.real_logic.artio.fields.UtcTimestampEncoder;
import uk.co.real_logic.artio.library.*;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class Initiator {

    public static void main(String[] args) throws Exception {

        final AtomicBoolean running = new AtomicBoolean(true);
        SigInt.register(() -> running.set(false));

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName("/dev/shm/initiator")
            .printConfigurationOnStart(true)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .deleteArchiveOnStart(true)
            .threadingMode(ArchiveThreadingMode.DEDICATED)
            .aeronDirectoryName("/dev/shm/initiator")
            .archiveDirectoryName("initiaStor")
            .replicationChannel("aeron:udp?endpoint=localhost:0")
            .controlChannel("aeron:udp?endpoint=localhost:9010");

        final EngineConfiguration engineConfiguration = new EngineConfiguration()
            .defaultHeartbeatIntervalInS(10)
            .logFileDir("initiator-logs")
            .initialAcceptedSessionOwner(InitialAcceptedSessionOwner.SOLE_LIBRARY)
            .deleteLogFileDirOnStart(true)
            .libraryAeronChannel("aeron:ipc")
            .sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysPersistent());

        engineConfiguration.aeronContext()
            .aeronDirectoryName("/dev/shm/initiator");

        engineConfiguration.aeronArchiveContext()
            .controlRequestChannel("aeron:udp?endpoint=localhost:9010")
            .controlResponseChannel("aeron:udp?endpoint=localhost:9020");

        final LibraryConfiguration libraryConfiguration = new LibraryConfiguration()
            .defaultHeartbeatIntervalInS(10)
            .libraryAeronChannels(Collections.singletonList("aeron:ipc"))
            .sessionExistsHandler(new AcquiringSessionExistsHandler())
            .sessionAcquireHandler((session, acquiredInfo) -> new EmptySessionHandler());

        libraryConfiguration.aeronContext()
            .aeronDirectoryName("/dev/shm/initiator");

        try (final ArchivingMediaDriver archivingMediaDriver = ArchivingMediaDriver.launch(mediaDriverContext, archiveContext);
             final FixEngine fixEngine = FixEngine.launch(engineConfiguration);
             final FixLibrary fixLibrary = FixLibrary.connect(libraryConfiguration)) {

            final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1000L);

            while (!fixLibrary.isConnected())
            {
                idleStrategy.idle(fixLibrary.poll(1));
            };

            final SessionConfiguration sessionConfig = SessionConfiguration.builder()
                    .timeoutInMs(10_000L)
                    .address("localhost", 9999)
                    .targetCompId("acceptor")
                    .senderCompId("initiator")
                    .sequenceNumbersPersistent(true)
                    .initialSentSequenceNumber(-1)
                    .initialReceivedSequenceNumber(-1)
                    .build();

            final Reply<Session> reply = Reply.await(fixLibrary.initiate(sessionConfig), fixLibrary, idleStrategy);

            if (reply.hasCompleted())
            {
                final Session session = reply.resultIfPresent();

                while (running.get())
                {
                    fixLibrary.poll(10);
                    sendNos(session);
                }
            }
        }
    }

    public static class EmptySessionHandler implements SessionHandler {
        @Override
        public ControlledFragmentHandler.Action onMessage(DirectBuffer directBuffer, int i, int i1, int i2, Session session, int i3, long l, long l1, long l2, OnMessageInfo onMessageInfo) {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        @Override
        public void onTimeout(int i, Session session) {

        }

        @Override
        public void onSlowStatus(int i, Session session, boolean b) {

        }

        @Override
        public ControlledFragmentHandler.Action onDisconnect(int i, Session session, DisconnectReason disconnectReason) {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        @Override
        public void onSessionStart(Session session) {
        }
    }

    final static NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
    final static UtcTimestampEncoder timestampEncoder = new UtcTimestampEncoder();
    public static void sendNos(Session session)
    {
        final int transactTimeLength = timestampEncoder.encode(System.currentTimeMillis());
        encoder
            .clOrdID("A")
            .side(Side.BUY)
            .transactTime(timestampEncoder.buffer(), transactTimeLength)
            .ordType(OrdType.MARKET)
            .price(new DecimalFloat(10, 10));
        encoder.instrument().symbol("MSFT");
        encoder.orderQtyData().orderQty(new DecimalFloat(10, 10));
        session.trySend(encoder);
    }
}