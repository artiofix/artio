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
package uk.co.real_logic.artio.system_tests;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.fixp.FixPConnection;
import uk.co.real_logic.artio.fixp.FixPMessageHeader;
import uk.co.real_logic.artio.ilink.ILink3ConnectionHandler;
import uk.co.real_logic.artio.library.NotAppliedResponse;
import uk.co.real_logic.artio.messages.DisconnectReason;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class FakeILink3ConnectionHandler implements ILink3ConnectionHandler
{
    @Override
    public Action onNotApplied(
        final FixPConnection connection,
        final long fromSequenceNumber, final long msgCount, final NotAppliedResponse response)
    {
        return CONTINUE;
    }

    @Override
    public Action onRetransmitReject(
        final FixPConnection connection, final String reason, final long requestTimestamp, final int errorCodes)
    {
        return CONTINUE;
    }

    @Override
    public Action onRetransmitTimeout(final FixPConnection connection)
    {
        return CONTINUE;
    }

    @Override
    public Action onSequence(final FixPConnection connection, final long nextSeqNo)
    {
        return CONTINUE;
    }

    @Override
    public Action onError(final FixPConnection connection, final Exception ex)
    {
        return CONTINUE;
    }

    @Override
    public Action onDisconnect(final FixPConnection connection, final DisconnectReason reason)
    {
        return CONTINUE;
    }

    @Override
    public Action onBusinessMessage(
        final FixPConnection connection,
        final int templateId,
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final boolean possRetrans,
        final FixPMessageHeader messageHeader)
    {
        return CONTINUE;
    }
}
