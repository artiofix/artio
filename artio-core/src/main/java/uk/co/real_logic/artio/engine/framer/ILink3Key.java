/*
 * Copyright 2020-2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import uk.co.real_logic.artio.fixp.FixPKey;
import uk.co.real_logic.artio.messages.FixPProtocolType;

import java.util.Objects;

public final class ILink3Key implements FixPKey
{
    private final int port;
    private final String host;
    private final String accessKeyId;

    public ILink3Key(final int port, final String host, final String accessKeyId)
    {
        this.port = port;
        this.host = host;
        this.accessKeyId = accessKeyId;
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

        final ILink3Key iLink3Key = (ILink3Key)o;

        if (port() != iLink3Key.port())
        {
            return false;
        }
        if (!Objects.equals(host(), iLink3Key.host()))
        {
            return false;
        }
        return Objects.equals(accessKeyId(), iLink3Key.accessKeyId());
    }

    @Override
    public int hashCode()
    {
        int result = port();
        result = 31 * result + (host() != null ? host().hashCode() : 0);
        result = 31 * result + (accessKeyId() != null ? accessKeyId().hashCode() : 0);
        return result;
    }

    public int port()
    {
        return port;
    }

    public String host()
    {
        return host;
    }

    public String accessKeyId()
    {
        return accessKeyId;
    }

    @Override
    public FixPProtocolType protocolType()
    {
        return FixPProtocolType.ILINK_3;
    }

    @Override
    public long sessionIdIfExists()
    {
        return UNK_SESSION;
    }
}
