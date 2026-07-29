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

import org.junit.jupiter.api.Test;
import uk.co.real_logic.artio.decoder.AbstractLogonDecoder;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.AuthenticationProxy;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;

import java.io.IOException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.co.real_logic.artio.system_tests.FixConnection.*;

public class ProxyProtocolSystemTest extends AbstractMessageBasedAcceptorSystemTest
{
    private String remoteAuthAddress;

    {
        optionalAuthStrategy = new AuthenticationStrategy()
        {
            public void authenticateAsync(
                final AbstractLogonDecoder logon, final AuthenticationProxy authProxy)
            {
                remoteAuthAddress = authProxy.remoteAddress();
                authProxy.accept();
            }

            public boolean authenticate(final AbstractLogonDecoder logon)
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    public void shouldSupportProxyV1Protocol() throws IOException
    {
        shouldSupportProxyProtocol(FixConnection::sendProxyV1Line, PROXY_SOURCE_IP, PROXY_SOURCE_PORT);
    }

    @Test
    public void shouldSupportProxyV1ProtocolLargest() throws IOException
    {
        shouldSupportProxyProtocol(
            FixConnection::sendProxyV1LargestLine, LARGEST_PROXY_SOURCE_IP, LARGEST_PROXY_SOURCE_PORT);
    }

    @Test
    public void shouldSupportProxyV2ProtocolTcpV4() throws IOException
    {
        shouldSupportProxyProtocol(FixConnection::sendProxyV2LineTcpV4, PROXY_SOURCE_IP, PROXY_V2_SOURCE_PORT);
    }

    @Test
    public void shouldSupportProxyV2ProtocolTcpV6() throws IOException
    {
        shouldSupportProxyProtocol(
            FixConnection::sendProxyV2LineTcpV6, PROXY_V2_IPV6_SOURCE_IP, PROXY_V2_IPV6_SOURCE_PORT);
    }

    @Test
    public void shouldSupportProxyV2ProtocolTcpV6Localhost() throws IOException
    {
        shouldSupportProxyProtocol(
            FixConnection::sendProxyV2LineTcpV6Localhost, "::1", PROXY_V2_IPV6_SOURCE_PORT);
    }

    private void shouldSupportProxyProtocol(
        final Consumer<FixConnection> sendLine, final String proxySourceIp, final int proxySourcePort)
        throws IOException
    {
        setup(true, true);

        setupLibrary();

        try (FixConnection connection = FixConnection.initiate(port))
        {
            sendLine.accept(connection);
            logon(connection);

            final Session session = acquireSession();

            assertEquals(proxySourceIp, session.connectedHost());
            assertEquals(proxySourcePort, session.connectedPort());
            assertEquals(proxySourceIp + ":" + proxySourcePort, remoteAuthAddress);
        }
    }
}
