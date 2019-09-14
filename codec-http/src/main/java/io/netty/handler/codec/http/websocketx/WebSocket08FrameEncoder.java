/*
 * Copyright 2012 The Netty Project
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
// (BSD License: http://www.opensource.org/licenses/bsd-license)
//
// Copyright (c) 2011, Joe Walnes and contributors
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or
// without modification, are permitted provided that the
// following conditions are met:
//
// * Redistributions of source code must retain the above
// copyright notice, this list of conditions and the
// following disclaimer.
//
// * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the
// following disclaimer in the documentation and/or other
// materials provided with the distribution.
//
// * Neither the name of the Webbit nor the names of
// its contributors may be used to endorse or promote products
// derived from this software without specific prior written
// permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
// CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
// GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
// OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package io.netty.handler.codec.http.websocketx;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * <p>
 * Encodes a web socket frame into wire protocol version 8 format. This code was forked from <a
 * href="https://github.com/joewalnes/webbit">webbit</a> and modified.
 * </p>
 *
 * WebSocketFrame编码器，负责把WebSocketFrame的子类转换为bytebuf
 */
public class WebSocket08FrameEncoder extends MessageToMessageEncoder<WebSocketFrame> implements WebSocketFrameEncoder {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocket08FrameEncoder.class);

    //延续帧  0000 0000
    private static final byte OPCODE_CONT = 0x0;
    //文本帧  0000 0001
    private static final byte OPCODE_TEXT = 0x1;
    //二进制帧 0000 0010
    private static final byte OPCODE_BINARY = 0x2;
    //关闭   0000 1000
    private static final byte OPCODE_CLOSE = 0x8;
    //心跳检测帧 0000 1001
    private static final byte OPCODE_PING = 0x9;
    //心跳应答帧 0000 1010
    private static final byte OPCODE_PONG = 0xA;

    /**
     * The size threshold for gathering writes. Non-Masked messages bigger than this size will be be sent fragmented as
     * a header and a content ByteBuf whereas messages smaller than the size will be merged into a single buffer and
     * sent at once.<br>
     * Masked messages will always be sent at once.
     *
     * //阈值，发送的字节超过此长度将不会合并到一个bytebuf中
     */
    private static final int GATHERING_WRITE_THRESHOLD = 1024;

    /**
     * 表示websocket是否需要对数据进行掩码运算
     * 掩码运算也叫XOR加密，详情可以在http://www.ruanyifeng.com/blog/2017/05/xor.html了解。
     * 那么websocket客户端发送到服务器端的数据需要进行XOR运算是为了防止攻击
     * 因为websocket发送的数据，黑客很有可能在数据字节码中加入http请求的关键字，比如getxx \r\n，
     * 如果不加以限制，那么某些代理服务器会以为这是一个http请求导致错误转发。
     * 那么通过对原生字节进行XOP计算后，http关键字会被转化为其它字节，从而避免攻击
     */
    private final boolean maskPayload;

    /**
     * Constructor
     *
     * @param maskPayload
     *            Web socket clients must set this to true to mask payload. Server implementations must set this to
     *            false.
     */
    public WebSocket08FrameEncoder(boolean maskPayload) {
        this.maskPayload = maskPayload;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        //要发送的数据
        final ByteBuf data = msg.content();
        //掩码XOR计算需要的KEY
        byte[] mask;

        //根据帧的类型确定opcode的值
        byte opcode;
        if (msg instanceof TextWebSocketFrame) {
            opcode = OPCODE_TEXT;
        } else if (msg instanceof PingWebSocketFrame) {
            opcode = OPCODE_PING;
        } else if (msg instanceof PongWebSocketFrame) {
            opcode = OPCODE_PONG;
        } else if (msg instanceof CloseWebSocketFrame) {
            opcode = OPCODE_CLOSE;
        } else if (msg instanceof BinaryWebSocketFrame) {
            opcode = OPCODE_BINARY;
        } else if (msg instanceof ContinuationWebSocketFrame) {
            opcode = OPCODE_CONT;
        } else {
            throw new UnsupportedOperationException("Cannot encode frame of type: " + msg.getClass().getName());
        }

        //要发送数据的长度
        int length = data.readableBytes();

        if (logger.isTraceEnabled()) {
            logger.trace("Encoding WebSocket Frame opCode={} length={}", opcode, length);
        }

        int b0 = 0;
        //判断消息是否是最后一个分片,如果是最后一个分片 那么FIN要设置为1
        if (msg.isFinalFragment()) {
            //1 << 7 左移7位  1000 0000  把FIN比特为设为1
            //bo = 0 | 128 (当两边操作数的位有一边为1时，结果为1，否则为0)，值不变。
            b0 |= 1 << 7;
            //计算完 b0=128  【1000 0000】
        }
        //RSV1, RSV2, RSV3：各占1个比特 正常全为0，属于扩展字段

        //msg.rsv() % 8 任何int摸8都返回小于8的数 二进制位<=[0000 0111]
        //<< 4 左移4位得到 [0111 0000]，这里假设的是rsv不为0的情况。
        //实际情况rsv是0，那么得到【0000 0000]
        b0 |= msg.rsv() % 8 << 4; //b0 |= 0  值没变还是128[1000 0000]

        //opcode % 128 值不变
        //我们假设opcode= 0x1; //文本帧  0000 0001
        b0 |= opcode % 128; //那么  bo |= 0x1 得到 [1000 0001]

        //                                   Fin    RSV  opcode
        //所以websocket第一个比特位已经得到 = 【 1     000    0001  】
        if (opcode == OPCODE_PING && length > 125) {
            throw new TooLongFrameException("invalid payload for PING (payload length must be <= 125, was "
                    + length);
        }

        //是否释放bytebuf的标记位
        boolean release = true;
        ByteBuf buf = null;
        try {
            //是否需要掩码，如果需要则需要4个字节的位置
            int maskLength = maskPayload ? 4 : 0;
            //数据的长度125之内
            if (length <= 125) {
                //size= 2+掩码的长度(如果有掩码，没有为0)
                //数据长度<=125，ws头2个字节+掩码长度即可
                int size = 2 + maskLength;
                //如果需要掩码 或者length<=1024
                if (maskPayload || length <= GATHERING_WRITE_THRESHOLD) {
                    //把size的值增大
                    size += length;
                }
                //分配缓冲区(如果maskPayload=true或length<=125，那么size就是websocket的头部长度+数据长度)
                buf = ctx.alloc().buffer(size);
                //写入websocket头的第一个字节：假设[10000001]
                buf.writeByte(b0);

                //websocket头第二个字节： 需要掩码为0x80 | (byte) length，假设长度120，那么得到 [1(需要掩码) 111 1000]
                //如果不需要掩码则得到 [0(不需要掩码)111 1000], 8个比特第一位表示是否需要掩码，其余7位表示长度。
                byte b = (byte) (maskPayload ? 0x80 | (byte) length : (byte) length);
                //写入第二个字节
                buf.writeByte(b);
                //数据长度65535之内

            } else if (length <= 0xFFFF) {
                //size= 4+掩码的长度(如果有掩码，没有为0)
                //数据长度 x>125 ，x<=65535,ws头需要4个字节+掩码长度
                int size = 4 + maskLength;
                //需要掩码 或 长度小于1024
                if (maskPayload || length <= GATHERING_WRITE_THRESHOLD) {
                    size += length;
                }
                //分配缓冲区
                buf = ctx.alloc().buffer(size);
                //写入第一个字节
                buf.writeByte(b0);
                //需要掩码写入【1111 1110】，不需要掩码写入【0111 1110】
                //第一个比特代表掩码，后面7个字节代表长度，写死126表示后续俩个字节为数据的真实长度。
                buf.writeByte(maskPayload ? 0xFE : 126);
                //假设length=3520 二进制为【00000000 00000000 00001101 11000000】
                //length分为俩个字节写入，先右移8位，把高位写入
                //右移8位：length >>> 8 = [00000000 00000000 00000000 00001101] & [11111111] = [00001101]
                buf.writeByte(length >>> 8 & 0xFF);
                //length & 0xFF = [00000000 00000000 00001101 11000000]  & [11111111]  = [11000000]
                //写入低8位
                buf.writeByte(length & 0xFF);
            } else {
                //size= 10+掩码的长度(如果有掩码，没有为0)
                //数据长度x>65535,ws头需要10个字节+掩码长度
                int size = 10 + maskLength;
                if (maskPayload || length <= GATHERING_WRITE_THRESHOLD) {
                    size += length;
                }
                //分配缓冲区
                buf = ctx.alloc().buffer(size);
                //写入第一个ws头字节
                buf.writeByte(b0);
                //写入第二个ws头字节
                //如果需要掩码为[1 1111111],否则为[0 1111111]
                //第一个比特表示掩码，后续7个字全都是1=127固定，表示后续8个字节为数据长度
                buf.writeByte(maskPayload ? 0xFF : 127);
                //写入8个字节为数据长度
                buf.writeLong(length);
            }

            // Write payload
            // 需要掩码的逻辑
            if (maskPayload) {
                //生成随机数作为XOR的KEY
                int random = (int) (Math.random() * Integer.MAX_VALUE);
                //返回字节数组
                mask = ByteBuffer.allocate(4).putInt(random).array();
                //把掩码写入到buf中
                buf.writeBytes(mask);

                //获得字符序列
                ByteOrder srcOrder = data.order();
                ByteOrder dstOrder = buf.order();

                int counter = 0;
                int i = data.readerIndex();
                int end = data.writerIndex();

                //如果字符序列相同
                if (srcOrder == dstOrder) {
                    // Use the optimized path only when byte orders match
                    // Remark: & 0xFF is necessary because Java will do signed expansion from
                    // byte to int which we don't want.
                    //把数组拼接为32位的int形式
                    int intMask = ((mask[0] & 0xFF) << 24)
                                | ((mask[1] & 0xFF) << 16)
                                | ((mask[2] & 0xFF) << 8)
                                | (mask[3] & 0xFF);

                    // If the byte order of our buffers it little endian we have to bring our mask
                    // into the same format, because getInt() and writeInt() will use a reversed byte order
                    //小端序列转换掩码
                    if (srcOrder == ByteOrder.LITTLE_ENDIAN) {
                        intMask = Integer.reverseBytes(intMask);
                    }

                    //每4个字节一组与掩码Key进行XOR运算
                    for (; i + 3 < end; i += 4) {
                        int intData = data.getInt(i);
                        //将结果写入buf
                        buf.writeInt(intData ^ intMask);
                    }
                }
                //不需要掩码才会走这个循环，如果上面需要掩码i的值已经被增加，这里不会循环
                for (; i < end; i++) {
                    //XOR计算
                    byte byteData = data.getByte(i);
                    buf.writeByte(byteData ^ mask[counter++ % 4]);
                }
                //返回buf到底层channel中输出
                out.add(buf);
            } else {
                //不需要掩码的逻辑
                //如果buf缓冲区可写的空间 >=data数据可读的长度，说明buf在创建时size已经包括了length
                if (buf.writableBytes() >= data.readableBytes()) {
                    // merge buffers as this is cheaper then a gathering write if the payload is small enough
                    //把data写入到buf中
                    buf.writeBytes(data);
                    //返回buf写入到底channel中
                    out.add(buf);
                } else {
                    //返回buf写入到底channel中
                    out.add(buf);
                    //返回data写入到底层channel中
                    //计数器必须要增加+，因为在父类中对data进行了释放ReferenceCountUtil.release(cast);
                    //计数器+1后，相当于变成了2，那么在父类中释放一次，在channel用完后会在释放一次。
                    out.add(data.retain());
                }
            }
            //正在情况不释放
            release = false;
        } finally {
            //不出异常的情况不释放buf，由底层使用完毕后释放
            if (release && buf != null) {
                buf.release();
            }
        }
    }
}
