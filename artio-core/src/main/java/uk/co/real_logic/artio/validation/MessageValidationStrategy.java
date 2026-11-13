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

import java.util.Collection;

/**
 * A strategy that, if message validation is switched on, validates each FIX message in turn before
 * the message is handed off the domain logic. Validation might including checking that the sender
 * or target comp id is on an approved list.
 */
public interface MessageValidationStrategy
{
    static MessageValidationStrategy none()
    {
        return new NoMessageValidationStrategy();
    }

    static MessageValidationStrategy senderCompId(final Collection<String> validSenderIds)
    {
        return new SenderCompIdValidationStrategy(validSenderIds);
    }

    static MessageValidationStrategy targetCompId(final String gatewayCompId)
    {
        return new TargetCompIdValidationStrategy(gatewayCompId);
    }

    /**
     * Validate the header in question.
     *
     * @param header the header to validate.
     * @return true if valid, false otherwise.
     */
    boolean validate(SessionHeaderDecoder header);

    /**
     * Returns the id of the tag that was invalid if the header didn't validate, undefined otherwise.
     *
     * @return the id of the tag that was invalid.
     */
    int invalidTagId();

    /**
     * Returns the session reject reason if the header didn't validate, undefined otherwise.
     *
     * @return the session reject reason.
     */
    int rejectReason();

    /**
     * Compose two message validation strategies together to form a new message validation strategy where you
     * need to pass both strategies for a message to be valid.
     *
     * @param right the other validation strategy to compose with.
     * @return the new message validation strategy.
     */
    default MessageValidationStrategy and(final MessageValidationStrategy right)
    {
        final MessageValidationStrategy left = this;
        return new MessageValidationStrategy()
        {
            private int invalidTagId;
            private int rejectReason;

            @Override
            public boolean validate(final SessionHeaderDecoder header)
            {
                final boolean leftValid = left.validate(header);

                if (leftValid)
                {
                    final boolean rightValid = right.validate(header);
                    if (rightValid)
                    {
                        return true;
                    }
                    else
                    {
                        invalidTagId = right.invalidTagId();
                        rejectReason = right.rejectReason();
                    }
                }
                else
                {
                    invalidTagId = left.invalidTagId();
                    rejectReason = left.rejectReason();
                }

                return false;
            }

            @Override
            public int invalidTagId()
            {
                return invalidTagId;
            }

            @Override
            public int rejectReason()
            {
                return rejectReason;
            }
        };
    }
}
