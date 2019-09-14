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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteOrder;
import java.util.List;

import static io.netty.buffer.ByteBufUtil.readBytes;

/**
 * Decodes a web socket frame from wire protocol version 8 format. This code was forked from <a
 * href="https://github.com/joewalnes/webbit">webbit</a> and modified.
 *
 * WebSocket08FrameDecoder是Netty协议WS协议的解码器。
 */
public class WebSocket08FrameDecoder extends ByteToMessageDecoder
        implements WebSocketFrameDecoder {

    /**
     * 当前解码器状态枚举
     */
    enum State {
        READING_FIRST,
        READING_SECOND,
        READING_SIZE,
        MASKING_KEY,
        PAYLOAD,
        CORRUPT
    }

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocket08FrameDecoder.class);

    //定义opcode
    private static final byte OPCODE_CONT = 0x0;
    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_CLOSE = 0x8;
    private static final byte OPCODE_PING = 0x9;
    private static final byte OPCODE_PONG = 0xA;

    private final WebSocketDecoderConfig config;

    //分片发送的数量
    private int fragmentedFramesCount;
    //当前ws帧是否是完整的
    private boolean frameFinalFlag;
    //当前ws荷载数据是否已经掩码
    private boolean frameMasked;
    //RSV1 RSV2 RSV3
    private int frameRsv;
    //ws帧内 opocde的值
    private int frameOpcode;
    //荷载数据的长度
    private long framePayloadLength;
    //掩码
    private byte[] maskingKey;
    //ws协议PayloadLength表示的长度
    private int framePayloadLen1;
    //是否收到关闭帧
    private boolean receivedClosingHandshake;
    //初始状态
    private State state = State.READING_FIRST;

    /**
     * Constructor
     *
     * @param expectMaskedFrames
     *            Web socket servers must set this to true processed incoming masked payload. Client implementations
     *            must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload. Setting this to an appropriate value for you application
     *            helps check for denial of services attacks.
     */
    public WebSocket08FrameDecoder(boolean expectMaskedFrames, boolean allowExtensions, int maxFramePayloadLength) {
        this(expectMaskedFrames, allowExtensions, maxFramePayloadLength, false);
    }

    /**
     * Constructor
     *
     * @param expectMaskedFrames
     *            Web socket servers must set this to true processed incoming masked payload. Client implementations
     *            must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload. Setting this to an appropriate value for you application
     *            helps check for denial of services attacks.
     * @param allowMaskMismatch
     *            When set to true, frames which are not masked properly according to the standard will still be
     *            accepted.
     */
    public WebSocket08FrameDecoder(boolean expectMaskedFrames, boolean allowExtensions, int maxFramePayloadLength,
                                   boolean allowMaskMismatch) {
        this(WebSocketDecoderConfig.newBuilder()
            .expectMaskedFrames(expectMaskedFrames)
            .allowExtensions(allowExtensions)
            .maxFramePayloadLength(maxFramePayloadLength)
            .allowMaskMismatch(allowMaskMismatch)
            .build());
    }

    /**
     * Constructor
     *
     * @param decoderConfig
     *            Frames decoder configuration.
     */
    public WebSocket08FrameDecoder(WebSocketDecoderConfig decoderConfig) {
        this.config = ObjectUtil.checkNotNull(decoderConfig, "decoderConfig");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Discard all data received if closing handshake was received before.
        //如果已经收到关闭帧，则丢弃说有字节
        if (receivedClosingHandshake) {
            in.skipBytes(actualReadableBytes());
            return;
        }

        switch (state) {
        case READING_FIRST:
            if (!in.isReadable()) {
                return;
            }

            //把荷载数据长度设置为0
            framePayloadLength = 0;

            // FIN, RSV, OPCODE
            //读取ws帧的第一个字节，解析出FIN  RSV OPCODE
            byte b = in.readByte();
            //b & 10000000 得到FIN
            frameFinalFlag = (b & 0x80) != 0;
            //b & 01110000 完了右移4位 得到RSV
            frameRsv = (b & 0x70) >> 4;
            // b & 00001111 得到opcode
            frameOpcode = b & 0x0F;

            if (logger.isTraceEnabled()) {
                logger.trace("Decoding WebSocket Frame opCode={}", frameOpcode);
            }
            //改变状态
            state = State.READING_SECOND;
        case READING_SECOND:
            if (!in.isReadable()) {
                return;
            }
            // MASK, PAYLOAD LEN 1
            //读取ws帧的第二个字节
            b = in.readByte();
            //计算是否需要掩码
            frameMasked = (b & 0x80) != 0;
            //ws协议PayloadLength表示的长度
            framePayloadLen1 = b & 0x7F;

            //如果RSV不为0说明使用了WS扩展协议，allowExtensions如果设置为不允许扩展则报错
            //目前RSV都为0，还没有扩展协议
            if (frameRsv != 0 && !config.allowExtensions()) {
                protocolViolation(ctx, in, "RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                return;
            }

            //如果不允许缺失掩码  并且 客户端又没有掩码 则报错
            if (!config.allowMaskMismatch() && config.expectMaskedFrames() != frameMasked) {
                protocolViolation(ctx, in, "received a frame that is not masked as expected");
                return;
            }

            //如果opcpde为一个控制帧 如果 ping pong close
            if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                // control frames MUST NOT be fragmented
                //控制帧必须是一个完整的帧，所有frameFinalFlag必须为true
                if (!frameFinalFlag) {
                    protocolViolation(ctx, in, "fragmented control frame");
                    return;
                }

                // control frames MUST have payload 125 octets or less
                //控制帧framePayload必须小于等于125
                if (framePayloadLen1 > 125) {
                    protocolViolation(ctx, in, "control frame with payload length > 125 octets");
                    return;
                }

                // check for reserved control frame opcodes
                //控制帧目前只能是close ping pong，其它目前ws还未定义，出现则报错
                if (!(frameOpcode == OPCODE_CLOSE || frameOpcode == OPCODE_PING
                      || frameOpcode == OPCODE_PONG)) {
                    protocolViolation(ctx, in, "control frame using reserved opcode " + frameOpcode);
                    return;
                }

                // close frame : if there is a body, the first two bytes of the
                // body MUST be a 2-byte unsigned integer (in network byte
                // order) representing a getStatus code
                //关闭帧framePayloadLen1必为0，不能携带数据
                if (frameOpcode == 8 && framePayloadLen1 == 1) {
                    protocolViolation(ctx, in, "received close control frame with payload len 1");
                    return;
                }
            } else { // data frame
                // check for reserved data frame opcodes
                // 小于7的都是数据帧
                // %x0：表示一个延续帧。当Opcode为0时，表示本次数据传输采用了数据分片，当前收到的数据帧为其中一个数据分片。
                // %x1：表示这是一个文本帧（frame）
                // %x2：表示这是一个二进制帧（frame）
                // check for reserved data frame opcodes
                // 目前只支持这三种帧，其它抛出异常
                if (!(frameOpcode == OPCODE_CONT || frameOpcode == OPCODE_TEXT
                      || frameOpcode == OPCODE_BINARY)) {
                    protocolViolation(ctx, in, "data frame using reserved opcode " + frameOpcode);
                    return;
                }

                // 如果是延续帧，那前面必须有一个Text或Binary帧，通过fragmentedFramesCount>0来判断
                // check opcode vs message fragmentation state 1/2
                if (fragmentedFramesCount == 0 && frameOpcode == OPCODE_CONT) {
                    protocolViolation(ctx, in, "received continuation data frame outside fragmented message");
                    return;
                }

                // check opcode vs message fragmentation state 2/2
                // 如果fragmentedFramesCount != 0 说明前面出现了text或binary帧，并且fin为false 指示后续还有数据
                // 但是frameOpcode又不是一个延续帧，说明出现混乱情况报错
                // 我觉得frameOpcode != OPCODE_PING是一个无效的判断
                if (fragmentedFramesCount != 0 && frameOpcode != OPCODE_CONT && frameOpcode != OPCODE_PING) {
                    protocolViolation(ctx, in,
                                      "received non-continuation data frame while inside fragmented message");
                    return;
                }
            }

            //修改状态
            state = State.READING_SIZE;
        case READING_SIZE:

            // Read frame payload length
            // 如果payload length=126 后续2个字节是荷载数据的长度
            if (framePayloadLen1 == 126) {
                if (in.readableBytes() < 2) {
                    return;
                }
                // 读2个字节，按无符号处理
                framePayloadLength = in.readUnsignedShort();
                if (framePayloadLength < 126) {
                    protocolViolation(ctx, in, "invalid data frame length (not using minimal length encoding)");
                    return;
                }
                //127 后续8个字节是何在数据的长度
            } else if (framePayloadLen1 == 127) {
                if (in.readableBytes() < 8) {
                    return;
                }
                //读取8个字节为数据长度
                framePayloadLength = in.readLong();
                // TODO: check if it's bigger than 0x7FFFFFFFFFFFFFFF, Maybe
                // just check if it's negative?

                if (framePayloadLength < 65536) {
                    protocolViolation(ctx, in, "invalid data frame length (not using minimal length encoding)");
                    return;
                }
            } else {
                // payload length<125 说明framePayloadLen1本身就表示数据长度
                framePayloadLength = framePayloadLen1;
            }

            // 如果荷载数据的长度 大于阈值，抛出异常
            if (framePayloadLength > config.maxFramePayloadLength()) {
                protocolViolation(ctx, in, WebSocketCloseStatus.MESSAGE_TOO_BIG,
                    "Max frame length of " + config.maxFramePayloadLength() + " has been exceeded.");
                return;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("Decoding WebSocket Frame length={}", framePayloadLength);
            }

            // 转换状态
            state = State.MASKING_KEY;
        case MASKING_KEY:
            // 是否有掩码
            if (frameMasked) {
                if (in.readableBytes() < 4) {
                    return;
                }
                // 读取4个字节，读取掩码
                if (maskingKey == null) {
                    maskingKey = new byte[4];
                }
                in.readBytes(maskingKey);
            }
            //转换状态
            state = State.PAYLOAD;
        case PAYLOAD:
            //可读数据达不到荷载数据长度则等待下一轮事件
            if (in.readableBytes() < framePayloadLength) {
                return;
            }

            ByteBuf payloadBuffer = null;
            try {
                //将荷载数据读到新的缓冲区中
                payloadBuffer = readBytes(ctx.alloc(), in, toFrameLength(framePayloadLength));

                // Now we have all the data, the next checkpoint must be the next
                // frame
                //切换状态为初始状态，进行下一轮读取。
                state = State.READING_FIRST;

                // Unmask data if needed
                //如果有掩码，需要进行XOR二次计算还原出原文
                if (frameMasked) {
                    unmask(payloadBuffer);
                }

                // Processing ping/pong/close frames because they cannot be
                // fragmented
                //根据情况封装不同数据帧
                if (frameOpcode == OPCODE_PING) {
                    out.add(new PingWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                    payloadBuffer = null;
                    return;
                }
                if (frameOpcode == OPCODE_PONG) {
                    out.add(new PongWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                    payloadBuffer = null;
                    return;
                }
                if (frameOpcode == OPCODE_CLOSE) {
                    //如果是对方发的Close帧则关闭socket
                    receivedClosingHandshake = true;
                    checkCloseFrameBody(ctx, payloadBuffer);
                    out.add(new CloseWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                    payloadBuffer = null;
                    return;
                }

                // Processing for possible fragmented messages for text and binary
                // frames
                if (frameFinalFlag) {
                    //如果是最终的分片则fragmentedFramesCount=0
                    // Final frame of the sequence. Apparently ping frames are
                    // allowed in the middle of a fragmented message
                    if (frameOpcode != OPCODE_PING) {
                        fragmentedFramesCount = 0;
                    }
                } else {
                    // Increment counter
                    //否则fragmentedFramesCount++
                    fragmentedFramesCount++;
                }

                // Return the frame
                // 返回各种帧
                if (frameOpcode == OPCODE_TEXT) {
                    out.add(new TextWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                    payloadBuffer = null;
                    return;
                } else if (frameOpcode == OPCODE_BINARY) {
                    out.add(new BinaryWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                    payloadBuffer = null;
                    return;
                } else if (frameOpcode == OPCODE_CONT) {
                    out.add(new ContinuationWebSocketFrame(frameFinalFlag, frameRsv,
                                                           payloadBuffer));
                    payloadBuffer = null;
                    return;
                } else {
                    throw new UnsupportedOperationException("Cannot decode web socket frame with opcode: "
                                                            + frameOpcode);
                }
            } finally {
                //释放缓冲区，如果payloadBuffer！=null 说明没有成功返回数据帧
                if (payloadBuffer != null) {
                    payloadBuffer.release();
                }
            }
        case CORRUPT:
            if (in.isReadable()) {
                // If we don't keep reading Netty will throw an exception saying
                // we can't return null if no bytes read and state not changed.
                in.readByte();
            }
            return;
        default:
            throw new Error("Shouldn't reach here.");
        }
    }

    private void unmask(ByteBuf frame) {
        int i = frame.readerIndex();
        int end = frame.writerIndex();

        ByteOrder order = frame.order();

        // Remark: & 0xFF is necessary because Java will do signed expansion from
        // byte to int which we don't want.
        // 把掩码二进制数组转换为int
        int intMask = ((maskingKey[0] & 0xFF) << 24)
                    | ((maskingKey[1] & 0xFF) << 16)
                    | ((maskingKey[2] & 0xFF) << 8)
                    | (maskingKey[3] & 0xFF);

        // If the byte order of our buffers it little endian we have to bring our mask
        // into the same format, because getInt() and writeInt() will use a reversed byte order
        // 如果是小端序，需要把INT类型的掩码反转
        if (order == ByteOrder.LITTLE_ENDIAN) {
            intMask = Integer.reverseBytes(intMask);
        }

        // XOR运算，还原原始值
        for (; i + 3 < end; i += 4) {
            int unmasked = frame.getInt(i) ^ intMask;
            frame.setInt(i, unmasked);
        }
        for (; i < end; i++) {
            frame.setByte(i, frame.getByte(i) ^ maskingKey[i % 4]);
        }
    }

    //抛出异常
    private void protocolViolation(ChannelHandlerContext ctx, ByteBuf in, String reason) {
        protocolViolation(ctx, in, WebSocketCloseStatus.PROTOCOL_ERROR, reason);
    }

    private void protocolViolation(ChannelHandlerContext ctx, ByteBuf in, WebSocketCloseStatus status, String reason) {
        protocolViolation(ctx, in, new CorruptedWebSocketFrameException(status, reason));
    }

    // 抛出异常，关闭socket
    private void protocolViolation(ChannelHandlerContext ctx, ByteBuf in, CorruptedWebSocketFrameException ex) {
        state = State.CORRUPT;
        int readableBytes = in.readableBytes();
        if (readableBytes > 0) {
            // Fix for memory leak, caused by ByteToMessageDecoder#channelRead:
            // buffer 'cumulation' is released ONLY when no more readable bytes available.
            in.skipBytes(readableBytes);
        }
        if (ctx.channel().isActive() && config.closeOnProtocolViolation()) {
            Object closeMessage;
            if (receivedClosingHandshake) {
                closeMessage = Unpooled.EMPTY_BUFFER;
            } else {
                WebSocketCloseStatus closeStatus = ex.closeStatus();
                String reasonText = ex.getMessage();
                if (reasonText == null) {
                    reasonText = closeStatus.reasonText();
                }
                closeMessage = new CloseWebSocketFrame(closeStatus, reasonText);
            }
            ctx.writeAndFlush(closeMessage).addListener(ChannelFutureListener.CLOSE);
        }
        throw ex;
    }

    private static int toFrameLength(long l) {
        if (l > Integer.MAX_VALUE) {
            throw new TooLongFrameException("Length:" + l);
        } else {
            return (int) l;
        }
    }

    /** */
    protected void checkCloseFrameBody(
            ChannelHandlerContext ctx, ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return;
        }
        if (buffer.readableBytes() == 1) {
            protocolViolation(ctx, buffer, WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid close frame body");
        }

        // Save reader index
        int idx = buffer.readerIndex();
        buffer.readerIndex(0);

        // Must have 2 byte integer within the valid range
        int statusCode = buffer.readShort();
        if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
            protocolViolation(ctx, buffer, "Invalid close frame getStatus code: " + statusCode);
        }

        // May have UTF-8 message
        if (buffer.isReadable()) {
            try {
                new Utf8Validator().check(buffer);
            } catch (CorruptedWebSocketFrameException ex) {
                protocolViolation(ctx, buffer, ex);
            }
        }

        // Restore reader index
        buffer.readerIndex(idx);
    }
}
