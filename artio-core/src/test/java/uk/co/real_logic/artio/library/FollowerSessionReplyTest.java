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
package uk.co.real_logic.artio.library;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.co.real_logic.artio.builder.Decoder;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.HeaderDecoder;
import uk.co.real_logic.artio.messages.FixPProtocolType;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FollowerSessionReplyTest
{
    @Test
    public void shouldEncodeBeginStringAndBodyLengthForFixtHeader()
    {
        final SessionHeaderEncoder headerEncoder = new uk.co.real_logic.artio.fixt.builder.HeaderEncoder()
            .senderCompID("INITIATOR")
            .targetCompID("ACCEPTOR");

        final LibraryPoller libraryPoller = mockConnectedPoller();
        new FollowerSessionReply(libraryPoller, 1000L, headerEncoder);

        final EncodedFollowerHeader encoded = captureEncodedHeader(libraryPoller);
        final String encodedStr = encoded.buffer.getAscii(encoded.offset, encoded.length);
        assertTrue(encodedStr.startsWith("8=FIXT.1.1\0019="), encodedStr);

        final uk.co.real_logic.artio.fixt.decoder.HeaderDecoder decoder =
            new uk.co.real_logic.artio.fixt.decoder.HeaderDecoder();
        assertEquals(encoded.length, decoder.decode(encoded.buffer, encoded.offset, encoded.length));
        assertEquals(Decoder.NO_ERROR, decoder.rejectReason());
        assertTrue(decoder.bodyLength() > 0);
        assertEquals("FIXT.1.1", decoder.beginStringAsString());
        assertEquals("A", decoder.msgTypeAsString());
        assertEquals("INITIATOR", decoder.senderCompIDAsString());
        assertEquals("ACCEPTOR", decoder.targetCompIDAsString());
    }

    @Test
    public void shouldEncodeBeginStringAndBodyLengthForDefaultFixHeader()
    {
        final SessionHeaderEncoder headerEncoder = new uk.co.real_logic.artio.builder.HeaderEncoder()
            .senderCompID("INITIATOR")
            .targetCompID("ACCEPTOR");

        final LibraryPoller libraryPoller = mockConnectedPoller();
        new FollowerSessionReply(libraryPoller, 1000L, headerEncoder);

        final EncodedFollowerHeader encoded = captureEncodedHeader(libraryPoller);
        final String encodedStr = encoded.buffer.getAscii(encoded.offset, encoded.length);
        assertTrue(encodedStr.startsWith("8=FIX.4.4\0019="), encodedStr);

        final HeaderDecoder decoder = new HeaderDecoder();
        assertEquals(encoded.length, decoder.decode(encoded.buffer, encoded.offset, encoded.length));
        assertEquals(Decoder.NO_ERROR, decoder.rejectReason());
        assertTrue(decoder.bodyLength() > 0);
        assertEquals("FIX.4.4", decoder.beginStringAsString());
        assertEquals("A", decoder.msgTypeAsString());
        assertEquals("INITIATOR", decoder.senderCompIDAsString());
        assertEquals("ACCEPTOR", decoder.targetCompIDAsString());
    }

    private static LibraryPoller mockConnectedPoller()
    {
        final LibraryPoller libraryPoller = mock(LibraryPoller.class);
        when(libraryPoller.isConnected()).thenReturn(true);
        when(libraryPoller.register(any())).thenReturn(1L);
        return libraryPoller;
    }

    private static EncodedFollowerHeader captureEncodedHeader(final LibraryPoller libraryPoller)
    {
        final ArgumentCaptor<MutableAsciiBuffer> bufferCaptor = ArgumentCaptor.forClass(MutableAsciiBuffer.class);
        final ArgumentCaptor<Integer> offsetCaptor = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<Integer> lengthCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(libraryPoller).saveFollowerSessionRequest(
            eq(1L),
            eq(FixPProtocolType.NULL_VAL),
            bufferCaptor.capture(),
            offsetCaptor.capture(),
            lengthCaptor.capture());

        return new EncodedFollowerHeader(
            bufferCaptor.getValue(), offsetCaptor.getValue(), lengthCaptor.getValue());
    }

    private static final class EncodedFollowerHeader
    {
        private final MutableAsciiBuffer buffer;
        private final int offset;
        private final int length;

        private EncodedFollowerHeader(
            final MutableAsciiBuffer buffer,
            final int offset,
            final int length)
        {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
        }
    }
}
