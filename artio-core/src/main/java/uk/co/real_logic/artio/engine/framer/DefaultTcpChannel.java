/*
 * Copyright 2015-2025 Real Logic Limited., Monotonic Ltd.
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
package uk.co.real_logic.artio.engine.framer;

import org.agrona.LangUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class DefaultTcpChannel extends TcpChannel
{
    private final SocketChannel socketChannel;

    public DefaultTcpChannel(final SocketChannel socketChannel) throws IOException
    {
        super(socketChannel.getRemoteAddress().toString());
        this.socketChannel = socketChannel;
    }

    @Override
    public SelectionKey register(final Selector sel, final int ops, final Object att) throws ClosedChannelException
    {
        return socketChannel.register(sel, ops, att);
    }

    // Any subclass should maintain the API that negative numbers of bytes are never returned
    @Override
    public int write(final ByteBuffer src, final int seqNum, final boolean replay) throws IOException
    {
        final int written = socketChannel.write(src);
        if (written < 0)
        {
            // normalise the negative return and the exceptional path
            throw new IOException("Disconnected " + remoteAddress + ", written=" + written);
        }
        return written;
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException
    {
        return socketChannel.read(dst);
    }

    @Override
    public void close()
    {
        if (socketChannel.isOpen())
        {
            try
            {
                socketChannel.close();
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
        }
    }

    @Override
    public void onReplayComplete(final long correlationId)
    {
    }
}
