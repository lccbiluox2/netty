package io.netty.example.server;

import io.netty.channel.ChannelHandler;
import io.netty.example.server.handler.TimeServerFixTcpStickyExceptionHandler;
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
public class TimeServerFixTcpStickyException {

    public static void main(String[] args) throws Exception {
        int port = 8091;
        LinkedList<ChannelHandler> handlers = new LinkedList<ChannelHandler>();
        handlers.add(new LineBasedFrameDecoder(1024));
        handlers.add(new StringDecoder());
        handlers.add(new TimeServerFixTcpStickyExceptionHandler());
        TimeServerBinder.bind(port, handlers);
    }

}
