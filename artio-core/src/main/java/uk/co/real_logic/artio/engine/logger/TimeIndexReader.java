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
package uk.co.real_logic.artio.engine.logger;

import org.agrona.IoUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.storage.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.storage.messages.TimeIndexRecordDecoder;

import java.io.File;
import java.nio.MappedByteBuffer;

import static uk.co.real_logic.artio.engine.logger.TimeIndexWriter.fileNamePrefix;

class TimeIndexReader
{
    private static final int SCAN_START = 0;
    private static final int SCAN_END = 1;

    private final String logFileDir;
    private final int streamid;

    TimeIndexReader(final String logFileDir, final int streamid)
    {
        this.logFileDir = logFileDir;
        this.streamid = streamid;
    }

    boolean findPositionRange(
        final IndexQuery indexQuery, final Long2ObjectHashMap<PositionRange> recordingIdToPositionRange)
    {
        final File logDir = new File(logFileDir);
        final String fileNamePrefix = fileNamePrefix(streamid);
        if (!logDir.exists() || !logDir.isDirectory())
        {
            return false;
        }

        for (final String file : logDir.list())
        {
            if (file.startsWith(fileNamePrefix))
            {
                final long recordingId;
                try
                {
                    recordingId = Long.parseLong(file.substring(fileNamePrefix.length()));
                }
                catch (final NumberFormatException ignore)
                {
                    // Not a file Artio wrote, such as a renamed copy, so it isn't a recording's index.
                    continue;
                }

                recordingIdToPositionRange.put(recordingId, findPositionRange(indexQuery, new File(logDir, file)));
            }
        }

        return true;
    }

    private PositionRange findPositionRange(final IndexQuery indexQuery, final File file)
    {
        final long beginTimestampInclusive = indexQuery.beginTimestampInclusive();
        final long endTimestampExclusive = indexQuery.endTimestampExclusive();

        final MappedByteBuffer mappedByteBuffer = LoggerUtil.mapExistingFile(file);
        try
        {
            final UnsafeBuffer buffer = new UnsafeBuffer(mappedByteBuffer);
            final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
            final TimeIndexRecordDecoder timeIndexRecord = new TimeIndexRecordDecoder();
            headerDecoder.wrap(buffer, 0);

            final int blockLength = headerDecoder.blockLength();
            final int version = headerDecoder.version();

            int offset = MessageHeaderDecoder.ENCODED_LENGTH;
            final int capacity = buffer.capacity();

            long startPosition = 0;
            long endPosition = Long.MAX_VALUE;

            int state = beginTimestampInclusive == IndexQuery.NO_BEGIN ? SCAN_END : SCAN_START;

            loop: while ((offset + TimeIndexRecordDecoder.BLOCK_LENGTH) <= capacity)
            {
                timeIndexRecord.wrap(buffer, offset, blockLength, version);

                final long timestampInNs = timeIndexRecord.timestamp();

                // timeIndexRecord.position() is the endPosition of the record

                switch (state)
                {
                    case SCAN_START:
                    {
                        if (timestampInNs >= beginTimestampInclusive)
                        {
                            state = SCAN_END;
                            // Deliberate fall through
                        }
                        else
                        {
                            startPosition = timeIndexRecord.position();
                            break;
                        }
                    }

                    /* fall-thru */
                    case SCAN_END:
                    default:
                    {
                        if (timestampInNs >= endTimestampExclusive)
                        {
                            endPosition = timeIndexRecord.position();
                            break loop;
                        }

                        break;
                    }
                }

                offset += TimeIndexRecordDecoder.BLOCK_LENGTH;
            }

            return new PositionRange(startPosition, endPosition);
        }
        finally
        {
            IoUtil.unmap(mappedByteBuffer);
        }
    }
}
