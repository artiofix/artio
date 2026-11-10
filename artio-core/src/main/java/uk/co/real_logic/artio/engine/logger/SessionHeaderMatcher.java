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
package uk.co.real_logic.artio.engine.logger;

import uk.co.real_logic.artio.engine.SessionInfo;
import uk.co.real_logic.artio.session.CompositeKey;

import java.util.Objects;
import java.util.function.Predicate;

class SessionHeaderMatcher implements Predicate<SessionInfo>
{
    private final HeaderField headerField;
    private final String value;

    SessionHeaderMatcher(final HeaderField headerField, final String value)
    {
        Objects.requireNonNull(headerField);
        Objects.requireNonNull(value);

        this.headerField = headerField;
        this.value = value;
    }

    @Override
    public boolean test(final SessionInfo sessionInfo)
    {
        final CompositeKey compositeKey = sessionInfo.sessionKey();
        switch (headerField)
        {
            case SENDER_COMP_ID:
                return compositeKey.localCompId().equals(value);

            case TARGET_COMP_ID:
                return compositeKey.remoteCompId().equals(value);

            case NOT_OPTIMISED:
            default:
                throw new IllegalStateException("Unoptimisable header field");
        }
    }

    @Override
    public String toString()
    {
        return "SessionHeaderMatcher{" +
            "headerField=" + headerField +
            ", value='" + value + '\'' +
            '}';
    }
}
