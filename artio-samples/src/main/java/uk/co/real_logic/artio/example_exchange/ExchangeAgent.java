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
package uk.co.real_logic.artio.example_exchange;

import org.agrona.concurrent.Agent;
import uk.co.real_logic.artio.library.AcquiringSessionExistsHandler;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.util.Collections;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static java.util.Collections.singletonList;
import static uk.co.real_logic.artio.example_exchange.ExchangeApplication.ACCEPTOR_COMP_ID;
import static uk.co.real_logic.artio.example_exchange.ExchangeApplication.INITIATOR_COMP_ID;

public class ExchangeAgent implements Agent
{
    private static final int FRAGMENT_LIMIT = 10;

    private FixLibrary library;

    @Override
    public void onStart()
    {
        final MessageValidationStrategy validationStrategy = MessageValidationStrategy.targetCompId(ACCEPTOR_COMP_ID)
            .and(MessageValidationStrategy.senderCompId(Collections.singletonList(INITIATOR_COMP_ID)));

        final LibraryConfiguration configuration = new LibraryConfiguration();

        // You register the new session handler - which is your application hook
        // that receives messages for new sessions
        configuration
            .libraryConnectHandler(new LoggingLibraryConnectHandler())
            .sessionAcquireHandler((session, acquiredInfo) -> onAcquire(session))
            .sessionExistsHandler(new AcquiringSessionExistsHandler(true))
            .libraryAeronChannels(singletonList(IPC_CHANNEL));

        library = FixLibrary.connect(configuration);

        System.out.println("Connecting library");
    }

    private SessionHandler onAcquire(final Session session)
    {
        System.out.println(session.compositeKey() + " logged in");
        return new ExchangeSessionHandler();
    }

    @Override
    public int doWork()
    {
        return library.poll(FRAGMENT_LIMIT);
    }

    @Override
    public String roleName()
    {
        return "Exchange";
    }
}
