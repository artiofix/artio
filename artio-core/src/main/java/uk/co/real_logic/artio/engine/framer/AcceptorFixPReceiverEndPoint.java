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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.fixp.FixPProtocol;
import uk.co.real_logic.artio.fixp.FixPRejectRefIdExtractor;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

class AcceptorFixPReceiverEndPoint extends FixPReceiverEndPoint
{
    private final int finishedSendingTemplateId;
    private final int finishedReceivingTemplateId;

    private boolean requiresAuthentication = true;

    AcceptorFixPReceiverEndPoint(
        final long connectionId,
        final TcpChannel channel,
        final int bufferSize,
        final ErrorHandler errorHandler,
        final Framer framer,
        final GatewayPublication publication,
        final int libraryId,
        final EpochNanoClock epochNanoClock,
        final long correlationId,
        final FixPProtocol protocol,
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
            protocol.encodingType(),
            throttleWindowInMs,
            throttleLimitOfMessages,
            fixPRejectRefIdExtractor);
        finishedSendingTemplateId = protocol.finishedSendingTemplateId();
        finishedReceivingTemplateId = protocol.finishedReceivingTemplateId();
    }

    @Override
    void checkMessage(final MutableAsciiBuffer buffer, final int offset, final int messageSize)
    {
        if (requiresAuthentication && pendingAcceptorLogon == null)
        {
            try
            {
                pendingAcceptorLogon = fixPGatewaySession.onLogon(buffer, offset, messageSize, channel, framer);
            }
            catch (final IllegalArgumentException e)
            {
                completeDisconnect(DisconnectReason.FIRST_MESSAGE_NOT_LOGON);
            }
        }

        final int finishedSendingTemplateId = this.finishedSendingTemplateId;
        if (finishedSendingTemplateId != FixPProtocol.DOES_NOT_SUPPORT_SEQUENCE_FINISHING_TEMPLATE_ID)
        {
            final int templateId = readTemplateId(buffer, offset);

            if (templateId == finishedSendingTemplateId || templateId == finishedReceivingTemplateId)
            {
                fixPGatewaySession.onEndSequence();
            }
        }
    }

    @Override
    boolean requiresAuthentication()
    {
        return requiresAuthentication;
    }

    void authenticated()
    {
        requiresAuthentication = false;
        framer.receiverEndPointPollingOptional(connectionId);
    }

    @Override
    void trackDisconnect()
    {
    }
}
