package com.atemukesu.nebula.client.render;

import com.atemukesu.nebula.Nebula;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;

import net.minecraft.resource.Resource;
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
 * GPU 粒子渲染器
 * 使用 OpenGL Instancing + 纹理数组实现高性能粒子渲染
 */
public class GpuParticleRenderer {

    // 着色器资源路径
    private static final Identifier VERTEX_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.vsh");
    private static final Identifier FRAGMENT_SHADER_ID = new Identifier(Nebula.MOD_ID, "shaders/nebula_particle.fsh");

    // OpenGL 对象
    private static int vao = -1;
    private static int quadVbo = -1;
    private static int instanceVbo = -1;
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

    // 动态扩容阈值
    private static int currentBufferSize = 0;
    private static int lastFrameUsedBytes = 0;

    // 单个粒子的字节大小: PrevPos(12) + Pos(12) + Color(4) + Size(4) + TexID(4) + SeqID(4) =
    // 40
    public static final int BYTES_PER_PARTICLE = 40;

    // 临时矩阵缓冲
    private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    /**
     * 初始化渲染器
     */
    public static void init() {
        if (initialized)
            return;

        RenderSystem.assertOnRenderThread();

        try {
            // 1. 初始化纹理管理器
            ParticleTextureManager.init();

            // 2. 从资源文件加载并编译 Shader
            shaderProgram = createShaderProgram();
            if (shaderProgram > 0) {
                shaderCompiled = true;

                // 获取 Uniform 位置
                uModelViewMat = GL20.glGetUniformLocation(shaderProgram, "ModelViewMat");
                uProjMat = GL20.glGetUniformLocation(shaderProgram, "ProjMat");
                uCameraRight = GL20.glGetUniformLocation(shaderProgram, "CameraRight");
                uCameraUp = GL20.glGetUniformLocation(shaderProgram, "CameraUp");
                uOrigin = GL20.glGetUniformLocation(shaderProgram, "Origin");
                uSampler0 = GL20.glGetUniformLocation(shaderProgram, "Sampler0");
                uUseTexture = GL20.glGetUniformLocation(shaderProgram, "UseTexture");
                uPartialTicks = GL20.glGetUniformLocation(shaderProgram, "PartialTicks");
            }

            // 3. 创建 VAO
            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);

            // 4. 创建静态 Quad VBO (单位正方形)
            float[] quadData = {
                    // x, y, z, u, v
                    0, 0, 0, 0, 1,
                    1, 0, 0, 1, 1,
                    1, 1, 0, 1, 0,
                    0, 1, 0, 0, 0
            };
            quadVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadData, GL15.GL_STATIC_DRAW);

