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

import io.aeron.Aeron;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.CompositeAgent;
import uk.co.real_logic.artio.engine.EngineScheduler;

import java.util.ArrayList;
import java.util.List;

import static org.agrona.concurrent.AgentRunner.startOnThread;

public class DefaultLibraryScheduler implements LibraryScheduler
{
    private AgentRunner monitoringRunner;

    public void launch(
        final LibraryConfiguration configuration,
        final ErrorHandler errorHandler,
        final Agent monitoringAgent,
        final Agent conductorAgent)
    {
        if (monitoringRunner != null)
        {
            EngineScheduler.fail();
        }

        final List<Agent> agents = new ArrayList<>();
        agents.add(conductorAgent);
        if (null != monitoringAgent)
        {
            agents.add(monitoringAgent);
        }

        monitoringRunner = new AgentRunner(
            configuration.monitoringThreadIdleStrategy(),
            errorHandler,
            null,
            new CompositeAgent(agents)
            {
                public void onStart()
                {
                    FixLibrary.setClientConductorThread();
                    super.onStart();
                }
            });
        startOnThread(monitoringRunner, configuration.threadFactory());
    }

    public void configure(final Aeron.Context aeronContext)
    {
        aeronContext.useConductorAgentInvoker(true);
    }

    public void close(final int libraryId)
    {
        EngineScheduler.awaitRunnerStart(monitoringRunner);

        CloseHelper.close(monitoringRunner);
    }
}
