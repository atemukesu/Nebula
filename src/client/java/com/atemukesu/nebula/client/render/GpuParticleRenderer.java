package com.atemukesu.nebula.client.render;

import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.config.ModConfig;
import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;
import com.atemukesu.nebula.client.util.IrisUtil;

/**
 * GPU 粒子渲染器 (PMB)
 * <p>
 * 使用 OpenGL 4.4+ Persistent Mapped Buffer 技术：
 * - 持久映射缓冲区，整个程序生命周期内保持映射状态
 * - 使用 Triple Buffering 避免 CPU-GPU 同步等待
 * - 使用 Fence Sync 确保安全写入
 * </p>
 * 
 * 相比传统的 glBufferSubData：
 * - 无需每帧 map/unmap 开销
 * - 无需 buffer orphaning
 * - 更低的驱动开销
 */
public class GpuParticleRenderer {

    private static final Identifier VERTEX_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.vsh");
    private static final Identifier FRAGMENT_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.fsh");

    // OIT Shader
    private static final Identifier OIT_VSH_ID = new Identifier(Nebula.MOD_ID, "shaders/oit_composite.vsh");
    private static final Identifier OIT_FSH_ID = new Identifier(Nebula.MOD_ID, "shaders/oit_composite.fsh");

    // OpenGL 对象句柄
    private static int vao = -1;
    private static int quadVbo = -1;
    private static int shaderProgram = -1;

    // OIT 相关对象
    private static OitFramebuffer oitFbo;
    private static int oitProgram = -1;

    private static boolean initialized = false;
    private static boolean shaderCompiled = false;

    // Uniform locations
    private static int uModelViewMat = -1;
    private static int uProjMat = -1;
    private static int uCameraRight = -1;
    private static int uCameraUp = -1;
    private static int uOrigin = -1;
    private static int uSampler0 = -1;
    private static int uUseTexture = -1;
    private static int uPartialTicks = -1;
    private static int uEmissiveStrength = -1;

    private static int uRenderPass = -1;

    // OIT Composite Uniforms
    private static int uOitAccum = -1;
    private static int uOitReveal = -1;

    private static final int SSBO_BINDING_INDEX = 0;

    // 临时矩阵缓冲
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    // ========== Persistent Mapped Buffer 相关 ==========

    // Triple Buffering: 3 个缓冲区轮流使用，避免 CPU-GPU 同步
    private static final int BUFFER_COUNT = 3;
    private static final int INITIAL_BUFFER_SIZE = 8 * 1024 * 1024; // 每个缓冲区 8MB

    private static int[] ssbos = new int[BUFFER_COUNT];
    private static ByteBuffer[] mappedBuffers = new ByteBuffer[BUFFER_COUNT];
    private static long[] fences = new long[BUFFER_COUNT];
    private static int currentBufferIndex = 0;
    private static int currentBufferSize = INITIAL_BUFFER_SIZE;

    private static int lastFrameUsedBytes = 0;
    private static boolean pmbSupported = false;
    private static boolean useFallback = false;

