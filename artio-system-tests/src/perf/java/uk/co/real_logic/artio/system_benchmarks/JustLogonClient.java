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
package uk.co.real_logic.artio.system_benchmarks;

import uk.co.real_logic.artio.decoder.LogoutDecoder;
import uk.co.real_logic.artio.system_tests.TestFixConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static uk.co.real_logic.artio.system_benchmarks.BenchmarkConfiguration.BAD_LOGON_NO_LINGER;

public class JustLogonClient
{
    public static void main(final String[] args) throws IOException
    {
        int logonCount = 0;

        while (true)
        {
            System.out.println("Attempting logon:");
            final int port = BenchmarkConfiguration.PORT;
            try (TestFixConnection testFixConnection = new TestFixConnection(SocketChannel.open(
                new InetSocketAddress("localhost", port)),
                BenchmarkConfiguration.INITIATOR_ID,
                BenchmarkConfiguration.ACCEPTOR_ID))
            {
                final boolean badLogon = BenchmarkConfiguration.BAD_LOGON;
                System.out.println("badLogon = " + badLogon);
                System.out.println("badLogon no linger = " + BAD_LOGON_NO_LINGER);
                testFixConnection.password(badLogon ? "wrong_password" :
                    BenchmarkConfiguration.VALID_PASSWORD);
                testFixConnection.logon(false);
                final LogoutDecoder logout = testFixConnection.readLogout();
                System.out.println(logout);

                // Linger connection for maximum amount of time possible
                if (badLogon && !BAD_LOGON_NO_LINGER)
                {
                    testFixConnection.awaitDisconnect();
                }
            }
            logonCount++;
            System.out.println("Disconnected: " + logonCount);

            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(20));
        }
    }
}
