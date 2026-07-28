package uk.co.real_logic.artio.engine.framer;

import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.EngineConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static java.net.StandardSocketOptions.*;
import static java.nio.channels.SelectionKey.OP_CONNECT;
import static uk.co.real_logic.artio.messages.InitialAcceptedSessionOwner.SOLE_LIBRARY;

public class DefaultTcpChannelSupplier extends TcpChannelSupplier
{
    private final EngineConfiguration configuration;
    private final boolean hasBindAddress;
    private final Set<SocketChannel> openingSocketChannels = new HashSet<>();

    private Selector selector;
    private ServerSocketChannel listeningChannel;

    public DefaultTcpChannelSupplier(final EngineConfiguration configuration)
    {
        hasBindAddress = configuration.hasBindAddress();
        this.configuration = configuration;
        try
        {
            selector = Selector.open();
            if (configuration.bindAtStartup() && configuration.initialAcceptedSessionOwner() != SOLE_LIBRARY)
            {
                bind();
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public int pollSelector(final long timeInMs, final TcpChannelSupplier.NewChannelHandler handler) throws IOException
    {
        if (hasBindAddress || openingSocketChannels.size() > 0)
        {
            selector.selectNow();
            final Set<SelectionKey> selectionKeys = selector.selectedKeys();
            final int unprocessedConnections = selectionKeys.size();
            if (unprocessedConnections > 0)
            {
                final Iterator<SelectionKey> it = selectionKeys.iterator();
                while (it.hasNext())
                {
                    final SelectionKey selectionKey = it.next();

                    if (selectionKey.isAcceptable())
                    {
                        final SocketChannel channel = listeningChannel.accept();
                        if (channel != null)
                        {
                            configure(channel);
                            channel.configureBlocking(false);

                            handler.onNewChannel(timeInMs, newTcpChannel(channel));
                        }

                        it.remove();
                    }
                    else if (selectionKey.isConnectable())
                    {
                        final TcpChannelSupplier.InitiatedChannelHandler channelHandler =
                            (TcpChannelSupplier.InitiatedChannelHandler)selectionKey.attachment();
                        final SocketChannel channel = (SocketChannel)selectionKey.channel();
                        try
                        {
                            if (channel.finishConnect())
                            {
                                selectionKey.interestOps(selectionKey.interestOps() & (~OP_CONNECT));
                                it.remove();

                                onFinishConnect(channelHandler, channel);
                            }
                        }
                        catch (final IOException e)
                        {
                            channelHandler.onInitiatedChannel(null, e);
                            it.remove();
                            openingSocketChannels.remove(channel);
                        }
                    }
                }
            }

            return unprocessedConnections;
        }

        return 0;
    }

    protected void onFinishConnect(
        final InitiatedChannelHandler channelHandler, final SocketChannel channel) throws IOException
    {
        channelHandler.onInitiatedChannel(newTcpChannel(channel), null);
        openingSocketChannels.remove(channel);
    }

    public void unbind() throws IOException
    {
        if (listeningChannel != null)
        {
            listeningChannel.close();
            selector.selectNow();
            listeningChannel = null;
        }
    }

    public void bind() throws IOException
    {
        if (hasBindAddress && listeningChannel == null)
        {
            listeningChannel = ServerSocketChannel.open();
            if (configuration.receiverSocketBufferSize() > 0)
            {
                listeningChannel.setOption(SO_RCVBUF, configuration.receiverSocketBufferSize());
            }
            listeningChannel.bind(configuration.bindAddress()).configureBlocking(false);
            listeningChannel.register(selector, SelectionKey.OP_ACCEPT);
        }
    }

    private void configure(final SocketChannel channel) throws IOException
    {
        channel.setOption(TCP_NODELAY, true);
        if (configuration.receiverSocketBufferSize() > 0)
        {
            channel.setOption(SO_RCVBUF, configuration.receiverSocketBufferSize());
        }
        if (configuration.senderSocketBufferSize() > 0)
        {
            channel.setOption(SO_SNDBUF, configuration.senderSocketBufferSize());
        }
    }

    public void close()
    {
        Exceptions.closeAll(
            selector,
            () ->
            {
                if (listeningChannel != null)
                {
                    listeningChannel.configureBlocking(true);
                }
            },
            listeningChannel);
    }

    public void open(final InetSocketAddress address, final TcpChannelSupplier.InitiatedChannelHandler channelHandler)
        throws IOException
    {
        final SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        configure(channel);
        try
        {
            channel.connect(address);
        }
        catch (final Exception e)
        {
            try
            {
                channel.close();
            }
            catch (final IOException ce)
            {
                e.addSuppressed(ce);
            }
            throw e;
        }
        channel.register(selector, OP_CONNECT, channelHandler);
        openingSocketChannels.add(channel);
    }

    protected TcpChannel newTcpChannel(final SocketChannel channel) throws IOException
    {
        return new DefaultTcpChannel(channel);
    }

    public void stopConnecting(final InetSocketAddress address) throws IOException
    {
        final Iterator<SocketChannel> iterator = openingSocketChannels.iterator();
        while (iterator.hasNext())
        {
            final SocketChannel channel = iterator.next();
            if (channel.getRemoteAddress().equals(address))
            {
                CloseHelper.quietClose(channel);
                iterator.remove();
                break;
            }
        }
    }
}
