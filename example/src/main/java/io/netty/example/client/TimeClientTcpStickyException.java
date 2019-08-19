package io.netty.example.client;

import io.netty.channel.ChannelHandler;
import io.netty.example.client.handler.TimeClientTcpStickyExceptionHandler;

import java.util.LinkedList;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-06
 */
public class TimeClientTcpStickyException {

    public static void main(String[] args) throws Exception {
        LinkedList<ChannelHandler> handlers = new LinkedList<ChannelHandler>();
        handlers.add(new TimeClientTcpStickyExceptionHandler());
        TimeClientBinder.connect(8091, "127.0.0.1", handlers);
    }

}
