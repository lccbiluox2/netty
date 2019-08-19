package io.netty.example.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.LinkedList;

/**
 * Netty时间服务器客户端
 */
public class TimeClientBinder {

    public static void connect(int port, String host, final LinkedList<ChannelHandler> channelHandlers) throws Exception {
        // 配置客户端NIO线程组，与服务端不同，客户端只需要一个IO线程组
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            // 客户端的Channel需要设置为NioSocketChannel
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel)
                                throws Exception {
                            System.out.println("client initChannel..");
                            for (ChannelHandler channelHandler : channelHandlers) {
                                socketChannel.pipeline().addLast(channelHandler);
                            }
                        }
                    });
            // 发起连接操作
            ChannelFuture f = b.connect(host, port).sync();
            // 等待客户端链路关闭
            // 建议采用异步方式调用，即获取ChannelFuture后注册监听器，异步处理连接操作结果，不要阻塞调用方的线程
            f.channel().closeFuture().sync();
        } finally {
            // 优雅退出，释放NIO线程组
            group.shutdownGracefully();
        }
    }


}