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

import io.aeron.archive.ArchivingMediaDriver;
import org.junit.jupiter.api.AfterEach;


import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.validation.SessionPersistenceStrategy;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static org.agrona.CloseHelper.close;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.TestFixtures.*;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

public class ResendRedundantResendRequestTest
{
    private final int port = unusedPort();

    private ArchivingMediaDriver mediaDriver;
    private FixEngine engine;

    private void setup(final boolean resendRedundantResendRequest)
    {
        mediaDriver = launchMediaDriver();

        delete(ACCEPTOR_LOGS);
        final EngineConfiguration config = new EngineConfiguration()
            .bindTo("localhost", port)
            .libraryAeronChannel(IPC_CHANNEL)
            .monitoringFile(acceptorMonitoringFile("engineCounters"))
            .logFileDir(ACCEPTOR_LOGS)
            .sessionPersistenceStrategy(SessionPersistenceStrategy.alwaysPersistent())
            .acceptedSessionSendRedundantResendRequests(resendRedundantResendRequest);
        configureAeronArchive(config.aeronArchiveContext());
        engine = FixEngine.launch(config);
    }

    @Test
    public void shouldNotSendRedundantResendRequestsByDefault() throws IOException
    {
        setup(false);

        try (FixConnection connection = FixConnection.initiate(port))
        {
            connection.msgSeqNum(3);
            connection.logon(false);
            connection.readLogon(1);

            // await resend request
            connection.readResendRequest(1, 0);

            // reply with first message of resend
            connection.sendExecutionReport(1, true);

            // send new message
            connection.sendExecutionReport(4, false);

            // reply with last message of resend
            connection.sendExecutionReport(2, true);

            // check I've not received another resend request

            // exchange test request / heartbeat
            final String testReqId = "thisIsATest";
            connection.msgSeqNum(5);
            connection.exchangeTestRequestHeartbeat(testReqId);

            LockSupport.parkNanos(500);

            assertEquals(0, connection.pollData());

            connection.logoutAndAwaitReply();
        }
    }

    @AfterEach
    public void tearDown()
    {
        close(engine);
        cleanupMediaDriver(mediaDriver);
    }
}
