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

public final class HybridPingPong extends AbstractPingPong
{
    private final FileChannel pingWriteBuffer = NetworkBenchmarkUtil.newFile("ping");
    private final MappedByteBuffer mappedPingWriteBuffer;
    private final ByteBuffer pingReadBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    private final FileChannel pongWriteBuffer = NetworkBenchmarkUtil.newFile("pong");
    private final MappedByteBuffer mappedPongWriteBuffer;
    private final ByteBuffer pongReadBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    public static void main(final String[] args) throws IOException
    {
        new HybridPingPong().benchmark();
    }

    public HybridPingPong() throws IOException
    {
        mappedPingWriteBuffer = pingWriteBuffer.map(READ_WRITE, 0, MESSAGE_SIZE);
        mappedPongWriteBuffer = pongWriteBuffer.map(READ_WRITE, 0, MESSAGE_SIZE);
    }

    @Override
    protected void ping(final SocketChannel channel, final long time) throws IOException
    {
        writeChannel(channel, pingWriteBuffer, mappedPingWriteBuffer, time);

        final long result = readByteBuffer(channel, pingReadBuffer);

        checkEqual(time, result);
    }

    @Override
    protected void pong(final SocketChannel channel) throws IOException
    {
        final long value = readByteBuffer(channel, pongReadBuffer);

        writeChannel(channel, pongWriteBuffer, mappedPongWriteBuffer, value);
    }
}
