package com.atemukesu.nebula.client.render;

import com.atemukesu.nebula.Nebula;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 粒子纹理管理器
 * 负责加载和管理粒子纹理到 GL_TEXTURE_2D_ARRAY
 * 无状态工具类，配合 TextureCacheSystem 使用
 */
public class ParticleTextureManager {

    /**
     * 纹理层信息
     */
    public static class TextureLayerInfo {
        public int startLayer; // 在纹理数组中的起始层
        public int totalFrames; // 总帧数 (rows * cols)
        public int rows;
        public int cols;

        public TextureLayerInfo(int startLayer, int rows, int cols) {
            this.startLayer = startLayer;
            this.rows = rows;
            this.cols = cols;
            this.totalFrames = rows * cols;
        }
    }

    /**
     * 纹理条目（从 NBL 文件读取）
     */
    public static class TextureEntry {
        public String path; // Minecraft Identifier 格式: "minecraft:textures/particle/..."
        public int rows; // Sprite sheet 行数
        public int cols; // Sprite sheet 列数

        public TextureEntry(String path, int rows, int cols) {
            this.path = path;
            this.rows = rows;
            this.cols = cols;
        }
    }

    /**
     * 加载结果封装
     */
    public static class LoadedResult {
        public final int glTextureId;
        public final TextureAtlasMap map;

        public LoadedResult(int glTextureId, TextureAtlasMap map) {
            this.glTextureId = glTextureId;
            this.map = map;
        }
    }

    /**
     * 加载默认纹理 (Fallback)
     */
    public static LoadedResult uploadDefaultTexture() {
        // 尝试从资源加载默认粒子纹理
        Identifier defaultTexId = new Identifier(Nebula.MOD_ID, "textures/particle/nebula_particle.png");
        NativeImage image = loadFromIdentifier(defaultTexId.toString());

        int textureWidth;
        int textureHeight;
        ByteBuffer data;

        if (image != null) {
            textureWidth = image.getWidth();
            textureHeight = image.getHeight();
            data = extractFrame(image, 0, 0, textureWidth, textureHeight);
            image.close();
            Nebula.LOGGER.info("Loaded default particle texture: {} ({}x{})", defaultTexId, textureWidth,
                    textureHeight);
        } else {
            // Fallback: Procedural Circle
            int size = 64;
            textureWidth = size;
            textureHeight = size;
            data = createProceduralCircle(size);
            Nebula.LOGGER.warn("Default particle texture not found, created fallback procedural texture.");
        }

        int textureArrayId = GL30.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArrayId);

