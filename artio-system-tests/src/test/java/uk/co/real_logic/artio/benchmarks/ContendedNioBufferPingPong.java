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

// TODO: alter the send/receive buffer sizes
// TODO: look at aeron buffer sizes
public final class ContendedNioBufferPingPong extends AbstractContendedPingPong
{
    private static final ByteBuffer SEND_PING_BUFFER = ByteBuffer.allocateDirect(MESSAGE_SIZE);
    private static final ByteBuffer READ_RESPONSE_BUFFER = ByteBuffer.allocateDirect(MESSAGE_SIZE);

    public static void main(final String[] args) throws Exception
    {
        new ContendedNioBufferPingPong().benchmark();
    }

    @Override
    protected void sendPing(final SocketChannel channel, final long time) throws IOException
    {
        writeByteBuffer(channel, SEND_PING_BUFFER, time);
    }

    @Override
    protected long readResponse(final SocketChannel channel) throws IOException
    {
        return readByteBuffer(channel, READ_RESPONSE_BUFFER);
    }
}
