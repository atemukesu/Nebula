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

package com.atemukesu.nebula.particle.data;

/**
 * 内存中的粒子状态，用于计算 P-Frame 差值。
 * 这不是 GPU 数据，而是逻辑数据。
 * 按照 NBL 文档的 "Zero Basis Principle"，新生成的粒子默认全是 0。
 */
public class ParticleState {
    public float x, y, z;
    public int r, g, b, a;
    public float size;
    public int texID;
    public int seqID;

    // [Feature] GPU Interpolation
    // 每次更新前，先将 x/y/z 存入 prev
    public float prevX, prevY, prevZ;

    // Lifecycle tracking
    public int lastSeenFrame = -1;
    public int id; // Needed for stateMap iteration in processIFrame

    public ParticleState() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        // 初始化 prev = current
        this.prevX = 0;
        this.prevY = 0;
        this.prevZ = 0;

        this.r = 0;
        this.g = 0;
        this.b = 0;
        this.a = 0;
        this.size = 0;
        this.texID = 0;
        this.seqID = 0;
    }

    @Deprecated
    public void read(java.nio.ByteBuffer data) {
    }

    @Deprecated
    public void readPFrame(java.nio.ByteBuffer data) {
    }

    @Deprecated
    public ParticleState copy() {
        ParticleState copy = new ParticleState();
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        // Copy prev
        copy.prevX = this.prevX;
        copy.prevY = this.prevY;
        copy.prevZ = this.prevZ;
        copy.lastSeenFrame = this.lastSeenFrame;
        copy.id = this.id;

        copy.r = this.r;
        copy.g = this.g;
        copy.b = this.b;
        copy.a = this.a;
        copy.size = this.size;
        copy.texID = this.texID;
        copy.seqID = this.seqID;
        return copy;
    }
}
