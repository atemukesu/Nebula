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

import com.atemukesu.nebula.Nebula;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>纹理缓存系统</h1>
 * <hr>
 * 管理粒子纹理的加载和缓存<br>
 * 使用引用计数机制，确保纹理在不再使用时被正确释放
 */
public class TextureCacheSystem {

    // 缓存池：Key -> Resource
    private static final Map<String, SharedTextureResource> cache = new HashMap<>();

    /**
     * 获取或加载纹理资源
     *
     * @param cacheKey 唯一标识符
     * @param entries  如果缓存不存在，用于加载的纹理列表
     * @return 准备好的共享资源 (引用计数)
     */
    public static synchronized SharedTextureResource acquire(String cacheKey,
            List<ParticleTextureManager.TextureEntry> entries) {
        // 1. 检查缓存
        if (cache.containsKey(cacheKey)) {
            SharedTextureResource resource = cache.get(cacheKey);
            resource.grab(); // 引用 +1
            return resource;
        }

        // 2. 缓存未命中，执行实际加载
        ParticleTextureManager.LoadedResult result;
        if (entries == null || entries.isEmpty()) {
            // 如果没有 entries，尝试加载默认纹理
            result = ParticleTextureManager.uploadDefaultTexture();
        } else {
            result = ParticleTextureManager.uploadTextures(entries);
        }

        SharedTextureResource newResource = new SharedTextureResource(
                cacheKey,
                result.glTextureId,
                result.map);

        newResource.grab(); // 引用 = 1
        cache.put(cacheKey, newResource);

        Nebula.LOGGER.info("Texture cache miss: {}. Loaded new resource (ID: {}).", cacheKey, result.glTextureId);

        return newResource;
    }

    /**
     * 释放资源
     * 当一个 Streamer 播放结束时调用
     * 
     * @param resource 要释放的资源
     */
    public static synchronized void release(SharedTextureResource resource) {
        if (resource == null)
            return;

        // 引用 -1
        boolean shouldDestroy = resource.drop();

        if (shouldDestroy) {
            // 没人用了，彻底删除
            String key = resource.getResourceKey();
            cache.remove(key);
            resource.dispose(); // 删除 GL 纹理
            Nebula.LOGGER.info("Disposed texture resource: {}", key);
        }
    }
}
