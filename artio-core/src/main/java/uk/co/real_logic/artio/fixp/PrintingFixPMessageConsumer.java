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
package uk.co.real_logic.artio.fixp;

import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.sbe.CompositeDecoderFlyweight;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.messages.FixPMessageDecoder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.SOFH_LENGTH;

public class PrintingFixPMessageConsumer implements FixPMessageConsumer
{
    private final CompositeDecoderFlyweight header;
    private final MethodHandle templateId;
    private final MethodHandle blockLength;
    private final MethodHandle version;
    private final StringBuilder builder = new StringBuilder();
    private final int inboundStreamId;

    private FixPMessageDissector dissector;

    public PrintingFixPMessageConsumer(final int inboundStreamId, final FixPProtocol protocol)
    {
        this(inboundStreamId, protocol, null);
        dissector = new FixPMessageDissector(this::log, protocol.messageDecoders());
    }

    public PrintingFixPMessageConsumer(
        final int inboundStreamId, final FixPProtocol protocol, final FixPMessageDissector dissector)
    {
        this.inboundStreamId = inboundStreamId;
        this.dissector = dissector;
        this.header = protocol.makeHeader();

        final Class<?> protocolHdrClass = header.getClass();
        final MethodType methodType = MethodType.methodType(int.class);
        try
        {
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            templateId = lookup.findVirtual(protocolHdrClass, "templateId", methodType).bindTo(header);
            blockLength = lookup.findVirtual(protocolHdrClass, "blockLength", methodType).bindTo(header);
            version = lookup.findVirtual(protocolHdrClass, "version", methodType).bindTo(header);
        }
        catch (final NoSuchMethodException | IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
    }

    private void log(final String prefix, final Consumer<StringBuilder> appendTo)
    {
        final StringBuilder builder = this.builder;
        builder.setLength(0);
        builder.append(prefix);
        appendTo.accept(builder);
        System.out.println(builder);
    }

    @Override
    public void onMessage(
        final FixPMessageDecoder unused,
        final DirectBuffer buffer,
        final int start,
        final ArtioLogHeader logHeader)
    {
        int offset = start + SOFH_LENGTH;

        final CompositeDecoderFlyweight header = this.header;
        header.wrap(buffer, offset);

        try
        {
            final int templateId = (int)this.templateId.invokeExact();
            final int blockLength = (int)this.blockLength.invokeExact();
            final int version = (int)this.version.invokeExact();
            final boolean inbound = logHeader != null && logHeader.streamId() == inboundStreamId;

            offset += header.encodedLength();

            dissector.onBusinessMessage(templateId, buffer, offset, blockLength, version, inbound);
        }
        catch (final Throwable t)
        {
            LangUtil.rethrowUnchecked(t);
        }
    }
}
