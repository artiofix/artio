/*
 * Copyright 2015-2025 Real Logic Limited, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.protocol;

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.engine.framer.MessageTypeExtractor;
import uk.co.real_logic.artio.messages.*;
import uk.co.real_logic.artio.util.CharFormatter;

import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.LogTag.FIX_CONNECTION;
import static uk.co.real_logic.artio.protocol.GatewayPublication.FRAME_SIZE;

public final class ProtocolSubscription implements ControlledFragmentHandler
{
    private static final Action UNKNOWN_TEMPLATE = null;

    private final CharFormatter disconnectFormatter = new CharFormatter(
        "FixSubscription Disconnect: %s [%s]");
    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final DisconnectDecoder disconnect = new DisconnectDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final FixPMessageDecoder iLinkMessage = new FixPMessageDecoder();

    private final ProtocolHandler protocolHandler;
    private final Action defaultAction;

    public static ProtocolSubscription of(final ProtocolHandler protocolHandler)
    {
        return new ProtocolSubscription(protocolHandler, CONTINUE);
    }

    public static ControlledFragmentHandler of(
        final ProtocolHandler protocolHandler, final ControlledFragmentHandler other)
    {
        final ProtocolSubscription subscription = new ProtocolSubscription(protocolHandler, UNKNOWN_TEMPLATE);
        return (buffer, offset, length, header) ->
        {
            final Action action = subscription.onFragment(buffer, offset, length, header);

            if (action == UNKNOWN_TEMPLATE)
            {
                return other.onFragment(buffer, offset, length, header);
            }

            return action;
        };
    }

    private ProtocolSubscription(final ProtocolHandler protocolHandler, final Action defaultAction)
    {
        this.protocolHandler = protocolHandler;
        this.defaultAction = defaultAction;
    }

    @Override
    public Action onFragment(
        final DirectBuffer buffer, final int start, final int length, final Header header)
    {
        int offset = start;

        messageHeader.wrap(buffer, offset);

        final int blockLength = messageHeader.blockLength();
        final int version = messageHeader.version();
        offset += MessageHeaderDecoder.ENCODED_LENGTH;

        switch (messageHeader.templateId())
        {
            case FixMessageDecoder.TEMPLATE_ID:
            {
                return onFixMessage(buffer, offset, blockLength, version, header);
            }

            case DisconnectDecoder.TEMPLATE_ID:
            {
                return onDisconnect(buffer, offset, blockLength, version);
            }

            case FixPMessageDecoder.TEMPLATE_ID:
            {
                return onILinkMessage(buffer, offset, blockLength, version);
            }
        }

        return defaultAction;
    }

    private Action onILinkMessage(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version)
    {
        iLinkMessage.wrap(buffer, offset, blockLength, version);
        final long connectionId = iLinkMessage.connection();
        return protocolHandler.onFixPMessage(
            connectionId,
            buffer,
            offset + FixPMessageDecoder.BLOCK_LENGTH);
    }

    private Action onDisconnect(
        final DirectBuffer buffer, final int offset, final int blockLength, final int version)
    {
        disconnect.wrap(buffer, offset, blockLength, version);
        final int libraryId = disconnect.libraryId();
        final long connectionId = disconnect.connection();
        final DisconnectReason reason = disconnect.reason();
        if (DebugLogger.isEnabled(FIX_CONNECTION))
        {
            DebugLogger.log(FIX_CONNECTION, disconnectFormatter.clear()
                .with(connectionId)
                .with(reason.toString()));
        }

        return protocolHandler.onDisconnect(libraryId, connectionId, reason);
    }

    private Action onFixMessage(
        final DirectBuffer buffer,
        final int offset,
        final int blockLength,
        final int version,
        final Header header)
    {
        messageFrame.wrap(buffer, offset, blockLength, version);
        final int metaDataLength = messageFrame.skipMetaData();

        final int messageLength = messageFrame.bodyLength();
        final long messageType = MessageTypeExtractor.getMessageType(messageFrame);
        return protocolHandler.onMessage(
            buffer,
            offset + FRAME_SIZE + metaDataLength,
            messageLength,
            messageFrame.libraryId(),
            messageFrame.connection(),
            messageFrame.session(),
            messageFrame.sequenceIndex(),
            messageType,
            messageFrame.timestamp(),
            messageFrame.status(),
            messageFrame.sequenceNumber(),
            header,
            metaDataLength);
    }
}
