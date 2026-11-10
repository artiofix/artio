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
package uk.co.real_logic.artio.binary_entrypoint;

import uk.co.real_logic.artio.fixp.FixPKey;
import uk.co.real_logic.artio.messages.FixPProtocolType;

public class BinaryEntryPointKey implements FixPKey
{
    private final long sessionID;

    public BinaryEntryPointKey(final long sessionID)
    {
        this.sessionID = sessionID;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final BinaryEntryPointKey that = (BinaryEntryPointKey)o;

        return sessionID == that.sessionID;
    }

    @Override
    public int hashCode()
    {
        return (int)(sessionID ^ (sessionID >>> 32));
    }

    @Override
    public FixPProtocolType protocolType()
    {
        return FixPProtocolType.BINARY_ENTRYPOINT;
    }

    @Override
    public long sessionIdIfExists()
    {
        return sessionID;
    }

    @Override
    public String toString()
    {
        return "BinaryEntryPointKey{" +
            "sessionID=" + sessionID +
            '}';
    }
}
