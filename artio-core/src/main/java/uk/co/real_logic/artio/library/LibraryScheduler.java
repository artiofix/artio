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
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.status.AtomicCounter;

/**
 * Interface for determining how a Library's Agents are allocated to threads.
 */
public interface LibraryScheduler
{
    /**
     * Invoked by the Library to start the threads.
     * Should only return once they are started.
     *
     * Whatever thread is used to start the conductorAgent should invoke {@link FixLibrary#setClientConductorThread()}.
     *
     * @param configuration the library's configuration object.
     * @param errorHandler the ErrorHandler used by the library.
     * @param errorCounter the AtomicCounter used by the library to count errors.
     * @param countedErrorHandler the CountedErrorHandler used by the library.
     * @param monitoringAgent the monitoring agent to schedule.
     * @param conductorAgent if aeron has useConductorInvoker enable it
     *                       is the agent for the conductor, otherwise null.
     */
    void launch(
        LibraryConfiguration configuration,
        ErrorHandler errorHandler,
        AtomicCounter errorCounter,
        ErrorHandler countedErrorHandler,
        Agent monitoringAgent,
        Agent conductorAgent);

    /**
     * Invoked by the Library to stop the threads. Should only return once they are completed stopped.
     *
     * @param libraryId the id of the library that is being closed.
     */
    void close(int libraryId);

    /**
     * Used to configure the aeron context object. This can be hooked in order to
     * switch the Aeron Client into Invoking mode, or inject a Media Driver
     *
     * @param aeronContext the context of the Aeron client being used by this Engine instance.
     */
    void configure(Aeron.Context aeronContext);
}
