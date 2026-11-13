/*
 * Copyright 2021 Monotonic Ltd.
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

import uk.co.real_logic.artio.binary_entrypoint.BinaryEntryPointConnection;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPConnectionHandler;
import uk.co.real_logic.artio.library.FixPConnectionAcquiredHandler;

public class FakeFixPConnectionAcquiredHandler implements FixPConnectionAcquiredHandler
{
    private final FakeBinaryEntrypointConnectionHandler connectionHandler;

    private boolean invoked = false;
    private FixPConnection connection;
    private long sessionVerIdAtAcquire;

    public FakeFixPConnectionAcquiredHandler(final FakeBinaryEntrypointConnectionHandler connectionHandler)
    {
        this.connectionHandler = connectionHandler;
    }

    @Override
    public FixPConnectionHandler onConnectionAcquired(final FixPConnection connection)
    {
        invoked = true;
        this.connection = connection;
        if (connection instanceof BinaryEntryPointConnection)
        {
            final BinaryEntryPointConnection binaryEntryPointConnection = (BinaryEntryPointConnection)connection;
            sessionVerIdAtAcquire = binaryEntryPointConnection.sessionVerId();
        }
        return connectionHandler;
    }

    public long sessionVerIdAtAcquire()
    {
        return sessionVerIdAtAcquire;
    }

    public boolean invoked()
    {
        return invoked;
    }

    public FixPConnection connection()
    {
        return connection;
    }

    public void reset()
    {
        invoked = false;
        connection = null;
    }
}
