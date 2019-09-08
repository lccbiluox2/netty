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

package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 *
 * {@link AbstractBootstrap}是一个帮助类，它使引导{@link通道}变得很容易。它支持方法链接，以提供配置{@link AbstractBootstrap}的
 * 简单方法。
 *
 * 当不在{@link ServerBootstrap}上下文中使用时，{@link #bind()}方法对于无连接传输非常有用，比如数据报(UDP).
 *
 * 提供了一个ChannelFactory对象用来创建Channel,一个Channel会对应一个EventLoop用于IO的事件处理，在一个Channel的整个生命周期中
 * 只会绑定一个EventLoop,这里可理解给Channel分配一个线程进行IO事件处理，结束后回收该线程。
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    /**
     * 如果是ServerBootStrap,以下都是针对NioServerSocketChannel的
     * 如果是BootStrap，对应的就是SocketChannel
     *
     * 这里的EventLoopGroup 作为服务端 Acceptor 线程，负责处理客户端的请求接入
     * 作为客户端 Connector 线程，负责注册监听连接操作位，用于判断异步连接结果。
     */
    volatile EventLoopGroup group;
    /**
     * 创建Channer 工厂 根据传入的类型来创建不同的Channer
     * 比如服务器传入的是：NioServerSocketChannel.class
     * 客户端传入：NioSocketChannel.class 。 加上这个注解代表这个已经过期有更好的替代类
     */
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;
    /**
     * SocketAddress 是用来绑定一个服务端口 用的
     */
    private volatile SocketAddress localAddress;
    /**
     * ChannelOption 可以添加Channer 添加一些配置信息
     */
    private final Map<ChannelOption<?>, Object> options = new ConcurrentHashMap<ChannelOption<?>, Object>();
    /** 初始化channel的属性值 */
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    /**
     * ChannelHandler 是具体怎么处理Channer 的IO事件。业务逻辑Handler，主要是HandlerInitializer，也可能是普通Handler
     */
    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        options.putAll(bootstrap.options);
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     *
     * 处理客户端的所有的事件， 传入一个EventLoopGroup,不管服务端还是客户端都会调用该方法
     */
    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        //  注意this.group只能设置一次, 这意味着group(group)方法只能被调用一次.
        this.group = group;
        // 强制转换成子类
        return self();
    }

    /**
     * 返回子类对象，返回对象本身
     */
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     *
     * 通过 channelClass 对象 创建一个 channel对象。设置服务端的Channel,Netty通过Channel工厂类创建不同的Channel。
     * 对于服务端传入:Netty需要创建NioServerSocketChannel
     * 对于客户端传入:NioSocketChannel.class
     */
    public B channel(Class<? extends C> channelClass) {
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")
        ));
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     * 创建好Channel后，返回对象本身
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }

        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     * 用于创建一个实例，当bind()方法调用的时候，才会创建，
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     *
     * 设置一些Channel相关参数
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        if (value == null) {
            options.remove(option);
        } else {
            options.put(option, value);
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     */
    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     * 服务端方法: 绑定端口 对该端口进行监听
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     * 客户端方法: 需要传入访问的地址和端口
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate();
        //这个方法这里省略调，具体可以看源码
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }

    /**
     * 服务端启动过程：
     * 1. 反射创建NioServerSocketChannel->channelFactory.newChannel()入口
     *  a. newSocket() 调用SelectorProvider.openServerSocketChannel()创建JDK SocketChannel
     *  b. AbstractNioChannel
     *      创建id, unsafe, pipeline
     *      设置监听事件为OP_ACCEPT
     *      configureBlocking(false) 设置channel的阻塞模式为非阻塞
     *  c. 新建NioServerSocketChannelConfig(tcp参数配置)
     * 2. 初始化服务端channel->init(channel)入口
     *  a. setChanelOptions(tcp相关配置), setChannelAttributes(用户自定义属性),
     *  b. 保存属性ChildOptions, ChildAttrs
     *  c. 配置用户自定义服务端处理器pipeline.addLast(config.handler())
     *  d. 服务端pipeline，增加ServerBootstrapAcceptor Handler，用于给新连接初始化一些属性设置
     * 3. 注册selector->AbstractChannel.register(channel)入口
     *  a. this.eventLoop = eventLoop 把nio线程和当前channel绑定
     *  b. register0() 实际注册、此时isActive为false
     *      doRegister() 将jdk底层的channel注册到事件轮询器上，并把Netty的channel当做attachment注册上去，后续有轮询到java
     *      channel事件可以直接用Netty的Channel去传播处理，此处监听事件为0，表示不关心任何事件。
     *      javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
     *      invokeHandlerAddedIfNeeded() 回调ServerHandler.handlerAdd()方法
     *      fireChannelRegistered() 传播channelRegister事件，回调ServerHandler.handlerRegistered()方法
     * 4. 端口绑定->AbstractUnsafe.bind()入口
     *  a. doBinid()
     *      javaChannel().bind(localAddress, config.getBacklog()) jdk底层绑定
     *  b. pipeline.fireChannelActive() 传播channelActive事件，回调ServerHandler.handlerActive()方法
     *      HeaderContext.readIfAutoRead()修改之前监听的事件为Accept
     *          Tail.read()
     *              AbstractNioChannel.doBeginRead()，修改监听的事件为Op_Accept
     * @param localAddress
     * @return
     */
    private ChannelFuture  doBind(final SocketAddress localAddress) {
        // 初始化并注册一个NioServerSocketChannel，调用initAndRegister()方法, 先初始化channel,并注册到event loop
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        // 检查注册的channel是否出错
        if (regFuture.cause() != null) {
            return regFuture;
        }

        // 等待注册完成，检查注册操作是否完成
        if (regFuture.isDone()) {
            // At this point we know that the registration was complete and successful.
            // 如果完成，在这个点上我们知道注册已经完成并且成功，继续bind操作, 创建一个ChannelPromise
            ChannelPromise promise = channel.newPromise();
            // 执行channel的bind，调用doBind0()方法来继续真正的bind操作
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            // 通常这个时候注册的future应该都已经完成,但是万一没有, 我们也需要处理，为这个channel创建一个PendingRegistrationPromise
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            // 然后给注册的future添加一个listener, 在operationComplete()回调时
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    // 检查是否出错
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        // 在event loop上注册失败, 因此直接让ChannelPromise失败, 避免一旦我们试图访问这个channel的eventloop
                        // 导致IllegalStateException
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        // 注册已经成功, 因此设置正确的executor以便使用
                        // 注: 这里已经以前有过一个bug, 有issue记录
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();

                        // 调用doBind0()方法来继续真正的bind操作
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }

    /**
     * 初始化什么？注册什么？
     *
     *
     * @return
     */
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            // 服务端channelFactory生产的是NioServerSocketChannel
            // 客户端channelFactory生产的是NioSocketChannel
            channel = channelFactory.newChannel();
            // 初始化NioServerSocketChannel或NioSocketChannel
            // 服务端和客户端是不同的
            init(channel);
        } catch (Throwable t) {
            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly();
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        // 调用MultithreadEventLoopGroup的register方法
        // 向boss EventLoopGroup中注册此channel
        ChannelFuture regFuture = config().group().register(channel);
        // 如果注册出错
        if (regFuture.cause() != null) {
            // 判断是否已经注册
            if (channel.isRegistered()) {
                // channel已经注册的就关闭
                channel.close();
            } else {
                // 还没有注册的就强行关闭
                channel.unsafe().closeForcibly();
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        // 如果代码走到这里而且promise没有失败, 那么是下面两种情况之一:
        // 1) 如果我们尝试了从event loop中注册, 那么现在注册已经完成
        //    现在可以安全的尝试 bind()或者connect(), 因为channel已经注册成功
        // 2) 如果我们尝试了从另外一个线程中注册, 注册请求已经成功添加到event loop的任务队列中等待后续执行
        //    现在可以安全的尝试 bind()或者connect():
        //         因为 bind() 或 connect() 会在安排的注册任务之后执行
        //         而register(), bind(), 和 connect() 都被确认是同一个线程
        return regFuture;
    }

    abstract void init(Channel channel) throws Exception;

    /**
     *
     * @param regFuture
     * @param channel
     * @param localAddress
     * @param promise
     */
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        // 这个方法在channelRegistered()方法触发前被调用.让handler有机会在它的channelRegistered()实现中构建pipeline
        // 给channel的event loop增加一个一次性任务
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                    // 绑定端口的方法, 如果成功则绑定localAddress到channel
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    // 如果不成功则设置错误到promise
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     * <p>
     * 创建默认的ChannelPipeline,用于调度和执行网络事件
     * 设置父类的Handler,父类的handler是客户端新接入的接连SocketChannel对应的ChannelPipeline 的handler
     */
    public B handler(ChannelHandler handler) {
        // 设置的是父类AbstractBootstrap里的成员，也就是该handler是被NioServerSocketChannel使用
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     *
     * 返回一个AbstractBootstrapConfig
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        return copiedMap(options);
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    static Map.Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Map.Entry[size];
    }

    @SuppressWarnings("unchecked")
    static Map.Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Map.Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
