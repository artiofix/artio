/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.library;

import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.fix_gateway.CommonConfiguration;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.ENGINE_LIBRARY_ID;


/**
 * Provides configuration for initiating an instance of Fix Library. Individual configuration options are
 * documented on their setters.
 *
 * NB: DO NOT REUSE this object over multiple {@code FixLibrary.connect()} calls.
 *
 * @see FixLibrary
 */
public final class LibraryConfiguration extends CommonConfiguration
{
    public static final GatewayErrorHandler DEFAULT_GATEWAY_ERROR_HANDLER =
        (errorType, libraryId, message) -> CONTINUE;
    public static final SentPositionHandler DEFAULT_SENT_POSITION_HANDLER = position -> CONTINUE;
    public static final SessionExistsHandler DEFAULT_SESSION_EXISTS_HANDLER =
        (library,
         sessionId,
         senderCompId,
         senderSubId,
         senderLocationId,
         targetCompId,
         remoteSubId,
         remoteLocationId,
         username,
         password) -> {};
    public static final LibraryConnectHandler DEFAULT_LIBRARY_CONNECT_HANDLER = new LibraryConnectHandler()
    {
        public void onConnect(final FixLibrary library)
        {
        }

        public void onDisconnect(final FixLibrary library)
        {
        }
    };

    private final int libraryId;
    {
        int libraryId;
        do
        {
            libraryId = ThreadLocalRandom.current().nextInt();
        }
        while (libraryId == ENGINE_LIBRARY_ID);

        this.libraryId = libraryId;
    }

    private SessionAcquireHandler sessionAcquireHandler;
    private IdleStrategy libraryIdleStrategy = backoffIdleStrategy();
    private SessionExistsHandler sessionExistsHandler = DEFAULT_SESSION_EXISTS_HANDLER;
    private GatewayErrorHandler gatewayErrorHandler = DEFAULT_GATEWAY_ERROR_HANDLER;
    private SentPositionHandler sentPositionHandler = DEFAULT_SENT_POSITION_HANDLER;
    private List<String> libraryAeronChannels = new ArrayList<>();
    private LibraryConnectHandler libraryConnectHandler = DEFAULT_LIBRARY_CONNECT_HANDLER;
    private LibraryScheduler scheduler = new DefaultLibraryScheduler();
    private String libraryName = "";

    /**
     * When a new session connects to the gateway you register a callback handler to find
     * out about the event. This method sets the handler for this library instance.
     *
     * Only needed if this is the accepting library instance.
     *
     * @param sessionAcquireHandler the new session handler
     * @return this
     */
    public LibraryConfiguration sessionAcquireHandler(final SessionAcquireHandler sessionAcquireHandler)
    {
        this.sessionAcquireHandler = sessionAcquireHandler;
        return this;
    }

    /**
     * Sets the idle strategy for the FIX library instance.
     *
     * @param libraryIdleStrategy the idle strategy for the FIX library instance.
     * @return this
     */
    public LibraryConfiguration libraryIdleStrategy(final IdleStrategy libraryIdleStrategy)
    {
        this.libraryIdleStrategy = libraryIdleStrategy;
        return this;
    }

    public LibraryConfiguration sessionExistsHandler(final SessionExistsHandler sessionExistsHandler)
    {
        this.sessionExistsHandler = sessionExistsHandler;
        return this;
    }

    public LibraryConfiguration gatewayErrorHandler(final GatewayErrorHandler gatewayErrorHandler)
    {
        this.gatewayErrorHandler = gatewayErrorHandler;
        return this;
    }

    public LibraryConfiguration sentPositionHandler(final SentPositionHandler sentPositionHandler)
    {
        this.sentPositionHandler = sentPositionHandler;
        return this;
    }

    public LibraryConfiguration libraryConnectHandler(final LibraryConnectHandler libraryConnectHandler)
    {
        this.libraryConnectHandler = libraryConnectHandler;
        return this;
    }

    public LibraryConfiguration scheduler(final LibraryScheduler scheduler)
    {
        this.scheduler = scheduler;
        return this;
    }

    public SessionAcquireHandler sessionAcquireHandler()
    {
        return sessionAcquireHandler;
    }

    public int libraryId()
    {
        return libraryId;
    }

    public IdleStrategy libraryIdleStrategy()
    {
        return libraryIdleStrategy;
    }

    public GatewayErrorHandler gatewayErrorHandler()
    {
        return gatewayErrorHandler;
    }

    public SentPositionHandler sentPositionHandler()
    {
        return sentPositionHandler;
    }

    public LibraryConnectHandler libraryConnectHandler()
    {
        return libraryConnectHandler;
    }

    public LibraryScheduler scheduler()
    {
        return scheduler;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration sessionIdStrategy(final SessionIdStrategy sessionIdStrategy)
    {
        super.sessionIdStrategy(sessionIdStrategy);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration monitoringBuffersLength(final Integer monitoringBuffersLength)
    {
        super.monitoringBuffersLength(monitoringBuffersLength);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration monitoringFile(final String monitoringFile)
    {
        super.monitoringFile(monitoringFile);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration libraryAeronChannels(final List<String> libraryAeronChannels)
    {
        this.libraryAeronChannels = libraryAeronChannels;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration replyTimeoutInMs(final long replyTimeoutInMs)
    {
        super.replyTimeoutInMs(replyTimeoutInMs);
        return this;
    }

    void conclude()
    {
        super.conclude("library-" + libraryId());

        if (libraryAeronChannels.isEmpty())
        {
            throw new IllegalArgumentException("You must specify at least one channel to connect to");
        }
    }

    SessionExistsHandler sessionExistsHandler()
    {
        return sessionExistsHandler;
    }

    public List<String> libraryAeronChannels()
    {
        return libraryAeronChannels;
    }

    String libraryName()
    {
        return libraryName;
    }

    public LibraryConfiguration libraryName(String libraryName)
    {
        this.libraryName = libraryName;
        return this;
    }
}
