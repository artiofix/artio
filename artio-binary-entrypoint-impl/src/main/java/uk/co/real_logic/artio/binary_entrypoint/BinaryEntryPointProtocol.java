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

import b3.entrypoint.fixp.sbe.FinishedReceivingDecoder;
import b3.entrypoint.fixp.sbe.FinishedSendingDecoder;
import b3.entrypoint.fixp.sbe.NegotiateEncoder;
import b3.entrypoint.fixp.sbe.NegotiateResponseDecoder;
import io.aeron.ExclusivePublication;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.logger.FixPSequenceNumberHandler;
import uk.co.real_logic.artio.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.artio.fixp.*;
import uk.co.real_logic.artio.library.FixPSessionOwner;
import uk.co.real_logic.artio.library.InternalFixPConnection;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.sbe.ir.Ir;

import static uk.co.real_logic.artio.fixp.AbstractFixPOffsets.templateId;
import static uk.co.real_logic.artio.fixp.SimpleOpenFramingHeader.BINARY_ENTRYPOINT_TYPE;

public class BinaryEntryPointProtocol extends FixPProtocol
{
    private static final String SBE_IR_FILE = "binary_entrypoint.sbeir";

    private static final class LazyLoader
    {
        static final Ir IR = AbstractFixPOffsets.loadSbeIr(NegotiateEncoder.class, SBE_IR_FILE);
    }

    public static Ir loadSbeIr()
    {
        return LazyLoader.IR;
    }

    @Override
    protected Ir loadIr()
    {
        return loadSbeIr();
    }

    protected IntHashSet findAllTemplateIds()
    {
        final IntHashSet templateIds = new IntHashSet();
        loadSbeIr().messages().forEach(messageTokens ->
        {
            final int templateId = templateId(messageTokens);
            templateIds.add(templateId);
        });
        return templateIds;
    }

    public static <T> T unsupported()
    {
        throw new UnsupportedOperationException("Binary Entrypoint is only implemented as an acceptor");
    }

    public BinaryEntryPointProtocol()
    {
        super(
            FixPProtocolType.BINARY_ENTRYPOINT,
            BINARY_ENTRYPOINT_TYPE,
            FinishedSendingDecoder.TEMPLATE_ID,
            FinishedReceivingDecoder.TEMPLATE_ID,
            NegotiateResponseDecoder.TEMPLATE_ID,
            "b3.entrypoint.fixp.sbe");
    }

    @Override
    public BinaryEntryPointParser makeParser(final FixPConnection connection)
    {
        return new BinaryEntryPointParser((InternalBinaryEntryPointConnection)connection);
    }

    @Override
    public BinaryEntryPointProxy makeProxy(
        final FixPMessageDissector fixPDissector,
        final ExclusivePublication publication,
        final EpochNanoClock epochNanoClock)
    {
        return new BinaryEntryPointProxy(this, fixPDissector, 0, publication, epochNanoClock);
    }

    @Override
    public BinaryEntryPointOffsets makeOffsets()
    {
        return new BinaryEntryPointOffsets();
    }

    @Override
    public InternalFixPConnection makeAcceptorConnection(
        final long connectionId,
        final GatewayPublication outboundPublication,
        final GatewayPublication inboundPublication,
        final int libraryId,
        final FixPSessionOwner owner,
        final long lastReceivedSequenceNumber,
        final long lastSentSequenceNumber,
        final long lastConnectPayload,
        final FixPContext context,
        final CommonConfiguration configuration,
        final FixPMessageDissector dissector)
    {
        return new InternalBinaryEntryPointConnection(
            this,
            connectionId,
            outboundPublication,
            inboundPublication,
            libraryId,
            owner,
            lastReceivedSequenceNumber,
            lastSentSequenceNumber,
            lastConnectPayload,
            configuration,
            (BinaryEntryPointContext)context,
            dissector);
    }

    @Override
    public AbstractFixPStorage makeStorage(final EpochNanoClock clock)
    {
        return new BinaryEntryPointStorage();
    }

    @Override
    public AbstractFixPSequenceExtractor makeSequenceExtractor(
        final FixPSequenceNumberHandler handler,
        final SequenceNumberIndexReader sequenceNumberReader)
    {
        return new BinaryEntryPointSequenceExtractor(
            handler,
            sequenceNumberReader);
    }

    @Override
    public boolean explicitSequenceNumbers()
    {
        return false;
    }
}
