/*
 * Copyright 2015-2016 Real Logic Limited., Monotonic Ltd.
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

import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.artio.engine.SectorFramer;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MetaDataStatus;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberDecoder;
import uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import static io.aeron.archive.status.RecordingPos.NULL_RECORDING_ID;
import static uk.co.real_logic.artio.engine.ConnectedSessionInfo.UNK_SESSION;
import static uk.co.real_logic.artio.engine.SectorFramer.OUT_OF_SPACE;
import static uk.co.real_logic.artio.engine.logger.IndexedPositionReader.UNKNOWN_POSITION;
import static uk.co.real_logic.artio.engine.logger.SequenceNumberIndexDescriptor.*;
import static uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder.BLOCK_LENGTH;
import static uk.co.real_logic.artio.storage.messages.LastKnownSequenceNumberEncoder.SCHEMA_VERSION;

/**
 * Designed to used on a single thread
 */
public class SequenceNumberIndexReader implements AutoCloseable
{
    private final MessageHeaderDecoder fileHeaderDecoder = new MessageHeaderDecoder();
    private final LastKnownSequenceNumberDecoder lastKnownDecoder = new LastKnownSequenceNumberDecoder();
    private final AtomicBuffer inMemoryBuffer;
    private final SectorFramer sectorFramer;
    private final IndexedPositionReader positions;
    private final ErrorHandler errorHandler;
    private final RecordingIdLookup recordingIdLookup;
    private final RandomAccessFile metaDataFile;

    public SequenceNumberIndexReader(
        final AtomicBuffer inMemoryBuffer,
        final ErrorHandler errorHandler,
        final RecordingIdLookup recordingIdLookup,
        final String metaDataDir)
    {
        this.inMemoryBuffer = inMemoryBuffer;
        this.errorHandler = errorHandler;
        this.recordingIdLookup = recordingIdLookup;
        final int positionTableOffset = positionTableOffset(inMemoryBuffer.capacity());
        sectorFramer = new SectorFramer(positionTableOffset);
        validateBuffer();
        positions = new IndexedPositionReader(positionsBuffer(inMemoryBuffer, positionTableOffset));
        metaDataFile = openMetaDataFile(metaDataDir);
    }

    private RandomAccessFile openMetaDataFile(final String metaDataDir)
    {
        if (metaDataDir != null)
        {
            final File metaDataFile = metaDataFile(metaDataDir);

            try
            {
                return new RandomAccessFile(metaDataFile, "r");
            }
            catch (final FileNotFoundException e)
            {
                LangUtil.rethrowUnchecked(e);
            }
        }
        return null;
    }

    public int lastKnownSequenceNumber(final long sessionId)
    {
        int position = SequenceNumberIndexDescriptor.HEADER_SIZE;
        while (true)
        {
            position = sectorFramer.claim(position, RECORD_SIZE);
            if (position == OUT_OF_SPACE)
            {
                return UNK_SESSION;
            }

            lastKnownDecoder.wrap(inMemoryBuffer, position, BLOCK_LENGTH, SCHEMA_VERSION);

            if (lastKnownDecoder.sessionId() == sessionId)
            {
                final int sequenceNumber = lastKnownDecoder.sequenceNumber();
                return sequenceNumber;
            }

            position += RECORD_SIZE;
        }
    }

    public long indexedPosition(final int aeronSessionId)
    {
        if (recordingIdLookup == null)
        {
            return UNKNOWN_POSITION;
        }

        final long recordingId = recordingIdLookup.findRecordingId(aeronSessionId);
        if (recordingId == NULL_RECORDING_ID)
        {
            return UNKNOWN_POSITION;
        }
        return positions.indexedPosition(recordingId);
    }

    private void validateBuffer()
    {
        LoggerUtil.validateBuffer(
            inMemoryBuffer,
            fileHeaderDecoder,
            LastKnownSequenceNumberEncoder.SCHEMA_ID,
            errorHandler);
    }

    public MetaDataStatus readMetaData(final long sessionId, final DirectBuffer buffer)
    {
        if (metaDataFile == null)
        {
            return MetaDataStatus.FILE_ERROR;
        }

        if (lastKnownSequenceNumber(sessionId) == UNK_SESSION)
        {
            return MetaDataStatus.UNKNOWN_SESSION;
        }

        final int metaDataPosition = lastKnownDecoder.metaDataPosition();
        if (metaDataPosition == NO_META_DATA)
        {
            return MetaDataStatus.NO_META_DATA;
        }

        try
        {
            metaDataFile.seek(metaDataPosition);
            final int metaDataLength = metaDataFile.readInt();

            final byte[] metaDataValue = new byte[metaDataLength];
            metaDataFile.read(metaDataValue);

            buffer.wrap(metaDataValue);

            return MetaDataStatus.OK;
        }
        catch (final IOException e)
        {
            errorHandler.onError(e);
            return MetaDataStatus.FILE_ERROR;
        }
    }

    public void close()
    {
        CloseHelper.close(metaDataFile);
    }

}
