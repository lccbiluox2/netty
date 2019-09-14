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
package io.netty.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Default {@link AttributeMap} implementation which use simple synchronization per bucket to keep the memory overhead
 * as low as possible.
 *
 * 默认 AttributeMap 的实现，它使用每个桶的简单同步来尽可能降低内存开销。
 */
public class DefaultAttributeMap implements AttributeMap {

    /**
     * 以原子方式更新attributes变量的引用
     */
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, AtomicReferenceArray> updater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class, AtomicReferenceArray.class, "attributes");

    /**
     * 默认桶的大小
     */
    private static final int BUCKET_SIZE = 4;
    /**
     * 掩码 桶大小-1
     */
    private static final int MASK = BUCKET_SIZE  - 1;

    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    /**
     * 延迟初始化，节约内存
     */
    @SuppressWarnings("UnusedDeclaration")
    private volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    @SuppressWarnings("unchecked")
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        //当attributes为空时则创建它，默认数组长度4
        if (attributes == null) {
            // Not using ConcurrentHashMap due to high memory consumption.
            // 没有使用ConcurrentHashMap为了节约内存
            // Not using ConcurrentHashMap due to high memory consumption.
            attributes = new AtomicReferenceArray<DefaultAttribute<?>>(BUCKET_SIZE);

            // 原子方式更新attributes，如果attributes为null则把新创建的对象赋值
            // 原子方式更新解决了并发赋值问题
            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }

        // 根据key计算下标
        int i = index(key);
        //返回attributes数组中的第一个元素
        DefaultAttribute<?> head = attributes.get(i);
        //头部为空说明第一次添加，这个方法可能多个线程同时调用，因为判断head全部为null
        if (head == null) {
            // No head exists yet which means we may be able to add the attribute without synchronization and just
            // use compare and set. At worst we need to fallback to synchronization and waste two allocations.
            //创建一个空对象为链表结构的起点
            head = new DefaultAttribute();
            //创建一个attr对象，把head传进去
            DefaultAttribute<T> attr = new DefaultAttribute<T>(head, key);

            //链表头head的next = attr
            head.next = attr;
            //attr的prev = head
            attr.prev = head;

            //给数组i位置赋值，这里用compareAndSet原子更新方法解决并发问题
            //只有i位置为null能够设置成功，且只有一个线程能设置成功
            if (attributes.compareAndSet(i, null, head)) {
                //设置成功后返回attr
                // we were able to add it so return the attr right away
                return attr;
            } else {
                //设置失败的，说明数组i位置已经被其它线程赋值
                //所以要把head重新赋值，不能用上面new出来的head，需要拿到之前线程设置进去的head
                head = attributes.get(i);
            }
        }

        //对head同步加锁
        synchronized (head) {
            //curr 赋值为head    head为链表结构的第一个变量
            DefaultAttribute<?> curr = head;
            for (;;) {
                //依次获取next
                DefaultAttribute<?> next = curr.next;
                //next==null，说明curr为最后一个元素
                if (next == null) {
                    //创建一个新对象传入head和key
                    DefaultAttribute<T> attr = new DefaultAttribute<T>(head, key);

                    //curr后面指向attr
                    curr.next = attr;
                    //attr的前面指向curr
                    attr.prev = curr;
                    //返回新对象
                    return attr;
                }

                //如果next的key等于传入的key，并且没有被移除
                if (next.key == key && !next.removed) {
                    return (Attribute<T>) next;
                }
                //否则把curr变量指向下一个元素
                curr = next;
            }
        }
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        //attributes为null直接返回false
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            // no attribute exists
            return false;
        }

        //计算数组下标
        int i = index(key);
        //获取头 为空直接返回false
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            // No attribute exists which point to the bucket in which the head should be located
            return false;
        }

        // We need to synchronize on the head.
        //对head同步加锁
        synchronized (head) {
            // Start with head.next as the head itself does not store an attribute.
            //从head的下一个开始，因为head不存储元素
            DefaultAttribute<?> curr = head.next;
            //为null说明没有节点了
            while (curr != null) {
                //key一致并且没有被移除则返回true
                if (curr.key == key && !curr.removed) {
                    return true;
                }
                //curr指向下一个
                curr = curr.next;
            }
            return false;
        }
    }

    private static int index(AttributeKey<?> key) {
        //与掩码&运算，数值肯定<=mask 正好是数组下标
        return key.id() & MASK;
    }

    /**
     * 此类继承AtomicReference，以原子方式存取变量
     * @param <T>
     */
    @SuppressWarnings("serial")
    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        private static final long serialVersionUID = -2661411462200283011L;

        /**
         * 链表的头的引用
         */
        // The head of the linked-list this attribute belongs to
        private final DefaultAttribute<?> head;
        private final AttributeKey<T> key;

        // Double-linked list to prev and next node to allow fast removal
        /**
         * 链表数据结构，前一个的变量引用
         */
        private DefaultAttribute<?> prev;
        /**
         * 链表数据结构，后一个的变量引用
         */
        private DefaultAttribute<?> next;

        // Will be set to true one the attribute is removed via getAndRemove() or remove()
        /**
         * 当调用getAndRemove() or remove()俩个方法时设置为true
         */
        private volatile boolean removed;

        DefaultAttribute(DefaultAttribute<?> head, AttributeKey<T> key) {
            this.head = head;
            this.key = key;
        }

        // Special constructor for the head of the linked-list.
        DefaultAttribute() {
            head = this;
            key = null;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        /**
         * 如果不存在value设设置新的value
         * @param value
         * @return
         */
        @Override
        public T setIfAbsent(T value) {
            //这里调用AtomicReference的compareAndSet，传入null和value
            //如果之前的值为null,则AtomicReference的值设置为value，并返回true
            //返回true说明设置成功，循环结束，方法返回null
            //返回false说明AtomicReference之前已经设置过value，那么返回oldvalue
            while (!compareAndSet(null, value)) {
                T old = get();
                if (old != null) {
                    return old;
                }
            }
            return null;
        }

        /**
         * 把AtomicReference的值设置为null，并且把自己从队列中移除，并且返回AtomicReference之前的value
         * @return
         */
        @Override
        public T getAndRemove() {
            removed = true;
            T oldValue = getAndSet(null);
            remove0();
            return oldValue;
        }

        /**
         * 把AtomicReference的值设置为null，并且把自己从队列中移除
         */
        @Override
        public void remove() {
            removed = true;
            set(null);
            remove0();
        }

        private void remove0() {
            //在头部对象上获取锁
            synchronized (head) {
                //如果前面引用为null，说明已经移除
                if (prev == null) {
                    // Removed before.
                    return;
                }

                //前面对象的next引用需要指向当前对象的next
                //因为链表结构，自己移除了，需要把前面对象和后面对象连接。...
                prev.next = next;

                //当前对象的next不为空的话，需要把next的prev设置为this.prev...
                if (next != null) {
                    next.prev = prev;
                }

                // Null out prev and next - this will guard against multiple remove0() calls which may corrupt
                // the linked list for the bucket.
                //把自己的前后引用设置为空
                prev = null;
                next = null;
            }
        }
    }
}
