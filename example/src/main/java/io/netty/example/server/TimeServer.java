package io.netty.example.server;

import io.netty.channel.ChannelHandler;
import io.netty.example.server.handler.TimeServerHandler;

import java.util.LinkedList;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-06
 */
public class TimeServer {

    public static void main(String[] args) throws Exception {
        int port = 8091;
        LinkedList<ChannelHandler> handlers = new LinkedList<ChannelHandler>();
        handlers.add(new TimeServerHandler());
        TimeServerBinder.bind(port, handlers);
    }

}
