package io.netty.example.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-21
 */
public class ClientConnectionPool {

    /**
     * 正确的客户端连接池创建方式
     */
    private static void initEventLoopGroup(int poolSize) throws Exception {

        // 创建多个连接时，EventLoopGroup可以重用
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                    }
                });
        for (int i = 0; i < poolSize; i++) {
            // 尽管Bootstrap自身不是线程安全的，但是执行Bootstrap的连接操作是串行执行的
            // 而且connect方法本身是线程安全的，它会创建一个新的NioSocketChannel，并从初始的EventLoopGroup中选择一个NioEventLoop线程执行真正的Channel
            // 连接操作，与执行Bootstrap的线程无关，所以通过一个Bootstrap连续发起多个连接操作是安全的
            b.connect("127.0.0.1", 8091).sync();

            // 此时EventLoopGroup是共享的，当某个链路发生异常时或者关闭时，只需要关闭并释放Channel本身即可，不能销毁Channel所使用的NioEventLoop和所在的线程组EventLoopGroup
            //
        }

    }

}
