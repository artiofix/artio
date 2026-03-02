/*
 * Copyright 2015-2025 Real Logic Limited.
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
package uk.co.real_logic.artio.library;

import org.agrona.Strings;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.ReproductionClock;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.session.DirectSessionProxy;
import uk.co.real_logic.artio.session.ResendRequestController;
import uk.co.real_logic.artio.session.SessionIdStrategy;
import uk.co.real_logic.artio.session.SessionProxyFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.engine.FixEngine.ENGINE_LIBRARY_ID;


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
    public static final SessionExistsHandler DEFAULT_SESSION_EXISTS_HANDLER =
        (library,
        sessionId,
        senderCompId,
        senderSubId,
        senderLocationId,
        targetCompId,
        remoteSubId,
        remoteLocationId,
        logonReceivedSequence,
        logonSequenceIndex) -> {};
    public static final LibraryConnectHandler DEFAULT_LIBRARY_CONNECT_HANDLER = new LibraryConnectHandler()
    {
        public void onConnect(final FixLibrary library)
        {
        }

        public void onDisconnect(final FixLibrary library)
        {
        }
    };

    public static final SessionProxyFactory DEFAULT_SESSION_PROXY_FACTORY = DirectSessionProxy::new;

    private int libraryId = ENGINE_LIBRARY_ID;

    private SessionAcquireHandler sessionAcquireHandler;
    private IdleStrategy libraryIdleStrategy = backoffIdleStrategy();
    private SessionExistsHandler sessionExistsHandler = DEFAULT_SESSION_EXISTS_HANDLER;
    private GatewayErrorHandler gatewayErrorHandler = DEFAULT_GATEWAY_ERROR_HANDLER;
    private List<String> libraryAeronChannels = new ArrayList<>();
    private LibraryConnectHandler libraryConnectHandler = DEFAULT_LIBRARY_CONNECT_HANDLER;
    private LibraryScheduler scheduler = new DefaultLibraryScheduler();
    private String libraryName = "";
    private SessionProxyFactory sessionProxyFactory = DEFAULT_SESSION_PROXY_FACTORY;
    private FixPConnectionExistsHandler fixPConnectionExistsHandler;
    private FixPConnectionAcquiredHandler fixPConnectionAcquiredHandler;
    private LibraryReproductionConfiguration reproductionConfiguration;
    private long libraryCycleThresholdNs = DEFAULT_CYCLE_THRESHOLD_NS;

    /**
     * When a new FIX session connects to the gateway you register a callback handler to find
     * out about the event. This method sets the handler for this library instance.
     *
     * Only needed if this is the accepting library instance.
     *
     * @param sessionAcquireHandler the new session handler
     * @return this
     * @see #fixPConnectionAcquiredHandler(FixPConnectionAcquiredHandler) for the FIXP equivalent
     */
    public LibraryConfiguration sessionAcquireHandler(final SessionAcquireHandler sessionAcquireHandler)
    {
        this.sessionAcquireHandler = sessionAcquireHandler;
        return this;
    }

    /**
     * When a new FIXP session connects to the gateway you register a callback handler to find
     * out about the event. This method sets the handler for this library instance.
     *
     * Only needed if this is the accepting library instance.
     *
     * @param fixPConnectionAcquiredHandler the new session handler
     * @return this
     * @see #sessionAcquireHandler(SessionAcquireHandler) for the FIX equivalent
     */
    public LibraryConfiguration fixPConnectionAcquiredHandler(
        final FixPConnectionAcquiredHandler fixPConnectionAcquiredHandler)
    {
        this.fixPConnectionAcquiredHandler = fixPConnectionAcquiredHandler;
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

    public LibraryConfiguration fixPConnectionExistsHandler(
        final FixPConnectionExistsHandler fixPConnectionExistsHandler)
    {
        this.fixPConnectionExistsHandler = fixPConnectionExistsHandler;
        return this;
    }

    public LibraryConfiguration gatewayErrorHandler(final GatewayErrorHandler gatewayErrorHandler)
    {
        this.gatewayErrorHandler = gatewayErrorHandler;
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

    /**
     * Sets the library id to be used by this library. This is especially useful when in reproduction mode, as it
     * allows a library to have the same library id as a previous run. Under normal circumstances it isn't necessary to
     * set a library id - a random one will be assigned.
     *
     * @param libraryId the id to be used by this library.
     * @return this
     */
    public LibraryConfiguration libraryId(final int libraryId)
    {
        if (libraryId == ENGINE_LIBRARY_ID || libraryId < ENGINE_LIBRARY_ID)
        {
            throw new IllegalArgumentException("Invalid library id: " + libraryId);
        }

        this.libraryId = libraryId;
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

    public LibraryConnectHandler libraryConnectHandler()
    {
        return libraryConnectHandler;
    }

    public LibraryScheduler scheduler()
    {
        return scheduler;
    }

    public SessionProxyFactory sessionProxyFactory()
    {
        return sessionProxyFactory;
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
     * Sets the list of aeron channels used to connect to the Engine
     *
     * @param libraryAeronChannels the list of aeron channels used to connect to the Engine
     * @return this
     */
    public LibraryConfiguration libraryAeronChannels(final List<String> libraryAeronChannels)
    {
        this.libraryAeronChannels = libraryAeronChannels;
        return this;
    }

    /**
     * Sets the factory for creating Session Proxies.
     *
     * @see uk.co.real_logic.artio.session.SessionProxy
     *
     * @param sessionProxyFactory the factory for creating Session Proxies.
     * @return this
     */
    public LibraryConfiguration sessionProxyFactory(final SessionProxyFactory sessionProxyFactory)
    {
        this.sessionProxyFactory = sessionProxyFactory;
        return this;
    }

    /**
     * Enable inbound reproduction mode for the Library.
     *
     * Inbound reproduction mode needs to be started using: {@link FixEngine#startReproduction()}. In order to use this
     * then the Engine that this library connects to must also have its
     * {@link uk.co.real_logic.artio.engine.EngineConfiguration#reproduceInbound(long, long)} mode enabled.
     *
     * @param startInNs the start time to reproduce from.
     * @param endInNs the end time to reproduce until.
     * @return this
     */
    public LibraryConfiguration reproduceInbound(
        final long startInNs, final long endInNs)
    {
        final ReproductionClock clock = new ReproductionClock(startInNs);
        epochNanoClock(clock);
        this.reproductionConfiguration = new LibraryReproductionConfiguration(startInNs, endInNs, clock);
        return this;
    }

    /**
     * Set a threshold for the library work cycle time which when exceeded it will increment the
     * library cycle time exceeded count.
     *
     * @param libraryCycleThresholdNs value in nanoseconds. The value 0 will disable duty cycle tracking on the library.
     * @return this for fluent API.
     */
    public LibraryConfiguration libraryCycleThresholdNs(final long libraryCycleThresholdNs)
    {
        this.libraryCycleThresholdNs = libraryCycleThresholdNs;
        return this;
    }

    // ------------------------
    // BEGIN INHERITED SETTERS
    // ------------------------

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration replyTimeoutInMs(final long replyTimeoutInMs)
    {
        super.replyTimeoutInMs(replyTimeoutInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration noEstablishFixPTimeoutInMs(final long noEstablishFixPTimeoutInMs)
    {
        super.noEstablishFixPTimeoutInMs(noEstablishFixPTimeoutInMs);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration fixPAcceptedSessionMaxRetransmissionRange(
        final int fixPAcceptedSessionMaxRetransmissionRange)
    {
        super.fixPAcceptedSessionMaxRetransmissionRange(fixPAcceptedSessionMaxRetransmissionRange);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration epochNanoClock(final EpochNanoClock epochNanoClock)
    {
        super.epochNanoClock(epochNanoClock);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration resendRequestController(final ResendRequestController resendRequestController)
    {
        super.resendRequestController(resendRequestController);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration defaultHeartbeatIntervalInS(final int value)
    {
        super.defaultHeartbeatIntervalInS(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public LibraryConfiguration forcedHeartbeatIntervalInS(final int value)
    {
        super.forcedHeartbeatIntervalInS(value);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public CommonConfiguration disableHeartbeatRepliesToTestRequests(
        final boolean disableHeartbeatRepliesToTestRequests)
    {
        return super.disableHeartbeatRepliesToTestRequests(disableHeartbeatRepliesToTestRequests);
    }

    // ------------------------
    // END INHERITED SETTERS
    // ------------------------

    LibraryConfiguration conclude()
    {
        concludeLibraryId();

        super.conclude("library-" + libraryId());

        if (isReproductionEnabled() && reproductionConfiguration.clock() != epochNanoClock())
        {
            throw new IllegalArgumentException("Do no set the nano clock when using reproduction mode");
        }

        if (libraryAeronChannels.isEmpty())
        {
            throw new IllegalArgumentException("You must specify at least one channel to connect to");
        }

        if (Strings.isEmpty(aeronContext().clientName()))
        {
            aeronContext().clientName("fix-library-" + libraryId);
        }

        return this;
    }

    private void concludeLibraryId()
    {
        while (libraryId == ENGINE_LIBRARY_ID || libraryId < ENGINE_LIBRARY_ID)
        {
            libraryId = ThreadLocalRandom.current().nextInt();
        }
    }

    SessionExistsHandler sessionExistsHandler()
    {
        return sessionExistsHandler;
    }

    FixPConnectionExistsHandler fixPConnectionExistsHandler()
    {
        return fixPConnectionExistsHandler;
    }

    FixPConnectionAcquiredHandler fixPConnectionAcquiredHandler()
    {
        return fixPConnectionAcquiredHandler;
    }

    public List<String> libraryAeronChannels()
    {
        return libraryAeronChannels;
    }

    String libraryName()
    {
        return libraryName;
    }

    LibraryReproductionConfiguration reproductionConfiguration()
    {
        return reproductionConfiguration;
    }

    boolean isReproductionEnabled()
    {
        return reproductionConfiguration != null;
    }

    public long libraryCycleThresholdNs()
    {
        return libraryCycleThresholdNs;
    }

    public LibraryConfiguration libraryName(final String libraryName)
    {
        this.libraryName = libraryName;
        return this;
    }
}
