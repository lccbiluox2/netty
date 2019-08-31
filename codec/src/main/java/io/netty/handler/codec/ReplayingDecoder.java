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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.Signal;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * A specialized variation of {@link ByteToMessageDecoder} which enables implementation
 * of a non-blocking decoder in the blocking I/O paradigm.
 *
 * {@link ByteToMessageDecoder}的一种特殊变体，它支持在阻塞I/O范式中实现非阻塞译码器。
 *
 * <p>
 * The biggest difference between {@link ReplayingDecoder} and
 * {@link ByteToMessageDecoder} is that {@link ReplayingDecoder} allows you to
 * implement the {@code decode()} and {@code decodeLast()} methods just like
 * all required bytes were received already, rather than checking the
 * availability of the required bytes.  For example, the following
 * {@link ByteToMessageDecoder} implementation:
 *
 * {@link ReplayingDecoder}和{@link ByteToMessageDecoder}之间最大的区别是，{@link ReplayingDecoder}允许您实现{@code decode()}
 * 和{@code decodeLast()}方法，就像已经接收了所有需要的字节一样，而不是检查所需字节的可用性。例如，下面的{@link ByteToMessageDecoder}
 * 实现:
 * TODO: 我们使用ReplayingDecoder的时候，不用判断数据到底在不在，数据够不够。就像我们所需要的数据都已经收到了一样。
 *
 * <pre>
 * public class IntegerHeaderFrameDecoder extends {@link ByteToMessageDecoder} {
 *
 *   {@code @Override}
 *   protected void decode({@link ChannelHandlerContext} ctx,
 *                           {@link ByteBuf} buf, List&lt;Object&gt; out) throws Exception {
 *
 *      // 读取一个整数 如果不够，那么直接返回
 *     if (buf.readableBytes() < 4) {
 *        return;
 *     }
 *
 *     // 标识
 *     buf.markReaderIndex();
 *     // 读取一个整形,相对方法，意味着会移动4个位置
 *     int length = buf.readInt();
 *
 *     // 判断后续的位置是否大于length
 *     if (buf.readableBytes() < length) {
 *        buf.resetReaderIndex();  标志A
 *        return;
 *     }
 *
 *     out.add(buf.readBytes(length));
 *   }
 * }
 *
 * 代码图示：
 * 这个程序是读取一个协议的头部，假设一个协议包含消息头和消息体，因为消息头是固定的假设是一个lint类型，消息体是不固定的，我们想读取数据的
 * 时候，是先读取消息头，获取下面消息体的长度，然后进行读取。
 *
 * | 消息头  |<---------------------------------- 消息体 ---------------->|
 * ====================================================================================================
 * |        |           |           |           |           |           |           |           |      |
 * ====================================================================================================
 *         标志A
 *         重置到这个位置
 *         否则就读取后面的消息体。
 * </pre>
 * is simplified like the following with {@link ReplayingDecoder}:
 * <pre>
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;{@link Void}&gt; {
 *
 *   protected void decode({@link ChannelHandlerContext} ctx,
 *                           {@link ByteBuf} buf) throws Exception {
 *
 *     out.add(buf.readBytes(buf.readInt()));
 *   }
 * }
 * </pre>
 *
 * <h3>How does this work?</h3>
 * TODO : 这是如何实现的呢？
 * <p>
 * {@link ReplayingDecoder} passes a specialized {@link ByteBuf}
 * implementation which throws an {@link Error} of certain type when there's not
 * enough data in the buffer.  In the {@code IntegerHeaderFrameDecoder} above,
 * you just assumed that there will be 4 or more bytes in the buffer when
 * you call {@code buf.readInt()}.  If there's really 4 bytes in the buffer,
 * it will return the integer header as you expected.  Otherwise, the
 * {@link Error} will be raised and the control will be returned to
 * {@link ReplayingDecoder}.  If {@link ReplayingDecoder} catches the
 * {@link Error}, then it will rewind the {@code readerIndex} of the buffer
 * back to the 'initial' position (i.e. the beginning of the buffer) and call
 * the {@code decode(..)} method again when more data is received into the
 * buffer.
 *
 * {@link ReplayingDecoder}传递一个特殊的{@link ByteBuf}实现，当缓冲区中没有足够的数据时，这个实现会抛出一个特定类型的{@link Error}。
 * 在上面的{@code IntegerHeaderFrameDecoder}中，您只是假设在调用{@code buf.readInt()}时，缓冲区中会有4个或更多字节。如果缓冲区中
 * 确实有4个字节，它将像您期望的那样返回整数报头。否则，将引发{@link错误}，并将控件返回给{@link ReplayingDecoder}。如果{@link
 * ReplayingDecoder}捕获了{@link Error}，那么它将把缓冲区的{@code readerIndex}倒回“初始”位置(即缓冲区的开始位置)，并在缓冲区接
 * 收到更多数据时再次调用{@code decode(..)}方法。
 *
 * <p>
 * Please note that {@link ReplayingDecoder} always throws the same cached
 * {@link Error} instance to avoid the overhead of creating a new {@link Error}
 * and filling its stack trace for every throw.
 *
 * <h3>Limitations</h3>
 * TODO : 限制
 * <p>
 * At the cost of the simplicity, {@link ReplayingDecoder} enforces you a few
 * limitations:
 * <ul>
 * <li>Some buffer operations are prohibited.</li>
 * <li>Performance can be worse if the network is slow and the message
 *     format is complicated unlike the example above.  In this case, your
 *     decoder might have to decode the same part of the message over and over
 *     again.</li>
 * <li>You must keep in mind that {@code decode(..)} method can be called many
 *     times to decode a single message.  For example, the following code will
 *     not work:
 *
 *  以简单性为代价，{@link ReplayingDecoder}给你施加了一些限制:
 *  1. 一些缓冲区操作是禁止的。
 *  2. 如果网络速度较慢且消息格式与上面的示例不同，那么性能可能会更差。在这种情况下，您的解码器可能不得不反复解码消息的相同部分。
 *
 * 您必须记住，可以多次调用{@code decode(..)}方法来解码一条消息。例如，以下代码将不起作用:
 *
 * <pre> public class MyDecoder extends {@link ReplayingDecoder}&lt;{@link Void}&gt; {
 *
 *   private final Queue&lt;Integer&gt; values = new LinkedList&lt;Integer&gt;();
 *
 *   {@code @Override}
 *   public void decode(.., {@link ByteBuf} buf, List&lt;Object&gt; out) throws Exception {
 *
 *     // A message contains 2 integers.
 *     values.offer(buf.readInt());
 *     values.offer(buf.readInt());
 *
 *     // This assertion will fail intermittently since values.offer()
 *     // can be called more than two times!
 *     assert values.size() == 2;
 *     out.add(values.poll() + values.poll());
 *   }
 * }</pre>
 *      The correct implementation looks like the following, and you can also
 *      utilize the 'checkpoint' feature which is explained in detail in the
 *      next section.
 * <pre> public class MyDecoder extends {@link ReplayingDecoder}&lt;{@link Void}&gt; {
 *
 *   private final Queue&lt;Integer&gt; values = new LinkedList&lt;Integer&gt;();
 *
 *   {@code @Override}
 *   public void decode(.., {@link ByteBuf} buf, List&lt;Object&gt; out) throws Exception {
 *
 *     // Revert the state of the variable that might have been changed
 *     // since the last partial decode.
 *     values.clear();
 *
 *     // A message contains 2 integers.
 *     values.offer(buf.readInt());
 *     values.offer(buf.readInt());
 *
 *     // Now we know this assertion will never fail.
 *     assert values.size() == 2;
 *     out.add(values.poll() + values.poll());
 *   }
 * }</pre>
 *     </li>
 * </ul>
 *
 * <h3>Improving the performance</h3>
 * <p>
 * Fortunately, the performance of a complex decoder implementation can be
 * improved significantly with the {@code checkpoint()} method.  The
 * {@code checkpoint()} method updates the 'initial' position of the buffer so
 * that {@link ReplayingDecoder} rewinds the {@code readerIndex} of the buffer
 * to the last position where you called the {@code checkpoint()} method.
 *
 * <h4>Calling {@code checkpoint(T)} with an {@link Enum}</h4>
 * <p>
 * Although you can just use {@code checkpoint()} method and manage the state
 * of the decoder by yourself, the easiest way to manage the state of the
 * decoder is to create an {@link Enum} type which represents the current state
 * of the decoder and to call {@code checkpoint(T)} method whenever the state
 * changes.  You can have as many states as you want depending on the
 * complexity of the message you want to decode:
 *
 * <pre>
 * public enum MyDecoderState {
 *   // 读取长度的字段
 *   READ_LENGTH,
 *   // 读取内容的字段
 *   READ_CONTENT;
 * }
 *
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;<strong>MyDecoderState</strong>&gt; {
 *
 *   private int length;
 *
 *   public IntegerHeaderFrameDecoder() {
 *     // Set the initial state.
 *     <strong>super(MyDecoderState.READ_LENGTH);</strong>
 *   }
 *
 *   {@code @Override}
 *   protected void decode({@link ChannelHandlerContext} ctx,
 *                           {@link ByteBuf} buf, List&lt;Object&gt; out) throws Exception {
 *     switch (state()) {
 *     case READ_LENGTH:
 *       // 读取长度的字节
 *       length = buf.readInt();
 *       <strong>checkpoint(MyDecoderState.READ_CONTENT);</strong>
 *     case READ_CONTENT:
 *       // 读取内容
 *       ByteBuf frame = buf.readBytes(length);
 *       <strong>checkpoint(MyDecoderState.READ_LENGTH);</strong>
 *       // 然后将内容，写到输出
 *       out.add(frame);
 *       break;
 *     default:
 *       throw new Error("Shouldn't reach here.");
 *     }
 *   }
 * }
 * </pre>
 *
 * <h4>Calling {@code checkpoint()} with no parameter</h4>
 * <p>
 * An alternative way to manage the decoder state is to manage it by yourself.
 * <pre>
 * public class IntegerHeaderFrameDecoder
 *      extends {@link ReplayingDecoder}&lt;<strong>{@link Void}</strong>&gt; {
 *
 *   <strong>private boolean readLength;</strong>
 *   private int length;
 *
 *   {@code @Override}
 *   protected void decode({@link ChannelHandlerContext} ctx,
 *                           {@link ByteBuf} buf, List&lt;Object&gt; out) throws Exception {
 *     if (!readLength) {
 *       length = buf.readInt();
 *       <strong>readLength = true;</strong>
 *       <strong>checkpoint();</strong>
 *     }
 *
 *     if (readLength) {
 *       ByteBuf frame = buf.readBytes(length);
 *       <strong>readLength = false;</strong>
 *       <strong>checkpoint();</strong>
 *       out.add(frame);
 *     }
 *   }
 * }
 * </pre>
 *
 * <h3>Replacing a decoder with another decoder in a pipeline</h3>
 * 用管道中的另一个解码器替换一个解码器
 * <p>
 * If you are going to write a protocol multiplexer, you will probably want to
 * replace a {@link ReplayingDecoder} (protocol detector) with another
 * {@link ReplayingDecoder}, {@link ByteToMessageDecoder} or {@link MessageToMessageDecoder}
 * (actual protocol decoder).
 * It is not possible to achieve this simply by calling
 * {@link ChannelPipeline#replace(ChannelHandler, String, ChannelHandler)}, but
 * some additional steps are required:
 * <pre>
 * public class FirstDecoder extends {@link ReplayingDecoder}&lt;{@link Void}&gt; {
 *
 *     {@code @Override}
 *     protected void decode({@link ChannelHandlerContext} ctx,
 *                             {@link ByteBuf} buf, List&lt;Object&gt; out) {
 *         ...
 *         // Decode the first message
 *         Object firstMessage = ...;
 *
 *         // Add the second decoder
 *         ctx.pipeline().addLast("second", new SecondDecoder());
 *
 *         if (buf.isReadable()) {
 *             // Hand off the remaining data to the second decoder
 *             out.add(firstMessage);
 *             out.add(buf.readBytes(<b>super.actualReadableBytes()</b>));
 *         } else {
 *             // Nothing to hand off
 *             out.add(firstMessage);
 *         }
 *         // Remove the first decoder (me)
 *         ctx.pipeline().remove(this);
 *     }
 * </pre>
 * @param <S>
 *        the state type which is usually an {@link Enum}; use {@link Void} if state management is
 *        unused
 *        状态类型，通常是{@link Enum};如果状态管理未使用，则使用{@link Void}
 */
public abstract class ReplayingDecoder<S> extends ByteToMessageDecoder {

    static final Signal REPLAY = Signal.valueOf(ReplayingDecoder.class, "REPLAY");

    private final ReplayingDecoderByteBuf replayable = new ReplayingDecoderByteBuf();
    private S state;
    private int checkpoint = -1;

    /**
     * Creates a new instance with no initial state (i.e: {@code null}).
     */
    protected ReplayingDecoder() {
        this(null);
    }

    /**
     * Creates a new instance with the specified initial state.
     */
    protected ReplayingDecoder(S initialState) {
        state = initialState;
    }

    /**
     * Stores the internal cumulative buffer's reader position.
     */
    protected void checkpoint() {
        checkpoint = internalBuffer().readerIndex();
    }

    /**
     * Stores the internal cumulative buffer's reader position and updates
     * the current decoder state.
     */
    protected void checkpoint(S state) {
        checkpoint();
        state(state);
    }

    /**
     * Returns the current state of this decoder.
     * @return the current state of this decoder
     */
    protected S state() {
        return state;
    }

    /**
     * Sets the current state of this decoder.
     * @return the old state of this decoder
     */
    protected S state(S newState) {
        S oldState = state;
        state = newState;
        return oldState;
    }

    @Override
    final void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        try {
            replayable.terminate();
            if (cumulation != null) {
                callDecode(ctx, internalBuffer(), out);
            } else {
                replayable.setCumulation(Unpooled.EMPTY_BUFFER);
            }
            decodeLast(ctx, replayable, out);
        } catch (Signal replay) {
            // Ignore
            replay.expect(REPLAY);
        }
    }

    @Override
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        replayable.setCumulation(in);
        try {
            while (in.isReadable()) {
                int oldReaderIndex = checkpoint = in.readerIndex();
                int outSize = out.size();

                if (outSize > 0) {
                    fireChannelRead(ctx, out, outSize);
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    if (ctx.isRemoved()) {
                        break;
                    }
                    outSize = 0;
                }

                S oldState = state;
                int oldInputLength = in.readableBytes();
                try {
                    decodeRemovalReentryProtection(ctx, replayable, out);

                    // Check if this handler was removed before continuing the loop.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See https://github.com/netty/netty/issues/1664
                    if (ctx.isRemoved()) {
                        break;
                    }

                    if (outSize == out.size()) {
                        if (oldInputLength == in.readableBytes() && oldState == state) {
                            throw new DecoderException(
                                    StringUtil.simpleClassName(getClass()) + ".decode() must consume the inbound " +
                                    "data or change its state if it did not decode anything.");
                        } else {
                            // Previous data has been discarded or caused state transition.
                            // Probably it is reading on.
                            continue;
                        }
                    }
                } catch (Signal replay) {
                    replay.expect(REPLAY);

                    // Check if this handler was removed before continuing the loop.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See https://github.com/netty/netty/issues/1664
                    if (ctx.isRemoved()) {
                        break;
                    }

                    // Return to the checkpoint (or oldPosition) and retry.
                    int checkpoint = this.checkpoint;
                    if (checkpoint >= 0) {
                        in.readerIndex(checkpoint);
                    } else {
                        // Called by cleanup() - no need to maintain the readerIndex
                        // anymore because the buffer has been released already.
                    }
                    break;
                }

                if (oldReaderIndex == in.readerIndex() && oldState == state) {
                    throw new DecoderException(
                           StringUtil.simpleClassName(getClass()) + ".decode() method must consume the inbound data " +
                           "or change its state if it decoded something.");
                }
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
}
