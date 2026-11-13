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
package uk.co.real_logic.artio.ilink;

import org.agrona.DirectBuffer;
import uk.co.real_logic.artio.ArtioLogHeader;
import uk.co.real_logic.artio.fixp.FixPMessageConsumer;
import uk.co.real_logic.artio.messages.FixPMessageDecoder;

/**
 * Consumer to read iLink3 messages from the archive.
 *
 * Deprecated: replaced with {@link FixPMessageConsumer}.
 */
@Deprecated
@FunctionalInterface
public interface ILinkMessageConsumer extends FixPMessageConsumer
{
    /**
     * Callback for receiving iLink3 business messages. Details of business messages can be found in the
     * <a href="https://www.cmegroup.com/confluence/display/EPICSANDBOX/iLink+3+Application+Layer">CME
     * Documentation</a>. These may also be referred to as application layer messages.
     *
     * @param iLinkMessage the header of the iLink message containing a local timestamp.
     * @param buffer the buffer containing the message.
     * @param offset the offset within the buffer at which your message starts.
     * @param header a header object that can reflects properties of the original message before it was archived.
     */
    void onBusinessMessage(FixPMessageDecoder iLinkMessage, DirectBuffer buffer, int offset, ArtioLogHeader header);

    @Override
    default void onMessage(
        final FixPMessageDecoder fixPMessage,
        final DirectBuffer buffer,
        final int offset,
        final ArtioLogHeader header)
    {
        onBusinessMessage(fixPMessage, buffer, offset, header);
    }
}
