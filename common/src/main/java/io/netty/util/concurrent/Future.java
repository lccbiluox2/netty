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
package io.netty.util.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;


/**
 * The result of an asynchronous operation.
 * 异步操作的结果.
 *
 * TODO: 问题：netty为什么会重写了Java的Future方法，并且增加新的方法呢？
 *  因为future的get方法是一个尴尬的方法，因为这个方法调用会一直阻塞到得到结果，一旦调用就会阻塞，而且我们不知道什么时候去调用这个方法。
 *  netty的listener方法就在一部分程度上解决了这个问题。
 *
 * TODO： JDK的Future有什么作用？与Netty有什么区别？
 *      JDK提供的Future只能通过手工的方法检查执行结果，而这个操作是阻塞的，Netty则对ChannleFuture进行了增强，通过channelFutureListener
 *      以回调的方法来获取执行结果，去除手工检查阻塞的操作，值得注意的是：channelFutureListener的OperationComplete方法是由IO线程执行的，
 *      因此要注意的是不要在这里执行耗时的操作，或者需要通过另外的线程池来执行。
 *
 *  这里画个图来说明：
 *                                                  ---addListner->  listnenerA
 *  channelFuture继承了future对象。  <-   Future --->
 *                                                  ---addListner->  listnenerB
 *  如果任务执行完了，那么Future会调用注册在Future上的每一个listener，然后调用 hannelFutureListener的operationComplete方法，并且
 *  把future自己这个对象传递给每个future,listener拿到这个future,就可以拿到关联的channel，就可以对channnle进行任何操作。
 *
 *  TODO：Future如何知道channel中的任务是完成了呢？然后调用他们的方法呢？
 *      那么就要说说 promise这个接口了。这个接口是可写的而且只能写一次，不管成功还是失败。
 *      promise ： 中文是程诺的意思。
 *
 */
@SuppressWarnings("ClassNameSameAsAncestorName")
public interface Future<V> extends java.util.concurrent.Future<V> {

    /**
     * Returns {@code true} if and only if the I/O operation was completed
     * successfully.
     * TODO: 如果 IO 操作成功完成，返回 true,isSuccess是isDone方法的一个特例。
     */
    boolean isSuccess();

    /**
     * returns {@code true} if and only if the operation can be cancelled via {@link #cancel(boolean)}.
     *
     * 当且仅当操作可以取消，返回 true
     */
    boolean isCancellable();

    /**
     * Returns the cause of the failed I/O operation if the I/O operation has
     * failed.
     *
     * @return the cause of the failure.
     *         {@code null} if succeeded or this future is not
     *         completed yet.
     *
     * 如果 IO 操作失败，返回 IO 操作失败的原因
     * 如果操作成功或者 future 没有完成，返回 null
     *
     */
    Throwable cause();

    /**
     * Adds the specified listener to this future.  The
     * specified listener is notified when this future is
     * {@linkplain #isDone() done}.  If this future is already
     * completed, the specified listener is notified immediately.
     *
     * 将指定的侦听器添加到此 future, 当这个 future 处于 isDone() 状态的时候指定的监听器会接到通知
     * 如果这个 future 已经完成后，指定的侦听器会立即收到通知
     *
     * 这里实际用到了观察者模式
     */
    Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    /**
     * Adds the specified listeners to this future.  The
     * specified listeners are notified when this future is
     * {@linkplain #isDone() done}.  If this future is already
     * completed, the specified listeners are notified immediately.
     *
     * 将指定的多个侦听器添加到此 future, 当这个 future 处于 isDone() 状态的时候指定的多个监听器都会接到通知
     * 如果这个 future 已经完成后，指定的多个侦听器会立即都收到通知
     */
    Future<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    /**
     * Removes the first occurrence of the specified listener from this future.
     * The specified listener is no longer notified when this
     * future is {@linkplain #isDone() done}.  If the specified
     * listener is not associated with this future, this method
     * does nothing and returns silently.
     */
    Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    /**
     * Removes the first occurrence for each of the listeners from this future.
     * The specified listeners are no longer notified when this
     * future is {@linkplain #isDone() done}.  If the specified
     * listeners are not associated with this future, this method
     * does nothing and returns silently.
     */
    Future<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    /**
     * Waits for this future until it is done, and rethrows the cause of the failure if this future
     * failed.
     *
     * 等待这个 Future，直到完成为止，如果这个 Future 失败了，就会重新抛出失败的原因。
     */
    Future<V> sync() throws InterruptedException;

    /**
     * Waits for this future until it is done, and rethrows the cause of the failure if this future
     * failed.
     *
     * 等待这个 Future，直到完成为止，如果这个 Future 失败了，就会重新抛出失败的原因。
     */
    Future<V> syncUninterruptibly();

    /**
     * Waits for this future to be completed.
     *
     * @throws InterruptedException
     *         if the current thread was interrupted
     *
     * 等待future 完成
     */
    Future<V> await() throws InterruptedException;

    /**
     * Waits for this future to be completed without
     * interruption.  This method catches an {@link InterruptedException} and
     * discards it silently.
     */
    Future<V> awaitUninterruptibly();

    /**
     * Waits for this future to be completed within the
     * specified time limit.
     *
     * @return {@code true} if and only if the future was completed within
     *         the specified time limit
     *
     * @throws InterruptedException
     *         if the current thread was interrupted
     */
    boolean await(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Waits for this future to be completed within the
     * specified time limit.
     *
     * @return {@code true} if and only if the future was completed within
     *         the specified time limit
     *
     * @throws InterruptedException
     *         if the current thread was interrupted
     */
    boolean await(long timeoutMillis) throws InterruptedException;

    /**
     * Waits for this future to be completed within the
     * specified time limit without interruption.  This method catches an
     * {@link InterruptedException} and discards it silently.
     *
     * @return {@code true} if and only if the future was completed within
     *         the specified time limit
     */
    boolean awaitUninterruptibly(long timeout, TimeUnit unit);

    /**
     * Waits for this future to be completed within the
     * specified time limit without interruption.  This method catches an
     * {@link InterruptedException} and discards it silently.
     *
     * @return {@code true} if and only if the future was completed within
     *         the specified time limit
     */
    boolean awaitUninterruptibly(long timeoutMillis);

    /**
     * Return the result without blocking. If the future is not done yet this will return {@code null}.
     *
     * As it is possible that a {@code null} value is used to mark the future as successful you also need to check
     * if the future is really done with {@link #isDone()} and not rely on the returned {@code null} value.
     *
     * 没有阻塞的返回结果，如果 future 没有完成，那么将会返回空。
     *
     * 不要依赖null值去判断是否完成，因为结果可能就是返回null，因此你需要调用 isDone 方法去判断。因为 Runable 永远返回 null.
     */
    V getNow();

    /**
     * {@inheritDoc}
     *
     * If the cancellation was successful it will fail the future with a {@link CancellationException}.
     */
    @Override
    boolean cancel(boolean mayInterruptIfRunning);
}
