/*
 * Copyright 2019 Adaptive Financial Consulting Ltd.
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
import uk.co.real_logic.artio.decoder.AbstractUserRequestDecoder;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

public class CapturingAuthenticationStrategy implements AuthenticationStrategy
{
    private final MessageValidationStrategy delegate;

    private String logonPassword;
    private String userRequestPassword;
    private String userRequestNewPassword;
    private long sessionId;

    private volatile boolean receivedUserRequest;

    public CapturingAuthenticationStrategy(final MessageValidationStrategy delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public boolean authenticate(final AbstractLogonDecoder logon)
    {
        logonPassword = logon.passwordAsString();

        return delegate.validate(logon.header());
    }

    @Override
    public void onUserRequest(
        final AbstractUserRequestDecoder userRequest, final long sessionId)
    {
        userRequestPassword = new String(userRequest.password(), 0, userRequest.passwordLength());
        userRequestNewPassword = new String(userRequest.newPassword(), 0, userRequest.newPasswordLength());
        receivedUserRequest = true;
        this.sessionId = sessionId;
    }

    public String logonPassword()
    {
        return logonPassword;
    }

    public String userRequestPassword()
    {
        return userRequestPassword;
    }

    public String userRequestNewPassword()
    {
        return userRequestNewPassword;
    }

    public boolean receivedUserRequest()
    {
        return receivedUserRequest;
    }

    public long sessionId()
    {
        return sessionId;
    }
}
