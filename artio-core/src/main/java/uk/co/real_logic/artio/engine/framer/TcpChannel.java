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
package uk.co.real_logic.artio.engine.framer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

public abstract class TcpChannel implements AutoCloseable
{
    public static final int UNKNOWN_SEQ_NUM = -1;

    protected final String remoteAddress;

    public TcpChannel(final String remoteAddress) throws IOException
    {
        this.remoteAddress = remoteAddress;
    }

    public String remoteAddr()
    {
        return remoteAddress;
    }

    public abstract SelectionKey register(Selector sel, int ops, Object att)
        throws ClosedChannelException;

    // Any subclass should maintain the API that negative numbers of bytes are never returned
    public abstract int write(ByteBuffer src, int seqNum, boolean replay) throws IOException;

    public abstract int read(ByteBuffer dst) throws IOException;

    @Override
    public abstract void close();

    public abstract void onReplayComplete(long correlationId);
}