        GL30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8,
                textureWidth, textureHeight, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        GL30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, 0, textureWidth, textureHeight, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);

        setupTextureParams();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);

        List<TextureLayerInfo> infos = new ArrayList<>();
        infos.add(new TextureLayerInfo(0, 1, 1));

        return new LoadedResult(textureArrayId, new TextureAtlasMap(infos));
    }

    /**
     * 从 NBL 纹理定义加载纹理并返回结果
     */
    public static LoadedResult uploadTextures(List<TextureEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return uploadDefaultTexture();
        }

        // 计算总层数
        int totalLayers = 0;
        for (TextureEntry entry : entries) {
            totalLayers += entry.rows * entry.cols;
        }

        if (totalLayers == 0) {
            return uploadDefaultTexture();
        }

        // 加载第一个纹理获取尺寸
        TextureEntry first = entries.get(0);
        NativeImage firstImage = loadFromIdentifier(first.path);

        if (firstImage == null) {
            Nebula.LOGGER.error("Failed to load first texture: {}", first.path);
            return uploadDefaultTexture();
        }

        // 每帧尺寸
        int textureWidth = firstImage.getWidth() / first.cols;
        int textureHeight = firstImage.getHeight() / first.rows;

        Nebula.LOGGER.info("Uploading {} textures with {} total layers (frame size: {}x{})",
                entries.size(), totalLayers, textureWidth, textureHeight);

        // 创建纹理数组
        int textureArrayId = GL30.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArrayId);

        GL30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8,
                textureWidth, textureHeight, totalLayers, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        // 上传每个纹理的每帧
        int currentLayer = 0;
        List<TextureLayerInfo> textureLayerInfos = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            TextureEntry entry = entries.get(i);
            int startLayer = currentLayer;

            NativeImage image;
            if (i == 0) {
                image = firstImage;
            } else {
                image = loadFromIdentifier(entry.path);
            }

            if (image != null) {
                int frameWidth = image.getWidth() / entry.cols;
                int frameHeight = image.getHeight() / entry.rows;

                for (int row = 0; row < entry.rows; row++) {
                    for (int col = 0; col < entry.cols; col++) {
                        ByteBuffer frameData = extractFrame(image, col, row, frameWidth, frameHeight);

                        // 尺寸不匹配时缩放
                        if (frameWidth != textureWidth || frameHeight != textureHeight) {
                            frameData = resizeFrame(frameData, frameWidth, frameHeight,
                                    textureWidth, textureHeight);
                        }

                        GL30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0,
                                0, 0, currentLayer,
                                textureWidth, textureHeight, 1,
                                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, frameData);

                        currentLayer++;
                    }
                }

                if (i != 0) {
                    image.close();
                }
            } else {
                // 填充空白帧
                int framesToFill = entry.rows * entry.cols;
                ByteBuffer blank = createBlankFrame(textureWidth, textureHeight);
                for (int f = 0; f < framesToFill; f++) {
                    blank.rewind();
                    GL30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0,
                            0, 0, currentLayer++,
                            textureWidth, textureHeight, 1,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, blank);
                }
            }

            textureLayerInfos.add(new TextureLayerInfo(startLayer, entry.rows, entry.cols));
        }

        firstImage.close();
        setupTextureParams();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);

        return new LoadedResult(textureArrayId, new TextureAtlasMap(textureLayerInfos));
    }

    /**
     * 生成圆形渐变纹理数据
     */
    private static ByteBuffer createProceduralCircle(int size) {
        ByteBuffer data = BufferUtils.createByteBuffer(size * size * 4);
        float center = size / 2.0f;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x - center + 0.5f;
                float dy = y - center + 0.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy) / center;

                // 平滑圆形渐变
                float alpha = 1.0f - Math.min(1.0f, dist);
                alpha = alpha * alpha;

                data.put((byte) 255); // R
                data.put((byte) 255); // G
                data.put((byte) 255); // B
                data.put((byte) (alpha * 255)); // A
            }
        }
        data.flip();
        return data;
    }

    private static NativeImage loadFromIdentifier(String path) {
        try {
            String namespace = "minecraft"; // 默认命名空间
            String resourcePath = path;

            // 1. 分离命名空间 (例如 "nebula:textures/...")
            if (path.contains(":")) {
                String[] parts = path.split(":", 2);
                namespace = parts[0];
                resourcePath = parts[1];
            }

            if (!resourcePath.endsWith(".png")) {
                resourcePath += ".png";
            }

            // 构造 Identifier
            Identifier id = new Identifier(namespace, resourcePath);

            // 尝试加载资源
            Optional<Resource> resource = MinecraftClient.getInstance()
                    .getResourceManager().getResource(id);

            if (resource.isPresent()) {
                try (InputStream is = resource.get().getInputStream()) {
                    return NativeImage.read(is);
                }
            } else {
                // 如果找不到，尝试给路径加上 textures/ 前缀再找一次 (兼容简写路径)
                if (!resourcePath.startsWith("textures/")) {
                    Identifier retryId = new Identifier(namespace, "textures/" + resourcePath);
                    Optional<Resource> retryResource = MinecraftClient.getInstance()
                            .getResourceManager().getResource(retryId);
                    if (retryResource.isPresent()) {
                        try (InputStream is = retryResource.get().getInputStream()) {
                            return NativeImage.read(is);
                        }
                    }
                }

                Nebula.LOGGER.warn("Texture not found: {}", id);
            }
        } catch (IOException e) {
            Nebula.LOGGER.error("Failed to load texture: {}", path, e);
        }
        return null;
    }

    private static ByteBuffer extractFrame(NativeImage image, int col, int row,
            int frameWidth, int frameHeight) {
        ByteBuffer data = BufferUtils.createByteBuffer(frameWidth * frameHeight * 4);

        int startX = col * frameWidth;
        int startY = row * frameHeight;

        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int pixel = image.getColor(startX + x, startY + y);

                // NativeImage 使用 ABGR 格式
                int a = (pixel >> 24) & 0xFF;
                int b = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int r = pixel & 0xFF;

                data.put((byte) r);
                data.put((byte) g);
                data.put((byte) b);
                data.put((byte) a);
            }
        }

        data.flip();
        return data;
    }

    private static ByteBuffer resizeFrame(ByteBuffer src, int srcW, int srcH,
            int dstW, int dstH) {
        ByteBuffer dst = BufferUtils.createByteBuffer(dstW * dstH * 4);

        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                int srcX = x * srcW / dstW;
                int srcY = y * srcH / dstH;
                int srcIdx = (srcY * srcW + srcX) * 4;

                dst.put(src.get(srcIdx));
                dst.put(src.get(srcIdx + 1));
                dst.put(src.get(srcIdx + 2));
                dst.put(src.get(srcIdx + 3));
            }
        }

        dst.flip();
        return dst;
    }

    private static ByteBuffer createBlankFrame(int width, int height) {
        ByteBuffer data = BufferUtils.createByteBuffer(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            data.put((byte) 255);
            data.put((byte) 255);
            data.put((byte) 255);
            data.put((byte) 0);
        }
        data.flip();
        return data;
    }

    private static void setupTextureParams() {
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
