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
