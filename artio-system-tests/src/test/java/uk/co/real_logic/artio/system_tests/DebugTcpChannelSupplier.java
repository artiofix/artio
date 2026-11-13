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
package uk.co.real_logic.artio.system_tests;

import org.agrona.collections.IntArrayList;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.framer.DefaultTcpChannel;
import uk.co.real_logic.artio.engine.framer.DefaultTcpChannelSupplier;
import uk.co.real_logic.artio.engine.framer.TcpChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;

/**
 * Hook for testing interaction with different networking conditions.
 */
public class DebugTcpChannelSupplier extends DefaultTcpChannelSupplier
{
    public static final int WRITE_MAX = -1;
    public static final int NULL_WRITE_BYTES = Integer.MIN_VALUE;

    private final ArrayList<TcpChannel> channels = new ArrayList<>();
    private boolean isEnabled = true;

    private final ArrayList<Runnable> pausedOperations = new ArrayList<>();
    private volatile boolean connectsPaused = false;

    private final IntArrayList bytesToBeWritten;
    private int index;

    public DebugTcpChannelSupplier(final EngineConfiguration configuration)
    {
        this(configuration, new IntArrayList());
    }

    public DebugTcpChannelSupplier(final EngineConfiguration configuration, final IntArrayList bytesToBeWritten)
    {
        super(configuration);
        this.bytesToBeWritten = bytesToBeWritten;
    }

    @Override
    protected synchronized TcpChannel newTcpChannel(final SocketChannel channel) throws IOException
    {
        final TcpChannel tcpChannel = new DebugTcpChannel(channel);
        channels.add(tcpChannel);
        return tcpChannel;
    }

    public synchronized void disable()
    {
        isEnabled = false;
        channels.forEach(TcpChannel::close);
        channels.clear();
    }

    public synchronized void enable()
    {
        isEnabled = true;

        if (!channels.isEmpty())
        {
            throw new IllegalStateException(
                "Tried enabling channel supplier, but channels were already connected");
        }
    }

    @Override
    public synchronized int pollSelector(final long timeInMs, final NewChannelHandler handler) throws IOException
    {
        if (!connectsPaused && !pausedOperations.isEmpty())
        {
            pausedOperations.forEach(Runnable::run);
            pausedOperations.clear();
        }

        if (isEnabled)
        {
            return super.pollSelector(timeInMs, handler);
        }
        else
        {
            return super.pollSelector(timeInMs, (ignore, socketChannel) -> socketChannel.close());
        }
    }

    public synchronized void pauseConnects()
    {
        connectsPaused = true;
    }

    public synchronized void unpauseConnects()
    {
        connectsPaused = false;
    }

    public synchronized void unpauseAndDiscardConnects()
    {
        connectsPaused = false;
        pausedOperations.clear();
    }

    @Override
    protected void onFinishConnect(
        final InitiatedChannelHandler channelHandler, final SocketChannel channel) throws IOException
    {
        if (connectsPaused)
        {
            pausedOperations.add(() ->
            {
                try
                {
                    super.onFinishConnect(channelHandler, channel);
                }
                catch (final IOException e)
                {
                    // Deliberately blank.
                }
            });
        }
        else
        {
            super.onFinishConnect(channelHandler, channel);
        }
    }

    @Override
    public synchronized void open(
        final InetSocketAddress address,
        final InitiatedChannelHandler handler)
        throws IOException
    {
        if (isEnabled)
        {
            super.open(address, handler);
        }
        else
        {
            // Deliberately blank - black hole the connection
        }
    }

    class DebugTcpChannel extends DefaultTcpChannel
    {
        private int remaining;

        DebugTcpChannel(final SocketChannel socketChannel) throws IOException
        {
            super(socketChannel);
        }

        @Override
        public int write(final ByteBuffer src, final int seqNum, final boolean replay) throws IOException
        {
            final IntArrayList maxBytesToBeWritten = DebugTcpChannelSupplier.this.bytesToBeWritten;
            if (index < maxBytesToBeWritten.size())
            {
                // Fake back-pressured TCP writes for testing purposes.
                int bytesToWrite = maxBytesToBeWritten.getInt(index);
                /*System.out.println("index = " + index + ", maxBytesToWrite = " + bytesToWrite
                    + ", remaining = " + remaining);*/
                if (bytesToWrite == WRITE_MAX)
                {
                    bytesToWrite = src.remaining();
                }
                if (remaining == 0)
                {
                    // first write
                    remaining = src.remaining() - bytesToWrite;
                    super.write(src, seqNum, replay);
                }
                else if (remaining < bytesToWrite)
                {
                    // Invariant failed, bug in our code
                    throw new RuntimeException("Failed to write bytes: " + remaining + " < " + bytesToWrite);
                }
                else
                {
                    // Fake some bp for the remaining writes.
                    remaining -= bytesToWrite;
                }
                index++;
                return bytesToWrite;
            }

            return super.write(src, seqNum, replay);
        }
    }
}
