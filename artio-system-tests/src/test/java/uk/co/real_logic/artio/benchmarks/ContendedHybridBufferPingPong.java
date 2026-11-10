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
package uk.co.real_logic.artio.benchmarks;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static uk.co.real_logic.artio.benchmarks.NetworkBenchmarkUtil.*;

public final class ContendedHybridBufferPingPong extends AbstractContendedPingPong
{
    private final FileChannel writeChannel = newFile("ping");
    private final MappedByteBuffer mappedWriteBuffer;

    private final ByteBuffer readResponseBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    public static void main(final String[] args) throws Exception
    {
        new ContendedHybridBufferPingPong().benchmark();
    }

    public ContendedHybridBufferPingPong() throws IOException
    {
        mappedWriteBuffer = writeChannel.map(READ_WRITE, 0, MESSAGE_SIZE);
    }

    @Override
    protected void sendPing(final SocketChannel channel, final long time) throws IOException
    {
        writeChannel(channel, writeChannel, mappedWriteBuffer, time);
    }

    @Override
    protected long readResponse(final SocketChannel channel) throws IOException
    {
        return readByteBuffer(channel, readResponseBuffer);
    }
}
