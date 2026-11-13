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
package uk.co.real_logic.artio.validation;

import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;

/**
 * A dummy message validation strategy that checks nothing.
 */
class NoMessageValidationStrategy implements MessageValidationStrategy
{
    NoMessageValidationStrategy()
    {
    }

    @Override
    public boolean validate(final SessionHeaderDecoder logon)
    {
        return true;
    }

    @Override
    public int invalidTagId()
    {
        return notSupported();
    }

    @Override
    public int rejectReason()
    {
        return notSupported();
    }

    private int notSupported()
    {
        throw new UnsupportedOperationException(
            "NoMessageValidationStrategy never fails, invoking me is a breach of the contract");
    }
}
