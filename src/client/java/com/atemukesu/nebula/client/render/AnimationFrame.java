package com.atemukesu.nebula.client.render;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * 单帧动画数据，直接存储 GPU 友好的数据格式
 */
public class AnimationFrame {

    // 单个粒子的字节大小:
    // Pos(12) + Color(4) + Size(4) + TexID(4) + SeqID(4) = 28 bytes
    public static final int BYTES_PER_PARTICLE = 28;

    // 直接存储 GPU 友好的数据
    public ByteBuffer gpuData;
    public int particleCount;

    public AnimationFrame(int particleCount) {
        this.particleCount = particleCount;
        // 使用 Direct Buffer 提高 native 交互速度
        this.gpuData = BufferUtils.createByteBuffer(particleCount * BYTES_PER_PARTICLE);
    }

    /**
     * 写入单个粒子数据
     */
    public void putParticle(float x, float y, float z,
            byte r, byte g, byte b, byte a,
            float size, float tex, float seq) {
        gpuData.putFloat(x).putFloat(y).putFloat(z);
        gpuData.put(r).put(g).put(b).put(a);
        gpuData.putFloat(size);
        gpuData.putFloat(tex);
        gpuData.putFloat(seq);
    }

    /**
     * 准备读取数据
     */
    public void flip() {
        gpuData.flip();
    }

    /**
     * 重置缓冲区以便重新写入
     */
    public void clear() {
        gpuData.clear();
    }

    /**
     * 获取数据大小（字节）
     */
    public int getByteSize() {
        return particleCount * BYTES_PER_PARTICLE;
    }
}
