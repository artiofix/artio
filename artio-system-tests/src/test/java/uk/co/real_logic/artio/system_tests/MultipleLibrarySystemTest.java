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
package uk.co.real_logic.artio.system_tests;

import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.Reply;
import uk.co.real_logic.artio.messages.SessionReplyStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_REPLY_TIMEOUT_IN_MS;
import static uk.co.real_logic.artio.TestFixtures.cleanupMediaDriver;
import static uk.co.real_logic.artio.TestFixtures.launchMediaDriver;
import static uk.co.real_logic.artio.dictionary.generation.Exceptions.closeAll;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class MultipleLibrarySystemTest extends AbstractGatewayToGatewaySystemTest
{
    @BeforeEach
    public void launch()
    {
        delete(ACCEPTOR_LOGS);

        mediaDriver = launchMediaDriver();

        launchAcceptingEngine();
        initiatingEngine = launchInitiatingEngine(libraryAeronPort, nanoClock);
        initiatingLibrary = newInitiatingLibrary(libraryAeronPort, initiatingHandler, nanoClock);
        testSystem = new TestSystem(initiatingLibrary);

        connectSessions();
    }

    @Test
    @Timeout(TEST_TIMEOUT_IN_MS)
    public void shouldEnableLibraryConnectionsOneAfterAnother()
    {
        for (int i = 0; i < 10; i++)
        {
            DebugLogger.log(LogTag.FIX_TEST, "Iteration: " + i);

            acceptingLibrary = testSystem.add(newAcceptingLibrary(acceptingHandler, nanoClock));

            while (!acceptingLibrary.isConnected())
            {
                testSystem.poll();

                Thread.yield();
            }

            acquireAcceptingSession();

            final Reply<SessionReplyStatus> reply = testSystem.awaitReply(acceptingLibrary.releaseToGateway(
                acceptingSession, DEFAULT_REPLY_TIMEOUT_IN_MS));

            assertEquals(SessionReplyStatus.OK, reply.resultIfPresent());
            acceptingLibrary.close();
        }
    }

    @AfterEach
    public void shutdown()
    {
        closeAll(
            initiatingLibrary,
            acceptingLibrary,
            initiatingEngine,
            acceptingEngine,
            () -> cleanupMediaDriver(mediaDriver));
    }
}
