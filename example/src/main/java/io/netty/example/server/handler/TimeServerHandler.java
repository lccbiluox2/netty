package io.netty.example.server.handler;

import java.util.Date;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Netty时间服务器服务端处理类
 */
public class TimeServerHandler extends ChannelInboundHandlerAdapter {

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
        System.out.println("client socket address: " + ctx.channel().remoteAddress());
        System.out.println("server channelRead..");
        // 将msg转成Netty的ByteBuf对象
        ByteBuf buf = (ByteBuf) msg;
        // 获取缓冲区的可读字节数，根据可读的字节数创建byte数组
        byte[] req = new byte[buf.readableBytes()];
        // 将缓冲区中的字节数组复制到新建的byte数组中
        buf.readBytes(req);
        // 通过构造函数获取请求消息
        String body = new String(req, "UTF-8");
        System.out.println("The time server receive order:" + body);
        String currentTime = "QUERY TIME ORDER".equalsIgnoreCase(body) ? new Date(
                System.currentTimeMillis()).toString() : "BAD ORDER";
        ByteBuf resp = Unpooled.copiedBuffer(currentTime.getBytes());
        // 异步发送消息给客户端
        ctx.write(resp);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        System.out.println("server channelReadComplete..");
        // 将消息发送队列中的消息写入到SocketChannel中并发送给客户端。
        // 从性能角度考虑，为了防止频繁地唤醒Selector进行消息发送，Netty的write方法并不直接将消息写入SocketChannel中，
        // 调用write方法只是将消息放入缓冲数组中，再通过flush方法将发送缓冲区中的消息全部写到SocketChannel中
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        System.out.println("server exceptionCaught..");
        // 发生异常时，关闭ChannelHandlerContext，释放和ChannelHandlerContext相关联的句柄等资源
        ctx.close();
    }

}