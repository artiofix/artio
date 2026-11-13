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
package uk.co.real_logic.artio.engine;

import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.LangUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.CloseChecker;
import uk.co.real_logic.artio.CommonConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static java.nio.file.StandardOpenOption.WRITE;
import static uk.co.real_logic.artio.AtomicBuffers.checkAligned;

public class MappedFile implements AutoCloseable
{
    private final File file;
    private FileChannel fileChannel;
    private AtomicBuffer buffer;

    public static MappedFile map(final File bufferFile, final int size)
    {
        final FileChannel fileChannel;
        try
        {
            if (bufferFile.exists())
            {
                // NB: closing RAF or FileChannel closes them both
                fileChannel = new RandomAccessFile(bufferFile, "rw").getChannel();
            }
            else
            {
                fileChannel = IoUtil.createEmptyFile(bufferFile, size);
            }

            final MappedByteBuffer mappedBuffer = fileChannel.map(READ_WRITE, 0, fileChannel.size());
            return new MappedFile(bufferFile, fileChannel, new UnsafeBuffer(mappedBuffer));
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
            return null;
        }
    }

    public static MappedFile map(final String bufferPath, final int size)
    {
        return map(new File(bufferPath), size);
    }

    public MappedFile(final File file, final FileChannel fileChannel, final AtomicBuffer buffer)
    {
        CloseChecker.onOpen(file.getAbsolutePath(), this);
        this.file = file;
        this.fileChannel = fileChannel;
        this.buffer = checkAligned(buffer);
    }

    public AtomicBuffer buffer()
    {
        return buffer;
    }

    public File file()
    {
        return file;
    }

    public void force()
    {
        force(fileChannel);
    }

    public void transferTo(final File backupLocation)
    {
        try (FileChannel backupChannel = FileChannel.open(backupLocation.toPath(), WRITE))
        {
            fileChannel.transferTo(0L, fileChannel.size(), backupChannel);
            force(backupChannel);
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public void remap()
    {
        close();
        map();
    }

    public void map()
    {
        final MappedFile remappedFile = map(file, buffer.capacity());
        this.fileChannel = remappedFile.fileChannel;
        this.buffer = remappedFile.buffer;
    }

    @Override
    public void close()
    {
        CloseChecker.onClose(file.getAbsolutePath(), this);
        IoUtil.unmap(buffer.byteBuffer());
        CloseHelper.close(fileChannel);
    }

    public boolean isOpen()
    {
        return fileChannel.isOpen();
    }

    private void force(final FileChannel fileChannel)
    {
        if (CommonConfiguration.FORCE_WRITES)
        {
            try
            {
                fileChannel.force(true);
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
        }
    }

    @Override
    public String toString()
    {
        return "MappedFile{" +
            "file=" + file +
            '}';
    }
}
