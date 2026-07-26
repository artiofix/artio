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
package uk.co.real_logic.artio.session;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import uk.co.real_logic.artio.builder.SessionHeaderEncoder;
import uk.co.real_logic.artio.decoder.SessionHeaderDecoder;
import uk.co.real_logic.artio.dictionary.generation.CodecUtil;
import uk.co.real_logic.artio.storage.messages.SenderAndTargetCompositeKeyDecoder;
import uk.co.real_logic.artio.storage.messages.SenderAndTargetCompositeKeyEncoder;

import java.util.Arrays;

import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.artio.dictionary.SessionConstants.SENDER_COMP_ID;
import static uk.co.real_logic.artio.dictionary.SessionConstants.TARGET_COMP_ID;
import static uk.co.real_logic.artio.session.SessionIdStrategy.checkMissing;

/**
 * A simple, and dumb session id Strategy based upon hashing SenderCompID and TargetCompID. Makes no assumptions
 * about the nature of either identifiers.
 */
class SenderAndTargetSessionIdStrategy implements SessionIdStrategy
{
    private static final int BLOCK_AND_LENGTH_FIELDS_LENGTH = SenderAndTargetCompositeKeyEncoder.BLOCK_LENGTH + 4;

    private final SenderAndTargetCompositeKeyEncoder keyEncoder = new SenderAndTargetCompositeKeyEncoder();
    private final SenderAndTargetCompositeKeyDecoder keyDecoder = new SenderAndTargetCompositeKeyDecoder();
    private final int actingBlockLength = keyDecoder.sbeBlockLength();
    private final int actingVersion = keyDecoder.sbeSchemaVersion();

    SenderAndTargetSessionIdStrategy()
    {
    }

    public CompositeKey onAcceptLogon(final SessionHeaderDecoder header) throws IllegalArgumentException
    {
        requireNonNull(header, "header");

        final int localCompIDLength = header.targetCompIDLength();
        final int remoteCompIDLength = header.senderCompIDLength();

        if (localCompIDLength == 0 || remoteCompIDLength == 0)
        {
            throw new IllegalArgumentException("Missing comp id");
        }

        return new CompositeKeyImpl(
            header.targetCompID(), localCompIDLength,
            header.senderCompID(), remoteCompIDLength);
    }

    public CompositeKey onInitiateLogon(
        final String localCompId,
        final String localSubId,
        final String localLocationId,
        final String remoteCompId,
        final String remoteSubId,
        final String remoteLocationId)
    {
        requireNonNull(localCompId, "senderCompId");
        requireNonNull(remoteCompId, "targetCompId");

        final char[] senderCompID = localCompId.toCharArray();
        final char[] targetCompID = remoteCompId.toCharArray();
        return new CompositeKeyImpl(
            senderCompID, senderCompID.length, targetCompID, targetCompID.length);
    }

    public void setupSession(final CompositeKey compositeKey, final SessionHeaderEncoder headerEncoder)
    {
        requireNonNull(compositeKey, "compositeKey");
        requireNonNull(headerEncoder, "headerEncoder");

        final CompositeKeyImpl composite = (CompositeKeyImpl)compositeKey;
        headerEncoder.senderCompID(checkMissing(composite.localCompID));
        headerEncoder.targetCompID(checkMissing(composite.remoteCompID));
    }

    public int save(final CompositeKey compositeKey, final MutableDirectBuffer buffer, final int offset)
    {
        requireNonNull(compositeKey, "compositeKey");
        requireNonNull(buffer, "buffer");

        final String localCompId = compositeKey.localCompId();
        final String remoteCompId = compositeKey.remoteCompId();

        final int length = localCompId.length() + remoteCompId.length() + BLOCK_AND_LENGTH_FIELDS_LENGTH;
        if (buffer.capacity() < offset + length)
        {
            return INSUFFICIENT_SPACE;
        }

        keyEncoder.wrap(buffer, offset);
        keyEncoder.localCompId(localCompId);
        keyEncoder.remoteCompId(remoteCompId);

        return length;
    }

    public CompositeKey load(final DirectBuffer buffer, final int offset, final int length)
    {
        requireNonNull(buffer, "buffer");

        keyDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);

        final int localCompIdLength = keyDecoder.localCompIdLength();
        final byte[] localCompId = new byte[localCompIdLength];
        keyDecoder.getLocalCompId(localCompId, 0, localCompIdLength);

        final int remoteCompIdLength = keyDecoder.remoteCompIdLength();
        final byte[] remoteCompId = new byte[remoteCompIdLength];
        keyDecoder.getRemoteCompId(remoteCompId, 0, remoteCompIdLength);

        return new CompositeKeyImpl(localCompId, remoteCompId);
    }

    public int validateCompIds(final CompositeKey compositeKey, final SessionHeaderDecoder header)
    {
        final CompositeKeyImpl key = (CompositeKeyImpl)compositeKey;

        if (!CodecUtil.equals(header.senderCompID(), key.remoteCompID, header.senderCompIDLength()))
        {
            return SENDER_COMP_ID;
        }

        if (!CodecUtil.equals(header.targetCompID(), key.localCompID, header.targetCompIDLength()))
        {
            return TARGET_COMP_ID;
        }

        return 0;
    }

    private static final class CompositeKeyImpl implements CompositeKey
    {
        private final char[] localCompID;
        private final char[] remoteCompID;
        private final String localCompIDStr;
        private final String remoteCompIDStr;
        private final int hashCode;

        private CompositeKeyImpl(
            final char[] localCompID,
            final int localCompIDLength,
            final char[] remoteCompID,
            final int remoteCompIDLength)
        {
            this.localCompID = Arrays.copyOf(localCompID, localCompIDLength);
            this.remoteCompID = Arrays.copyOf(remoteCompID, remoteCompIDLength);
            this.localCompIDStr = new String(this.localCompID);
            this.remoteCompIDStr = new String(this.remoteCompID);
            hashCode = hash(this.localCompID, this.remoteCompID);
        }

        private CompositeKeyImpl(final byte[] localCompID, final byte[] remoteCompID)
        {
            this.localCompID = CodecUtil.fromBytes(localCompID);
            this.remoteCompID = CodecUtil.fromBytes(remoteCompID);
            this.localCompIDStr = new String(this.localCompID);
            this.remoteCompIDStr = new String(this.remoteCompID);
            hashCode = hash(this.localCompID, this.remoteCompID);
        }

        private int hash(final char[] senderCompID, final char[] targetCompID)
        {
            int result = Arrays.hashCode(senderCompID);
            result = 31 * result + Arrays.hashCode(targetCompID);
            return result;
        }

        public int hashCode()
        {
            return hashCode;
        }

        public boolean equals(final Object obj)
        {
            if (obj instanceof CompositeKeyImpl)
            {
                final CompositeKeyImpl compositeKey = (CompositeKeyImpl)obj;
                return Arrays.equals(compositeKey.localCompID, localCompID) &&
                    Arrays.equals(compositeKey.remoteCompID, remoteCompID);
            }

            return false;
        }

        public String toString()
        {
            return "CompositeKey{" +
                "localCompId=" + localCompId() +
                ", remoteCompId=" + remoteCompId() +
                '}';
        }

        public String localCompId()
        {
            return localCompIDStr;
        }

        public String localSubId()
        {
            return "";
        }

        public String localLocationId()
        {
            return "";
        }

        public String remoteCompId()
        {
            return remoteCompIDStr;
        }

        public String remoteSubId()
        {
            return "";
        }

        public String remoteLocationId()
        {
            return "";
        }
    }
}
