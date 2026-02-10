package com.atemukesu.nebula.client.render;

import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.config.BlendMode;
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
 * GPU 粒子渲染器 (Persistent Mapped Buffer 高性能版)
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

    // 发光强度
    private static float emissiveStrength = 1.0f;

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
            ParticleTextureManager.init();

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
        // 注意：这里不绑定 OIT FBO，因为 Pass 1 需要绘制到主 FBO
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
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFboId);

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
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        // 4. 解绑粒子纹理
        ParticleTextureManager.unbind();

        // 5. 重置纹理绑定 (使用 RenderSystem 确保状态同步)
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        RenderSystem.bindTexture(0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
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
            boolean useTexture, float partialTicks) {

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
        if (uEmissiveStrength != -1)
            GL20.glUniform1f(uEmissiveStrength, emissiveStrength);

        // Textures
        if (useTexture && ParticleTextureManager.isInitialized()) {
            ParticleTextureManager.bind(0);
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

        // 动态设置 HDR 强度: Iris 开启时增强亮度(1.5)，关闭时保持原色(1.0)
        float currentEmissive = IrisUtil.isIrisRenderingActive() ? 1.5f : 1.0f;
        if (uEmissiveStrength != -1)
            GL20.glUniform1f(uEmissiveStrength, currentEmissive);

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

    // TODO: 2. 当粒子距离相机过远时，会异常发亮
    // TODO: 3. 粒子光效不柔和

    /**
     * 执行 SSBO 实例化渲染
     * 
     * @param bindFramebuffer 是否绑定 MC 主 Framebuffer。
     *                        在 Iris 环境下应传入 false，保持 Iris 的渲染目标。
     *                        在原版环境下应传入 true。
     */
    public static void renderInstanced(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            boolean useTexture, float partialTicks,
            boolean bindFramebuffer) {

        if (particleCount <= 0 || data == null || !initialized || !shaderCompiled)
            return;

        RenderSystem.assertOnRenderThread();

        int dataSize = data.remaining();
        lastFrameUsedBytes = dataSize;

        // 检查是否需要扩容（PMB 模式下需要重新创建缓冲区）
        if (dataSize > currentBufferSize) {
            expandBuffers(dataSize);
        }

        // 选择当前帧使用的缓冲区，并上传数据
        int ssbo;
        if (useFallback) {
            ssbo = uploadDataFallback(data, dataSize);
        } else {
            ssbo = uploadDataPMB(data, dataSize);
        }

        // 备份视口
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        // 【Iris 兼容】只有在非 Iris 模式下，才强制绑定 MC 主 Framebuffer
        if (bindFramebuffer && !IrisUtil.isIrisRenderingActive()) {
            MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        }

        // 【Iris 兼容】在使用 Iris 时，显式绑定其半透明 Framebuffer
        // 参考 NeoInstancedRenderManager，通过反射调用 Iris 的 bind() 方法
        if (IrisUtil.isIrisRenderingActive()) {
            IrisUtil.bindIrisTranslucentFramebuffer();
        }

        // 获取当前绑定的 Framebuffer ID (无论是 MC 的还是 Iris 的)
        // 此时如果是 Iris 模式，targetFboId 应该是 Iris 的 writingToAfterTranslucent FBO
        int targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // 渲染状态设置
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        BlendMode blendMode = ModConfig.getInstance().getBlendMode();

        // 绑定 SSBO 到 Binding Point 0
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, ssbo);
        GL30.glBindVertexArray(vao); // 绑定粒子 VAO

        // 使用粒子 Shader
        GL20.glUseProgram(shaderProgram);

        // Upload Common Uniforms
        uploadMatrix(uModelViewMat, modelViewMatrix);
        uploadMatrix(uProjMat, projMatrix);

        // uniform 有效性检查
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

        // 动态设置 HDR 强度: Iris 开启时增强亮度(1.5)，关闭时保持原色(1.0)
        float currentEmissive = IrisUtil.isIrisRenderingActive() ? 1.5f : 1.0f;
        if (uEmissiveStrength != -1)
            GL20.glUniform1f(uEmissiveStrength, currentEmissive);

        // 纹理设置
        if (useTexture && ParticleTextureManager.isInitialized()) {
            ParticleTextureManager.bind(0);
            if (uSampler0 != -1)
                GL20.glUniform1i(uSampler0, 0);
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 1);
        } else {
            if (uUseTexture != -1)
                GL20.glUniform1i(uUseTexture, 0);
        }

        // --- 渲染逻辑分支 ---

        if (blendMode == BlendMode.OIT && oitFbo != null && oitProgram > 0) {
            // ==========================================
            // Pass 1: 不透明粒子 (Opaque)
            // 目标: 主 FBO (Iris/MC)
            // ==========================================
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFboId);
            if (uRenderPass != -1)
                GL20.glUniform1i(uRenderPass, 0); // Opaque Pass

            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
            // 绘制实体粒子
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

            // ==========================================
            // Pass 2: 半透明粒子 (Translucent)
            // 目标: OIT FBO
            // ==========================================
            oitFbo.resize(viewport[2], viewport[3]);
            oitFbo.bindAndShareDepth(targetFboId);
            oitFbo.clear(); // Clear Accum(0) & Reveal(1)

            // 保持使用 粒子 Shader (shaderProgram)
            if (uRenderPass != -1)
                GL20.glUniform1i(uRenderPass, 1); // Translucent Pass

            RenderSystem.depthMask(false); // OIT 核心：不写深度
            RenderSystem.enableBlend();
            // OIT 专用混合模式 (Requires GL 4.0+)
            GL40.glBlendFunci(0, GL11.GL_ONE, GL11.GL_ONE); // Accum
            GL40.glBlendFunci(1, GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR); // Reveal

            // 绘制半透明粒子 (Instanced)
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

            // ==========================================
            // Pass 3: 全屏合成 (Composite)
            // 目标: 主 FBO (Iris/MC)
            // ==========================================
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFboId);

            // 切换到 合成 Shader
            GL20.glUseProgram(oitProgram);

            // 恢复状态用于合成
            RenderSystem.depthMask(false); // 合成时不写深度，只贴颜色
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); // 标准混合把结果贴上去

            // 绑定纹理
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oitFbo.getAccumTexture());
            if (uOitAccum != -1)
                GL20.glUniform1i(uOitAccum, 0);

            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oitFbo.getRevealTexture());
            if (uOitReveal != -1)
                GL20.glUniform1i(uOitReveal, 1);

            // 绘制全屏四边形 (使用 Quad VBO)
            // 注意：这里的 VAO 仍然是粒子的 VAO，但只要它有 Position (0) 属性且 Quad VBO 有数据即可
            GL31.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);

        } else {
            // === 传统渲染路径 (Two-Pass: Opaque + Translucent) ===
            //
            // 逻辑：
            // Pass 1: 不透明粒子 - 开启深度写入，使用 Alpha 混合，uRenderPass = 0
            // Pass 2: 透明粒子 - 关闭深度写入，使用配置的混合模式，uRenderPass = 1

            // ==========================================
            // Pass 1: 不透明粒子 (Opaque)
            // ==========================================
            if (uRenderPass != -1)
                GL20.glUniform1i(uRenderPass, 0); // Opaque Pass

            RenderSystem.depthMask(true); // 关键：不透明粒子必须写深度！
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);

            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);

            // ==========================================
            // Pass 2: 透明粒子 (Translucent)
            // ==========================================
            // ==========================================
            // Pass 2: 透明粒子 (Translucent)
            // ==========================================
            if (uRenderPass != -1)
                GL20.glUniform1i(uRenderPass, 2); // Translucent Pass (Mode 2 = Standard HDR/LDR output)

            RenderSystem.depthMask(false); // 透明粒子不写深度

            // 根据配置应用混合模式 (只用于透明粒子)
            switch (blendMode) {
                case ADDITIVE:
                    // 加法混合：发光粒子效果
                    RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
                    break;
                case ALPHA:
                default:
                    // 标准 Alpha 混合
                    RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA,
                            GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
                    break;
            }

            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);
        }

        // 收集性能数据到 PerformanceStats
        PerformanceStats stats = PerformanceStats.getInstance();
        stats.setShaderProgram(shaderProgram);
        stats.setVao(vao);
        stats.setSsbo(ssbo);
        stats.setBufferSizeBytes(currentBufferSize);
        stats.setUsedBufferBytes(lastFrameUsedBytes);
        stats.setPmbSupported(pmbSupported);
        stats.setUsingFallback(useFallback);
        stats.setIrisMode(!bindFramebuffer);
        stats.setOrigin(originX, originY, originZ);
        stats.setShouldBindFramebuffer(bindFramebuffer);
        stats.setLastGlError(GL11.glGetError(), "Post-render"); // 简单检查错误

        // PMB Fence Logic
        if (!useFallback) {
            if (fences[currentBufferIndex] != 0) {
                GL32.glDeleteSync(fences[currentBufferIndex]);
            }
            fences[currentBufferIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            currentBufferIndex = (currentBufferIndex + 1) % BUFFER_COUNT;
        }

        // === 状态恢复===

        // 1. 解绑我们使用的资源
        GL30.glBindVertexArray(0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, 0); // 关键：解绑 SSBO
        ParticleTextureManager.unbind();

        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // 2. 恢复 OpenGL 状态
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();

        // 3. 正确重置 Shader
        // 不要只调用 glUseProgram(0)，必须通知 RenderSystem 我们清理了 Shader
        // 否则 MC 以为 Shader 还是它的，直接 setUniform 就会报错崩溃
        GL20.glUseProgram(0);
        RenderSystem.setShader(() -> null);

        // 4. 恢复视口
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        // 5. 恢复 Framebuffer 状态
        if (bindFramebuffer) {
            // 确保切回 MC 的写状态
            MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        }

        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /**
     * PMB 模式数据上传
     */
    private static int uploadDataPMB(ByteBuffer data, int dataSize) {
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

        return ssbos[bufferIndex];
    }

    /**
     * 降级模式数据上传（传统 glBufferSubData）
     */
    private static int uploadDataFallback(ByteBuffer data, int dataSize) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbos[0]);

        // Orphan the buffer (废弃旧数据，避免同步等待)
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);

        // 写入数据
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

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
     * 渲染粒子（默认绑定 MC 主 Framebuffer，用于原版渲染路径）
     */
    public static void render(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            float partialTicks) {
        render(data, particleCount, modelViewMatrix, projMatrix,
                cameraRight, cameraUp, originX, originY, originZ, partialTicks, true);
    }

    /**
     * 渲染粒子
     * 
     * @param data            粒子数据缓冲区
     * @param particleCount   粒子数量
     * @param modelViewMatrix 模型视图矩阵
     * @param projMatrix      投影矩阵
     * @param cameraRight     相机右向量
     * @param cameraUp        相机上向量
     * @param originX         原点 X
     * @param originY         原点 Y
     * @param originZ         原点 Z
     * @param partialTicks    部分 ticks
     * @param bindFramebuffer 是否绑定 MC 主 Framebuffer。
     *                        Iris 环境下应传入 false，原版环境下应传入 true。
     */
    public static void render(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            float partialTicks, boolean bindFramebuffer) {
        if (shaderCompiled) {
            renderInstanced(data, particleCount, modelViewMatrix, projMatrix,
                    cameraRight, cameraUp, originX, originY, originZ, true, partialTicks, bindFramebuffer);
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
        ParticleTextureManager.cleanup();
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
     * 检查是否支持 PMB
     * 
     * @return 是否支持 PMB
     */
    public static boolean isPMBSupported() {
        return pmbSupported && !useFallback;
    }

    /**
     * 获取当前发光强度
     * 
     * @return 发光强度值 (默认 1.5)
     */
    public static float getEmissiveStrength() {
        return emissiveStrength;
    }

    /**
     * 设置发光强度
     * 
     * @param strength 发光强度值 (建议范围 0.5 - 3.0)
     */
    public static void setEmissiveStrength(float strength) {
        emissiveStrength = Math.max(0.1f, Math.min(5.0f, strength));
        Nebula.LOGGER.info("[GpuParticleRenderer] Emissive strength set to: {}", emissiveStrength);
    }
}