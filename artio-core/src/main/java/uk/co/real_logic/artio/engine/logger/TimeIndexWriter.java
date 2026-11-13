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

import org.agrona.ErrorHandler;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.storage.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.TimeIndexRecordEncoder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

class TimeIndexWriter implements AutoCloseable
{
    static final String FILE_NAME = "time-index-";

    static File fileLocation(final String logFileDir, final int streamid, final long recordingId)
    {
        return new File(logFileDir + File.separator + FILE_NAME + streamid + "-" + recordingId);
    }

    private static final int BUFFER_SIZE = Math.max(
        MessageHeaderEncoder.ENCODED_LENGTH,
        TimeIndexRecordEncoder.BLOCK_LENGTH);

    private final TimeIndexRecordEncoder recordEncoder = new TimeIndexRecordEncoder();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[BUFFER_SIZE]);

    private final Long2ObjectHashMap<RecordingWriter> recordingIdToWriter = new Long2ObjectHashMap<>();
    private final String logFileDir;
    private final int streamId;
    private final long indexFlushIntervalInNs;
    private final ErrorHandler errorHandler;

    TimeIndexWriter(
        final String logFileDir,
        final int streamId,
        final long indexFlushIntervalInNs,
        final ErrorHandler errorHandler)
    {
        this.logFileDir = logFileDir;
        this.streamId = streamId;
        this.indexFlushIntervalInNs = indexFlushIntervalInNs;
        this.errorHandler = errorHandler;
    }

    public void onRecord(final long recordingId, final long endPosition, final long timestamp)
    {
        RecordingWriter writer = recordingIdToWriter.get(recordingId);
        if (writer == null)
        {
            writer = new RecordingWriter(recordingId);
            recordingIdToWriter.put(recordingId, writer);
        }
        writer.onRecord(endPosition, timestamp);
    }

    public int doWork()
    {
        int work = 0;
        for (final RecordingWriter recordingWriter : recordingIdToWriter.values())
        {
            work += recordingWriter.doWork();
        }
        return work;
    }

    @Override
    public void close()
    {
        recordingIdToWriter.values().forEach(RecordingWriter::close);
        recordingIdToWriter.clear();
    }

    class RecordingWriter
    {
        private final RandomAccessFile file;
        private final TimeIndexRecordEncoder recordEncoder;
        private final UnsafeBuffer buffer;

        private long nextFlushInNs = 0;
        private long endPosition;
        private long timestampInNs;

        RecordingWriter(final long recordingId)
        {
            recordEncoder = TimeIndexWriter.this.recordEncoder;
            buffer = TimeIndexWriter.this.buffer;

            final File file = fileLocation(logFileDir, streamId, recordingId);
            if (file.exists())
            {
                this.file = loadFile(file);
            }
            else
            {
                this.file = createFile(file);
            }
            recordEncoder.wrap(buffer, 0);
        }

        private RandomAccessFile loadFile(final File file)
        {
            try
            {
                final RandomAccessFile raf = new RandomAccessFile(file, "rwd");
                raf.seek(raf.length());
                return raf;
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
                return null;
            }
        }

        private RandomAccessFile createFile(final File file)
        {
            try
            {
                final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
                recordEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

                final RandomAccessFile raf = new RandomAccessFile(file, "rwd");
                raf.write(buffer.byteArray(), 0, MessageHeaderEncoder.ENCODED_LENGTH);
                return raf;
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
                return null;
            }
        }

        public void onRecord(final long endPosition, final long timestampInNs)
        {
            this.endPosition = endPosition;
            this.timestampInNs = timestampInNs;
        }

        int doWork()
        {
            final long endPosition = this.endPosition;
            final long timestampInNs = this.timestampInNs;

            if (endPosition != 0 && timestampInNs > nextFlushInNs)
            {
                update(endPosition, timestampInNs);
                nextFlushInNs = timestampInNs + indexFlushIntervalInNs;
                return 1;
            }
            return 0;
        }

        private void update(final long endPosition, final long timestampInNs)
        {
            try
            {
                final RandomAccessFile file = this.file;
                if (file != null)
                {
                    recordEncoder
                        .position(endPosition)
                        .timestamp(timestampInNs);
                    file.write(buffer.byteArray(), 0, TimeIndexRecordEncoder.BLOCK_LENGTH);
                }
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
        }

        void close()
        {
            update(endPosition, timestampInNs);
            try
            {
                file.getFD().sync();
            }
            catch (final IOException e)
            {
                errorHandler.onError(e);
            }
            Exceptions.closeAll(file);
        }
    }
}
