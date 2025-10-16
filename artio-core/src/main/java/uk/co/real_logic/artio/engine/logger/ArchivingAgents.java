package uk.co.real_logic.artio.engine.logger;

import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.CompletionPosition;

import java.util.Objects;

public class ArchivingAgents implements Agent, FragmentHandler
{
    static final int LIMIT = 20;

    private final Indexer indexer;
    private final AbstractReplayer abstractReplayer;
    private final Subscription subscription;
    private final CompletionPosition completionPosition;

    public ArchivingAgents(
        final Indexer indexer,
        final AbstractReplayer abstractReplayer,
        final Subscription subscription,
        final CompletionPosition completionPosition
    )
    {
        this.indexer = Objects.requireNonNull(indexer);
        this.abstractReplayer = abstractReplayer;
        this.subscription = subscription;
        this.completionPosition = completionPosition;
    }

    public void onStart()
    {
        indexer.onStart();
        if (null != abstractReplayer)
        {
            abstractReplayer.onStart();
        }
    }

    public int doWork() throws Exception
    {
        int workCount = 0;
        workCount += subscription.poll(this, LIMIT);
        workCount += indexer.doWork();
        if (null != abstractReplayer)
        {
            workCount += abstractReplayer.doWork();
        }
        return workCount;
    }

    public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        indexer.onFragment(buffer, offset, length, header);
        if (null != abstractReplayer)
        {
            abstractReplayer.onFragment(buffer, offset, length, header);
        }
    }

    public void catchIndexUp(final AeronArchive aeronArchive, final ErrorHandler errorHandler)
    {
        indexer.catchIndexUp(aeronArchive, errorHandler);
    }

    public void onClose()
    {
        quiesce();

        Exceptions.closeAll(() -> Exceptions.closeAll(indexer, abstractReplayer), subscription);
    }

    private void quiesce()
    {
        while (!completionPosition.hasCompleted())
        {
            Thread.yield();
        }

        if (completionPosition.wasStartupComplete())
        {
            return;
        }

        // We know that any remaining data to quiesce at this point must be in the subscription.
        subscription.controlledPoll(this::quiesceFragment, Integer.MAX_VALUE);
    }

    private ControlledFragmentHandler.Action quiesceFragment(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        if (header.position() <= completedPosition(header.sessionId()))
        {
            onFragment(buffer, offset, length, header);
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        else
        {
            return ControlledFragmentHandler.Action.ABORT;
        }
    }

    private long completedPosition(final int aeronSessionId)
    {
        return completionPosition.positions().get(aeronSessionId);
    }

    public String roleName()
    {
        if (null != abstractReplayer)
        {
            return indexer.roleName() + "," + abstractReplayer.roleName();
        }

        return indexer.roleName();
    }
}
