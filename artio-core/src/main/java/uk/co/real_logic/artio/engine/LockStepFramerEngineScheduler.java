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
import org.agrona.LangUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.AgentRunner;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;

import static org.agrona.concurrent.AgentRunner.startOnThread;

public class LockStepFramerEngineScheduler implements EngineScheduler
{
    private AgentRunner archivingRunner;
    private AgentRunner monitoringRunner;
    private AgentInvoker framerInvoker;
    private RecordingCoordinator recordingCoordinator;

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
        this.recordingCoordinator = recordingCoordinator;
        framerInvoker = new AgentInvoker(errorHandler, null, framer);
        framerInvoker.start();

        if (archivingRunner != null)
        {
            EngineScheduler.fail();
        }

        archivingRunner = new AgentRunner(
            configuration.archiverIdleStrategy(),
            errorHandler,
            null,
            indexingAgent);

        startOnThread(archivingRunner, configuration.threadFactory());

        if (monitoringAgent != null)
        {
            monitoringRunner = new AgentRunner(
                configuration.monitoringThreadIdleStrategy(),
                errorHandler,
                null,
                monitoringAgent);
            startOnThread(monitoringRunner, configuration.threadFactory());
        }
    }

    public int invokeFramer()
    {
        try
        {
            return framerInvoker.invoke();
        }
        catch (final Exception e)
        {
            LangUtil.rethrowUnchecked(e);
            return 0;
        }
    }

    @Override
    public void close()
    {
        EngineScheduler.awaitRunnerStart(archivingRunner);
        EngineScheduler.awaitRunnerStart(monitoringRunner);

        Exceptions.closeAll(framerInvoker, recordingCoordinator, archivingRunner, monitoringRunner);
    }

    @Override
    public int pollFramer()
    {
        return 0;
    }

    @Override
    public void configure(final Aeron.Context aeronContext)
    {
    }

}
