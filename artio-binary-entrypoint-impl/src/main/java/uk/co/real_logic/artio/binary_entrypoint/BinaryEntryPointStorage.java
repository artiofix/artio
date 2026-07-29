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

import org.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.artio.fixp.AbstractFixPStorage;
import uk.co.real_logic.artio.fixp.FixPKey;
import uk.co.real_logic.artio.fixp.InternalFixPContext;

import java.nio.ByteOrder;

import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.BitUtil.SIZE_OF_SHORT;

public class BinaryEntryPointStorage extends AbstractFixPStorage
{
    private static final short SHORT_TRUE = (short)1;
    private static final short SHORT_FALSE = (short)0;

    private static final int SESSION_ID_OFFSET = 0;
    private static final int SESSION_ID_LENGTH = SIZE_OF_LONG;
    private static final int SESSION_VER_ID_OFFSET = SESSION_ID_OFFSET + SESSION_ID_LENGTH;
    private static final int SESSION_VER_ID_LENGTH = SIZE_OF_LONG;
    private static final int TIMESTAMP_OFFSET = SESSION_VER_ID_OFFSET + SESSION_VER_ID_LENGTH;
    private static final int TIMESTAMP_LENGTH = SIZE_OF_LONG;
    private static final int ENTERING_FIRM_OFFSET = TIMESTAMP_OFFSET + TIMESTAMP_LENGTH;
    private static final int ENTERING_FIRM_LENGTH = SIZE_OF_LONG;
    private static final int ENDED_OFFSET = ENTERING_FIRM_OFFSET + ENTERING_FIRM_LENGTH;
    private static final int ENDED_LENGTH = SIZE_OF_SHORT;
    private static final int FROM_NEGOTIATE_OFFSET = ENDED_OFFSET + ENDED_LENGTH;
    private static final int FROM_NEGOTIATE_LENGTH = SIZE_OF_SHORT;
    private static final int ENTRY_LENGTH = FROM_NEGOTIATE_OFFSET + FROM_NEGOTIATE_LENGTH;

    public BinaryEntryPointStorage()
    {
    }

    public BinaryEntryPointContext newInitiatorContext(
        final FixPKey key, final int offset)
    {
        throw new UnsupportedOperationException();
    }

    public BinaryEntryPointContext loadContext(
        final AtomicBuffer buffer, final int offset, final int fileVersion)
    {
        final long sessionId = buffer.getLong(offset + SESSION_ID_OFFSET);
        final long sessionVerId = buffer.getLong(offset + SESSION_VER_ID_OFFSET);
        final long timestamp = buffer.getLong(offset + TIMESTAMP_OFFSET);
        final long enteringFirm = buffer.getLong(offset + ENTERING_FIRM_OFFSET);
        final boolean ended = buffer.getShort(offset + ENDED_OFFSET) == SHORT_TRUE;
        final boolean fromNegotiate = buffer.getShort(offset + FROM_NEGOTIATE_OFFSET) == SHORT_TRUE;

        final BinaryEntryPointContext context = new BinaryEntryPointContext(
            sessionId, sessionVerId, timestamp, enteringFirm, fromNegotiate, "", "", "", "");
        context.ended(ended);
        context.offset(offset);
        return context;
    }

    public int saveContext(
        final InternalFixPContext fixPContext, final AtomicBuffer buffer, final int offset, final int fileVersion)
    {
        final BinaryEntryPointContext context = (BinaryEntryPointContext)fixPContext;
        context.offset(offset);

        buffer.putLong(offset + SESSION_ID_OFFSET, context.sessionID(), ByteOrder.LITTLE_ENDIAN);
        putSessionVerId(buffer, context, offset);
        putTimestamp(buffer, context, offset);
        buffer.putLong(offset + ENTERING_FIRM_OFFSET, context.enteringFirm(), ByteOrder.LITTLE_ENDIAN);
        putEnded(buffer, context, offset + ENDED_OFFSET);
        putShort(buffer, offset + FROM_NEGOTIATE_OFFSET, context.fromNegotiate());

        return ENTRY_LENGTH;
    }

    public void updateContext(
        final InternalFixPContext fixPContext, final AtomicBuffer buffer)
    {
        final BinaryEntryPointContext context = (BinaryEntryPointContext)fixPContext;
        final int offset = context.offset();

        putSessionVerId(buffer, context, offset);
        putTimestamp(buffer, context, offset);
        putEnded(buffer, context, offset + ENDED_OFFSET);
    }

    private void putEnded(final AtomicBuffer buffer, final BinaryEntryPointContext context, final int offset)
    {
        putShort(buffer, offset, context.ended());
    }

    private void putShort(final AtomicBuffer buffer, final int offset, final boolean value)
    {
        buffer.putShort(offset, value ? SHORT_TRUE : SHORT_FALSE, ByteOrder.LITTLE_ENDIAN);
    }

    private void putTimestamp(final AtomicBuffer buffer, final BinaryEntryPointContext context, final int offset)
    {
        buffer.putLong(offset + TIMESTAMP_OFFSET, context.requestTimestampInNs(), ByteOrder.LITTLE_ENDIAN);
    }

    private void putSessionVerId(final AtomicBuffer buffer, final BinaryEntryPointContext context, final int offset)
    {
        buffer.putLong(offset + SESSION_VER_ID_OFFSET, context.sessionVerID(), ByteOrder.LITTLE_ENDIAN);
    }
}
