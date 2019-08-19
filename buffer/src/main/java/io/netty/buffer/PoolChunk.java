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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
/**
 * 感言：多年之前，从C内存的手动管理上升到java的自动GC，是历史的巨大进步。然而多年之后，netty的内存实现又曲线的回到了手动管理模式，
 * 正印证了马克思哲学观：社会总是在螺旋式前进的，没有永远的最好。的确，就内存管理而言，GC给程序员带来的价值是不言而喻的，
 * 不仅大大的降低了程序员的负担，而且也极大的减少了内存管理带来的Crash困扰，不过也有很多情况，可能手动的内存管理更为合适。
 */

/**
 * 在PoolChunk中，数组组织成完全二叉树结构，二叉树叶子节点为2048个page，也就是二叉树有11层，每个page的父节点用于分配pageSize*2大小内存，
 * 同理，对于page父节点的父节点，用于分配pageSize*4大小内存。
 * <p>
 * // 来自PoolChunk的PageRun / PoolSubpage分配算法的描述
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * <p>
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 * <p>
 * // 首先，我们会分配一个size = chunkSize的字节数组，每当需要创建给定大小的ByteBuf时，
 * 我们从数据第一个位置开始搜索，判断在字节数组中有足够的空白空间来容纳请求的大小，并且返回一个偏移量
 * （以后这段数据空间就只会由这个ByteBuf使用了）
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * // 为简单起见，根据PoolArena＃normalizeCapacity方法对所有大小进行标准化
 * 这确保了当我们请求size> = pageSize的内存段时，normalizedCapacity等于下一个最近的2的幂
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 * <p>
 * // 要找到至少达到请求大小可用的块中的第一个偏移量，我们构造一个完整的平衡二叉树并将其存储在一个数组中（就像堆一样） -  memoryMap
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 * <p>
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 * <p>
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder is the last level and the leafs consist of pages
 * <p>
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 * <p>
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 * memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 * is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 * <p>
 * As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 * <p>
 * Initialization -
 * In the beginning we construct the memoryMap array by storing the depth of a node at each node
 * i.e., memoryMap[id] = depth_of_id
 * <p>
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 * some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 * is thus marked as unusable
 * <p>
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 * <p>
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 * <p>
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 * note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 * <p>
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 * <p>
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
// Chunk = 块
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    // 竞技场
    /**
     * 该PoolChunk所属的PoolAren
     */
    final PoolArena<T> arena;
    /**
     * 堆外内存 DirectBuffer
     * 堆内内存 byte[]
     */
    final T memory;
    /**
     * 是内存池还是非内存池方式
     */
    final boolean unpooled;
    final int offset;
    /**
     * PoolChunk的物理视图是连续的PoolSubpage,用PoolSubpage保持，memoryMap是所有PoolSubpage的逻辑映射，映射为一颗平衡二叉树，
     * 用来标记每一个PoolSubpage是否被分配
     * 动态记录每个节点层数
     */
    private final byte[] memoryMap;
    /**
     * 表示各个id对应的深度，是个固定值，初始化之后不再改变
     */
    private final byte[] depthMap;
    /**
     * 与叶子节点个数相同，一个叶子节点映射PoolSubpage中的一个元素，若叶子结点与该元素完成了映射，说明该叶子节点已经分配出去了
     */
    private final PoolSubpage<T>[] subpages;
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     * 用来判断申请的内存是否超过pageSize的大小
     */
    private final int subpageOverflowMask;
    /**
     * 每个PoolSubpage的大小，默认为8k
     */
    private final int pageSize;
    /**
     * pageSize 2的pageShifts幂,log2(8k)=13
     */
    private final int pageShifts;
    /**
     * 平衡二叉树的高度11
     */
    private final int maxOrder;
    /**
     * PoolChunk的总内存大小，为chunkSize = 2^{maxOrder} * pageSize，即16M
     */
    private final int chunkSize;
    private final int log2ChunkSize;
    /**
     * PoolChunk由maxSubpageAllocs个PoolSubpage组成, 默认2048个
     */
    private final int maxSubpageAllocs;
    /**
     * Used to mark memory as unusable
     * 标记为已被分配的值，该值为 maxOrder + 1=12, 当memoryMap[id] = unusable时，则表示id节点已被分配
     */
    private final byte unusable;

    /**
     * // 用作从内存创建的ByteBuffer的缓存。 这些缓存是重复的，因此PoolChunk是内存缓存的容器。
     * // 这些通常需要在Pooled * ByteBuf中进行操作，因此可能会产生额外的GC，这可以通过缓存重复项来大大减少。
     * // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
     * // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
     * // may produce extra GC, which can be greatly reduced by caching the duplicates.
     * //
     * // 如果PoolChunk未被池化，那么这可能为空，此时池中的ByteBuffer实例没有任何意义。
     * // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
     */
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 当前PoolChunk剩余可分配内存, 初始为16M
     */
    private int freeBytes;

    /**
     * 一个PoolChunk分配后，会根据其使用率挂在一个PoolChunkList中(q050, q025...)
     */
    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        // 针对数据构造二叉树，实际从memoryMap下标为1的节点开始使用，数组元素个数为4096
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * Creates a special chunk that is not pooled.
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // normCapacity>= pageSize
            // 根据normCapacity计算在该Chunk中二叉树哪一层节点进行分配
            // 返回的是可分配normCapacity的节点的id，int
            handle = allocateRun(normCapacity);
        } else {
            // 创建Subpage，small）返回是第几个long,long第几位
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }


        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        // 初始化PooledByteBuf
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        // 开始更新父类节点的值
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            // 获取相邻节点的值，父节点的层号选取两个子节点层号最小的那个层号, 表示该父节点能分配的最大内存
            byte val2 = value(id ^ 1);
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * 从该层首节点进行分配，如果首节点不能分配那交给兄弟节点进行分配
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        // 从根节点开始遍历
        int id = 1;
        int initial = -(1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        // 首先检查第1层的层号, 若大于申请的层号, 那么该节点不够申请的大小, 直接退出
        if (val > d) { // unusable
            return -1;
        }

        // 若不退出的话, 那就意味该二叉树一定可以找到大小为d层号的节点, 并且在该节点的下标一定>=2^d
        // id < 2^d
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }

        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // mark as unusable
        // 将成功找到的那个节点层号标记为unusable，意味着已经分配出去了
        setValue(id, unusable);
        // 更新该节点的所有祖父父节点的层号
        updateParentsAlloc(id);
        // 返回的是查找到的那个节点的下标
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 算出当前大小的内存需要在哪一层完成分配
        // 比如申请32k, d = 11 - (log2(32k)-13) = 9
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 开始进入二叉树对应的id层中通过allocateNode查找哪个节点还没有分配出去
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        // 找到了可分配节点，计算分配内存的index
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 找到arena中对应阶级的subpage头节点，不存数据的头结点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        // subpages are only be allocated from pages i.e., leaves
        // subpage只能从叶子节点开始找起
        int d = maxOrder;
        synchronized (head) {
            // 只在叶子节点找到一个为8k的节点，肯定可以找到一个节点
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;

            // 第几个PoolSubpage（叶子节点）
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                // subpage为null的可能为: PoolSubpage释放时, 并没有从subpages中取出, 该PoolSubpage还存放在subpages的数组里
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            // 计算在该Subpage分配内存的index
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 首先通过handle获取属于哪个page: memoryMapIdx，低32位放着哪个叶子节点
        int memoryMapIdx = memoryMapIdx(handle);
        // 属于PoolSubpage里面哪个子内存块:bitmapIdx，高32位放着一个PoolSubpage里面哪段的哪个
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) {
            // free a subpage
            // 一定是小于8K的内存释放
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
                this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
