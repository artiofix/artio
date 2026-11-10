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
package uk.co.real_logic.artio.example_fixp_exchange;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.collections.CollectionUtil;
import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointConnection;
import uk.co.real_logic.artio.example_exchange.LoggingLibraryConnectHandler;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.FixPConnectionExistsHandler;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.messages.SessionReplyStatus;

import java.util.ArrayList;
import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.library.FixLibrary.NO_MESSAGE_REPLAY;

public class FixPExchangeAgent implements Agent
{
    private static final int FRAGMENT_LIMIT = 10;
    private final AcquiringFixPExistsHandler existsHandler = new AcquiringFixPExistsHandler();

    private FixLibrary library;

    @Override
    public void onStart()
    {
        final LibraryConfiguration configuration = new LibraryConfiguration();

        // You register the new session handler - which is your application hook
        // that receives messages for new sessions
        configuration
            .libraryConnectHandler(new LoggingLibraryConnectHandler())
            .fixPConnectionAcquiredHandler(connection -> onAcquire((BinaryEntryPointConnection)connection))
            .fixPConnectionExistsHandler(existsHandler)
            .libraryAeronChannels(singletonList(IPC_CHANNEL));

        library = FixLibrary.connect(configuration);

        System.out.println("Connecting library");
    }

    private FixPConnectionHandler onAcquire(final BinaryEntryPointConnection connection)
    {
        System.out.println(connection.key() + " logged in" + " with sessionId=" + connection.sessionId());
        return new FixPExchangeSessionHandler(connection);
    }

    @Override
    public int doWork()
    {
        return library.poll(FRAGMENT_LIMIT) + existsHandler.poll();
    }

    @Override
    public String roleName()
    {
        return "Exchange";
    }

    static class AcquiringFixPExistsHandler implements FixPConnectionExistsHandler
    {
        private final List<Reply<SessionReplyStatus>> replies = new ArrayList<>();

        @Override
        public Action onConnectionExists(
            final FixLibrary library,
            final long surrogateSessionId,
            final FixPProtocolType protocol,
            final FixPContext context)
        {
            System.out.println("context = " + context + ", protocol = " + protocol + " connected");

            final Reply<SessionReplyStatus> reply = library.requestSession(
                surrogateSessionId, NO_MESSAGE_REPLAY, NO_MESSAGE_REPLAY, 5_000);
            replies.add(reply);

            return Action.CONTINUE;
        }

        int poll()
        {
            final List<Reply<SessionReplyStatus>> replies = this.replies;
            return CollectionUtil.removeIf(replies, reply ->
            {
                if (reply.isExecuting())
                {
                    return false;
                }

                if (reply.hasErrored())
                {
                    reply.error().printStackTrace();
                }
                else if (reply.hasTimedOut())
                {
                    System.err.println(reply + " has timed out");
                }

                return true;
            });
        }
    }
}
