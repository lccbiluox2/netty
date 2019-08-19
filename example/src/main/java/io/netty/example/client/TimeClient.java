package io.netty.example.client;

import io.netty.channel.ChannelHandler;
import io.netty.example.client.handler.TimeClientHandler;

import java.util.LinkedList;

/**
 * Description:
 * Netty时间服务器客户端
 *
 * @author mwt
 * @version 1.0
 * @date 2019-07-06
 */
public class TimeClient {

    public static void main(String[] args) throws Exception {

        LinkedList<ChannelHandler> handlers = new LinkedList<ChannelHandler>();
        handlers.add(new TimeClientHandler());
        TimeClientBinder.connect(8091, "127.0.0.1", handlers);
    }

}
