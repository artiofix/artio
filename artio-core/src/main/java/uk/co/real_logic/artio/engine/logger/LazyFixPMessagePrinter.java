/*
 * Copyright 2020 Monotonic Limited.
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

import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;
import uk.co.real_logic.artio.fixp.FixPProtocol;
import uk.co.real_logic.artio.fixp.FixPProtocolFactory;
import uk.co.real_logic.artio.fixp.PrintingFixPMessageConsumer;
import uk.co.real_logic.artio.messages.FixPMessageDecoder;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.util.Lazy;

final class LazyFixPMessagePrinter implements FixPMessageConsumer
{
    private final int inboundStreamId;
    private final FixPProtocolType protocolType;

    private final Lazy<FixPMessageConsumer> lazyDelegate = new Lazy<>(this::makePrinter);

    private PrintingFixPMessageConsumer makePrinter()
    {
        final FixPProtocol protocol = FixPProtocolFactory.make(protocolType, Throwable::printStackTrace);
        return new PrintingFixPMessageConsumer(inboundStreamId, protocol);
    }

    LazyFixPMessagePrinter(final int inboundStreamId, final FixPProtocolType protocolType)
    {
        this.inboundStreamId = inboundStreamId;
        this.protocolType = protocolType;
    }

    @Override
    public void onMessage(
        final FixPMessageDecoder iLinkMessage, final DirectBuffer buffer, final int offset, final ArtioLogHeader header)
    {
        lazyDelegate.get().onMessage(iLinkMessage, buffer, offset, header);
    }
}
