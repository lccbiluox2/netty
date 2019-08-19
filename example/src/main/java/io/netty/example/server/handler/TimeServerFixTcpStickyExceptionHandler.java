package io.netty.example.server.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.Date;

/**
 * Netty时间服务器服务端处理类
 */
public class TimeServerFixTcpStickyExceptionHandler extends ChannelInboundHandlerAdapter {

    private int counter;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    /**
     * 读取数据
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        // 接收到的消息就是删除回车换行后的消息，不需要额外考虑处理读半包的问题，也不需要对请求消息进行编码
        String body = (String) msg;
        System.out.println("The time server receive order:" + body + " ; the counter is:" + ++counter);

        String currentTime = "QUERY TIME ORDER".equalsIgnoreCase(body) ? new Date(
                System.currentTimeMillis()).toString() : "BAD ORDER";
        currentTime = currentTime + System.getProperty("line.separator");

        ByteBuf resp = Unpooled.copiedBuffer(currentTime.getBytes());
        ctx.writeAndFlush(resp);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        System.out.println("server channelReadComplete..");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        System.out.println("server exceptionCaught..");
        // 发生异常时，关闭ChannelHandlerContext，释放和ChannelHandlerContext相关联的句柄等资源
        ctx.close();
    }

}