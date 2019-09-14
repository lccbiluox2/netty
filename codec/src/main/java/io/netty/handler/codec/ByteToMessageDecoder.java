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
package io.netty.handler.codec;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * {@link ChannelInboundHandlerAdapter}以类似流的方式将字节从一个{@link ByteBuf}解码为另一个消息类型。
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * 例如，这里有一个实现，它从输入{@link ByteBuf}读取所有可读字节，并创建一个新的{@link ByteBuf}。
 *
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>  帧检测
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 *
 * 通常，通过添加{@link DelimiterBasedFrameDecoder}、{@link FixedLengthFrameDecoder}、{@link LengthFieldBasedFrameDecoder}
 * 或{@link LineBasedFrameDecoder}，可以在管道的早期处理帧检测。
 *
 * TODO：为什么要进行帧检测呢？
 *      因为这个涉及TCP的粘包和拆包，
 *
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 *
 * 如果需要自定义帧解码器，那么在使用{@link ByteToMessageDecoder}实现自定义帧解码器时需要小心。通过检查{@link ByteBuf#readableBytes()}，
 * 确保缓冲区中有足够的字节来保存完整的帧。如果一个完整的帧没有足够的字节，那么直接返回，不要修改reader索引，以允许读取更多的字节。
 *
 * TODO:为什么这么说呢？
 *      因为假设你要传输的是一个int，占用4个字节，但是你的buffer只有3个字节，那么是不够的，因此readInt肯定是错误的。
 *
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 *
 * 要在不修改reader索引的情况下检查完整的帧，可以使用{@link ByteBuf#getInt(int)}（getInde是一个绝对方法）之类的方法。当使用像{@link ByteBuf#getInt(int)}
 * 这样的方法时，一个必须使用reader索引。例如，在 in.getInt(0) 中调用是假设帧从缓冲区的开始处开始，但并不总是这样。使用
 * in.getInt(in.readerIndex())。
 *
 * <h3>Pitfalls</h3>
 * TODO: 陷阱
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 *
 * 注意，{@link ByteToMessageDecoder} 的子类不能带{@link @Sharable}注释。
 * TODO: 什么是Sharable呢？
 *       意味着多个管道不能共享一个实例。多个管道意味着是多个线程共享。
 *
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 *
 * 一些方法，如{@link ByteBuf#readBytes(int)}，如果返回的缓冲区没有被释放或添加到out {@link List}，将导致内存泄漏。使用派生缓冲区，
 * 如{@link ByteBuf#readSlice(int)}，以避免内存泄漏。
 *
 * 继承ChannelInboundHandlerAdapter，处理入站事件
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                // 空间不足或有被引用或只读时，需要扩展（通过替换它）
                // 1.累积区容量不够容纳数据
                // 2.用户使用了slice().retain()或duplicate().retain()使refCnt增加
                //这个逻辑cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                //转换为这个逻辑好理解in.readableBytes() > cumulation.maxCapacity() - in.writerIndex()
                //条件1、cumulation的容量不够存储in的可读字节
                //条件2、cumulation的引用数大于1，即用户调用了retain()方法
                //条件3、cumulation变为只读的
                if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                    || cumulation.refCnt() > 1 || cumulation.isReadOnly()) {
                    // Expand cumulation (by replace it) when either there is not more room in the buffer
                    // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                    // duplicate().retain() or if its read-only.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    // 扩展缓冲区
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                } else {
                    //将cumulation赋值到一个临时变量
                    buffer = cumulation;
                }
                // 写入本次的消息，把in的字节写入到buffer中并返回
                buffer.writeBytes(in);
                return buffer;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                // 写入后释放
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     *
     * 通过将它们添加到{@link CompositeByteBuf}中来累积{@link ByteBuf}，因此尽可能不进行内存复制。注意{@link CompositeByteBuf}
     * 使用更复杂的索引实现，因此根据您的用例和解码器实现，这可能比使用{@link #MERGE_CUMULATOR}要慢。
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                if (cumulation.refCnt() > 1) {
                    // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the
                    // user use slice().retain() or duplicate().retain().
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    if (cumulation instanceof CompositeByteBuf) {
                        composite = (CompositeByteBuf) cumulation;
                    } else {
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    composite.addComponent(true, in);
                    in = null;
                    buffer = composite;
                }
                return buffer;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak if
                    // writeBytes(...) throw for whatever release (for example because of OutOfMemoryError).
                    in.release();
                }
            }
        }
    };

    private static final byte STATE_INIT = 0;
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    /**
     * 用来保存累计读取到的字节
     */
    ByteBuf cumulation;
    /** 累积器 */
    private Cumulator cumulator = MERGE_CUMULATOR;
    /** 设置为true后每个channelRead事件只解码出一个结果 某些特殊协议使用 **/
    private boolean singleDecode;
    /** 是否首个消息 **/
    private boolean first;

    /**
     * This flag is used to determine if we need to call {@link ChannelHandlerContext#read()} to consume more data
     * when {@link ChannelConfig#isAutoRead()} is {@code false}.
     *
     * 这个标志用于确定当{@link ChannelConfig#isAutoRead()}为{@code false}时，是否需要调用{@link ChannelConfig#isAutoRead()}
     * 来消耗更多数据。
     */
    private boolean firedChannelRead;

    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     */
    private byte decodeState = STATE_INIT;
    /** 累积区不丢弃字节的最大次数，16次后开始丢弃 */
    private int discardAfterReads = 16;
    /** 累积区不丢弃字节的channelRead次数 */
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // 是否正在解码中
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            // 正在解码中就把状态标志为STATE_HANDLER_REMOVED_PENDING，表示要销毁数据，等解码线程解完码后销毁数据，自己直接返回
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        //累加区有数据
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            // 释放累积区，GC回收
            cumulation = null;
            numReads = 0;
            // 缓冲区还有数据
            int readable = buf.readableBytes();
            if (readable > 0) {
                // 把缓冲区的数据全部读取出来，释放资源，发布channelRead事件
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                // 解码器已被删除故不再解码，只将数据传播到下一个Handler
                ctx.fireChannelRead(bytes);
                ctx.fireChannelReadComplete();
            } else {
                buf.release();
            }
        }
        // 空方法，子类可以覆盖,用户可进行的自定义处理
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 只处理ByteBuf类型的msg，其他透传
        if (msg instanceof ByteBuf) {
            // 解码结果列表，创建一个List,它实现了AbstractList接口.
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                ByteBuf data = (ByteBuf) msg;
                // 通过cumulation是否为空判断解码器是否缓存了没有解码完成的半包消息
                // 如果为空说明是首次解码或者最近一次已经处理完了半包消息
                first = cumulation == null; // 累积区为空表示首次解码
                if (first) {
                    // cumulation为空直接赋值，首次解码直接使用读入的ByteBuf作为累积区
                    cumulation = data;
                } else {
                    // cumulation不为空需要积累本次消息 ，非首次需要进行字节数据累积
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                // 调用解码方法
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                // 判断如果cumulation不为空且已经读取完毕，则释放cumulation
                if (cumulation != null && !cumulation.isReadable()) {
                    // 此时累积区不再有字节数据，已被处理完毕
                    numReads = 0;
                    cumulation.release();
                    cumulation = null;

                //读取次数达到一定阈值16次,则丢西已经度过的字节，释放一些空间
                } else if (++ numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275

                    // 连续discardAfterReads次后，累积区还有字节数据，此时丢弃一部分数据
                    numReads = 0;
                    // 读取了足够的数据，尝试丢弃一些字节，避免OOM风险
                    discardSomeReadBytes();
                }

                int size = out.size();
                // 本次没有解码出数据，此时size=0
                firedChannelRead |= out.insertSinceRecycled();
                // 触发事件，把解析出来的消息循环调用下一个handler
                fireChannelRead(ctx, out, size);
                // 回收数组，清除它并清空内部存储的所有entry
                out.recycle();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     *
     * 把消息循环调用到下一个handler
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     * 从{@link CodecOutputList}中获取{@code numElements}，并通过管道转发它们。
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 连续读次数置0
        numReads = 0;
        // 丢弃已读数据，节约内存
        discardSomeReadBytes();
        if (!firedChannelRead && !ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
        firedChannelRead = false;
        ctx.fireChannelReadComplete();
    }

    /**
     * 释放一些空间
     */
    protected final void discardSomeReadBytes() {
        //只有在refCnt()引用次数为1时才释放，否则可以子类正在使用已经读取过的字节.
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // 如果可能的话，丢弃一些字节以便在缓冲区中腾出更多空间，但是只有当refCnt == 1时才这样做，否则用户可能使用slice().retain()
            // 或duplicate().retain()。
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    /**
     * 连接断开处理逻辑
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                //释放资源
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                //调用下一个handler
                int size = out.size();
                fireChannelRead(ctx, out, size);
                //触发ChannelReadComplete事件
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                //触发ChannelInactive事件
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            //调用解码逻辑
            callDecode(ctx, cumulation, out);
            //调用解码逻辑-子类可以释放资源
            decodeLast(ctx, cumulation, out);
        } else {
            //传入对象-子类释放资源
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     *
     * 解码逻辑
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            //如果in还有可读字节则循环
            while (in.isReadable()) {
                int outSize = out.size();

                //如果out内有数据则调用下一个handle然后清空out
                if (outSize > 0) {
                    // 解码出消息就立即处理，防止消息等待
                    fireChannelRead(ctx, out, outSize);
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    // 如果ChannelHandlerContext已经移除，直接退出循环，继续操作缓冲区是不安全的
                    // 用户主动删除该Handler，继续操作in是不安全的
                    if (ctx.isRemoved()) {
                        break;
                    }
                    outSize = 0;
                }

                //拿到in的可读字节长度
                int oldInputLength = in.readableBytes();
                // 解码 子类需要实现的具体解码步骤
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                // 同样的检查操作, 用户主动删除该Handler，继续操作in是不安全的
                if (ctx.isRemoved()) {
                    break;
                }

                // 能运行到这说明outSize >0,即已经解码出数据了
                // 可读索引不变，说明自定义的decode有问题，所以抛出一个异常
                // 此时outSize都==0（这的代码容易产生误解 应该直接使用0）
                if (outSize == out.size()) {
                    //如果可读字节在交给子类读取后没有变化则结束循环,否则进行下一轮循环
                    if (oldInputLength == in.readableBytes()) {
                        // 没有读取出任何数据，没有消费ByteBuf,说明是个半包消息，需要继续读取后面的数据报文，退出循环
                        break;
                    } else {
                        // 已经读取部分数据，但数据还不够解码，继续读取
                        continue;
                    }
                }

                // 没有消费ByteBuf,out的长度却变了（解码出了一个或多个对象），这种情况认为是非法的
                // 运行到这里outSize>0 说明已经解码出消息
                // 如果解析出新消息，但是可读字节长度没变说明解析又错误
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                // 如果是单条消息解码器，则第一次解码完成之后就退出循环
                // 用户设定一个channelRead事件只解码一次
                //是否只解析1次
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * 从一个{@link ByteBuf}解码到另一个{@link ByteBuf}。该方法将被调用，直到从该方法返回的输入{@link ByteBuf}没有任何可读内容，
     * 或者直到从输入{@link ByteBuf}没有任何可读内容。
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     *                      {@link ByteToMessageDecoder}所属的{@link ChannelHandlerContext}
     * @param in            the {@link ByteBuf} from which to read data
     *                      用来读取数据的{@link ByteBuf}
     * @param out           the {@link List} to which decoded messages should be added
     *                      应该向其中添加解码消息的{@link列表}，为什么是object类型呢？因为nettya不知道你的数据类型。
     * @throws Exception    is thrown if an error occurs  如果发生错误，是否引发
     *
     * 子类解析ByteBuf，将解析出的对象放入out中.
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     *
     * 子类解析
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        // 记录解码器正在解码状态，防止解码过程中另一个线程调用handlerRemoved(ctx)销毁数据
        // 把状态设置为STATE_CALLING_CHILD_DECODE=表示子类正在解析中
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            // 调用子类解码器的decode方法，将ByteBuf解码成对象集合
            decode(ctx, in, out);
        } finally {
            // decodeState == STATE_HANDLER_REMOVED_PENDING 表示在解码过程中，有另外的线程把ctx移除了
            // 这里需要由当前线程调用handlerRemoved(ctx)来完成数据销毁
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            //把状态设置为初始状态
            decodeState = STATE_INIT;
            if (removePending) {
                //调用移除逻辑
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     *
     * 当{@link ChannelHandlerContext}处于活动状态时，最后一次调用。这意味着{@link #channelInactive(ChannelHandlerContext)}
     * 被触发。
     *
     * 默认情况下，这将只调用 {@link #decode(ChannelHandlerContext, ByteBuf, List)} ，但是子类可能会覆盖它来执行一些特殊的清理操作。
     *
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        //cumulation赋值到临时变量
        ByteBuf oldCumulation = cumulation;
        // 增加容量分配新的缓冲区（其实就是扩容了)
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        // 写入旧数据,把原来旧的oldCumulation内字节进入到cumulation当中并返回
        cumulation.writeBytes(oldCumulation);
        // 写入完成之后释放旧的缓冲区
        oldCumulation.release();
        return cumulation;
    }

    /**
     * 累计器，把从channel获取到的字节累计起来
     *
     * 两个ByteBuf参数`cumulation`指已经累积的字节数据，`in`表示该次`channelRead()`读取到的新数据。返回ByteBuf为累积数据后的新累积区
     * （必要时候自动扩容）
     *
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         *
         * 累积给定的{@link ByteBuf}并返回包含累积字节的{@link ByteBuf}。该实现负责正确处理给定的{@link ByteBuf}的生命周期，
         * 因此，如果一个{@link ByteBuf}被完全使用，则调用{@link ByteBuf#release()}。
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
