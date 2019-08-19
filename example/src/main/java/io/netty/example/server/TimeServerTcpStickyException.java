package io.netty.example.server;

import io.netty.channel.ChannelHandler;
import io.netty.example.server.handler.TimeServerTcpStickyExceptionHandler;

import java.util.LinkedList;

/**
 * Description:
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-06
 */
public class TimeServerTcpStickyException {

    public static void main(String[] args) throws Exception {
        int port = 8091;
        LinkedList<ChannelHandler> hanlers = new LinkedList<ChannelHandler>();
        hanlers.add(new TimeServerTcpStickyExceptionHandler());
        TimeServerBinder.bind(port, hanlers);
    }

}
