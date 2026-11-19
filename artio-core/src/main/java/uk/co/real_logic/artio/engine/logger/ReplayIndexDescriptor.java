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
package uk.co.real_logic.artio.engine.logger;

import org.agrona.BitUtil;
import org.agrona.IoUtil;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.ReplayIndexRecordDecoder;

import java.io.File;
import java.util.Objects;

public final class ReplayIndexDescriptor
{
    private static final int BEGIN_CHANGE_OFFSET = MessageHeaderEncoder.ENCODED_LENGTH;
    private static final int END_CHANGE_OFFSET = BEGIN_CHANGE_OFFSET + BitUtil.SIZE_OF_LONG;
    private static final int FOR_NEXT_SESSION_VERSION_OFFSET = END_CHANGE_OFFSET + BitUtil.SIZE_OF_LONG;
    public static final int HEADER_FILE_SIZE = FOR_NEXT_SESSION_VERSION_OFFSET + BitUtil.SIZE_OF_BYTE;

    public static final byte FOR_NEXT_SESSION_VERSION = 1;
    public static final byte NOT_FOR_NEXT_SESSION_VERSION = 0;

    public static final int RECORD_LENGTH = 32;
    static
    {
        // Safety check against making the ReplayIndexRecord big without modifying this
        // codeql[java/constant-comparison]
        if (RECORD_LENGTH < ReplayIndexRecordDecoder.BLOCK_LENGTH)
        {
            throw new IllegalStateException("Invalid record length");
        }
    }

    public static final int MAX_FILE_SEGMENT_CAPACITY = Integer.MAX_VALUE / RECORD_LENGTH;

    static File replayIndexHeaderFile(final String logFileDir, final long fixSessionId, final int streamId)
    {
        return new File(logFileDir + File.separator + "replay-index-" + fixSessionId + "-" + streamId + "-header");
    }

    static File replayIndexSegmentFile(
        final String logFileDir, final long fixSessionId, final int streamId, final int segmentIndex)
    {
        return new File(
            logFileDir + File.separator + "replay-index-" + fixSessionId + "-" + streamId + "-" + segmentIndex);
    }

    static LongHashSet listReplayIndexSessionIds(final File logFileDir, final int streamId)
    {
        final String prefix = "replay-index-";
        final String suffix = "-" + streamId + "-header";
        final LongHashSet sessionIds = new LongHashSet();
        for (final File file : Objects.requireNonNull(logFileDir.listFiles()))
        {
            final String fileName = file.getName();
            if (fileName.startsWith(prefix))
            {
                if (fileName.endsWith(suffix))
                {
                    final int suffixIndex = fileName.length() - suffix.length();
                    final String sessionIdString = fileName.substring(prefix.length(), suffixIndex);
                    final long sessionId = Long.parseLong(sessionIdString);
                    sessionIds.add(sessionId);
                }
            }
        }
        return sessionIds;
    }

    static void unmapBuffers(final UnsafeBuffer headerBuffer, final UnsafeBuffer[] segmentBuffers)
    {
        IoUtil.unmap(headerBuffer.byteBuffer());
        for (final UnsafeBuffer segmentBuffer : segmentBuffers)
        {
            if (segmentBuffer != null)
            {
                IoUtil.unmap(segmentBuffer.byteBuffer());
            }
        }
    }

    public static UnsafeBuffer replayPositionBuffer(final String logFileDir, final int streamId, final int bufferSize)
    {
        final String pathname = replayPositionPath(logFileDir, streamId);
        return new UnsafeBuffer(LoggerUtil.map(new File(pathname), bufferSize));
    }

    static String replayPositionPath(final String logFileDir, final int streamId)
    {
        return logFileDir + File.separator + "replay-positions-" + streamId;
    }

    static void endChangeOrdered(final AtomicBuffer buffer, final long changePosition)
    {
        buffer.putLongOrdered(END_CHANGE_OFFSET, changePosition);
    }

    static long endChangeVolatile(final AtomicBuffer buffer)
    {
        return buffer.getLongVolatile(END_CHANGE_OFFSET);
    }

    static void beginChangeOrdered(final AtomicBuffer buffer, final long changePosition)
    {
        buffer.putLongOrdered(BEGIN_CHANGE_OFFSET, changePosition);
    }

    static long beginChangeVolatile(final AtomicBuffer buffer)
    {
        return buffer.getLongVolatile(BEGIN_CHANGE_OFFSET);
    }

    static long beginChange(final AtomicBuffer buffer)
    {
        return buffer.getLong(BEGIN_CHANGE_OFFSET);
    }

    static boolean forNextSessionVersion(final AtomicBuffer buffer)
    {
        return buffer.getByte(FOR_NEXT_SESSION_VERSION_OFFSET) == FOR_NEXT_SESSION_VERSION;
    }

    static void forNextSessionVersion(final AtomicBuffer buffer, final boolean value)
    {
        final byte byteValue = value ? FOR_NEXT_SESSION_VERSION : NOT_FOR_NEXT_SESSION_VERSION;
        buffer.putByte(FOR_NEXT_SESSION_VERSION_OFFSET, byteValue);
    }

    static int offsetInSegment(final long changePosition, final long capacity)
    {
        // changePosition % capacity = changePosition & (capacity - 1)
        return (int)(changePosition & (capacity - 1));
    }

    public static int segmentIndex(final long position, final int segmentSizeBitShift, final long indexFileSize)
    {
        // position % indexFileSize
        final long offsetWithinRing = position & (indexFileSize - 1);

        // floor(offsetWithinRing / segmentSize)
        return (int)(offsetWithinRing >> segmentSizeBitShift);
    }

    static void checkIndexRecordCapacity(final int recordCapacity)
    {
        if (!BitUtil.isPowerOfTwo(recordCapacity))
        {
            throw new IllegalStateException(
                "IndexFileSize must be a positive power of 2: recordCapacity=" + recordCapacity);
        }
    }

    public static long capacityToBytes(final int indexSegmentCapacity)
    {
        return (long)indexSegmentCapacity * (long)RECORD_LENGTH;
    }

    public static int capacityToBytesInt(final int indexSegmentCapacity)
    {
        return indexSegmentCapacity * RECORD_LENGTH;
    }

    public static int segmentCount(final int indexFileCapacity, final int indexSegmentCapacity)
    {
        return indexFileCapacity / indexSegmentCapacity;
    }
}
