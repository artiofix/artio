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
package uk.co.real_logic.artio.system_tests;

import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.dictionary.FixDictionary;
import uk.co.real_logic.artio.validation.AuthenticationProxy;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

public class MultiVersionAuthenticationStrategy implements AuthenticationStrategy
{
    private final String expectedSenderCompID;
    private final Class<? extends FixDictionary> fixDictionaryClass;

    public MultiVersionAuthenticationStrategy(
        final String expectedSenderCompID, final Class<? extends FixDictionary> fixDictionaryClass)
    {
        this.expectedSenderCompID = expectedSenderCompID;
        this.fixDictionaryClass = fixDictionaryClass;
    }

    @Override
    public boolean authenticate(final AbstractLogonDecoder logon)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void authenticateAsync(final AbstractLogonDecoder logon, final AuthenticationProxy authProxy)
    {
        if (expectedSenderCompID.equals(logon.header().senderCompIDAsString()))
        {
            authProxy.accept(fixDictionaryClass);
        }
        else
        {
            authProxy.accept();
        }
    }
}
