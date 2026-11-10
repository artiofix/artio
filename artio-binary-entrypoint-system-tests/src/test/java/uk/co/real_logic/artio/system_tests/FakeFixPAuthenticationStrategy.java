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

import uk.co.real_logic.artio.fixp.FixPContext;
import uk.co.real_logic.artio.validation.FixPAuthenticationProxy;
import uk.co.real_logic.artio.validation.FixPAuthenticationStrategy;

public class FakeFixPAuthenticationStrategy implements FixPAuthenticationStrategy
{
    private volatile boolean accept;
    private volatile FixPContext lastSessionId;
    private Enum<?> rejectCode;

    public FakeFixPAuthenticationStrategy()
    {
        accept();
    }

    public void accept()
    {
        this.accept = true;
    }

    public void reject()
    {
        this.accept = false;
    }

    public void reject(final Enum<?> rejectCode)
    {
        this.rejectCode = rejectCode;
        this.accept = false;
    }

    @Override
    public void authenticate(final FixPContext context, final FixPAuthenticationProxy authProxy)
    {
        lastSessionId = context;

        if (accept)
        {
            authProxy.accept();
        }
        else
        {
            if (rejectCode != null)
            {
                authProxy.reject(rejectCode);
            }
            else
            {
                authProxy.reject();
            }
        }
    }

    public FixPContext lastSessionId()
    {
        return lastSessionId;
    }

    public void resetLastSessionId()
    {
        lastSessionId = null;
    }
}
