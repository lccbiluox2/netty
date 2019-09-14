/*
 * Copyright 2019 The Netty Project
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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpUtil.*;
import static io.netty.handler.codec.http.HttpVersion.*;
import static io.netty.util.internal.ObjectUtil.*;

/**
 * Handles the HTTP handshake (the HTTP Upgrade request) for {@link WebSocketServerProtocolHandler}.
 */
class WebSocketServerProtocolHandshakeHandler extends ChannelInboundHandlerAdapter {

    private final String websocketPath;
    private final String subprotocols;
    private final boolean checkStartsWith;
    private final long handshakeTimeoutMillis;
    private final WebSocketDecoderConfig decoderConfig;
    private ChannelHandlerContext ctx;
    private ChannelPromise handshakePromise;

    WebSocketServerProtocolHandshakeHandler(String websocketPath, String subprotocols,
            boolean checkStartsWith, long handshakeTimeoutMillis, WebSocketDecoderConfig decoderConfig) {
        this.websocketPath = websocketPath;
        this.subprotocols = subprotocols;
        this.checkStartsWith = checkStartsWith;
        this.handshakeTimeoutMillis = checkPositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
        this.decoderConfig = checkNotNull(decoderConfig, "decoderConfig");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        handshakePromise = ctx.newPromise();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        final FullHttpRequest req = (FullHttpRequest) msg;
        //如果HTTP请求路径不是websocketPath，则向下传递msg
        if (isNotWebSocketPath(req)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            //如果不是get请求返回403响应
            if (!GET.equals(req.method())) {
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN, ctx.alloc().buffer(0)));
                return;
            }

            //创建握手工厂
            final WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    getWebSocketLocation(ctx.pipeline(), req, websocketPath), subprotocols, decoderConfig);
            //根据不同的req返回不同版本的握手器
            final WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
            final ChannelPromise localHandshakePromise = handshakePromise;
            if (handshaker == null) {
                //当前服务器不支持客户端ws的协议版本
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                // Ensure we set the handshaker and replace this handler before we
                // trigger the actual handshake. Otherwise we may receive websocket bytes in this handler
                // before we had a chance to replace it.
                //
                // See https://github.com/netty/netty/issues/9471.
                //channel和握手器关联
                WebSocketServerProtocolHandler.setHandshaker(ctx.channel(), handshaker);
                //替换当前编解码器为forbiddenHttpRequestResponder
                //握手后如果再收到http请求则直接拒绝
                ctx.pipeline().replace(this, "WS403Responder",
                        WebSocketServerProtocolHandler.forbiddenHttpRequestResponder());


                final ChannelFuture handshakeFuture = handshaker.handshake(ctx.channel(), req);
                //握手完成后事件
                handshakeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        //失败抛出异常
                        if (!future.isSuccess()) {
                            localHandshakePromise.tryFailure(future.cause());
                            ctx.fireExceptionCaught(future.cause());
                        } else {
                            //成功激发事件
                            //这里要注意，接收事件的handler必须放在此类的后面，否则需要修改此类代码为ctx.channel.fireUserEventTriggered
                            localHandshakePromise.trySuccess();
                            // Kept for compatibility
                            ctx.fireUserEventTriggered(
                                    WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
                            ctx.fireUserEventTriggered(
                                    new WebSocketServerProtocolHandler.HandshakeComplete(
                                            req.uri(), req.headers(), handshaker.selectedSubprotocol()));
                        }
                    }
                });
                applyHandshakeTimeout();
            }
        } finally {
            req.release();
        }
    }

    // 判断http请求路径是否为websocketPath
    private boolean isNotWebSocketPath(FullHttpRequest req) {
        return checkStartsWith ? !req.uri().startsWith(websocketPath) : !req.uri().equals(websocketPath);
    }

    //返回http的输出
    private static void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        //不是长连接关闭底层tcp
        if (!isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    //获取ws的location
    private static String getWebSocketLocation(ChannelPipeline cp, HttpRequest req, String path) {
        String protocol = "ws";
        if (cp.get(SslHandler.class) != null) {
            // SSL in use so use Secure WebSockets
            protocol = "wss";
        }
        String host = req.headers().get(HttpHeaderNames.HOST);
        return protocol + "://" + host + path;
    }

    private void applyHandshakeTimeout() {
        final ChannelPromise localHandshakePromise = handshakePromise;
        final long handshakeTimeoutMillis = this.handshakeTimeoutMillis;
        if (handshakeTimeoutMillis <= 0 || localHandshakePromise.isDone()) {
            return;
        }

        final Future<?> timeoutFuture = ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                if (!localHandshakePromise.isDone() &&
                        localHandshakePromise.tryFailure(new WebSocketHandshakeException("handshake timed out"))) {
                    ctx.flush()
                       .fireUserEventTriggered(ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT)
                       .close();
                }
            }
        }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);

        // Cancel the handshake timeout when handshake is finished.
        localHandshakePromise.addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> f) throws Exception {
                timeoutFuture.cancel(false);
            }
        });
    }
}
