/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        // 调用父类构造函数
        super(parent, socket);
        // 创建 NioSocketChannelConfig
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    /**
     * 客户端的isActive
     */
    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        // JDK版本1.7以上
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            // 执行真正的异步connect
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
                // 如果没有注册成功，就注册OP_CONNECT事件 ，事件就绪时调用finishConnect()
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        // 判断JDK的SocketChannel连接结果，返回true表示连接成功
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        // AbstractNioChannel中关于连接超时的处理
        super.doClose();
        javaChannel().close();
    }

    /**
     * 真正从通道的ByteBuf中读取数据
     */
    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    /**
     * 调用NIO中的SocketChannel的写数据操作
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        SocketChannel ch = javaChannel();
        // 循环次数，默认16次，作用是如果数据量过大，一次性写完可能耗尽socket缓冲区。
        // 当循环了16次还是写不完，那么注册OP_WRITE事件，当socket可写的时候，再继续flush
        int writeSpinCount = config().getWriteSpinCount();
        do {
            // 循环写
            if (in.isEmpty()) {
                // All written so clear OP_WRITE
                // 所有数据已写完，不再关心OP_WRITE事件
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            // 获取每次写入的最大字节数
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();
            // 转换成原生的ByteBuffer数组，将ChannelOutBoundBuffer中的ByteBuf复制到Java NIO ByteBuffer数组中
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);
            // buffer的数量
            int nioBufferCnt = in.nioBufferCount();

            // Always us nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // 没有ByteBuffer，也就是只有FileRegion
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    // 使用父类方法进行普通处理
                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // 只有一个ByteBuffer，此时的处理等效于父类方法的处理

                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // 长度为1，取出数组的第一位，调用原生Java NIO写入Socket
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    final int localWrittenBytes = ch.write(buffer);
                    if (localWrittenBytes <= 0) {
                        // 如果实际写入的字节<=0，代表socket不可写，调用incompleteWrite，表明没有写进去
                        // 向多路复用器注册写事件
                        incompleteWrite(true);
                        return;
                    }
                    // 写进去，调整maxBytesPerGatheringWrite的值
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    // 并且清除缓冲区的数据
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
                default: {
                    // 多个ByteBuffer，采用gathering方法处理

                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe

                    // gathering方法，此时一次写多个ByteBuffer
                    // 如果是多个ByteBuffer,逻辑和上面一样，只不过写的是ByteBuffer数组
                    long attemptedBytes = in.nioBufferSize();
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);

        // 根据writeSpinCount判断是否写完。如果写完清除写事件，如果没写完，注册写事件
        // writeSpinCount < 0表示还没有写完
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    // 取消选择键selectionKey
                    doDeregister();
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            return null;
        }
    }

    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(getSendBufferSize() << 1);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
