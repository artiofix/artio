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
package uk.co.real_logic.artio;

import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.engine.logger.LoggerUtil;

import java.io.File;
import java.nio.MappedByteBuffer;

import static uk.co.real_logic.artio.AtomicBuffers.checkAligned;

/**
 * A memory mapped file that stores the error buffer.
 */
public final class MonitoringFile implements AutoCloseable
{
    private final MappedByteBuffer mappedByteBuffer;
    private final AtomicBuffer errorBuffer;
    private final String absolutePath;

    MonitoringFile(final boolean newFile, final CommonConfiguration configuration)
    {
        final File file = new File(configuration.monitoringFile()).getAbsoluteFile();
        absolutePath = file.getAbsolutePath();
        CloseChecker.validate(absolutePath);
        CloseChecker.onOpen(absolutePath, this);
        final int length;
        if (newFile)
        {
            IoUtil.deleteIfExists(file);

            length = configuration.monitoringBuffersLength();
            mappedByteBuffer = LoggerUtil.mapNewFile(file, length);
        }
        else
        {
            if (!file.exists() || !file.canRead() || !file.isFile())
            {
                throw new IllegalStateException("Unable to read from file: " + file);
            }

            mappedByteBuffer = IoUtil.mapExistingFile(file, "counters file");
            length = mappedByteBuffer.capacity();
        }

        final AtomicBuffer mappedFile = new UnsafeBuffer(mappedByteBuffer);
        errorBuffer = checkAligned(new UnsafeBuffer(mappedFile, 0, length));
    }

    public AtomicBuffer errorBuffer()
    {
        return errorBuffer;
    }

    @Override
    public void close()
    {
        IoUtil.unmap(mappedByteBuffer);
        CloseChecker.onClose(absolutePath, this);
    }
}
