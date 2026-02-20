/**
 * [AI GENERATION & MODIFICATION NOTICE / AI 编写与调整声明]
 *
 * ENGLISH:
 * This code was authored, modified, optimized, or adjusted by one or more of the
 * following AI models: Gemini 3 Pro, Gemini 3 Flash, and Claude 3.5 Opus.
 * Although efforts have been made to ensure functionality through testing, the
 * code is provided "AS IS". Please perform a thorough code audit before using,
 * reading, distributing, or modifying.
 *
 * 中文版：
 * 本代码由以下一个或多个 AI 模型编写、修改、优化或调整：
 * Gemini 3 Pro, Gemini 3 Flash, 以及 Claude 3.5 Opus。
 * 代码虽经努力测试以确保其功能实现，但仍按“原样”提供。在您进行使用、阅读、
 * 分发或修改前，请务必进行仔细的代码审计与测试。
 *
 * ----------------------------------------------------------------------------------
 * [LICENSE & WARRANTY / 开源协议与免责声明]
 *
 * ENGLISH:
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details <https://www.gnu.org/licenses/>.
 *
 * 中文版：
 * 本程序为自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可协议（GPL）条款
 *（可以选择版本 3 或更高版本）对本程序进行重新分发和/或修改。
 *
 * 本程序的发布是希望其能发挥作用，但【不附带任何担保】，甚至不包括对【适销性】或
 * 【特定用途适用性】的暗示保证。开发者不对因使用本代码产生的任何损害承担责任。
 * 详情请参阅 GNU 通用公共许可协议官方页面 <https://www.gnu.org/licenses/>。
 * ----------------------------------------------------------------------------------
 */

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