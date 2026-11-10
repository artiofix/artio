/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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
import uk.co.real_logic.artio.dictionary.CharArraySet;
import uk.co.real_logic.artio.dictionary.SessionConstants;

import java.util.Collection;

import static uk.co.real_logic.artio.fields.RejectReason.COMPID_PROBLEM;

/**
 * A message validation strategy that checks the sender comp id of each message.
 */
class SenderCompIdValidationStrategy implements MessageValidationStrategy
{
    private final CharArraySet validSenderIds;

    SenderCompIdValidationStrategy(final Collection<String> validSenderIds)
    {
        this.validSenderIds = new CharArraySet(validSenderIds);
    }

    @Override
    public boolean validate(final SessionHeaderDecoder header)
    {
        final char[] senderCompID = header.senderCompID();
        final int senderCompIDLength = header.senderCompIDLength();

        return validSenderIds.contains(senderCompID, senderCompIDLength);
    }

    @Override
    public int invalidTagId()
    {
        return SessionConstants.SENDER_COMP_ID;
    }

    @Override
    public int rejectReason()
    {
        return COMPID_PROBLEM.representation();
    }
}
