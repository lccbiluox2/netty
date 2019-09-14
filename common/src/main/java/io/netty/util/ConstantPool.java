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

package io.netty.util;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of {@link Constant}s.
 *
 * @param <T> the type of the constant
 *
 * 一个常量池。T 代表的是常量的类型。给常量一个名字和一个ID
 * 一个池对象，内部只能存放Constant类型
 */
public abstract class ConstantPool<T extends Constant<T>> {

    /**
     * 线程安全的MAP
     */
    private final ConcurrentMap<String, T> constants = PlatformDependent.newConcurrentHashMap();

    /**
     * 线程安全原子自增计数器
     */
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Shortcut of {@link #valueOf(String) valueOf(firstNameComponent.getName() + "#" + secondNameComponent)}.
     */
    public T valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        if (firstNameComponent == null) {
            throw new NullPointerException("firstNameComponent");
        }
        if (secondNameComponent == null) {
            throw new NullPointerException("secondNameComponent");
        }

        return valueOf(firstNameComponent.getName() + '#' + secondNameComponent);
    }

    /**
     * Returns the {@link Constant} which is assigned to the specified {@code name}.
     * If there's no such {@link Constant}, a new one will be created and returned.
     * Once created, the subsequent calls with the same {@code name} will always return the previously created one
     * (i.e. singleton.)
     *
     * @param name the name of the {@link Constant}
     */
    public T valueOf(String name) {
        checkNotNullAndNotEmpty(name);
        return getOrCreate(name);
    }

    /**
     * Get existing constant by name or creates new one if not exists. Threadsafe
     *
     * @param name the name of the {@link Constant}
     *
     * 根据 name 获取已存在的常量 或者 如果不存在则创建一个新的，线程安全
     */
    private T getOrCreate(String name) {
        //根据name在map中查询
        T constant = constants.get(name);
        //如果为空
        if (constant == null) {
            //则调用抽象方法传入自增ID和名称创建对象
            final T tempConstant = newConstant(nextId(), name);
            //把创建好的对象放入map中缓存，以name作为key
            constant = constants.putIfAbsent(name, tempConstant);
            //这里说明name做key 之前并没有数据
            if (constant == null) {
                //返回新创建的对象
                return tempConstant;
            }
        }

        //返回旧数据
        return constant;
    }

    /**
     * Returns {@code true} if a {@link AttributeKey} exists for the given {@code name}.
     *
     * 判断name当key是否在map当中
     */
    public boolean exists(String name) {
        checkNotNullAndNotEmpty(name);
        return constants.containsKey(name);
    }

    /**
     * Creates a new {@link Constant} for the given {@code name} or fail with an
     * {@link IllegalArgumentException} if a {@link Constant} for the given {@code name} exists.
     *
     * 创建一个对象，如果之前name在map当中存在旧值则抛出异常
     */
    public T newInstance(String name) {
        checkNotNullAndNotEmpty(name);
        return createOrThrow(name);
    }

    /**
     * Creates constant by name or throws exception. Threadsafe
     *
     * @param name the name of the {@link Constant}
     *
     * 根据 name 创建常量 或者 抛出异常，线程安全
     */
    private T createOrThrow(String name) {
        T constant = constants.get(name);
        if (constant == null) {
            // 创建一个常量
            final T tempConstant = newConstant(nextId(), name);
            // 如果没有的话 才会放到常量池中,如果多线程下，putIfAbsent 方法可能返回null，或者返回了其他线程的结果。
            constant = constants.putIfAbsent(name, tempConstant);
            // 这里为何进行双层的判断？里面这一层不加可以吗?
            if (constant == null) {
                // 返回常量
                return tempConstant;
            }
        }

        //逻辑与getOrCreate类似，区别就是当map当中以name为key存在旧值则直接抛异常
        throw new IllegalArgumentException(String.format("'%s' is already in use", name));
    }

    private static String checkNotNullAndNotEmpty(String name) {
        ObjectUtil.checkNotNull(name, "name");

        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }

        return name;
    }

    protected abstract T newConstant(int id, String name);

    @Deprecated
    public final int nextId() {
        // 院子问题，不会出现异常
        return nextId.getAndIncrement();
    }
}
