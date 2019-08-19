package io.netty.example.client;

import io.netty.channel.ChannelHandler;
import io.netty.example.client.handler.TimeClientFixTcpStickyExceptionHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;

import java.util.LinkedList;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-06
 */
public class TimeClientFixTcpStickyException {


    public static void main(String[] args) throws Exception {
        LinkedList<ChannelHandler> handlers = new LinkedList<ChannelHandler>();
        handlers.add(new LineBasedFrameDecoder(1024));
        handlers.add(new StringDecoder());
        handlers.add(new TimeClientFixTcpStickyExceptionHandler());

        TimeClientBinder.connect(8091, "192.168.15.101", handlers);
    }
}
