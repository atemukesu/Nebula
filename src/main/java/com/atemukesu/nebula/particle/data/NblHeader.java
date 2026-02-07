package com.atemukesu.nebula.particle.data;

import java.util.ArrayList;
import java.util.List;

/**
 * NBL 文件头信息
 */
public class NblHeader {
    public static final byte[] MAGIC = "NEBULAFX".getBytes();
    public static final int VERSION = 1;

    private int targetFps;
    private int totalFrames;
    private List<TextureEntry> textures;

    // 兼容不同的构造方式
    public NblHeader(int targetFps, int totalFrames, List<?> textureData) {
        this.targetFps = targetFps;
        this.totalFrames = totalFrames;
        this.textures = new ArrayList<>();

        // 转换纹理数据
        if (textureData != null) {
            for (Object item : textureData) {
                if (item instanceof TextureEntry) {
                    textures.add((TextureEntry) item);
                }
                // 其他类型的纹理数据会被忽略（由 ParticleTextureManager 单独处理）
            }
        }
    }

    public int getTargetFps() {
        return targetFps;
    }

    public int getTotalFrames() {
        return totalFrames;
    }

    public List<TextureEntry> getTextures() {
        return textures;
    }

    public static class TextureEntry {
        private String path;
        private int rows;
        private int cols;

        public TextureEntry(String path, int rows, int cols) {
            this.path = path;
            this.rows = rows;
            this.cols = cols;
        }

        public String getPath() {
            return path;
        }

        public int getRows() {
            return rows;
        }

        public int getCols() {
            return cols;
        }
    }
}
