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
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;

/**
 * Interface for determining how an Engine's Agents are allocated to threads.
 */
public interface EngineScheduler extends AutoCloseable
{
    /**
     * Invoked by the FIX Engine to start the threads.
     * Should only return once they are started.
     * @param configuration the engine's configuration object.
     * @param errorHandler the ErrorHandler used by the engine.
     * @param framer the framer agent to schedule.
     * @param indexingAgent the archiver agent to schedule.
     * @param monitoringAgent the monitoring agent to schedule.
     * @param conductorAgent if aeron has useConductorInvoker enable it
     * @param recordingCoordinator must be shut down after the Framer but before the conductorAgent.
     */
    void launch(
        EngineConfiguration configuration,
        ErrorHandler errorHandler,
        Agent framer,
        Agent indexingAgent,
        Agent monitoringAgent,
        Agent conductorAgent,
        RecordingCoordinator recordingCoordinator);

    /**
     * Invoked by the FIX Engine to stop the threads. Should only return once they are completed stopped.
     */
    @Override
    void close();

    int pollFramer();

    /**
     * Used to configure the aeron context object. This can be hooked in order to
     * switch the Aeron Client into Invoking mode, or inject a Media Driver
     *
     * @param aeronContext the context of the Aeron client being used by this Engine instance.
     */
    void configure(Aeron.Context aeronContext);

    static void fail()
    {
        throw new IllegalStateException("Cannot re-use scheduler for multiple launch attempts");
    }

    static void awaitRunnerStart(final AgentRunner runner)
    {
        if (runner != null)
        {
            while (runner.thread() == null)
            {
                Thread.yield();
            }
        }
    }
}
