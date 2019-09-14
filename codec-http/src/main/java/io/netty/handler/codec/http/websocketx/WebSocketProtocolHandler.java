/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;


import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * WebSocketFrame的子类会进入此类处理
 */
abstract class WebSocketProtocolHandler extends MessageToMessageDecoder<WebSocketFrame> {

    //默认true
    private final boolean dropPongFrames;

    /**
     * Creates a new {@link WebSocketProtocolHandler} that will <i>drop</i> {@link PongWebSocketFrame}s.
     */
    WebSocketProtocolHandler() {
        this(true);
    }

    /**
     * Creates a new {@link WebSocketProtocolHandler}, given a parameter that determines whether or not to drop {@link
     * PongWebSocketFrame}s.
     *
     * @param dropPongFrames
     *            {@code true} if {@link PongWebSocketFrame}s should be dropped
     */
    WebSocketProtocolHandler(boolean dropPongFrames) {
        this.dropPongFrames = dropPongFrames;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) throws Exception {
        //如果是Ping帧
        if (frame instanceof PingWebSocketFrame) {
            //ByteBuf引用计数器+1，因为父类会对frame.content进行释放。
            frame.content().retain();
            //写入Pong帧，在写入完成后框架对frame.content进行释放。
            ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content()));
            readIfNeeded(ctx);
            return;
        }
        //如果是Pong帧则不作处理
        if (frame instanceof PongWebSocketFrame && dropPongFrames) {
            readIfNeeded(ctx);
            return;
        }

        //其余数据帧则先把引用计数器+1，在放入out
        //放入out的帧会传递到我们自定义的handler处理
        out.add(frame.retain());
    }

    private static void readIfNeeded(ChannelHandlerContext ctx) {
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    /**
     * 出现异常继续传递并关闭socket连接
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
        ctx.close();
    }
}
