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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.fixp.FixPProtocol;
import uk.co.real_logic.artio.fixp.FixPRejectRefIdExtractor;
import uk.co.real_logic.artio.fixp.InternalFixPContext;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

class InitiatorFixPReceiverEndPoint extends FixPReceiverEndPoint
{
    private final InternalFixPContext context;
    private final FixPContexts fixPContexts;
    private final int negotiationResponse;

    InitiatorFixPReceiverEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final int bufferSize,
        final ErrorHandler errorHandler,
        final Framer framer,
        final GatewayPublication publication,
        final int libraryId,
        final InternalFixPContext context,
        final EpochNanoClock epochNanoClock,
        final long correlationId,
        final FixPContexts fixPContexts,
        final FixPProtocol fixPProtocol,
        final int throttleWindowInMs,
        final int throttleLimitOfMessages,
        final FixPRejectRefIdExtractor fixPRejectRefIdExtractor)
    {
        super(
            connectionId,
            channel,
            bufferSize,
            errorHandler,
            framer,
            publication,
            libraryId,
            epochNanoClock,
            correlationId,
            fixPProtocol.encodingType(),
            throttleWindowInMs,
            throttleLimitOfMessages,
            fixPRejectRefIdExtractor);
        this.context = context;
        this.fixPContexts = fixPContexts;
        this.negotiationResponse = fixPProtocol.negotiateResponseTemplateId();
        sessionId(context.surrogateSessionId());
    }

    @Override
    void checkMessage(final MutableAsciiBuffer buffer, final int offset, final int messageSize)
    {
        if (readTemplateId(buffer, offset) == negotiationResponse)
        {
            if (context.onInitiatorNegotiateResponse())
            {
                fixPContexts.saveNewContext(context);
            }
            else
            {
                fixPContexts.updateContext(context);
            }
        }
    }

    @Override
    void trackDisconnect()
    {
        context.onInitiatorDisconnect();
    }

    @Override
    boolean requiresAuthentication()
    {
        return false;
    }
}
