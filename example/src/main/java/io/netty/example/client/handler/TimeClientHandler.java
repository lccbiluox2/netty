package io.netty.example.client.handler;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class TimeClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = Logger
            .getLogger(TimeClientHandler.class.getName());

    private final ByteBuf firstMessage;

    public TimeClientHandler() {
        byte[] req = "QUERY TIME ORDER".getBytes();
        firstMessage = Unpooled.buffer(req.length);
        firstMessage.writeBytes(req);
    }

    /**
     * 当客户端和服务端TCP链路建立成功之后，Netty的NIO线程会调用channelActive方法
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 与服务端建立连接后
        System.out.println("client channelActive..");
        System.out.println("server socket address: " + ctx.channel().remoteAddress());
        // 给服务端初始发送一条数据
        ctx.writeAndFlush(firstMessage);
    }

    /**
     * 当服务端返回应答消息时，channelRead方法被调用
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        System.out.println("client channelRead..");
        //服务端返回消息后
        ByteBuf buf = (ByteBuf) msg;
        byte[] req = new byte[buf.readableBytes()];
        buf.readBytes(req);
        String body = new String(req, "UTF-8");
        System.out.println("Now is :" + body);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        System.out.println("client exceptionCaught..");
        logger.warning("Unexpected exception from downstream:"
                + cause.getMessage());
        // 释放客户端资源
        ctx.close();
    }

}