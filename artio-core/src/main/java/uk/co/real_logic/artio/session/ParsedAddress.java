/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.session;

public class ParsedAddress
{
    public static final ParsedAddress NO_ADDRESS = new ParsedAddress("", Session.UNKNOWN);

    private final String host;
    private final int port;

    public ParsedAddress(final String host, final int port)
    {
        this.host = host;
        this.port = port;
    }

    public static ParsedAddress parse(final String address)
    {
        if (address.isEmpty())
        {
            return NO_ADDRESS;
        }

        final int split = address.lastIndexOf(':');
        final int start = address.startsWith("/") ? 1 : 0;
        final String host = address.substring(start, split);

        int port;
        try
        {
            port = Integer.parseInt(address.substring(split + 1));
        }
        catch (final NumberFormatException ignore)
        {
            // A bad port shouldn't stop the session being used, so report it as unknown, like NO_ADDRESS does.
            port = Session.UNKNOWN;
        }

        return new ParsedAddress(host, port);
    }

    public String host()
    {
        return host;
    }

    public int port()
    {
        return port;
    }
}
