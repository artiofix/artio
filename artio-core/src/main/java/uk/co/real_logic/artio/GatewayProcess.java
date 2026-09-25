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
package uk.co.real_logic.artio;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.CompositeAgent;
import org.agrona.concurrent.SystemEpochClock;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.timing.HistogramLogAgent;
import uk.co.real_logic.artio.timing.Timer;

import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.List;

import static io.aeron.Aeron.NULL_VALUE;
import static uk.co.real_logic.artio.CommonConfiguration.TIME_MESSAGES;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;

public abstract class GatewayProcess implements AutoCloseable
{
    /** Common id used by messages in both engine and library */
    public static final long NO_CORRELATION_ID = 0;

    public static final long NO_CONNECTION_ID = -1;

    protected CommonConfiguration configuration;
    protected MonitoringFile monitoringFile;
    protected FixCounters fixCounters;
    protected ErrorHandler errorHandler;
    protected Aeron aeron;
    protected MonitoringAgent monitoringAgent;
    protected Agent monitoringCompositeAgent;
    protected long gatewayId = NULL_VALUE;

    protected void init(final CommonConfiguration configuration, final int libraryId)
    {
        this.configuration = configuration;
        initMonitoring(configuration);
        initAeron(configuration, libraryId);
    }

    protected abstract boolean shouldRethrowExceptionInErrorHandler();

    protected void initMonitoring(final CommonConfiguration configuration)
    {
        monitoringFile = new MonitoringFile(true, configuration);
        errorHandler = configuration.errorHandlerFactory().make(monitoringFile.errorBuffer());
    }

    public Agent conductorAgent()
    {
        final AgentInvoker invoker = aeron.conductorAgentInvoker();
        if (invoker == null)
        {
            return null;
        }

        return invoker.agent();
    }

    protected void initAeron(final CommonConfiguration configuration, final int libraryId)
    {
        final Aeron.Context context = configureAeronContext(configuration);
        aeron = Aeron.connect(context);

        setGatewayId(configuration);

        CloseChecker.onOpen(context.aeronDirectoryName(), aeron);
        fixCounters = new FixCounters(aeron, this instanceof FixEngine, libraryId, gatewayId);
    }

    private void setGatewayId(final CommonConfiguration configuration)
    {
        gatewayId = configuration.gatewayId();

        if (NULL_VALUE == gatewayId)
        {
            gatewayId = aeron.clientId();
        }
    }

    protected Aeron.Context configureAeronContext(final CommonConfiguration configuration)
    {
        final Aeron.Context ctx = configuration.aeronContext();
        ctx.errorHandler(
            (throwable) ->
            {
                if (shouldRethrowExceptionInErrorHandler())
                {
                    LangUtil.rethrowUnchecked(throwable);
                }

                if (!(throwable instanceof ClosedByInterruptException))
                {
                    errorHandler.onError(throwable);
                }
            });

        return ctx;
    }

    protected void initMonitoringAgent(
        final List<Timer> timers,
        final CommonConfiguration configuration,
        final AeronArchive aeronArchive,
        final Agent agent)
    {
        final List<Agent> agents = new ArrayList<>();
        if (TIME_MESSAGES)
        {
            agents.add(new HistogramLogAgent(
                timers,
                configuration.histogramLoggingFile(),
                configuration.histogramPollPeriodInMs(),
                errorHandler,
                new SystemEpochClock(),
                configuration.histogramHandler(),
                configuration.agentNamePrefix()));
        }

        final MonitoringAgentFactory monitoringAgentFactory = configuration.monitoringAgentFactory();
        if (monitoringAgentFactory != null)
        {
            monitoringAgent = monitoringAgentFactory.make(
                monitoringFile.errorBuffer(),
                configuration.agentNamePrefix(),
                aeronArchive);
            agents.add(monitoringAgent);
        }

        if (agent != null)
        {
            agents.add(agent);
        }

        if (!agents.isEmpty())
        {
            this.monitoringCompositeAgent = new CompositeAgent(agents);
        }
    }

    public void close()
    {
        closeAll(
            fixCounters,
            () ->
            {
                aeron.close();
                // Only record this as closed if the aeron.close() succeeded.
                CloseChecker.onClose(configuration.aeronContext().aeronDirectoryName(), aeron);
            },
            monitoringFile);
    }
}
