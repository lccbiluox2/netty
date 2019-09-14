/*
 * Copyright 2015 The Netty Project
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
 * Utility class for heap buffers.
 */
final class HeapByteBufUtil {

    /**
     * 读取一个字节
     * @param memory
     * @param index
     * @return
     */
    static byte getByte(byte[] memory, int index) {
        return memory[index];
    }

    /**
     * 读取二个字节short
     * @param memory
     * @param index
     * @return
     */
    static short getShort(byte[] memory, int index) {
        //大端序-人类顺序
        //memory[index] << 8，先读一个字节左移8位，注意<<运算完成后类型变为int
        //memory[index + 1]，读第二个字节，与前面的值|运算，首先要提升类型为int，所以必须&0xff。
        //A|B 得到正确数据，把int强转short，丢弃2个高位得到short类型
        return (short) (memory[index] << 8 | memory[index + 1] & 0xFF);
    }

    /**
     * 读取二个字节short,小端序，与前面字节顺序相反即可
     * @param memory
     * @param index
     * @return
     */
    static short getShortLE(byte[] memory, int index) {
        return (short) (memory[index] & 0xff | memory[index + 1] << 8);
    }

    /**
     * 读取3字节-大端序
     * @param memory
     * @param index
     * @return
     */
    static int getUnsignedMedium(byte[] memory, int index) {
        return  (memory[index]     & 0xff) << 16 |
                (memory[index + 1] & 0xff) <<  8 |
                memory[index + 2] & 0xff;
    }

    /**
     * 读取3字节-小端序
     * @param memory
     * @param index
     * @return
     */
    static int getUnsignedMediumLE(byte[] memory, int index) {
        return  memory[index]     & 0xff         |
                (memory[index + 1] & 0xff) <<  8 |
                (memory[index + 2] & 0xff) << 16;
    }

    /**
     * 读取4字节-大端序
     * @param memory
     * @param index
     * @return
     */
    static int getInt(byte[] memory, int index) {
        return  (memory[index]     & 0xff) << 24 |
                (memory[index + 1] & 0xff) << 16 |
                (memory[index + 2] & 0xff) <<  8 |
                memory[index + 3] & 0xff;
    }

    /**
     * 读取4字节-小端序
     * @param memory
     * @param index
     * @return
     */
    static int getIntLE(byte[] memory, int index) {
        return  memory[index]      & 0xff        |
                (memory[index + 1] & 0xff) << 8  |
                (memory[index + 2] & 0xff) << 16 |
                (memory[index + 3] & 0xff) << 24;
    }

    /**
     * 读取8字节-大端序
     * @param memory
     * @param index
     * @return
     */
    static long getLong(byte[] memory, int index) {
        return  ((long) memory[index]     & 0xff) << 56 |
                ((long) memory[index + 1] & 0xff) << 48 |
                ((long) memory[index + 2] & 0xff) << 40 |
                ((long) memory[index + 3] & 0xff) << 32 |
                ((long) memory[index + 4] & 0xff) << 24 |
                ((long) memory[index + 5] & 0xff) << 16 |
                ((long) memory[index + 6] & 0xff) <<  8 |
                (long) memory[index + 7] & 0xff;
    }

    /**
     * 读取8字节0小端序
     * @param memory
     * @param index
     * @return
     */
    static long getLongLE(byte[] memory, int index) {
        return  (long) memory[index]      & 0xff        |
                ((long) memory[index + 1] & 0xff) <<  8 |
                ((long) memory[index + 2] & 0xff) << 16 |
                ((long) memory[index + 3] & 0xff) << 24 |
                ((long) memory[index + 4] & 0xff) << 32 |
                ((long) memory[index + 5] & 0xff) << 40 |
                ((long) memory[index + 6] & 0xff) << 48 |
                ((long) memory[index + 7] & 0xff) << 56;
    }

    /**
     * 写入字节
     * @param memory
     * @param index
     * @param value
     */
    static void setByte(byte[] memory, int index, int value) {
        memory[index] = (byte) value;
    }

    /**
     * 写入short,通过一位拆分2个字节写入
     * @param memory
     * @param index
     * @param value
     */
    static void setShort(byte[] memory, int index, int value) {
        memory[index]     = (byte) (value >>> 8);
        memory[index + 1] = (byte) value;
    }

    /**
     * 写入short,通过一位拆分2个字节写入-小端序
     * @param memory
     * @param index
     * @param value
     */
    static void setShortLE(byte[] memory, int index, int value) {
        memory[index]     = (byte) value;
        memory[index + 1] = (byte) (value >>> 8);
    }

    /**
     * 把int的低位3个字节以大端序写入
     * @param memory
     * @param index
     * @param value
     */
    static void setMedium(byte[] memory, int index, int value) {
        memory[index]     = (byte) (value >>> 16);
        memory[index + 1] = (byte) (value >>> 8);
        memory[index + 2] = (byte) value;
    }

    /**
     * 把int的低位3个字节以小端序写入
     * @param memory
     * @param index
     * @param value
     */
    static void setMediumLE(byte[] memory, int index, int value) {
        memory[index]     = (byte) value;
        memory[index + 1] = (byte) (value >>> 8);
        memory[index + 2] = (byte) (value >>> 16);
    }

    /**
     * 写入int的4个字节-大端序
     * @param memory
     * @param index
     * @param value
     */
    static void setInt(byte[] memory, int index, int value) {
        memory[index]     = (byte) (value >>> 24);
        memory[index + 1] = (byte) (value >>> 16);
        memory[index + 2] = (byte) (value >>> 8);
        memory[index + 3] = (byte) value;
    }

    /**
     * 写入int的4个字节-小端序
     * @param memory
     * @param index
     * @param value
     */
    static void setIntLE(byte[] memory, int index, int value) {
        memory[index]     = (byte) value;
        memory[index + 1] = (byte) (value >>> 8);
        memory[index + 2] = (byte) (value >>> 16);
        memory[index + 3] = (byte) (value >>> 24);
    }

    /**
     * 写入long的8个字节-大端序
     * @param memory
     * @param index
     * @param value
     */
    static void setLong(byte[] memory, int index, long value) {
        memory[index]     = (byte) (value >>> 56);
        memory[index + 1] = (byte) (value >>> 48);
        memory[index + 2] = (byte) (value >>> 40);
        memory[index + 3] = (byte) (value >>> 32);
        memory[index + 4] = (byte) (value >>> 24);
        memory[index + 5] = (byte) (value >>> 16);
        memory[index + 6] = (byte) (value >>> 8);
        memory[index + 7] = (byte) value;
    }

    /**
     * 写入long的8个字节-小端序
     * @param memory
     * @param index
     * @param value
     */
    static void setLongLE(byte[] memory, int index, long value) {
        memory[index]     = (byte) value;
        memory[index + 1] = (byte) (value >>> 8);
        memory[index + 2] = (byte) (value >>> 16);
        memory[index + 3] = (byte) (value >>> 24);
        memory[index + 4] = (byte) (value >>> 32);
        memory[index + 5] = (byte) (value >>> 40);
        memory[index + 6] = (byte) (value >>> 48);
        memory[index + 7] = (byte) (value >>> 56);
    }

    private HeapByteBufUtil() { }
}
