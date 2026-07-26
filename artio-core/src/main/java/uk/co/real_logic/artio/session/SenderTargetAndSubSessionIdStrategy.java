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
import uk.co.real_logic.artio.storage.messages.SenderTargetAndSubCompositeKeyDecoder;
import uk.co.real_logic.artio.storage.messages.SenderTargetAndSubCompositeKeyEncoder;

import java.util.Arrays;

import static java.util.Objects.requireNonNull;
import static uk.co.real_logic.artio.dictionary.SessionConstants.*;
import static uk.co.real_logic.artio.session.SessionIdStrategy.checkMissing;

/**
 * A simple, and dumb session id Strategy based upon hashing SenderCompID and TargetCompID. Makes no assumptions
 * about the nature of either identifiers.
 */
class SenderTargetAndSubSessionIdStrategy implements SessionIdStrategy
{
    private static final int BLOCK_AND_LENGTH_FIELDS_LENGTH = SenderTargetAndSubCompositeKeyEncoder.BLOCK_LENGTH + 6;

    private final SenderTargetAndSubCompositeKeyEncoder keyEncoder = new SenderTargetAndSubCompositeKeyEncoder();
    private final SenderTargetAndSubCompositeKeyDecoder keyDecoder = new SenderTargetAndSubCompositeKeyDecoder();
    private final int actingBlockLength = keyDecoder.sbeBlockLength();
    private final int actingVersion = keyDecoder.sbeSchemaVersion();

    SenderTargetAndSubSessionIdStrategy()
    {
    }

    public CompositeKey onAcceptLogon(final SessionHeaderDecoder header)
    {
        requireNonNull(header, "header");

        final int localCompIDLength = header.targetCompIDLength();
        final int localSubIDLength = header.senderSubIDLength();
        final int remoteCompIDLength = header.senderCompIDLength();

        if (localCompIDLength == 0 || localSubIDLength == 0 || remoteCompIDLength == 0)
        {
            throw new IllegalArgumentException("Missing comp id");
        }

        return new CompositeKeyImpl(
            header.targetCompID(), localCompIDLength,
            header.senderSubID(), localSubIDLength,
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
        final char[] senderCompIdChars = localCompId.toCharArray();
        final char[] senderSubIdChars = localSubId.toCharArray();
        final char[] targetCompIdChars = remoteCompId.toCharArray();
        return new CompositeKeyImpl(
            senderCompIdChars,
            senderCompIdChars.length,
            senderSubIdChars,
            senderSubIdChars.length,
            targetCompIdChars,
            targetCompIdChars.length);
    }

    public void setupSession(final CompositeKey compositeKey, final SessionHeaderEncoder headerEncoder)
    {
        final CompositeKeyImpl composite = (CompositeKeyImpl)compositeKey;
        headerEncoder.senderCompID(checkMissing(composite.localCompID));
        headerEncoder.senderSubID(checkMissing(composite.localSubID));
        headerEncoder.targetCompID(checkMissing(composite.remoteCompID));
    }

    public int save(final CompositeKey compositeKey, final MutableDirectBuffer buffer, final int offset)
    {
        final String localCompID = compositeKey.localCompId();
        final String localSubID = compositeKey.localSubId();
        final String remoteCompID = compositeKey.remoteCompId();

        final int length =
            localCompID.length() + localSubID.length() + remoteCompID.length() + BLOCK_AND_LENGTH_FIELDS_LENGTH;

        if (buffer.capacity() < offset + length)
        {
            return INSUFFICIENT_SPACE;
        }

        keyEncoder.wrap(buffer, offset);
        keyEncoder.localCompId(localCompID);
        keyEncoder.localSubId(localSubID);
        keyEncoder.remoteCompId(remoteCompID);

        return length;
    }

    public CompositeKey load(final DirectBuffer buffer, final int offset, final int length)
    {
        keyDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);

        final int localCompIdLength = keyDecoder.localCompIdLength();
        final byte[] localCompId = new byte[localCompIdLength];
        keyDecoder.getLocalCompId(localCompId, 0, localCompIdLength);

        final int localSubIdLength = keyDecoder.localSubIdLength();
        final byte[] localSubId = new byte[localSubIdLength];
        keyDecoder.getLocalSubId(localSubId, 0, localSubIdLength);

        final int remoteCompIdLength = keyDecoder.remoteCompIdLength();
        final byte[] remoteCompId = new byte[remoteCompIdLength];
        keyDecoder.getRemoteCompId(remoteCompId, 0, remoteCompIdLength);

        return new CompositeKeyImpl(localCompId, localSubId, remoteCompId);
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

        final boolean hasTargetSubID = header.hasTargetSubID();
        if (!(hasTargetSubID && CodecUtil.equals(header.targetSubID(), key.localSubID, header.targetSubIDLength())))
        {
            return TARGET_SUB_ID;
        }

        return 0;
    }

    private static final class CompositeKeyImpl implements CompositeKey
    {
        private final char[] localCompID;
        private final char[] localSubID;
        private final char[] remoteCompID;
        private final String localCompIDStr;
        private final String localSubIDStr;
        private final String remoteCompIDStr;
        private final int hashCode;

        private CompositeKeyImpl(
            final char[] localCompID,
            final int localCompIDLength,
            final char[] localSubID,
            final int localSubIDLength,
            final char[] remoteCompID,
            final int remoteCompIDLength)
        {
            this.localCompID = Arrays.copyOf(localCompID, localCompIDLength);
            this.remoteCompID = Arrays.copyOf(remoteCompID, remoteCompIDLength);
            this.localSubID = Arrays.copyOf(localSubID, localSubIDLength);
            this.localCompIDStr = new String(this.localCompID);
            this.localSubIDStr = new String(this.localSubID);
            this.remoteCompIDStr = new String(this.remoteCompID);
            hashCode = hash(this.localCompID, this.localSubID, this.remoteCompID);
        }

        private CompositeKeyImpl(
            final byte[] localCompID,
            final byte[] localSubID,
            final byte[] remoteCompID)
        {
            this.localCompID = CodecUtil.fromBytes(localCompID);
            this.localSubID = CodecUtil.fromBytes(localSubID);
            this.remoteCompID = CodecUtil.fromBytes(remoteCompID);
            this.localCompIDStr = new String(this.localCompID);
            this.localSubIDStr = new String(this.localSubID);
            this.remoteCompIDStr = new String(this.remoteCompID);
            hashCode = hash(this.localCompID, this.localSubID, this.remoteCompID);
        }

        private int hash(final char[] localCompID, final char[] localSubID, final char[] remoteCompID)
        {
            int result = Arrays.hashCode(localCompID);
            result = 31 * result + Arrays.hashCode(localSubID);
            result = 31 * result + Arrays.hashCode(remoteCompID);
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
                    Arrays.equals(compositeKey.localSubID, localSubID) &&
                    Arrays.equals(compositeKey.remoteCompID, remoteCompID);
            }

            return false;
        }

        public String toString()
        {
            return "CompositeKey{" +
                "localCompId=" + localCompId() +
                ", localSubId=" + localSubId() +
                ", remoteCompId=" + remoteCompId() +
                '}';
        }

        public String localCompId()
        {
            return localCompIDStr;
        }

        public String localSubId()
        {
            return localSubIDStr;
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
