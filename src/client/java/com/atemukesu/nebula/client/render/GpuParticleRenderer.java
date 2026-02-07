package com.atemukesu.nebula.client.render;

import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * GPU 粒子渲染器 (SSBO 高性能版)
 * <p>
 * 使用 OpenGL 4.3+ Shader Storage Buffer Object (SSBO) 存储粒子数据。
 * 相比传统的 Vertex Attributes，SSBO 极大减少了驱动开销并允许更灵活的内存访问。
 * </p>
 */
public class GpuParticleRenderer {

    private static final Identifier VERTEX_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.vsh");
    private static final Identifier FRAGMENT_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.fsh");

    // OpenGL 对象句柄
    private static int vao = -1;
    private static int quadVbo = -1;
    private static int ssbo = -1; // Shader Storage Buffer Object
    private static int shaderProgram = -1;

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

    // SSBO 动态扩容控制
    private static int currentBufferSize = 0;

    // Shader 绑定点 (必须与 shader 中的 binding = 0 一致)
    private static final int SSBO_BINDING_INDEX = 0;

    // 临时矩阵缓冲
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private static int lastFrameUsedBytes = 0;

    /**
     * 初始化渲染器
     * <p>
     * 创建 Shader, VAO, Quad VBO 和 SSBO。
     * </p>
     */
    public static void init() {
        if (initialized)
            return;

        RenderSystem.assertOnRenderThread();

        try {
            ParticleTextureManager.init();

            // 1. 编译 Shader
            shaderProgram = createShaderProgram();
            if (shaderProgram > 0) {
                shaderCompiled = true;
                resolveUniforms();
            }

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

            // 注意：不再需要 glVertexAttribDivisor，数据全走 SSBO

            GL30.glBindVertexArray(0);

            // 4. 创建 SSBO
            ssbo = GL15.glGenBuffers();
            currentBufferSize = 8 * 1024 * 1024; // 初始 8MB
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            initialized = true;
            Nebula.LOGGER.info("GpuParticleRenderer initialized (SSBO Mode).");
        } catch (Exception e) {
            Nebula.LOGGER.error("Failed to initialize GpuParticleRenderer", e);
        }
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
    }

    /**
     * 执行 SSBO 实例化渲染
     */
    @SuppressWarnings("deprecation")
    public static void renderInstanced(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            boolean useTexture, float partialTicks) {

        if (particleCount <= 0 || data == null || !initialized || !shaderCompiled)
            return;

        RenderSystem.assertOnRenderThread();

        // 确保写入 Main Framebuffer
        MinecraftClient.getInstance().getFramebuffer().beginWrite(false);

        // 备份视口
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        // 渲染状态设置
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);

        GL20.glUseProgram(shaderProgram);

        // Upload Uniforms
        uploadMatrix(uModelViewMat, modelViewMatrix);
        uploadMatrix(uProjMat, projMatrix);
        GL20.glUniform3f(uCameraRight, cameraRight[0], cameraRight[1], cameraRight[2]);
        GL20.glUniform3f(uCameraUp, cameraUp[0], cameraUp[1], cameraUp[2]);
        GL20.glUniform3f(uOrigin, originX, originY, originZ);
        if (uPartialTicks != -1)
            GL20.glUniform1f(uPartialTicks, partialTicks);

        // 纹理设置
        if (useTexture && ParticleTextureManager.isInitialized()) {
            ParticleTextureManager.bind(0);
            GL20.glUniform1i(uSampler0, 0);
            GL20.glUniform1i(uUseTexture, 1);
        } else {
            GL20.glUniform1i(uUseTexture, 0);
        }

        // === SSBO 数据上传 ===
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        int needed = data.remaining();
        lastFrameUsedBytes = needed;

        // 自动扩容策略
        if (needed > currentBufferSize) {
            int newSize = Math.max(currentBufferSize * 2, needed);
            currentBufferSize = newSize;
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
            Nebula.LOGGER.info("SSBO Expanded to {} MB", newSize / 1024 / 1024);
        } else {
            // Orphan the buffer (废弃旧数据，避免同步等待)
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
        }

        // 写入数据
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, data);

        // 绑定 SSBO 到 Binding Point 0
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING_INDEX, ssbo);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0); // Unbind generic target

        // === Draw Call ===
        GL30.glBindVertexArray(vao);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);
        GL30.glBindVertexArray(0);

        // === 状态恢复 ===
        ParticleTextureManager.unbind();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        RenderSystem.setShader(() -> null); // Clear internal shader cache
        MinecraftClient.getInstance().getFramebuffer().beginWrite(false);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    private static void uploadMatrix(int location, Matrix4f matrix) {
        if (location >= 0 && matrix != null) {
            matrixBuffer.clear();
            matrix.get(matrixBuffer);
            matrixBuffer.rewind();
            GL20.glUniformMatrix4fv(location, false, matrixBuffer);
        }
    }

    // 对外封装方法
    public static void render(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            float partialTicks) {
        if (shaderCompiled) {
            renderInstanced(data, particleCount, modelViewMatrix, projMatrix,
                    cameraRight, cameraUp, originX, originY, originZ, true, partialTicks);
        }
    }

    public static void cleanup() {
        if (vao != -1) {
            GL30.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (quadVbo != -1) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = -1;
        }
        if (ssbo != -1) {
            GL15.glDeleteBuffers(ssbo);
            ssbo = -1;
        }
        if (shaderProgram > 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }
        ParticleTextureManager.cleanup();
        initialized = false;
        shaderCompiled = false;
    }

    public static void shrinkBuffer() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(GpuParticleRenderer::shrinkBuffer);
            return;
        }
        int initialSize = 1024 * 1024;
        if (currentBufferSize > initialSize && ssbo != -1) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, initialSize, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            currentBufferSize = initialSize;
        }
    }

    // ... loadShaderSource, createShaderProgram (保持不变，省略) ...
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
            Nebula.LOGGER.error("Failed to load shader: {}", id, e);
        }
        return null;
    }

    private static int createShaderProgram() {
        String vertexSource = loadShaderSource(VERTEX_SHADER_ID);
        String fragmentSource = loadShaderSource(FRAGMENT_SHADER_ID);
        if (vertexSource == null || fragmentSource == null)
            return -1;
        int vertexShader = 0, fragmentShader = 0, program = 0;
        try {
            vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vertexShader, vertexSource);
            GL20.glCompileShader(vertexShader);
            fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fragmentShader, fragmentSource);
            GL20.glCompileShader(fragmentShader);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);
            // 绑定 Quad 属性
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "UV");
            GL20.glLinkProgram(program);
            return program;
        } finally {
            if (vertexShader != 0)
                GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0)
                GL20.glDeleteShader(fragmentShader);
        }
    }

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
}