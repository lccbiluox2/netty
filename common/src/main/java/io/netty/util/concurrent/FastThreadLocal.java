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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * {@link ThreadLocal}的一个特殊变体，当从{@link FastThreadLocalThread}访问时，它会产生更高的访问性能。
 *
 * 在内部,{@link FastThreadLocal}使用一个常数索引数组,而不是使用散列码和哈希表,寻找一个变量。虽然看起来非常微妙，但是与使用哈希表
 * 相比，它的性能优势很小，并且在频繁访问时非常有用。
 *
 * 要利用这个线程本地变量，您的线程必须是{@link FastThreadLocalThread}或它的子类型。由于这个原因，默认情况下，由{@link
 * DefaultThreadFactory}创建的所有线程都是{@link FastThreadLocalThread}。
 *
 * 注意，快速路径只可能在扩展{@link FastThreadLocalThread}的线程上，因为它需要一个特殊的字段来存储必要的状态。任何其他类型的线程的
 * 访问都返回到常规的{@link ThreadLocal}。
 *
 * < / p >
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {

    /**
     * 获取InternalThreadLocalMap计数器的值,这个值是FastThreadLocal的静态变量，所有的FastThreadLocal都用这个值
     * 在InternalThreadLocalMap有一个数组indexedVariables,那么indexedVariables[variablesToRemoveIndex] =
     * Set<FastThreadLocal<?>>。
     *
     * 数组的variablesToRemoveIndex位置放一个SET，里面存储了全部的FastThreadLocal实例
     */
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    public static void removeAll() {
        // 获取线程内的InternalThreadLocalMap对象，如果存在就返回，不存在返回null
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            // 根据variablesToRemoveIndex下标获取threadLocalMap内部indexedVariables素组中的对象
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            // 如果不为空并且不等于UNSET，那么它一定是Set<FastThreadLocal<?>类型的对象
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                // 强制类型转换
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                // 转换为数组
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);
                // 循环调用tlv.remove
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            // 移除当前线程关联的InternalThreadLocalMap对象
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        //通过variablesToRemoveIndex值。获取threadLocalMap内部数组indexedVariables[variablesToRemoveIndex]对象
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            //如果返回空则创建一个set数据结构
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            //把threadLocalMap.indexedVariables[variablesToRemoveIndex]=set结构
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            //如果存在set则赋值给variablesToRemove
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        //把当前对象加入到set当中去
        variablesToRemove.add(variable);
    }

    /**
     * 	在threadLocalMap的数组中indexedVariables[variablesToRemoveIndex]内的Set<FastThreadLocal<?>>数据结构中移除自己
     * @param threadLocalMap
     * @param variable
     */
    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        // 根据variablesToRemoveIndex拿出threadLocalMap的indexedVariables数组中的Set<FastThreadLocal<?>>
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        // 如果返回值为null则结束
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        //转换为Set结构，移除自己this
        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    /**
     * 成员变量，通过InternalThreadLocalMap.nextVariableIndex()获取
     * 每个FastThreadLocal的对象，index值都会++
     */
    private final int index;

    public FastThreadLocal() {
        // 获取index的值，每个FastThreadLocal对象index的值都会递增
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     *
     * 从当前线程的InternalThreadLocalMap中获取对象
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    /**
     * Returns the current value for the current thread if it exists, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    public final V getIfExists() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap != null) {
            Object v = threadLocalMap.indexedVariable(index);
            if (v != InternalThreadLocalMap.UNSET) {
                return (V) v;
            }
        }
        return null;
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     *
     * 获取对象
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        // 获取threadLocalMap的indexedVariables[index]的对象
        Object v = threadLocalMap.indexedVariable(index);
        // 如果不等于UNSET则返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        // 否则初始化并设置对象
        return initialize(threadLocalMap);
    }

    /**
     * 初始化
     *
     * @param threadLocalMap
     * @return
     */
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            //调用子类方法获取对象V
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        // 把V设置到threadLocalMap的indexedVariables[index]位置
        threadLocalMap.setIndexedVariable(index, v);
        // 把自己加入到threadLocalMap的indexedVariables[variablesToRemoveIndex]位置的Set当中去
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     *
     * 往当前线程的threadLocalMap的indexedVariables[index]位置设置对象
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     *
     * 往threadLocalMap的indexedVariables[index]位置设置对象
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        // 如果V不等于UNSET
        if (value != InternalThreadLocalMap.UNSET) {
            setKnownNotUnset(threadLocalMap, value);
        } else {
            // 否则移除对象
            remove(threadLocalMap);
        }
    }

    /**
     * @return see {@link InternalThreadLocalMap#setIndexedVariable(int, Object)}.
     */
    private void setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        // 往threadLocalMap的indexedVariables[index]= value 设置对象
        if (threadLocalMap.setIndexedVariable(index, value)) {
            // 把当前对象加入到【threadLocalMap.indexedVariables[variablesToRemoveIndex]=set】 set数据结构中
            addToVariablesToRemove(threadLocalMap, this);
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     *
     * 判断当前对象是否往threadLocalMap的indexedVariables数组中设置了值
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     *
     * 判断当前对象是否往threadLocalMap的indexedVariables数组中设置了值
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     *
     * 传入threadLocalMap对象
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        //如果threadLocalMap == null则返回
        if (threadLocalMap == null) {
            return;
        }

        // 通过当前对象的index，在threadLocalMap的indexedVariables数组中清除对象
        Object v = threadLocalMap.removeIndexedVariable(index);
        // 在threadLocalMap的数组中indexedVariables[variablesToRemoveIndex]内的Set<FastThreadLocal<?>>数据结构
        // 中移除自己
        removeFromVariablesToRemove(threadLocalMap, this);

        // 如果V不等于UNSET则出发一个事件
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     *
     * 子类重写
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     *
     * 子类重写
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
