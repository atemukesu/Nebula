package com.atemukesu.nebula.client.render;

import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;

/**
 * 单帧动画数据容器
 * <p>
 * 存储准备上传到 GPU 的粒子数据。
 * 针对 SSBO (Shader Storage Buffer Object) 进行了优化，使用 48 字节对齐布局。
 * </p>
 */
public class AnimationFrame {

    /**
     * 单个粒子的字节大小 (48 bytes)
     * <p>
     * 适配 OpenGL std430 布局标准 (vec4 对齐):
     * 1. Vec4(PrevPos.xyz, Size) - 16 bytes
     * 2. Vec4(CurPos.xyz, ColorPacked) - 16 bytes
     * 3. Vec4(TexID, SeqID, Padding, Padding) - 16 bytes
     * </p>
     */
    public static final int BYTES_PER_PARTICLE = 48;

    /** 存储 GPU 友好的二进制数据 */
    public ByteBuffer gpuData;
    /** 当前帧包含的粒子数量 */
    public int particleCount;

    /**
     * 创建一个新的动画帧
     *
     * @param particleCount 预估的粒子数量
     */
    public AnimationFrame(int particleCount) {
        this.particleCount = particleCount;
        // 使用 Direct Buffer 提高 Native IO 传输速度
        this.gpuData = BufferUtils.createByteBuffer(particleCount * BYTES_PER_PARTICLE);
    }

    /**
     * 准备读取数据 (翻转缓冲区)
     * <p>
     * 在写入完成后，上传 GPU 前调用。
     * </p>
     */
    public void flip() {
        gpuData.flip();
    }

    /**
     * 重置缓冲区以便重新写入
     * <p>
     * 用于对象复用，避免频繁 GC。
     * </p>
     */
    public void clear() {
        gpuData.clear();
    }

    /**
     * 获取数据总字节大小
     *
     * @return 字节数
     */
    public int getByteSize() {
        return particleCount * BYTES_PER_PARTICLE;
    }
}