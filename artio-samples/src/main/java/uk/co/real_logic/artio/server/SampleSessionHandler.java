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
package uk.co.real_logic.artio.server;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.builder.Printer;
import uk.co.real_logic.artio.decoder.PrinterImpl;
import uk.co.real_logic.artio.library.OnMessageInfo;
import uk.co.real_logic.artio.library.SessionHandler;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;

public class SampleSessionHandler implements SessionHandler
{

    private final AsciiBuffer string = new MutableAsciiBuffer();
    private final Printer printer = new PrinterImpl();

    public SampleSessionHandler(final Session session)
    {
    }

    @Override
    public Action onMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final int libraryId,
        final Session session,
        final int sequenceIndex,
        final long messageType,
        final long timestampInNs,
        final long position,
        final OnMessageInfo messageInfo)
    {
        string.wrap(buffer);
        System.out.printf("%d -> %s%n", session.id(), printer.toString(string, offset, length, messageType));

        return CONTINUE;
    }

    @Override
    public void onTimeout(final int libraryId, final Session session)
    {
    }

    @Override
    public void onSlowStatus(final int libraryId, final Session session, final boolean hasBecomeSlow)
    {
    }

    @Override
    public Action onDisconnect(final int libraryId, final Session session, final DisconnectReason reason)
    {
        System.out.printf("%d Disconnected: %s%n", session.id(), reason);
        return CONTINUE;
    }

    @Override
    public void onSessionStart(final Session session)
    {
    }
}
