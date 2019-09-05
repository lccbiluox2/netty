/*
 * Copyright 2014 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks if a thread is alive periodically and runs a task when a thread dies.
 * <p>
 * This thread starts a daemon thread to check the state of the threads being watched and to invoke their
 * associated {@link Runnable}s.  When there is no thread to watch (i.e. all threads are dead), the daemon thread
 * will terminate itself, and a new daemon thread will be started again when a new watch is added.
 * </p>
 *
 * @deprecated will be removed in the next major release
 *
 * 检查线程是否处于活动状态，并在线程死亡时运行任务.
 *
 * 这个线程启动一个守护进程线程来检查被监视线程的状态，并调用它们关联的{@link Runnable}。当没有要监视的线程(即所有线程都已死)时，
 * 守护进程线程将自行终止，并且在添加新监视时将重新启动一个新的守护进程线程。
 */
@Deprecated
public final class ThreadDeathWatcher {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ThreadDeathWatcher.class);
    // visible for testing
    static final ThreadFactory threadFactory;

    // Use a MPMC queue as we may end up checking isEmpty() from multiple threads which may not be allowed to do
    // concurrently depending on the implementation of it in a MPSC queue.
    /**
     * 被观察的线程
     * */
    private static final Queue<Entry> pendingEntries = new ConcurrentLinkedQueue<Entry>();
    /**
     * 观察任务
     */
    private static final Watcher watcher = new Watcher();
    /** 观察线程是否启动 */
    private static final AtomicBoolean started = new AtomicBoolean();
    /** 观察线程 */
    private static volatile Thread watcherThread;

    static {
        String poolName = "threadDeathWatcher";
        String serviceThreadPrefix = SystemPropertyUtil.get("io.netty.serviceThreadPrefix");
        if (!StringUtil.isNullOrEmpty(serviceThreadPrefix)) {
            poolName = serviceThreadPrefix + poolName;
        }
        // because the ThreadDeathWatcher is a singleton, tasks submitted to it can come from arbitrary threads and
        // this can trigger the creation of a thread from arbitrary thread groups; for this reason, the thread factory
        // must not be sticky about its thread group
        threadFactory = new DefaultThreadFactory(poolName, true, Thread.MIN_PRIORITY, null);
    }

    /**
     * Schedules the specified {@code task} to run when the specified {@code thread} dies.
     *
     * @param thread the {@link Thread} to watch
     * @param task the {@link Runnable} to run when the {@code thread} dies
     *
     * @throws IllegalArgumentException if the specified {@code thread} is not alive
     *
     * 计划指定的任务在指定的线程死亡时运行。
     */
    public static void watch(Thread thread, Runnable task) {
        if (thread == null) {
            throw new NullPointerException("thread");
        }
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (!thread.isAlive()) {
            throw new IllegalArgumentException("thread must be alive.");
        }

        schedule(thread, task, true);
    }

    /**
     * Cancels the task scheduled via {@link #watch(Thread, Runnable)}.
     */
    public static void unwatch(Thread thread, Runnable task) {
        if (thread == null) {
            throw new NullPointerException("thread");
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        schedule(thread, task, false);
    }

    /**
     * 定时任务
     *
     * @param thread    线程
     * @param task      任务
     * @param isWatch   判断线程是否处于守望
     */
    private static void schedule(Thread thread, Runnable task, boolean isWatch) {
        // 加入等待队列
        pendingEntries.add(new Entry(thread, task, isWatch));

        // started为false则启动观察线程
        if (started.compareAndSet(false, true)) {
            final Thread watcherThread = threadFactory.newThread(watcher);
            // Set to null to ensure we not create classloader leaks by holds a strong reference to the inherited
            // classloader.
            // See:
            // - https://github.com/netty/netty/issues/7290
            // - https://bugs.openjdk.java.net/browse/JDK-7008595
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    watcherThread.setContextClassLoader(null);
                    return null;
                }
            });

            watcherThread.start();
            ThreadDeathWatcher.watcherThread = watcherThread;
        }
    }

    /**
     * Waits until the thread of this watcher has no threads to watch and terminates itself.
     * Because a new watcher thread will be started again on {@link #watch(Thread, Runnable)},
     * this operation is only useful when you want to ensure that the watcher thread is terminated
     * <strong>after</strong> your application is shut down and there's no chance of calling
     * {@link #watch(Thread, Runnable)} afterwards.
     *
     * @return {@code true} if and only if the watcher thread has been terminated
     */
    public static boolean awaitInactivity(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        Thread watcherThread = ThreadDeathWatcher.watcherThread;
        if (watcherThread != null) {
            watcherThread.join(unit.toMillis(timeout));
            return !watcherThread.isAlive();
        } else {
            return true;
        }
    }

    private ThreadDeathWatcher() { }

    private static final class Watcher implements Runnable {

        private final List<Entry> watchees = new ArrayList<Entry>();

        @Override
        public void run() {
            for (;;) {
                // 得到需要观察的线程
                fetchWatchees();
                // 线程异常结束执行清理操作
                notifyWatchees();

                // Try once again just in case notifyWatchees() triggered watch() or unwatch().
                // 再次执行，防止notify触发新的观察线程
                fetchWatchees();
                notifyWatchees();

                try {
                    // 周期1秒
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                    // Ignore the interrupt; do not terminate until all tasks are run.
                }

                // 没有需要被观察的线程则结束该观察线程
                if (watchees.isEmpty() && pendingEntries.isEmpty()) {

                    // Mark the current worker thread as stopped.
                    // The following CAS must always success and must be uncontended,
                    // because only one watcher thread should be running at the same time.
                    boolean stopped = started.compareAndSet(true, false);
                    assert stopped;

                    // Check if there are pending entries added by watch() while we do CAS above.
                    if (pendingEntries.isEmpty()) {
                        // A) watch() was not invoked and thus there's nothing to handle
                        //    -> safe to terminate because there's nothing left to do
                        // B) a new watcher thread started and handled them all
                        //    -> safe to terminate the new watcher thread will take care the rest
                        // 没有任务则退出
                        break;
                    }

                    // There are pending entries again, added by watch()
                    if (!started.compareAndSet(false, true)) {
                        // watch() started a new watcher thread and set 'started' to true.
                        // -> terminate this thread so that the new watcher reads from pendingEntries exclusively.
                        // 此时watch()方法启动新的观察线程，该线程退出
                        break;
                    }

                    // watch() added an entry, but this worker was faster to set 'started' to true.
                    // i.e. a new watcher thread was not started
                    // -> keep this thread alive to handle the newly added entries.
                }
            }
        }

        private void fetchWatchees() {
            for (;;) {
                Entry e = pendingEntries.poll();
                if (e == null) {
                    break;
                }

                if (e.isWatch) {
                    // 需要观察
                    watchees.add(e);
                } else {
                    // 正常原因死亡，不需要遗嘱
                    watchees.remove(e);
                }
            }
        }

        private void notifyWatchees() {
            List<Entry> watchees = this.watchees;
            for (int i = 0; i < watchees.size();) {
                Entry e = watchees.get(i);
                if (!e.thread.isAlive()) {
                    // 非正常死亡
                    watchees.remove(i);
                    try {
                        // 遗嘱中的清理操作
                        e.task.run();
                    } catch (Throwable t) {
                        logger.warn("Thread death watcher task raised an exception:", t);
                    }
                } else {
                    i ++;
                }
            }
        }
    }

    private static final class Entry {
        /** 被观察线程 **/
        final Thread thread;
        /** 清理缓存操作 **/
        final Runnable task;
        /** 是否需要被观察 **/
        final boolean isWatch;

        Entry(Thread thread, Runnable task, boolean isWatch) {
            this.thread = thread;
            this.task = task;
            this.isWatch = isWatch;
        }

        @Override
        public int hashCode() {
            return thread.hashCode() ^ task.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }

            if (!(obj instanceof Entry)) {
                return false;
            }

            Entry that = (Entry) obj;
            return thread == that.thread && task == that.task;
        }
    }
}
