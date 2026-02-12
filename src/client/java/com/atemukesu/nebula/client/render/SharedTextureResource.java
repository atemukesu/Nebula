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
