package io.netty.example.wulei;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.Charset;

/**
 * 测试类
 * netty-server's handler
 * read data
 * @author wulei
 */
public class SimpleHandler extends ChannelInboundHandlerAdapter{

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg)
			throws Exception {
		
		System.out.println("开始读取数据============");
		if(msg instanceof ByteBuf){
			ByteBuf req = (ByteBuf)msg;
			String content = req.toString(Charset.defaultCharset());
			System.out.println(content);
			
		}
		
		
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		// TODO Auto-generated method stub
		super.exceptionCaught(ctx, cause);
	}

	
	
	

}
