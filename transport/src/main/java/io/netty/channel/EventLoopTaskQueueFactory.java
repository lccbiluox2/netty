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
package io.netty.channel;

import java.util.Queue;

/**
 * Factory used to create {@link Queue} instances that will be used to store tasks for an {@link EventLoop}.
 *
 * Generally speaking the returned {@link Queue} MUST be thread-safe and depending on the {@link EventLoop}
 * implementation must be of type {@link java.util.concurrent.BlockingQueue}.
 *
 * 工厂用于创建{@link Queue}实例，该实例将用于存储{@link EventLoop}的任务。
 *
 * 一般来说，返回的{@link Queue}必须是线程安全的，并且根据{@link EventLoop}实现的类型必须是{@link
 * java.util.concurrent.BlockingQueue}。
 */
public interface EventLoopTaskQueueFactory {

    /**
     * Returns a new {@link Queue} to use.
     * @param maxCapacity the maximum amount of elements that can be stored in the {@link Queue} at a given point
     *                    in time.
     * @return the new queue.
     */
    Queue<Runnable> newTaskQueue(int maxCapacity);
}