            // Quad 属性
            int strideQuad = 5 * 4;
            GL20.glEnableVertexAttribArray(0); // Position
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, strideQuad, 0);
            GL20.glEnableVertexAttribArray(1); // UV
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, strideQuad, 12);

            // 5. 创建 Instance VBO
            instanceVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);

            // 预分配 8MB 显存
            currentBufferSize = 8 * 1024 * 1024;
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);

            // Instance 属性
            int strideInst = BYTES_PER_PARTICLE;

            // 0. PrevPos (vec3) - NEW offset 0
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, strideInst, 0);
            GL33.glVertexAttribDivisor(2, 1);

            // 1. CurrentPos (vec3) - MOVED offset 12
            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, strideInst, 12);
            GL33.glVertexAttribDivisor(3, 1);

            // 2. Color (vec4, normalized) - MOVED offset 24
            GL20.glEnableVertexAttribArray(4);
            GL20.glVertexAttribPointer(4, 4, GL11.GL_UNSIGNED_BYTE, true, strideInst, 24);
            GL33.glVertexAttribDivisor(4, 1);

            // 3. Size (float) - MOVED offset 28
            GL20.glEnableVertexAttribArray(5);
            GL20.glVertexAttribPointer(5, 1, GL11.GL_FLOAT, false, strideInst, 28);
            GL33.glVertexAttribDivisor(5, 1);

            // 4. TexID (float) - MOVED offset 32
            GL20.glEnableVertexAttribArray(6);
            GL20.glVertexAttribPointer(6, 1, GL11.GL_FLOAT, false, strideInst, 32);
            GL33.glVertexAttribDivisor(6, 1);

            // 5. SeqID (float) - MOVED offset 36
            GL20.glEnableVertexAttribArray(7);
            GL20.glVertexAttribPointer(7, 1, GL11.GL_FLOAT, false, strideInst, 36);
            GL33.glVertexAttribDivisor(7, 1);

            GL30.glBindVertexArray(0);
            initialized = true;

            Nebula.LOGGER.info("GpuParticleRenderer initialized (Shader: {}, VBO: {}KB)",
                    shaderCompiled ? "OK" : "FAILED", currentBufferSize / 1024);
        } catch (Exception e) {
            Nebula.LOGGER.error("Failed to initialize GpuParticleRenderer", e);
        }
    }

    /**
     * 从资源文件加载着色器源码
     */
    private static String loadShaderSource(Identifier id) {
        try {
            Optional<Resource> resource = MinecraftClient.getInstance()
                    .getResourceManager().getResource(id);

            if (resource.isPresent()) {
                try (InputStream is = resource.get().getInputStream();
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            } else {
                Nebula.LOGGER.error("Shader resource not found: {}", id);
            }
        } catch (IOException e) {
            Nebula.LOGGER.error("Failed to load shader: {}", id, e);
        }
        return null;
    }

    /**
     * 创建并编译着色器程序
     */
    private static int createShaderProgram() {
        // 加载着色器源码
        String vertexSource = loadShaderSource(VERTEX_SHADER_ID);
        String fragmentSource = loadShaderSource(FRAGMENT_SHADER_ID);

        if (vertexSource == null || fragmentSource == null) {
            Nebula.LOGGER.error("Failed to load shader sources");
            return -1;
        }

        int vertexShader = 0;
        int fragmentShader = 0;
        int program = 0;

        try {
            // 编译顶点着色器
            vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vertexShader, vertexSource);
            GL20.glCompileShader(vertexShader);

            if (GL20.glGetShaderi(vertexShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(vertexShader);
                Nebula.LOGGER.error("Vertex shader compilation failed: {}", log);
                return -1;
            }

            // 编译片元着色器
            fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fragmentShader, fragmentSource);
            GL20.glCompileShader(fragmentShader);

            if (GL20.glGetShaderi(fragmentShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(fragmentShader);
                Nebula.LOGGER.error("Fragment shader compilation failed: {}", log);
                return -1;
            }

            // 链接程序
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertexShader);
            GL20.glAttachShader(program, fragmentShader);

            // 绑定属性位置
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "UV");
            GL20.glBindAttribLocation(program, 2, "iPrevPos");
            GL20.glBindAttribLocation(program, 3, "iPos");
            GL20.glBindAttribLocation(program, 4, "iColor");
            GL20.glBindAttribLocation(program, 5, "iSize");
            GL20.glBindAttribLocation(program, 6, "iTexID");
            GL20.glBindAttribLocation(program, 7, "iSeqID");

            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                Nebula.LOGGER.error("Shader program linking failed: {}", log);
                return -1;
            }

            Nebula.LOGGER.info("Particle shader loaded and compiled from resources");
            return program;

        } finally {
            if (vertexShader != 0)
                GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0)
                GL20.glDeleteShader(fragmentShader);
        }
    }

    /**
     * 使用 Instancing 渲染粒子（高性能）
     */
    @SuppressWarnings("deprecation")
    public static void renderInstanced(ByteBuffer data, int particleCount,
            Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float[] cameraRight, float[] cameraUp,
            float originX, float originY, float originZ,
            boolean useTexture, float partialTicks) {

        if (particleCount <= 0 || data == null || !initialized || !shaderCompiled) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        // ==========================================
        // 1. 关键修正：强制绑定主帧缓冲区
        // ==========================================
        // 在 WorldRenderEvents.LAST 阶段，Iris 可能已经解绑了缓冲区。
        // 我们必须显式告诉 OpenGL："把东西画在屏幕上(Main Framebuffer)"。
        // false = 不要清除颜色/深度，保留场景原有的内容。
        MinecraftClient.getInstance().getFramebuffer().beginWrite(false);

        // 备份 Viewport (视口)，防止因为某些模组修改了视口导致错位
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        // 设置渲染状态
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false); // 禁用深度写入 (粒子通常半透明)

        // 使用自定义着色器
        GL20.glUseProgram(shaderProgram);

        // 上传 Uniform
        uploadMatrix(uModelViewMat, modelViewMatrix);
        uploadMatrix(uProjMat, projMatrix);
        GL20.glUniform3f(uCameraRight, cameraRight[0], cameraRight[1], cameraRight[2]);
        GL20.glUniform3f(uCameraUp, cameraUp[0], cameraUp[1], cameraUp[2]);
        GL20.glUniform3f(uOrigin, originX, originY, originZ);
        if (uPartialTicks != -1) {
            GL20.glUniform1f(uPartialTicks, partialTicks);
        }

        // 绑定纹理
        if (useTexture && ParticleTextureManager.isInitialized()) {
            ParticleTextureManager.bind(0);
            GL20.glUniform1i(uSampler0, 0);
            GL20.glUniform1i(uUseTexture, 1);
        } else {
            GL20.glUniform1i(uUseTexture, 0);
        }

        // 上传 Instance 数据
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);

        int needed = data.remaining();
        lastFrameUsedBytes = needed;

        // 缓冲区扩容/更新策略
        if (needed <= currentBufferSize) {
            // Orphaning: 显式废弃旧缓冲区，避免 GPU 等待
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
        } else {
            int newSize = Math.max(currentBufferSize * 2, needed);
            Nebula.LOGGER.info("Expanded instance buffer from {} to {} bytes", currentBufferSize, newSize);
            currentBufferSize = newSize;
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, currentBufferSize, GL15.GL_STREAM_DRAW);
        }

        // 上传数据
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, data);

        // 绘制
        GL30.glBindVertexArray(vao);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, particleCount);
        GL30.glBindVertexArray(0);

        // ==========================================
        // 2. 关键修正：终极状态恢复 (解决黑手/UI消失)
        // ==========================================

        // 解绑自定义纹理
        ParticleTextureManager.unbind();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);

        // [修复手部/物品全黑]
        // 原版渲染器(ItemRenderer/HandRenderer)假设 Texture Unit 0 永远绑定的是 BLOCK_ATLAS。
        // 如果这里不恢复，随后的手部渲染就会采样到错误的纹理（或者空纹理），导致变黑。
        RenderSystem.setShaderTexture(0, SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);

        // 恢复 OpenGL 基础状态
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true); // 恢复深度写入
        RenderSystem.disableBlend();

        // 恢复深度测试标准
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        // [修复 UI/Hotbar 消失]
        // 必须清除 RenderSystem 内部的 Shader 缓存。
        // 否则下一个渲染阶段(GUI)会误以为 Shader 没变，而不去绑定它需要的 Position/Color Shader。
        RenderSystem.setShader(() -> null);

        // [防御性恢复]
        // 再次确保绑定回 Main Framebuffer，虽然通常 GUI 也是画在这里，但显式绑定更安全。
        MinecraftClient.getInstance().getFramebuffer().beginWrite(false);

        // 恢复视口
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /**
     * 上传矩阵到 Uniform
     */
    private static void uploadMatrix(int location, Matrix4f matrix) {
        if (location >= 0 && matrix != null) {
            matrixBuffer.clear();
            matrix.get(matrixBuffer);
            matrixBuffer.rewind();
            GL20.glUniformMatrix4fv(location, false, matrixBuffer);
        }
    }

    /**
     * 渲染粒子
     */
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

    /**
     * 清理资源
     */
    public static void cleanup() {
        if (vao != -1) {
            GL30.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (quadVbo != -1) {
            GL15.glDeleteBuffers(quadVbo);
            quadVbo = -1;
        }
        if (instanceVbo != -1) {
            GL15.glDeleteBuffers(instanceVbo);
            instanceVbo = -1;
        }
        if (shaderProgram > 0) {
            GL20.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }
        ParticleTextureManager.cleanup();
        initialized = false;
        shaderCompiled = false;
    }

    public static int getBufferSize() {
        return currentBufferSize;
    }

    public static int getTypeSize() {
        return lastFrameUsedBytes;
    }

    /**
     * 缩减缓冲区到初始大小 (8MB)
     */
    public static void shrinkBuffer() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(GpuParticleRenderer::shrinkBuffer);
            return;
        }

        int initialSize = 1024 * 1024;
        if (currentBufferSize > initialSize && instanceVbo != -1) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, initialSize, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            Nebula.LOGGER.info("Shrunk instance buffer from {}MB to {}MB",
                    currentBufferSize / 1024 / 1024, initialSize / 1024 / 1024);
            currentBufferSize = initialSize;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isShaderCompiled() {
        return shaderCompiled;
    }
}
