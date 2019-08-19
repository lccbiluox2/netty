package io.netty.example.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.server.handler.TimeServerTcpStickyExceptionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.*;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Properties;

/**
 * Netty时间服务器服务端
 * Netty是一个异步非阻塞的通信框架，所有的I/O操作都是异步的，
 * 但是为了方便使用，例如在有些场景下应用需要同步等待一些I/O操作的结果，所以提供了ChannelFuture
 */
public class TimeServerBinder {

    public static void bind(int port, final LinkedList<ChannelHandler> channelHandlers) throws Exception {
        // boss group 服务端的TCP连接接入线程池
        final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // worker group 处理客户端网络I/O读写的工作线程池 默认是处理器的2倍
        final EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            // 配置服务器的NIO线程租
            ServerBootstrap b = new ServerBootstrap();
            // 绑定boss和worker线程组
            b.group(bossGroup, workerGroup)
                    // 设置channel类型，服务端用的是NioServerSocketChannel
                    .channel(NioServerSocketChannel.class)
                    // 设置channel的配置项
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    //.option(ChannelOption.SO_KEEPALIVE, true)
                    // 设置NioServerSocketChannel的handler
                    .handler(new LoggingHandler(LogLevel.INFO))
                    // 设置childHandler，作为新建的NioSocketChannel的初始化Handler
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            // 当新建的与客户端通信的NioSocketChannel被注册到EventLoop成功时，该方法会被调用，用于添加业务Handler
                            System.out.println("server initChannel..");
                            for (ChannelHandler channelHandler : channelHandlers) {
                                socketChannel.pipeline().addLast(channelHandler);
                            }
                        }
                    });

            // 用同步阻塞方式绑定服务端监听端口。整个创建初始化注册是在bind()方法内
            // 端口绑定执行得非常快，完成后程序就继续向下执行
            // 同步等待绑定结束
            ChannelFuture f = b.bind(port).sync();
            // 监听Close Future，同步等待关闭
            // 等待服务端监听端口关闭，通过sync或await，主动阻塞当前调用方的线程，等待操作结果，也就是通常说的异步转同步
            // main线程被阻塞在CloseFuture中，等待ChannelFuture关闭
            f.channel().closeFuture().sync();

            // 通过注册监听器GenericFutureListener，可以异步等待I/O执行结果。
            // 增加了服务端连接关闭的监听事件之后，不会阻塞main()线程的执行
//            f.channel().closeFuture().addListener(new ChannelFutureListener() {
//
//                public void operationComplete(ChannelFuture future) throws Exception {
//                    // 业务逻辑处理代码，此处省略
//                    bossGroup.shutdownGracefully();
//                    workerGroup.shutdownGracefully();
//                    System.out.println(future.channel().toString() + "链路关闭");
//                }
//            });

        } finally {
            // 优雅退出，释放线程池资源。
            // 来完成内存队列中积压消息的处理、链路的关闭和EventLoop线程的退出，以实现停机不中断业务
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }


}