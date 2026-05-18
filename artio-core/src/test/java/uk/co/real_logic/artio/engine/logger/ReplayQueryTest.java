package uk.co.real_logic.artio.engine.logger;

import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.LogTag;

import java.nio.ByteBuffer;
import java.util.Collections;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class ReplayQueryTest
{
    private static final int ARCHIVE_REPLAY_STREAM = 4;

    @Test
    public void shouldCloseReplaySubscriptionWhenQueryCloses()
    {
        final AeronArchive aeronArchive = mock(AeronArchive.class);
        final AeronArchive.Context archiveContext = mock(AeronArchive.Context.class);
        final Aeron aeron = mock(Aeron.class);
        final Subscription subscription = mock(Subscription.class);
        final CountersReader countersReader = mock(CountersReader.class);
        final ErrorHandler errorHandler = mock(ErrorHandler.class);
        final IdleStrategy idleStrategy = mock(IdleStrategy.class);

        when(aeronArchive.context()).thenReturn(archiveContext);
        when(archiveContext.aeron()).thenReturn(aeron);
        when(aeron.countersReader()).thenReturn(countersReader);
        when(aeron.addSubscription(IPC_CHANNEL, ARCHIVE_REPLAY_STREAM)).thenReturn(subscription);

        final ReplayQuery replayQuery = new ReplayQuery(
            "unused",
            1,
            1,
            (file) -> ByteBuffer.allocate(0),
            1,
            idleStrategy,
            aeronArchive,
            errorHandler,
            NoOpReplayQueryListener.INSTANCE,
            ARCHIVE_REPLAY_STREAM,
            8,
            8);

        final ReplayOperation replayOperation = replayQuery.newReplayOperation(
            Collections.emptyList(), LogTag.REPLAY, mock(MessageTracker.class));
        assertTrue(replayOperation.pollReplay());

        replayQuery.close();

        verify(subscription).close();
    }
}
