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
import java.nio.channels.SocketChannel;

import static uk.co.real_logic.artio.benchmarks.NetworkBenchmarkUtil.*;

public final class NioBufferPingPong extends AbstractPingPong
{
    private final ByteBuffer pingWriteBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);
    private final ByteBuffer pingReadBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    private final ByteBuffer pongWriteBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);
    private final ByteBuffer pongReadBuffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    public static void main(final String[] args) throws IOException
    {
        new NioBufferPingPong().benchmark();
    }

    @Override
    protected void ping(final SocketChannel channel, final long time) throws IOException
    {
        writeByteBuffer(channel, pingWriteBuffer, time);

        final long result = readByteBuffer(channel, pingReadBuffer);

        checkEqual(time, result);
    }

    @Override
    protected void pong(final SocketChannel channel) throws IOException
    {
        final long value = readByteBuffer(channel, pongReadBuffer);

        writeByteBuffer(channel, pongWriteBuffer, value);
    }
}
