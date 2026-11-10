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

import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConnectHandler;

class FakeConnectHandler implements LibraryConnectHandler
{
    private boolean shouldCloseOnConnect = false;
    private Exception exception;

    @Override
    public void onConnect(final FixLibrary library)
    {
        if (shouldCloseOnConnect)
        {
            try
            {
                library.close();
            }
            catch (final Exception e)
            {
                this.exception = e;
            }
        }
    }

    @Override
    public void onDisconnect(final FixLibrary library)
    {
    }

    void shouldCloseOnConnect(final boolean shouldCloseOnConnect)
    {
        this.shouldCloseOnConnect = shouldCloseOnConnect;
    }

    public Exception exception()
    {
        return exception;
    }
}
