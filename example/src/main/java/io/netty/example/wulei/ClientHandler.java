package io.netty.example.wulei;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 测试类
 * netty-client's handler
 *
 * @author wulei
 */
public class ClientHandler extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg)
			throws Exception {
		System.out.println("客户端开始读取数据");
		
		
		
	}
	
	

}
