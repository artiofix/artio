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
package uk.co.real_logic.artio.engine;

import io.aeron.Aeron;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.CompositeAgent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.agrona.concurrent.AgentRunner.startOnThread;

/**
 * A scheduler that schedules all engine agents onto a single thread.
 *
 * Can also (optionally schedule the media driver's agent onto the same thread.
 */
public class LowResourceEngineScheduler implements EngineScheduler
{
    private AgentRunner runner;
    private ErrorHandler errorHandler;
    private Agent framer;
    private RecordingCoordinator recordingCoordinator;

    public LowResourceEngineScheduler()
    {
    }

    @Override
    public void launch(
        final EngineConfiguration configuration,
        final ErrorHandler errorHandler,
        final Agent framer,
        final Agent indexingAgent,
        final Agent monitoringAgent,
        final Agent conductorAgent,
        final RecordingCoordinator recordingCoordinator)
    {
        this.errorHandler = errorHandler;
        this.framer = framer;
        this.recordingCoordinator = recordingCoordinator;

        if (runner != null)
        {
            EngineScheduler.fail();
        }

        final List<Agent> agents = new ArrayList<>();
        Collections.addAll(agents,
            monitoringAgent, framer, indexingAgent, new RecordingCoordinatorAgent(), conductorAgent);

        agents.removeIf(Objects::isNull);

        runner = new AgentRunner(
            configuration.framerIdleStrategy(),
            errorHandler,
            null,
            new CompositeAgent(agents));
        startOnThread(runner, configuration.threadFactory());
    }

    @Override
    public void close()
    {
        EngineScheduler.awaitRunnerStart(runner);

        CloseHelper.close(runner);
    }

    @Override
    public int pollFramer()
    {
        if (framer != null)
        {
            try
            {
                return framer.doWork();
            }
            catch (final Throwable e)
            {
                if (errorHandler != null)
                {
                    errorHandler.onError(e);
                }
                else
                {
                    // Should never happen, but best to be defensive
                    e.printStackTrace();
                }
            }
        }

        return 0;
    }

    @Override
    public void configure(final Aeron.Context aeronContext)
    {
        aeronContext.useConductorAgentInvoker(true);
    }

    /**
     * Adapt a recording coordinator to the Agent interface to enable it to be shutdown in order.
     */
    private final class RecordingCoordinatorAgent implements Agent
    {
        @Override
        public int doWork()
        {
            // Deliberately empty
            return 0;
        }

        @Override
        public String roleName()
        {
            return "RecordingCoordinator";
        }

        @Override
        public void onClose()
        {
            recordingCoordinator.close();
        }
    }
}
