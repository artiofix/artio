/*
 * Copyright 2015-2016 Real Logic Limited.
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

import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberDecoder;

import java.io.File;

import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static uk.co.real_logic.artio.engine.SectorFramer.SECTOR_SIZE;
import static uk.co.real_logic.artio.engine.SectorFramer.nextSectorStart;

/**
 * Stores a cache of the last sent sequence number.
 * <p>
 * Each instance is not thread-safe, however, they can share a common
 * off-heap in a single-writer threadsafe manner.
 * <p>
 * Message Header
 * Series of LastKnownSequenceNumber records
 * ...
 * Positions Table
 */
final class SequenceNumberIndexDescriptor
{
    static final int HEADER_SIZE = MessageHeaderDecoder.ENCODED_LENGTH;
    static final int RECORD_SIZE = LastKnownSequenceNumberDecoder.BLOCK_LENGTH;

    static final int NO_META_DATA = -1;
    static final long META_DATA_MAGIC_NUMBER = 0xBEEF;
    static final int META_DATA_FILE_VERSION = 1;
    static final int READABLE_META_DATA_FILE_VERSION = META_DATA_FILE_VERSION;
    static final int META_DATA_FILE_HEADER_LENGTH = SIZE_OF_LONG + SIZE_OF_INT;
    static final int SIZE_OF_META_DATA_LENGTH = SIZE_OF_INT;

    static final double SEQUENCE_NUMBER_RATIO = 0.9;

    public static File metaDataFile(final String logFileDir)
    {
        return new File(logFileDir + "/metadata");
    }

    static AtomicBuffer positionsBuffer(final AtomicBuffer buffer, final int positionsOffset)
    {
        return new UnsafeBuffer(buffer, positionsOffset, buffer.capacity() - positionsOffset);
    }

    /**
     * Calculated an offset in the sequence number index for storing positions.
     * This is the sector aligned location, closest to SEQUENCE_NUMBER_RATIO * fileCapacity.
     *
     * @param fileCapacity the capacity of the overall table
     * @return an offset in the sequence number index for storing positions.
     */
    static int positionTableOffset(final int fileCapacity)
    {
        final int proposedCapacity = nextSectorStart((int)(fileCapacity * SEQUENCE_NUMBER_RATIO));
        if (proposedCapacity == fileCapacity)
        {
            return fileCapacity - SECTOR_SIZE;
        }
        return proposedCapacity;
    }

    public static File passingFile(final String indexFilePath)
    {
        return new File(indexFilePath + "-passing");
    }

    public static File writableFile(final String indexFilePath)
    {
        return new File(indexFilePath + "-writable");
    }
}
