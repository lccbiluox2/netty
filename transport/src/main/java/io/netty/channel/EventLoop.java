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
package io.netty.channel;

import io.netty.util.concurrent.OrderedEventExecutor;

/**
 * Will handle all the I/O operations for a {@link Channel} once registered.
 *
 * One {@link EventLoop} instance will usually handle more than one {@link Channel} but this may depend on
 * implementation details and internals.
 *
 * TODO: 重要的结论，在我们的业务开发中，不要将长时间的耗时任务放入到EventLoop的执行队列中，因为他将会一直阻塞到该线程所对应的所有的
 *       channle上的其他执行任务，如果我们需要进行阻塞调用或者是耗时操作（实际开发中很常见，那么我们需要使用一个专门的EventExecutor
 *       （业务线程池））。
 *       业务线程池的实现方法：
 *       1. 在ChannelHandler的回调方法中，使用自己定义的业务县城次，这样可以实现异步调用。如和Sparing boot结合的时候，事先定义好线程池
 *       2. 借助Netty提供的想ChannelPipline添加ChannelHandler时调用的addLast方法来传递EventExecutor.
 * TODO:    说明
 *       默认情况下（调用addlast）,channelHandler中回调方法都是由IO线程所执行的，如果调用了ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler);
 *       方法，嘛呢ChannelHandler中的毁掉方法由参数中的group线程来执行。
 */
public interface EventLoop extends OrderedEventExecutor, EventLoopGroup {
    @Override
    EventLoopGroup parent();
}
