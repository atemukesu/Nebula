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

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 共享纹理资源
 * 代表显存中实际存在的一个 TextureArray
 */
public class SharedTextureResource {
    /**
     * opengl 纹理 ID
     */
    private final int glTextureId;
    /**
     * 纹理图集映射表
     */
    private final TextureAtlasMap atlasMap;
    /**
     * 缓存键
     */
    private final String resourceKey;

    private int referenceCount = 0; // 引用计数

    public SharedTextureResource(String key, int glId, TextureAtlasMap map) {
        this.resourceKey = key;
        this.glTextureId = glId;
        this.atlasMap = map;
    }

    // === 引用计数管理 ===

    public synchronized void grab() {
        referenceCount++;
    }

    public synchronized boolean drop() {
        referenceCount--;
        return referenceCount <= 0;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    // === 渲染与逻辑 ===

    public void bind() {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);
    }

    public TextureAtlasMap getMap() {
        return atlasMap;
    }

    public int getGlTextureId() {
        return glTextureId;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    // 真正的销毁逻辑
    void dispose() {
        if (glTextureId != -1) {
            GL11.glDeleteTextures(glTextureId);
        }
    }
}
