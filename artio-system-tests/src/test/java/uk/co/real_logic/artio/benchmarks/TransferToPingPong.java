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
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

import static uk.co.real_logic.artio.benchmarks.NetworkBenchmarkUtil.readChannel;
import static uk.co.real_logic.artio.benchmarks.NetworkBenchmarkUtil.writeChannel;

public final class TransferToPingPong extends AbstractPingPong
{
    private static final FileChannel PING_BUFFER = NetworkBenchmarkUtil.newFile("ping");

    private static final FileChannel PONG_BUFFER = NetworkBenchmarkUtil.newFile("pong");

    public static void main(final String[] args) throws IOException
    {
        new TransferToPingPong().benchmark();
    }

    @Override
    protected void ping(final SocketChannel channel, final long time) throws IOException
    {
        writeChannel(channel, PING_BUFFER, null, time);

        readChannel(channel, PING_BUFFER);
    }

    @Override
    protected void pong(final SocketChannel channel) throws IOException
    {
        final long time = readChannel(channel, PONG_BUFFER);

        writeChannel(channel, PONG_BUFFER, null, time);
    }
}
