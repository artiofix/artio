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
package uk.co.real_logic.artio.timing;

import org.HdrHistogram.Histogram;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardOpenOption.*;

class HistogramLogWriter implements HistogramHandler
{
    private static final int BUFFER_SIZE = 1024 * 1024;

    private final FileChannel logFile;
    private final ByteBuffer buffer;
    private final ErrorHandler errorHandler;

    HistogramLogWriter(final int numberOfTimers, final String logFile, final ErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
        buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        buffer.putInt(numberOfTimers);
        this.logFile = open(logFile);
    }

    @Override
    public void identifyTimer(final int id, final String name)
    {
        final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(id);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
    }

    @Override
    public void onEndTimerIdentification()
    {
        writeBuffer();
    }

    @Override
    public void onTimerUpdate(final int id, final Histogram histogram)
    {
        buffer.putInt(id);
        histogram.encodeIntoByteBuffer(buffer);
    }

    @Override
    public void onBeginTimerUpdate(final long currentTimeInMs)
    {
        buffer.clear();
        buffer.putLong(currentTimeInMs);
    }

    @Override
    public void onEndTimerUpdate()
    {
        writeBuffer();
    }

    private FileChannel open(final String logFile)
    {
        try
        {
            final Path path = Paths.get(logFile);
            final Path parent = path.getParent();
            if (!Files.exists(parent))
            {
                Files.createDirectories(parent);
            }
            return FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING);
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
            return null;
        }
    }

    private void writeBuffer()
    {
        try
        {
            buffer.flip();
            logFile.write(buffer);
            logFile.force(true);
        }
        catch (final IOException ex)
        {
            errorHandler.onError(ex);
        }
    }

    @Override
    public void close()
    {
        try
        {
            logFile.force(true);
        }
        catch (final IOException ex)
        {
            errorHandler.onError(ex);
        }

        CloseHelper.close(logFile);
    }
}