    /**
     * 初始化渲染器
     * <p>
     * 创建 Shader, VAO, Quad VBO 和 Persistent Mapped Buffers。
     * </p>
     */
    public static void init() {
        if (initialized)
            return;

        RenderSystem.assertOnRenderThread();

        try {
            // ParticleTextureManager.init(); // Removed

            // 检查 OpenGL 版本，需要 4.4+ 支持 PMB
            String version = GL11.glGetString(GL11.GL_VERSION);
            Nebula.LOGGER.info("[GpuParticleRenderer] OpenGL Version: {}", version);

            // 检查是否支持 ARB_buffer_storage (OpenGL 4.4 核心功能)
            pmbSupported = GL.getCapabilities().GL_ARB_buffer_storage;
            Nebula.LOGGER.info("[GpuParticleRenderer] Persistent Mapped Buffer supported: {}", pmbSupported);

            // 1. 编译 Shader
            shaderProgram = createShaderProgram();
            if (shaderProgram > 0) {
                shaderCompiled = true;
                resolveUniforms();
            }

            // 1.1 编译 OIT Shader
            oitProgram = createShaderProgram(OIT_VSH_ID, OIT_FSH_ID);
            if (oitProgram > 0) {
                uOitAccum = GL20.glGetUniformLocation(oitProgram, "uAccumTexture");
                uOitReveal = GL20.glGetUniformLocation(oitProgram, "uRevealTexture");
            } else {
                Nebula.LOGGER.error("[GpuParticleRenderer] Failed to compile OIT shader");
            }

            // 1.2 初始化 OIT FBO
            oitFbo = new OitFramebuffer();

            // 2. 创建 VAO (仅用于绘制 Quad)
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);

            // 3. 创建静态 Quad VBO (标准正方形)
            float[] quadData = {
                    0, 0, 0, 0, 1,
                    1, 0, 0, 1, 1,
                    1, 1, 0, 1, 0,
                    0, 1, 0, 0, 0
            };
            quadVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadData, GL15.GL_STATIC_DRAW);

