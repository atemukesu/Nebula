package com.atemukesu.nebula.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * OIT (Order Independent Transparency) 专用 Framebuffer
 * 包含两个纹理附件：
 * 1. Accumulate Texture (RGBA16F): 存储累积的颜色和权重
 * 2. Revealage Texture (R8): 存储混合系数
 * 
 * 关键特性：
 * - 必须共享主 Framebuffer 的深度缓冲区，以实现正确的遮挡关系
 */
public class OitFramebuffer {

    private int fbo = -1;
    private int accumTexture = -1;
    private int revealTexture = -1;

    // 记录当前的宽和高，用于检测 resize
    private int width = -1;
    private int height = -1;

    public void resize(int width, int height) {
        if (this.width == width && this.height == height && fbo != -1) {
            return; // 尺寸未变且已初始化
        }

        destroy(); // 重建前先销毁旧的

        this.width = width;
        this.height = height;

        RenderSystem.assertOnRenderThread();

        // 1. 创建 FBO
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        // 2. 创建 Accum Texture (RGBA16F)
        // 存储: RGB * weight, alpha * weight
        accumTexture = createTexture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, accumTexture,
                0);

        // 3. 创建 Reveal Texture (R8)
        // 存储: revealage (剩下的透明度)
        revealTexture = createTexture(width, height, GL30.GL_R8, GL11.GL_RED, GL11.GL_FLOAT);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, revealTexture,
                0);

        // 4. 设置 Draw Buffers，告诉 OpenGL 我们要同时写入这两个附件
        GL30.glDrawBuffers(new int[] { GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1 });

        // 检查完整性 (此时还没有深度缓冲，可能会报不完整，但我们稍后会动态挂载深度)
        // int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        // if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
        // Nebula.LOGGER.error("OIT Framebuffer is incomplete! Status: 0x" +
        // Integer.toHexString(status));
        // }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private int createTexture(int width, int height, int internalFormat, int format, int type) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        // Cast null to ByteBuffer to avoid ambiguity
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);

        // 使用 NEAREST 过滤，因为合成着色器使用 texelFetch
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // 使用 CLAMP_TO_EDGE 防止边缘采样问题
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);

        return tex;
    }

    private boolean hasWarnedDepthFailure = false;

    /**
     * 绑定 OIT FBO，并尝试挂载当前激活 FBO 的深度附件。
     * 这对于在 Iris 模式下工作至关重要。
     * 
     * @param sourceFboId 外部传入的当前绑定的 FBO ID
     */
    public void bindAndShareDepth(int sourceFboId) {
        if (fbo == -1)
            return;

        // 绑定我们的 OIT FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        // 尝试获取源 FBO 的深度附件
        // 核心逻辑：我们不拥有深度缓冲，必须借用主渲染管线的深度缓冲
        int sourceDepthTex = 0;
        int sourceType = GL11.GL_NONE; // GL_TEXTURE or GL_RENDERBUFFER

        if (sourceFboId == 0) {
            // 如果是缺省 framebuffer (0)，我们无法直接 attachment query
            // 但在 Minecraft 中，即使是 "Main" FBO 也是一个 FBO 对象，很少是 0
            // 如果真的遇到了 0，尝试直接获取 Minecraft 的主 FBO 深度附件
            Framebuffer clientFbo = MinecraftClient.getInstance().getFramebuffer();
            if (clientFbo != null) {
                sourceDepthTex = clientFbo.getDepthAttachment();
                sourceType = GL11.GL_TEXTURE;
            }
        } else {
            // 绑定源 FBO 以查询属性 (保存当前绑定状态已经在外部完成或隐含)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFboId);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer params = stack.mallocInt(1);

                // 查询深度附件类型
                GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, params);
                int type = params.get(0);

                if (type == GL11.GL_TEXTURE) {
                    GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, params);
                    sourceDepthTex = params.get(0);
                    sourceType = GL11.GL_TEXTURE;
                } else if (type == GL30.GL_RENDERBUFFER) {
                    GL30.glGetFramebufferAttachmentParameteriv(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, params);
                    sourceDepthTex = params.get(0);
                    sourceType = GL30.GL_RENDERBUFFER;
                }
            }

            // 切回我们的 FBO
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        }

        // 挂载深度附件到我们的 OIT FBO
        if (sourceDepthTex != 0) {
            if (sourceType == GL11.GL_TEXTURE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                        sourceDepthTex, 0);
            } else if (sourceType == GL30.GL_RENDERBUFFER) {
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER,
                        sourceDepthTex);
            }
            // 成功挂载后重置警告，如果之后又失败了再警告？
            // 不，一旦失败一次就很可能一直失败。保持只警告一次。
        } else {
            if (!hasWarnedDepthFailure) {
                com.atemukesu.nebula.Nebula.LOGGER.warn(
                        "[OIT] Failed to share depth buffer from FBO {}. Particles may render incorrectly (Z-Test disabled/incorrect).",
                        sourceFboId);
                hasWarnedDepthFailure = true;
            }
        }
    }

    public void clear() {
        if (fbo == -1)
            return;

        // 显式清空两个颜色附件
        // Accum (0): 0, 0, 0, 0
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] { 0, 0, 0, 0 });

        // Reveal (1): 1, 1, 1, 1 (初始为 1，表示完全未被遮挡) (注意：R8 只有红色通道有效)
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[] { 1, 1, 1, 1 });

        // 注意：千万不要清空深度缓冲！因为我们共享了它，清空会把场景深度擦除
    }

    public void destroy() {
        if (fbo != -1) {
            GL30.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (accumTexture != -1) {
            GL11.glDeleteTextures(accumTexture);
            accumTexture = -1;
        }
        if (revealTexture != -1) {
            GL11.glDeleteTextures(revealTexture);
            revealTexture = -1;
        }
    }

    public int getAccumTexture() {
        return accumTexture;
    }

    public int getRevealTexture() {
        return revealTexture;
    }

    public int getFbo() {
        return fbo;
    }
}
