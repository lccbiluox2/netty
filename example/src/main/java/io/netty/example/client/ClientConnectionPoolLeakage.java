package io.netty.example.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Description:
 * Netty客户端连接池资源泄漏案例
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-21
 */
public class ClientConnectionPoolLeakage {

    /**
     * 错误的客户端线程模型
     * 采用BIO模式来调用NIO通信框架
     */
    private static void initClientPool(int poolSize) throws Exception {
        // init client pool
        for (int i = 0; i < poolSize; i++) {
            EventLoopGroup group = new NioEventLoopGroup();
            try {
                Bootstrap b = new Bootstrap();
                b.group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) throws Exception {
                                ChannelPipeline p = ch.pipeline();
                                p.addLast(new LoggingHandler());
                            }
                        });
                ChannelFuture f = b.connect("127.0.0.1", 8091).sync();
                f.channel().closeFuture().sync();
            } finally {
                group.shutdownGracefully();
            }
        }
    }
}