            // 设置 Quad 属性 (Pos + UV)
            int strideQuad = 5 * 4;
            GL20.glEnableVertexAttribArray(0); // in Position
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, strideQuad, 0);
            GL20.glEnableVertexAttribArray(1); // in UV
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, strideQuad, 12);

            GL30.glBindVertexArray(0);

            // 4. 创建 Persistent Mapped Buffers (或降级到传统 SSBO)
            if (pmbSupported) {
                createPersistentMappedBuffers();
            } else {
                createFallbackBuffer();
            }

            initialized = true;
            Nebula.LOGGER.info("[GpuParticleRenderer] Initialized successfully (PMB: {})", pmbSupported);
        } catch (Exception e) {
            Nebula.LOGGER.error("[GpuParticleRenderer] Failed to initialize", e);
        }
    }

    /**
     * 创建 Persistent Mapped Buffers (Triple Buffering)
     */
    private static void createPersistentMappedBuffers() {
        int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

        for (int i = 0; i < BUFFER_COUNT; i++) {
            ssbos[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[i]);

            // 使用 glBufferStorage 创建不可变存储，启用持久映射
            GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, flags);

            // 获取持久映射指针
            mappedBuffers[i] = GL30.glMapBufferRange(
                    GL43.GL_SHADER_STORAGE_BUFFER,
                    0,
                    currentBufferSize,
                    flags);

            if (mappedBuffers[i] == null) {
                Nebula.LOGGER.error("[GpuParticleRenderer] Failed to map buffer {}", i);
                useFallback = true;
                break;
            }

            fences[i] = 0; // 初始无 fence
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        if (!useFallback) {
            Nebula.LOGGER.info("[GpuParticleRenderer] Created {} Persistent Mapped Buffers ({} MB each)",
                    BUFFER_COUNT, currentBufferSize / 1024 / 1024);
        }
    }

    /**
     * 降级方案：创建传统 SSBO
     */
    private static void createFallbackBuffer() {
        useFallback = true;
        ssbos[0] = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[0]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        Nebula.LOGGER.info("[GpuParticleRenderer] Using fallback SSBO mode ({} MB)", currentBufferSize / 1024 / 1024);
    }

    private static void resolveUniforms() {
        uModelViewMat = GL20.glGetUniformLocation(shaderProgram, "ModelViewMat");
        uProjMat = GL20.glGetUniformLocation(shaderProgram, "ProjMat");
        uCameraRight = GL20.glGetUniformLocation(shaderProgram, "CameraRight");
        uCameraUp = GL20.glGetUniformLocation(shaderProgram, "CameraUp");
        uOrigin = GL20.glGetUniformLocation(shaderProgram, "Origin");
        uSampler0 = GL20.glGetUniformLocation(shaderProgram, "Sampler0");
        uUseTexture = GL20.glGetUniformLocation(shaderProgram, "UseTexture");
        uPartialTicks = GL20.glGetUniformLocation(shaderProgram, "PartialTicks");
        uEmissiveStrength = GL20.glGetUniformLocation(shaderProgram, "EmissiveStrength");

        uRenderPass = GL20.glGetUniformLocation(shaderProgram, "uRenderPass");

        // 调试日志 - 帮助排查 uniform 问题
        Nebula.LOGGER.info("[GpuParticleRenderer] Particle shader uniforms:");
        Nebula.LOGGER.info("  ModelViewMat={}, ProjMat={}", uModelViewMat, uProjMat);
        Nebula.LOGGER.info("  CameraRight={}, CameraUp={}, Origin={}", uCameraRight, uCameraUp, uOrigin);
        Nebula.LOGGER.info("  Sampler0={}, UseTexture={}", uSampler0, uUseTexture);
        Nebula.LOGGER.info("  PartialTicks={}, EmissiveStrength={}", uPartialTicks, uEmissiveStrength);
        Nebula.LOGGER.info("  RenderPass={}", uRenderPass);
    }

    // Global OIT (Order Independent Transparency) Support
    // ==========================================

    // 保存 Global OIT 状态
    private static int globalOitTargetFboId = -1;
    private static boolean globalOitActive = false;
    private static boolean globalOitCleared = false; // 确保每帧只清空一次
    @SuppressWarnings("unused")
    private static int globalOitViewportWidth = 0;
    @SuppressWarnings("unused")
    private static int globalOitViewportHeight = 0;
    private static final int[] oitCachedViewport = new int[4];

    /**
     * 开始 OIT 全局阶段
     * 必须在批量绘制粒子之前调用。
     * 只做初始化，不立即绑定 OIT FBO（因为 Pass 1 需要绘制到主 FBO）
     */
    public static void beginOIT(int targetFboId, int viewportWidth, int viewportHeight) {
        if (!initialized || oitFbo == null || oitProgram <= 0)
            return;

        RenderSystem.assertOnRenderThread();

        // 保存状态供后续使用
        globalOitTargetFboId = targetFboId;
        globalOitActive = true;
        globalOitCleared = false; // 新帧开始，重置清空标志
        globalOitViewportWidth = viewportWidth;
        globalOitViewportHeight = viewportHeight;

        // 调整 OIT FBO 大小
        oitFbo.resize(viewportWidth, viewportHeight);

        // Backup Viewport
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, oitCachedViewport);
        oitFbo.bindAndShareDepth(targetFboId);
        oitFbo.clear(); // 强制清空：Accum=0, Reveal=1 (背景可见)
        globalOitCleared = true; // 标记已清空，防止 renderOITBatch 重复清空(虽然重复清空也没事)
        // 切回目标 FBO，准备给 Pass 1 (不透明粒子) 使用
        if (targetFboId == MinecraftClient.getInstance().getFramebuffer().fbo) {
            MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        } else {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFboId);
        }
    }

    /**
     * 结束 OIT 全局阶段并合成
     * 必须在所有粒子绘制完成后调用。
     * - 切回主 FBO
     * - 执行全屏合成 Pass
     * - 恢复状态
     * 
     * @param targetFboId 目标 FBO ID
     */
    public static void endOITAndComposite(int targetFboId) {
        if (!initialized || oitFbo == null || oitProgram <= 0)
            return;

        RenderSystem.assertOnRenderThread();

        // Pass 3: Composite
        if (!IrisUtil.isIrisRenderingActive() && targetFboId == MinecraftClient.getInstance().getFramebuffer().fbo) {
            MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        } else {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFboId);
        }

        GL20.glUseProgram(oitProgram);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oitFbo.getAccumTexture());
        if (uOitAccum != -1)
            GL20.glUniform1i(uOitAccum, 0);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oitFbo.getRevealTexture());
        if (uOitReveal != -1)
            GL20.glUniform1i(uOitReveal, 1);

        // Draw Quad
        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0); // Pos
        GL20.glEnableVertexAttribArray(1); // UV
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        // 重置一下指针以防万一
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);

        GL31.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        GL30.glBindVertexArray(0);

        // === 完整状态恢复 ===

        // 1. 重置 Global OIT 状态
        globalOitActive = false;
        globalOitTargetFboId = -1;
        globalOitCleared = false;

        // 2. 正确重置 Shader (必须通知 RenderSystem！)
        GL20.glUseProgram(0);
        RenderSystem.setShader(() -> null);

        // 3. 恢复 OpenGL 状态
        RenderSystem.depthMask(true);
        RenderSystem.depthMask(true);
        // 【CRITICAL FIX】显式重置 BlendFunc，防止影响原版渲染
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // 4. 解绑粒子纹理
        // ParticleTextureManager.unbind(); // Removed
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);

        // 5. 重置纹理绑定 (使用 RenderSystem 确保状态同步)
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        RenderSystem.bindTexture(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(0);

        // 6. Restore Viewport
        GL11.glViewport(oitCachedViewport[0], oitCachedViewport[1], oitCachedViewport[2], oitCachedViewport[3]);
    }

    /**
     * OIT 批量绘制 (Two-Pass: Opaque + Translucent)
     * 
     * 执行两次绘制：
     * - Pass 1: 不透明粒子绘制到主 FBO
     * - Pass 2: 半透明粒子绘制到 OIT FBO（积累阶段）
     * 
     * 必须在 beginOIT 之后、endOITAndComposite 之前调用。
     */
    public static void renderOITBatch(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            boolean useTexture, int glTextureId, float partialTicks) {

        if (particleCount <= 0 || data == null || !initialized || !shaderCompiled)
            return;

        if (!globalOitActive || oitFbo == null) {
            Nebula.LOGGER.warn("[GpuParticleRenderer] renderOITBatch called without beginOIT!");
            return;
        }

        RenderSystem.assertOnRenderThread();

        int dataSize = data.remaining();
        lastFrameUsedBytes = dataSize;

        // Ensure buffer capacity
        if (dataSize > currentBufferSize) {
            expandBuffers(dataSize);
        }

        // Upload Data
        int ssbo;
        if (useFallback) {
            ssbo = uploadDataFallback(data, dataSize);
        } else {
            ssbo = uploadDataPMB(data, dataSize);
        }

        // Bind VAO & SSBO
        GL30.glBindVertexArray(vao);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, ssbo);

        // Use Particle Shader
        GL20.glUseProgram(shaderProgram);

        // Upload Uniforms
        uploadMatrix(uModelViewMat, modelViewMatrix);
        uploadMatrix(uProjMat, projMatrix);
        if (uCameraRight != -1)
            GL20.glUniform3f(uCameraRight, cameraRight[0], cameraRight[1], cameraRight[2]);
        if (uCameraUp != -1)
            GL20.glUniform3f(uCameraUp, cameraUp[0], cameraUp[1], cameraUp[2]);
        if (uOrigin != -1)
            GL20.glUniform3f(uOrigin, originX, originY, originZ);
        if (uPartialTicks != -1)
            GL20.glUniform1f(uPartialTicks, partialTicks);
        if (uPartialTicks != -1)
            GL20.glUniform1f(uPartialTicks, partialTicks);

        // 动态设置 HDR 强度: Iris 开启时使用用户自定义的亮度，关闭时保持原色(1.0)
        // 移至此处以确保同时应用于 Pass 1 (Opaque) 和 Pass 2 (Translucent)
        float currentEmissive = IrisUtil.isIrisRenderingActive() ? ModConfig.getInstance().getEmissiveStrength() : 1.0f;
        if (uEmissiveStrength != -1)
            GL20.glUniform1f(uEmissiveStrength, currentEmissive);

        // Textures
        if (useTexture && glTextureId > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);
            if (uSampler0 != -1)
                GL20.glUniform1i(uSampler0, 0);
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 1);
        } else {
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 0);
        }

        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // ==========================================
        // Pass 1: 不透明粒子 (Opaque)
        // 目标: 主 FBO
        // ==========================================
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, globalOitTargetFboId);
        if (uRenderPass != -1)
            GL20.glUniform1i(uRenderPass, 0); // Opaque Pass

        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();

        // 显式重置 GL Blend Func，防止上一次 Batch 的 Pass 2 (OIT Blend) 状态残留。
        // RenderSystem 可能会误以为状态未变而跳过指令，导致第二个 Batch 的不透明粒子
        // 继承了 OIT 的混合模式 (Additive)，从而变成半透明/发亮。
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);

        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

        // ==========================================
        // Pass 2: 半透明粒子 (Translucent)
        // 目标: OIT FBO
        // ==========================================
        oitFbo.bindAndShareDepth(globalOitTargetFboId);
        // 只在第一个 batch 时清空 OIT FBO
        if (!globalOitCleared) {
            oitFbo.clear();
            globalOitCleared = true;
        }

        if (uRenderPass != -1)
            GL20.glUniform1i(uRenderPass, 1); // Translucent Pass

        RenderSystem.depthMask(false); // OIT 核心：不写深度
        RenderSystem.enableBlend();
        // OIT 专用混合模式
        GL40.glBlendFunci(0, GL11.GL_ONE, GL11.GL_ONE); // Accum
        GL40.glBlendFunci(1, GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR); // Reveal

        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

        // Cleanup (部分，完整清理在 endOITAndComposite 中)
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, 0);
        GL30.glBindVertexArray(0);

        // Stats
        PerformanceStats stats = PerformanceStats.getInstance();
        stats.setShaderProgram(shaderProgram);
        stats.setVao(vao);
        stats.setSsbo(ssbo);
        stats.setBufferSizeBytes(currentBufferSize);
        stats.setUsedBufferBytes(lastFrameUsedBytes);
        stats.setPmbSupported(pmbSupported);
        stats.setUsingFallback(useFallback);
        stats.setIrisMode(IrisUtil.isIrisRenderingActive());
        stats.setTargetFboId(globalOitTargetFboId);
        stats.setOrigin(originX, originY, originZ);
        stats.setLastGlError(GL11.glGetError(), "Post-OIT-Batch");

        // PMB Fence Logic
        if (!useFallback) {
            if (fences[currentBufferIndex] != 0) {
                GL32.glDeleteSync(fences[currentBufferIndex]);
            }
            fences[currentBufferIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentBufferIndex = (currentBufferIndex + 1) % BUFFER_COUNT;
        }
    }

    // Standard Batch Rendering State
    private static boolean standardBatchActive = false;
    private static int standardRestoreFboId = -1;
    private static int[] standardViewport = new int[4];

    /**
     * 开始标准渲染批量 (Standard/Additive)
     * <p>
     * 初始化渲染状态，绑定 Shader 和 VAO，上传通用 Uniform。
     * 必须在调用 renderStandardBatch 之前调用。
     * </p>
     * 
     * @param restoreFboId 渲染结束后需要恢复的 FBO ID。如果为 -1，则不执行恢复。
     */
    public static void beginStandardRendering(Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp, int restoreFboId) {
        if (!initialized || !shaderCompiled)
            return;

        RenderSystem.assertOnRenderThread();

        if (standardBatchActive) {
            Nebula.LOGGER.warn("[GpuParticleRenderer] beginStandardRendering called while already active!");
            return;
        }

        standardBatchActive = true;
        standardRestoreFboId = restoreFboId;

        // Backup Viewport
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, standardViewport);

        // Render State
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // Bind Shader & VAO
        GL20.glUseProgram(shaderProgram);
        GL30.glBindVertexArray(vao);

        // Upload Common Uniforms
        uploadMatrix(uModelViewMat, modelViewMatrix);
        uploadMatrix(uProjMat, projMatrix);

        if (uCameraRight != -1)
            GL20.glUniform3f(uCameraRight, cameraRight[0], cameraRight[1], cameraRight[2]);
        if (uCameraUp != -1)
            GL20.glUniform3f(uCameraUp, cameraUp[0], cameraUp[1], cameraUp[2]);

        // Emissive Strength
        float currentEmissive = IrisUtil.isIrisRenderingActive() ? ModConfig.getInstance().getEmissiveStrength() : 1.0f;
        if (uEmissiveStrength != -1)
            GL20.glUniform1f(uEmissiveStrength, currentEmissive);
    }

    /**
     * 结束标准渲染批量
     * <p>
     * 恢复渲染状态，解绑资源。
     * </p>
     */
    public static void endStandardRendering() {
        if (!standardBatchActive)
            return;

        RenderSystem.assertOnRenderThread();

        // 1. Unbind Resources
        GL30.glBindVertexArray(0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, 0);
        // ParticleTextureManager.unbind(); // Removed
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);

        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // 2. Restore State
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();

        // 3. Reset Shader
        GL20.glUseProgram(0);
        RenderSystem.setShader(() -> null);

        // 4. Restore Viewport
        GL11.glViewport(standardViewport[0], standardViewport[1], standardViewport[2], standardViewport[3]);

        // 5. Restore Framebuffer
        if (standardRestoreFboId >= 0) {
            // 如果是 MC 主 FBO，用封装方法（更新状态）
            if (standardRestoreFboId == MinecraftClient.getInstance().getFramebuffer().fbo) {
                MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
            } else {
                // 否则直接 GL 绑定 (Iris 的 buffer)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, standardRestoreFboId);
            }
        }

        standardBatchActive = false;
        standardRestoreFboId = -1;
    }

    /**
     * 渲染标准批量中的一个实例
     * <p>
     * 必须在 beginStandardRendering 和 endStandardRendering 之间调用。
     * </p>
     */
    public static void renderStandardBatch(ByteBuffer data, int particleCount,
            float originX, float originY, float originZ,
            boolean useTexture, int glTextureId, float partialTicks) {

        if (!standardBatchActive) {
            Nebula.LOGGER.warn("[GpuParticleRenderer] renderStandardBatch called without beginStandardRendering!");
            return;
        }

        if (particleCount <= 0 || data == null)
            return;

        RenderSystem.assertOnRenderThread();

        int dataSize = data.remaining();
        lastFrameUsedBytes = dataSize;

        // Buffer Management
        if (dataSize > currentBufferSize) {
            expandBuffers(dataSize);
        }

        int ssbo;
        if (useFallback) {
            ssbo = uploadDataFallback(data, dataSize);
        } else {
            ssbo = uploadDataPMB(data, dataSize);
        }

        // Bind SSBO
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, ssbo);

        // Upload Instance-Specific Uniforms
        if (uOrigin != -1)
            GL20.glUniform3f(uOrigin, originX, originY, originZ);
        if (uPartialTicks != -1)
            GL20.glUniform1f(uPartialTicks, partialTicks);

        // Texture Binding
        if (useTexture && glTextureId > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);
            if (uSampler0 != -1)
                GL20.glUniform1i(uSampler0, 0);
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 1);
        } else {
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 0);
        }

        // Drawing Passes (2-Pass: Opaque + Translucent)
        BlendMode blendMode = ModConfig.getInstance().getBlendMode();

        // Pass 1: Opaque
        if (uRenderPass != -1)
            GL20.glUniform1i(uRenderPass, 0); // Opaque Pass

        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);

        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

        // Pass 2: Translucent
        if (uRenderPass != -1)
            GL20.glUniform1i(uRenderPass, 2); // Translucent Pass (Standard)

        RenderSystem.depthMask(false);

        switch (blendMode) {
            case ADDITIVE:
                RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
                break;
            case ALPHA:
            default:
                RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA,
                        GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
                break;
        }

        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

        // Stats
        PerformanceStats stats = PerformanceStats.getInstance();
        stats.setShaderProgram(shaderProgram);
        stats.setVao(vao);
        stats.setSsbo(ssbo);
        stats.setBufferSizeBytes(currentBufferSize);
        stats.setUsedBufferBytes(lastFrameUsedBytes);
        stats.setPmbSupported(pmbSupported);
        stats.setUsingFallback(useFallback);
        stats.setIrisMode(IrisUtil.isIrisRenderingActive());
        stats.setTargetFboId(standardRestoreFboId);
        stats.setOrigin(originX, originY, originZ);
        stats.setLastGlError(GL11.glGetError(), "Post-Standard-Batch-Instance");
        // Other stats are set per frame in Manager

        // Fence Logic
        if (!useFallback) {
            if (fences[currentBufferIndex] != 0) {
                GL32.glDeleteSync(fences[currentBufferIndex]);
            }
            fences[currentBufferIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentBufferIndex = (currentBufferIndex + 1) % BUFFER_COUNT;
        }
    }

    /**
     * PMB 模式数据上传
     */
    private static int uploadDataPMB(ByteBuffer data, int dataSize) {
        PerformanceStats stats = PerformanceStats.getInstance();
        stats.beginDataUpload();
        int bufferIndex = currentBufferIndex;

        // 等待 GPU 完成对这个缓冲区的使用
        if (fences[bufferIndex] != 0) {
            int waitResult = GL32.glClientWaitSync(fences[bufferIndex], GL32.GL_SYNC_FLUSH_COMMANDS_BIT,
                    1_000_000_000L); // 1秒超时
            if (waitResult == GL32.GL_WAIT_FAILED) {
                Nebula.LOGGER.warn("[GpuParticleRenderer] Fence wait failed");
            } else if (waitResult == GL32.GL_TIMEOUT_EXPIRED) {
                Nebula.LOGGER.warn("[GpuParticleRenderer] Fence wait timeout");
            }
            GL32.glDeleteSync(fences[bufferIndex]);
            fences[bufferIndex] = 0;
        }

        // 【性能优化】使用 MemoryUtil.memCopy 直接内存拷贝
        // 相比 ByteBuffer.put()，消除了 Java NIO 的边界检查开销
        // 对于 20MB+ 数据，CPU 占用显著降低
        ByteBuffer mappedBuffer = mappedBuffers[bufferIndex];
        long destAddress = MemoryUtil.memAddress(mappedBuffer);
        long srcAddress = MemoryUtil.memAddress(data);
        MemoryUtil.memCopy(srcAddress, destAddress, dataSize);
        stats.endDataUpload(); // 计时
        return ssbos[bufferIndex];
    }

    /**
     * 降级模式数据上传（传统 glBufferSubData）
     */
    private static int uploadDataFallback(ByteBuffer data, int dataSize) {
        PerformanceStats stats = PerformanceStats.getInstance();
        stats.beginDataUpload(); // 统计数据
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[0]);

        // Orphan the buffer (废弃旧数据，避免同步等待)
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);

        // 写入数据
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        stats.endDataUpload(); // 计时
        return ssbos[0];
    }

    /**
     * 扩容缓冲区
     */
    private static void expandBuffers(int needed) {
        int newSize = Math.max(currentBufferSize * 2, needed);
        Nebula.LOGGER.info("[GpuParticleRenderer] Expanding buffers: {} MB -> {} MB",
                currentBufferSize / 1024 / 1024, newSize / 1024 / 1024);

        // 清理旧缓冲区
        cleanupBuffers();

        currentBufferSize = newSize;

        // 重新创建缓冲区
        if (pmbSupported && !useFallback) {
            createPersistentMappedBuffers();
        } else {
            createFallbackBuffer();
        }
    }

    /**
     * 清理缓冲区（不清理 shader 和 VAO）
     */
    private static void cleanupBuffers() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (fences[i] != 0) {
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0;
            }
            if (mappedBuffers[i] != null) {
                // 解除映射
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[i]);
                GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
                mappedBuffers[i] = null;
            }
            if (ssbos[i] != 0) {
                GL15.glDeleteBuffers(ssbos[i]);
                ssbos[i] = 0;
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void uploadMatrix(int location, Matrix4f matrix) {
        if (location >= 0 && matrix != null) {
            matrixBuffer.clear();
            matrix.get(matrixBuffer);
            matrixBuffer.rewind();
            GL20.glUniformMatrix4fv(location, false, matrixBuffer);
        }
    }

    /**
     * 清理渲染器
     */
    public static void cleanup() {
        cleanupBuffers();

        if (vao != -1) {
            GL30.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (quadVbo != -1) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = -1;
        }
        if (shaderProgram > 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }

        if (oitFbo != null) {
            // oitFbo.delete(); // Commenting out until method verified
        }

        // ParticleTextureManager.cleanup(); // Removed

        initialized = false;
        shaderCompiled = false;
        pmbSupported = false;
        useFallback = false;
    }

    public static void shrinkBuffer() {
        // PMB 模式下缩容需要重新创建缓冲区，开销较大
        // 这里选择不自动缩容，保持当前大小
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
    }

    private static String loadShaderSource(Identifier id) {
        try {
            Optional<net.minecraft.resource.Resource> resource = MinecraftClient.getInstance().getResourceManager()
                    .getResource(id);
            if (resource.isPresent()) {
                try (InputStream is = resource.get().getInputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (IOException e) {
            Nebula.LOGGER.error("[GpuParticleRenderer] Failed to load shader: {}", id, e);
        }
        return null;
    }

    /**
     * 创建 Shader 程序 (使用默认粒子 Shader)
     */
    private static int createShaderProgram() {
        return createShaderProgram(VERTEX_SHADER_ID, FRAGMENT_SHADER_ID);
    }

    /**
     * 创建 Shader 程序
     * 
     * @param vshId 顶点着色器 ID
     * @param fshId 片元着色器 ID
     * @return Shader 程序句柄
     */
    private static int createShaderProgram(Identifier vshId, Identifier fshId) {
        String vertexSource = loadShaderSource(vshId);
        String fragmentSource = loadShaderSource(fshId);
        if (vertexSource == null || fragmentSource == null)
            return -1;
        int vertexShader = 0, fragmentShader = 0, program = 0;
        try {
            vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vertexShader, vertexSource);
            GL20.glCompileShader(vertexShader);

            if (GL20.glGetShaderi(vertexShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                Nebula.LOGGER.error("[GpuParticleRenderer] Vertex shader compile error ({}): {}", vshId,
                        GL20.glGetShaderInfoLog(vertexShader));
            }

            fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fragmentShader, fragmentSource);
            GL20.glCompileShader(fragmentShader);

            if (GL20.glGetShaderi(fragmentShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                Nebula.LOGGER.error("[GpuParticleRenderer] Fragment shader compile error ({}): {}", fshId,
                        GL20.glGetShaderInfoLog(fragmentShader));
            }

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            // 绑定 Quad 属性
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "UV");
            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                Nebula.LOGGER.error("[GpuParticleRenderer] Shader program link error: {}",
                        GL20.glGetProgramInfoLog(program));
            }

            return program;
        } finally {
            if (vertexShader != 0)
                GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0)
                GL20.glDeleteShader(fragmentShader);
        }
    }

    /**
     * 检查是否初始化
     * 
     * @return 是否初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isShaderCompiled() {
        return shaderCompiled;
    }

    public static int getBufferSize() {
        return currentBufferSize;
    }

    public static int getTypeSize() {
        return lastFrameUsedBytes;
    }

    /**
     * 预分配 OIT 资源
     * 
     * @param width  视口宽度
     * @param height 视口高度
     */
    public static void preloadOIT(int width, int height) {
        if (!initialized || oitFbo == null)
            return;
        Nebula.LOGGER.info("[GpuParticleRenderer] Pre-allocating OIT resources for size {}x{}", width, height);
        oitFbo.resize(width, height);
        // 预清空一下，防止脏数据
        oitFbo.bindAndShareDepth(0);
        oitFbo.clear();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 检查是否支持 PMB
     * 
     * @return 是否支持 PMB
     */
    public static boolean isPMBSupported() {
        return pmbSupported && !useFallback;
    }
}