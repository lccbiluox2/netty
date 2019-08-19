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

package io.netty.buffer;

/**
 * Netty中大于8K的内存是通过PoolChunk来分配的, 小于8k的内存是通过PoolSubpage分配的, 本章将详细描述如何通过PoolSubpage分配小于8K的内存。
 * 当申请小于8K的内存时, 会从分配一个8k的叶子节点, 若用不完的话, 存在很大的浪费, 所以通过PoolSubpage来管理8K的内存
 * <p>
 * 每一个PoolSubpage都会与PoolChunk里面的一个叶子节点映射起来, 然后将PoolSubpage根据用户申请的ElementSize化成几等分,
 * 之后只要再次申请ElementSize大小的内存, 将直接从这个PoolSubpage中分配
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    /**
     * 所属的PoolChunk
     */
    final PoolChunk<T> chunk;
    /**
     * 与PoolChunk中哪个节点映射一起来
     */
    private final int memoryMapIdx;
    private final int runOffset;
    /**
     * 该叶子节点的大小
     */
    private final int pageSize;
    /**
     * bitmap的每一位都描述的是一个element的使用情况
     * 数组中每个long的每一位表示一个块存储区域的使用情况：0表示未占用，1表示占用
     * 例如：对于一个4k的Page来说，如果这个Page用来分配1k的内存区域，那么long数组中就有一个long类型的元素且这个数值的低4位用来表示4个存储区域的占用情况
     */
    private final long[] bitmap;

    /**
     * arena双向链表的前驱节点
     */
    PoolSubpage<T> prev;
    /**
     * arena双向链表的后继节点
     */
    PoolSubpage<T> next;

    /**
     * 是否需要释放整个Page
     */
    boolean doNotDestroy;
    /**
     * 此次申请的大小，比如申请64b，那么这个page被分成了8k/64 = 128个
     * 块大小是由第一次申请的内存大小决定的
     */
    int elemSize;
    /**
     * 最多可以切分的小块数
     */
    private int maxNumElems;
    /**
     * 位图信息的长度，long的个数
     */
    private int bitmapLength;
    /**
     * 下一个可被分配的小块位置信息
     */
    private int nextAvail;
    /**
     * 可用的小块数
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 构造双向链表的头节点Head，这是一个特殊节点，不进行分配
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        /**
         * 这里为什么是10 ？
         *
         * 因为小于8K的内存, 最小是以16b为单位来分配的, 一个long类型为64位,
         * 最多需要pageSize/(16*64)个long就可以将一个PoolSubpage中所有element是否分配描述清楚了, log2(16*64)=10
         */
        // bitmap数组的最大长度 = pageSize / 16 / 64 ~ 10
        bitmap = new long[pageSize >>> 10];
        init(head, elemSize);
    }

    /**
     * 根据当前需要分配的内存大小，确定需要多少个bitmap元素
     *
     * @param head
     * @param elemSize elemSize代表此次申请的大小，比如申请64byte，那么这个page被分成了8k/64=2^7=128个
     */
    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 被分成了128份64大小的内存
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            // 6代表着long长度, 2^6=64，bitmap数组长度
            bitmapLength = maxNumElems >>> 6;
            // 如果最大块数不是64的整数倍，则 bitmapLength+1
            if ((maxNumElems & 63) != 0) {
                bitmapLength++;
            }

            for (int i = 0; i < bitmapLength; i++) {
                // 初始化为0，代表未占用
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // 若可分配的element个数为0，则将其从相应可分配连中去掉
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 若是没有可用块或是被销毁了，则返回-1
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 得到此Page中下一个可用“块”的位置bitmapIdx
        // 例如：假设bitmapIdx=66，则q=1，r=2，即是用bitmap[1]这个long类型数的第2个bit位来表示此“内存块”的
        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        // 此bit位此时应该为0
        assert (bitmap[q] >>> r & 1) == 0;
        // 将bitmap[q]这个long型的数的第r bit置为1，标识此“块”已经被分配
        bitmap[q] |= 1L << r;

        // 将Page的可用"块数"减1，如果结果为0，表示此Page无内存可分配了，将其从PoolArena所持有的链表中移除
        if (--numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 若当前可用的 elemSize 为0，则说明已经从相应可分配链中去掉了
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * 将改PoolSubpage加入到PoolArena的双向链表中
     * 每次新加入的节点都在head之后
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    /**
     * 从链表中删除
     */
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            // Page被第一次申请时nextAvail = 0,会直接返回。表示直接用第0位内存块
            this.nextAvail = -1;
            return nextAvail;
        }
        // 第2、3...次申请内存块
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i++) {
            // 对标识数组bitmap中的每一个long元素进行判断
            long bits = bitmap[i];
            if (~bits != 0) {
                // 还存在至少一个bit位不为1
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }


    /**
     * 在PoolSubpage中查找可用的element。
     *
     * @param i bitmap数组中的第几个long
     * @param bits long值
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        // 包含的段个数
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        // 对long类型的数的每一bit位进行判断
        for (int j = 0; j < 64; j++) {
            // //第j（bit）为0，即为可用
            if ((bits & 1) == 0) {
                // 第4个long+9  i<<6 + j
                int val = baseVal | j;
                // 不能大于总段数
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    /**
     * 可以得知针对大小小于8k的分配，返回的handle一定是大于int类型的数据，若handle高位不为0，则该handle映射的内存块一定是小于8k的内存块。
     * 该handle将page、element的位置信息全部编码进去了，这些信息也很容易解码出来。
     * 内存返回的handle可以唯一确定PoolChunk、poolSubpage中具体哪个element。
     */
    private long toHandle(int bitmapIdx) {
        // 高32放着一个PoolSubpage里面哪段的哪个，低32位放着哪个叶子节点
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
