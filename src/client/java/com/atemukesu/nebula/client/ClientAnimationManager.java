package com.atemukesu.nebula.client;

import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.loader.NblStreamer;
import com.atemukesu.nebula.client.render.AnimationFrame;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.client.util.IrisUtil;
import com.atemukesu.nebula.client.util.ReplayModUtil;
import com.atemukesu.nebula.config.ModConfig;
import com.atemukesu.nebula.config.BlendMode;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端动画管理器
 * 负责管理动画的播放、时间控制和渲染
 */
public class ClientAnimationManager {

    private static final ClientAnimationManager INSTANCE = new ClientAnimationManager();
    // 活跃的动画实例列表
    private final List<AnimationInstance> activeInstances = new ArrayList<>();
    // 统计信息
    private int currentInstanceCount = 0;
    private int currentParticleCount = 0;

    // 日志控制：避免刷屏
    private boolean hasLoggedIrisRenderPath = false;
    private boolean hasLoggedStandardRenderPath = false;

    private ClientAnimationManager() {
        // 初始化 GPU 渲染器
        GpuParticleRenderer.init();
        Nebula.LOGGER.info("[Nebula] ClientAnimationManager initialized.");
    }

    public static ClientAnimationManager getInstance() {
        return INSTANCE;
    }

    /**
     * 播放动画
     */
    public void playAnimation(String name, Vec3d origin) {
        Path animationPath = AnimationLoader.getAnimationPath(name);
        if (animationPath == null) {
            Nebula.LOGGER.warn("Animation not found: {}", name);
            return;
        }

        // [Config Control]
        // IsReplayModRendering True -> 强制渲染
        // IsReplayModRendering False, GameRendering True -> 渲染
        // IsReplayModRendering False, GameRendering False -> 不渲染且不加载
        if (!ReplayModUtil.isRendering() && !ModConfig.getInstance().shouldRenderInGame()) {
            // Discard: 不读取文件，不创建实例
            return;
        }

        try {
            File file = animationPath.toFile();
            AnimationInstance instance = new AnimationInstance(file, origin);
            instance.start();

            synchronized (activeInstances) {
                activeInstances.add(instance);
            }

            Nebula.LOGGER.info("Playing animation: {} at {}", name, origin);
        } catch (IOException e) {
            Nebula.LOGGER.error("Failed to start animation: {}", name, e);
        }
    }

    /**
     * 游戏 Tick 更新（常规模式）
     * 
     * @param client Minecraft 客户端实例
     */
    public void tick(MinecraftClient client) {
        // 清理已完成的动画实例
        synchronized (activeInstances) {
            activeInstances.removeIf(AnimationInstance::isFinished);
            currentInstanceCount = activeInstances.size();
        }
    }

    /**
     * 直接渲染方法（供 Iris Mixin 调用）
     * 接收 Iris 捕获的精确矩阵（带 Shader 抖动），绕过常规事件系统
     * 
     * @param modelViewMatrix  Iris 捕获的 G-Buffer 模型视图矩阵
     * @param projectionMatrix Iris 捕获的 G-Buffer 投影矩阵
     */
    public void renderTickDirect(Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        // 记录 Iris 渲染路径日志（只记录一次）
        if (!hasLoggedIrisRenderPath) {
            Nebula.LOGGER.info("[Nebula/Render] ✓ Using Iris render path (renderTickDirect).");
            hasLoggedIrisRenderPath = true;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null)
            return;

        // [Config Control] 如果不在 Replay 渲染模式且配置关闭了游戏内渲染，则跳过
        if (!ReplayModUtil.isRendering() && !ModConfig.getInstance().shouldRenderInGame()) {
            return;
        }

        // 获取相机信息
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getPos();

        // 使用 Iris 传入的 ModelView 矩阵，移除平移分量
        // 与原版模式保持一致的处理方式
        Matrix4f mvMatrix = new Matrix4f(modelViewMatrix);
        mvMatrix.m30(0.0f);
        mvMatrix.m31(0.0f);
        mvMatrix.m32(0.0f);

        // 计算相机方向向量 (用于 Billboard 朝向)
        float[] cameraRight = new float[3];
        float[] cameraUp = new float[3];
        calculateCameraVectors(camera, cameraRight, cameraUp);

        // 性能统计
        boolean shouldCollectStats = ModConfig.getInstance().getShowDebugHud();
        if (shouldCollectStats) {
            PerformanceStats.getInstance().beginFrame();
        }

        int totalParticles = 0;
        int renderedInstancesCount = 0;

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        // Global OIT Setup or Standard Batch Setup
        boolean isOIT = ModConfig.getInstance().getBlendMode() == BlendMode.OIT;
        int targetFboId = -1;
        if (!renderList.isEmpty()) {
            if (isOIT) {
                targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginOIT(targetFboId, client.getWindow().getFramebufferWidth(),
                        client.getWindow().getFramebufferHeight());
            } else {
                int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginStandardRendering(mvMatrix, projectionMatrix, cameraRight, cameraUp,
                        currentFbo);
            }
        }

        // 对于 Iris 路径，使用简化的距离剔除
        // (完整的 Frustum 构建需要更多参数，这里使用 AABB 的快速距离检查)
        double maxRenderDistance = client.options.getClampedViewDistance() * 16.0 * 2.0; // 扩大 2 倍以确保边缘不被剔除

        for (AnimationInstance instance : renderList) {
            boolean isVisible = true;

            // 【改进剔除】检查动画包围盒与相机的距离
            // 使用 BBox 最近点距离检查，防止大体积粒子特效被错误剔除
            Box bbox = instance.getWorldBoundingBox();
            if (bbox != null) {
                // 计算相机点到 AABB 的最近距离平方
                // 手动计算 point-to-AABB squared distance
                double dx = Math.max(bbox.minX - cameraPos.x, Math.max(0, cameraPos.x - bbox.maxX));
                double dy = Math.max(bbox.minY - cameraPos.y, Math.max(0, cameraPos.y - bbox.maxY));
                double dz = Math.max(bbox.minZ - cameraPos.z, Math.max(0, cameraPos.z - bbox.maxZ));
                double distSq = dx * dx + dy * dy + dz * dz;

                if (distSq > maxRenderDistance * maxRenderDistance) {
                    isVisible = false; // 确实远，剔除，但后台需继续从磁盘读数据
                }
            } else {
                // 回退逻辑：如果还没有 BBox（第一帧加载中），使用原点检查
                Vec3d origin = instance.getOrigin();
                double distSq = origin.squaredDistanceTo(cameraPos);
                if (distSq > maxRenderDistance * maxRenderDistance) {
                    isVisible = false;
                }
            }

            // 需要在此处获取 origin，供后续 offset 计算使用
            Vec3d origin = instance.getOrigin();

            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            // 【关键优化】即使不可见，也必须调用 getNextFrame
            // 这保证了后台 IO 线程持续读取数据，renderedFrames 紧跟当前时间
            // 从而避免因长时间不可见导致时间差过大，触发 Seek 操作引发卡顿
            ByteBuffer frameData = instance.getNextFrame();

            if (isVisible && frameData != null && frameData.remaining() > 0) {
                renderedInstancesCount++;
                ByteBuffer readBuffer = frameData.slice();

                int particleCount = readBuffer.remaining() / AnimationFrame.BYTES_PER_PARTICLE;
                totalParticles += particleCount;

                // 计算粒子系统原点相对于相机的偏移（复用剔除时获取的 origin）
                float relX = (float) (origin.x - cameraPos.x);
                float relY = (float) (origin.y - cameraPos.y);
                float relZ = (float) (origin.z - cameraPos.z);

                // 计算插值系数
                double now = ReplayModUtil.getCurrentAnimationTime();
                double start = instance.startSeconds;
                double elapsed = now - start;
                double currentFrameFloat = elapsed * instance.targetFps;

                float partialTicks = (float) (currentFrameFloat - (instance.renderedFrames - 1));
                if (partialTicks < 0)
                    partialTicks = 0;
                if (partialTicks > 1)
                    partialTicks = 1;

                // 使用 GPU 渲染器绘制
                if (isOIT) {
                    GpuParticleRenderer.renderOITBatch(
                            readBuffer,
                            particleCount,
                            mvMatrix,
                            projectionMatrix,
                            cameraRight,
                            cameraUp,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                } else {
                    GpuParticleRenderer.renderStandardBatch(
                            readBuffer,
                            particleCount,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                }
            }
        }

        if (!renderList.isEmpty()) {
            if (isOIT) {
                GpuParticleRenderer.endOITAndComposite(targetFboId);
            } else {
                GpuParticleRenderer.endStandardRendering();
            }
        }

        currentParticleCount = totalParticles;

        if (shouldCollectStats) {
            // 更新性能统计
            PerformanceStats stats = PerformanceStats.getInstance();
            stats.setParticleCount(totalParticles);
            stats.setInstanceCount(renderedInstancesCount);
            float effectiveEmissive = IrisUtil.isIrisRenderingActive()
                    ? ModConfig.getInstance().getEmissiveStrength()
                    : 1.0f; // 和实际值保持一致

            stats.setEmissiveStrength(effectiveEmissive);
            stats.setRenderInGame(ModConfig.getInstance().shouldRenderInGame());
            stats.endFrame();
        }
    }

    /**
     * 统一的 Mixin 渲染入口（每帧调用）
     * 由 NebulaWorldRendererMixin 调用，支持 Iris 和普通模式
     * 
     * @param modelViewMatrix  模型视图矩阵
     * @param projectionMatrix 投影矩阵
     * @param camera           相机对象
     * @param frustum          视锥体（用于剔除）
     * @param bindFramebuffer  是否绑定 MC 主 Framebuffer
     *                         普通模式传入 true，Iris 模式传入 false
     */
    public void renderTickMixin(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
            Camera camera, Frustum frustum, boolean bindFramebuffer) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null)
            return;

        // 开始帧计时
        boolean shouldCollectStats = ModConfig.getInstance().getShowDebugHud();
        if (shouldCollectStats) {
            PerformanceStats.getInstance().beginFrame();
        }

        // [Config Control] 如果不在 Replay 渲染模式且配置关闭了游戏内渲染，则跳过
        if (!ReplayModUtil.isRendering() && !ModConfig.getInstance().shouldRenderInGame()) {
            return;
        }

        // 记录日志（只记录一次）
        if (bindFramebuffer && !hasLoggedStandardRenderPath) {
            Nebula.LOGGER.info("[Nebula/Render] ✓ Rendering via Mixin (standard mode).");
            hasLoggedStandardRenderPath = true;
        } else if (!bindFramebuffer && !hasLoggedIrisRenderPath) {
            Nebula.LOGGER.info("[Nebula/Render] ✓ Rendering via Mixin (Iris mode).");
            hasLoggedIrisRenderPath = true;
        }

        Vec3d cameraPos = camera.getPos();

        int totalParticles = 0;
        int renderedInstancesCount = 0;

        // 使用传入的 ModelView 矩阵，移除平移分量
        Matrix4f mvMatrix = new Matrix4f(modelViewMatrix);
        mvMatrix.m30(0.0f);
        mvMatrix.m31(0.0f);
        mvMatrix.m32(0.0f);

        // 计算相机方向向量 (用于 Billboard 朝向)
        float[] cameraRight = new float[3];
        float[] cameraUp = new float[3];
        calculateCameraVectors(camera, cameraRight, cameraUp);

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        // Global OIT Setup or Standard Batch Setup
        boolean isOIT = ModConfig.getInstance().getBlendMode() == BlendMode.OIT;
        int targetFboId = -1;
        if (!renderList.isEmpty()) {
            if (isOIT) {
                targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginOIT(targetFboId, client.getWindow().getFramebufferWidth(),
                        client.getWindow().getFramebufferHeight());
            } else {
                int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginStandardRendering(mvMatrix, projectionMatrix, cameraRight, cameraUp,
                        currentFbo);
            }
        }

        // 最大渲染距离（用于距离剔除备用）
        double maxRenderDistance = client.options.getClampedViewDistance() * 16.0 * 2.0;

        for (AnimationInstance instance : renderList) {
            boolean isVisible = true;

            // 【视锥剔除】优先使用完整的视锥剔除
            if (frustum != null) {
                Box worldBbox = instance.getWorldBoundingBox();
                if (worldBbox != null && !frustum.isVisible(worldBbox)) {
                    isVisible = false;
                }
            } else {
                // 降级：简化距离剔除
                Vec3d origin = instance.getOrigin();
                double distSq = origin.squaredDistanceTo(cameraPos);
                if (distSq > maxRenderDistance * maxRenderDistance) {
                    isVisible = false;
                }
            }

            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            // 【关键优化】即使不可见，也必须调用 getNextFrame
            // 这保证了后台 IO 线程持续读取数据，renderedFrames 紧跟当前时间
            // 从而避免因长时间不可见导致时间差过大，触发 Seek 操作引发卡顿
            ByteBuffer frameData = instance.getNextFrame();

            if (isVisible && frameData != null && frameData.remaining() > 0) {
                renderedInstancesCount++;
                ByteBuffer readBuffer = frameData.slice();

                int particleCount = readBuffer.remaining() / AnimationFrame.BYTES_PER_PARTICLE;
                totalParticles += particleCount;

                Vec3d origin = instance.getOrigin();

                // 计算粒子系统原点相对于相机的偏移
                float relX = (float) (origin.x - cameraPos.x);
                float relY = (float) (origin.y - cameraPos.y);
                float relZ = (float) (origin.z - cameraPos.z);

                // 计算插值系数
                double now = ReplayModUtil.getCurrentAnimationTime();
                double start = instance.startSeconds;
                double elapsed = now - start;
                double currentFrameFloat = elapsed * instance.targetFps;

                float partialTicks = (float) (currentFrameFloat - (instance.renderedFrames - 1));
                if (partialTicks < 0)
                    partialTicks = 0;
                if (partialTicks > 1)
                    partialTicks = 1;

                // 使用 GPU 渲染器绘制
                if (isOIT) {
                    GpuParticleRenderer.renderOITBatch(
                            readBuffer,
                            particleCount,
                            mvMatrix,
                            projectionMatrix,
                            cameraRight,
                            cameraUp,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                } else {
                    GpuParticleRenderer.renderStandardBatch(
                            readBuffer,
                            particleCount,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                }
            }
        }

        currentParticleCount = totalParticles;

        if (shouldCollectStats) {
            // 更新性能统计
            PerformanceStats stats = PerformanceStats.getInstance();
            stats.setParticleCount(totalParticles);
            stats.setInstanceCount(renderedInstancesCount);
            float effectiveEmissive = IrisUtil.isIrisRenderingActive()
                    ? ModConfig.getInstance().getEmissiveStrength()
                    : 1.0f;

            stats.setEmissiveStrength(effectiveEmissive);
            stats.setRenderInGame(ModConfig.getInstance().shouldRenderInGame());
            stats.endFrame();
        }

        if (!renderList.isEmpty()) {
            if (isOIT) {
                GpuParticleRenderer.endOITAndComposite(targetFboId);
            } else {
                GpuParticleRenderer.endStandardRendering();
            }
        }
    }

    /**
     * 渲染 Tick（每帧调用）- 原版模式入口
     * 
     * 由 WorldRenderEvents.LAST 事件触发，仅在原版模式（非 Iris）下执行。
     * Iris 模式下由 NebulaWorldRendererMixin 处理，此方法会跳过。
     * 
     * @param context 世界渲染上下文
     */
    public void renderTick(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null)
            return;

        // 开始帧计时
        boolean shouldCollectStats = ModConfig.getInstance().getShowDebugHud();
        if (shouldCollectStats) {
            PerformanceStats.getInstance().beginFrame();
        }

        // [Config Control] 如果不在 Replay 渲染模式且配置关闭了游戏内渲染，则跳过
        if (!ReplayModUtil.isRendering() && !ModConfig.getInstance().shouldRenderInGame()) {
            return;
        }

        // [Iris 兼容] 如果 Iris 正在渲染，跳过此事件回调
        // 渲染由 MixinWorldRenderer 在正确的时机处理
        if (IrisUtil.isIrisRenderingActive()) {
            return;
        }

        // 记录标准渲染路径日志（只记录一次）
        if (!hasLoggedStandardRenderPath) {
            Nebula.LOGGER.info("[Nebula/Render] ✓ Using standard render path (WorldRenderEvents.LAST).");
            hasLoggedStandardRenderPath = true;
        }

        // 1. 获取相机信息
        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();

        // 2. 获取模型视图矩阵并移除平移分量
        Matrix4f modelViewMatrix = new Matrix4f(context.matrixStack().peek().getPositionMatrix());

        // 强制移除矩阵的平移分量 (第4列的前3行)，只保留旋转和缩放
        modelViewMatrix.m30(0.0f);
        modelViewMatrix.m31(0.0f);
        modelViewMatrix.m32(0.0f);

        Matrix4f projMatrix = context.projectionMatrix();

        // 3. 渲染状态设置
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);

        // 4. 计算相机方向向量 (用于 Billboard 朝向)
        float[] cameraRight = new float[3];
        float[] cameraUp = new float[3];
        calculateCameraVectors(camera, cameraRight, cameraUp);

        int totalParticles = 0;
        int renderedInstancesCount = 0;

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        // Global OIT Setup or Standard Batch Setup
        boolean isOIT = ModConfig.getInstance().getBlendMode() == BlendMode.OIT;
        int targetFboId = -1;
        if (!renderList.isEmpty()) {
            if (isOIT) {
                targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginOIT(targetFboId, client.getWindow().getFramebufferWidth(),
                        client.getWindow().getFramebufferHeight());
            } else {
                int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginStandardRendering(modelViewMatrix, projMatrix, cameraRight, cameraUp,
                        currentFbo);
            }
        }

        // 获取视锥用于剔除
        Frustum frustum = context.frustum();

        for (AnimationInstance instance : renderList) {
            boolean isVisible = true;

            // 【视锥剔除】检查动画的 AABB 是否在视锥内
            Box worldBbox = instance.getWorldBoundingBox();
            if (frustum != null && worldBbox != null) {
                if (!frustum.isVisible(worldBbox)) {
                    // AABB 不在视锥内
                    isVisible = false;
                }
            } else {
                // 【备用剔除】距离剔除
                double maxRenderDistance = client.options.getClampedViewDistance() * 16.0 * 2.0;
                if (worldBbox != null) {
                    double dx = Math.max(worldBbox.minX - cameraPos.x, Math.max(0, cameraPos.x - worldBbox.maxX));
                    double dy = Math.max(worldBbox.minY - cameraPos.y, Math.max(0, cameraPos.y - worldBbox.maxY));
                    double dz = Math.max(worldBbox.minZ - cameraPos.z, Math.max(0, cameraPos.z - worldBbox.maxZ));
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > maxRenderDistance * maxRenderDistance) {
                        isVisible = false;
                    }
                } else {
                    Vec3d origin = instance.getOrigin();
                    double distSq = origin.squaredDistanceTo(cameraPos);
                    if (distSq > maxRenderDistance * maxRenderDistance) {
                        isVisible = false;
                    }
                }
            }

            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            // 【关键优化】即使不可见，也必须调用 getNextFrame
            ByteBuffer frameData = instance.getNextFrame();

            // 使用 slice() 创建缓冲区视图
            if (isVisible && frameData != null && frameData.remaining() > 0) {
                renderedInstancesCount++;
                ByteBuffer readBuffer = frameData.slice();

                int particleCount = readBuffer.remaining() / AnimationFrame.BYTES_PER_PARTICLE;
                totalParticles += particleCount;

                Vec3d origin = instance.getOrigin();

                // 计算粒子系统原点相对于相机的偏移
                float relX = (float) (origin.x - cameraPos.x);
                float relY = (float) (origin.y - cameraPos.y);
                float relZ = (float) (origin.z - cameraPos.z);

                // 计算插值系数
                double now = ReplayModUtil.getCurrentAnimationTime();
                double start = instance.startSeconds;
                double elapsed = now - start;
                double currentFrameFloat = elapsed * instance.targetFps;

                float partialTicks = (float) (currentFrameFloat - (instance.renderedFrames - 1));
                if (partialTicks < 0)
                    partialTicks = 0;
                if (partialTicks > 1)
                    partialTicks = 1;

                // 使用 GPU 渲染器绘制
                if (isOIT) {
                    GpuParticleRenderer.renderOITBatch(
                            readBuffer,
                            particleCount,
                            modelViewMatrix,
                            projMatrix,
                            cameraRight,
                            cameraUp,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                } else {
                    GpuParticleRenderer.renderStandardBatch(
                            readBuffer,
                            particleCount,
                            relX,
                            relY,
                            relZ,
                            true,
                            partialTicks);
                }
            }
        }

        currentParticleCount = totalParticles;

        if (!renderList.isEmpty()) {
            if (isOIT) {
                GpuParticleRenderer.endOITAndComposite(targetFboId);
            } else {
                GpuParticleRenderer.endStandardRendering();
            }
        }

        if (shouldCollectStats) {
            // 更新性能统计
            PerformanceStats stats = PerformanceStats.getInstance();
            stats.setParticleCount(totalParticles);
            stats.setInstanceCount(renderedInstancesCount);
            float effectiveEmissive = IrisUtil.isIrisRenderingActive()
                    ? ModConfig.getInstance().getEmissiveStrength()
                    : 1.0f;

            stats.setEmissiveStrength(effectiveEmissive);
            stats.setRenderInGame(ModConfig.getInstance().shouldRenderInGame());
            stats.endFrame();
        }
    }

    /**
     * 计算相机的 Right 和 Up 向量用于 Billboard 渲染
     */
    private void calculateCameraVectors(Camera camera, float[] right, float[] up) {
        // 获取相机的旋转四元数
        Quaternionf rotation = camera.getRotation();

        // 定义世界坐标系下的基向量
        Vector3f vRight = new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f vUp = new Vector3f(0.0f, 1.0f, 0.0f);

        // 应用相机的旋转
        vRight.rotate(rotation);
        vUp.rotate(rotation);

        right[0] = vRight.x();
        right[1] = vRight.y();
        right[2] = vRight.z();

        up[0] = vUp.x();
        up[1] = vUp.y();
        up[2] = vUp.z();
    }

    /**
     * 清除所有动画实例
     */
    public void clearAllInstances() {
        synchronized (activeInstances) {
            for (AnimationInstance instance : activeInstances) {
                instance.stop();
            }
            activeInstances.clear();
        }
        currentInstanceCount = 0;
        currentParticleCount = 0;
        GpuParticleRenderer.shrinkBuffer();
        Nebula.LOGGER.info("All animation instances cleared");
    }

    public int getInstanceCount() {
        return currentInstanceCount;
    }

    public int getParticleCount() {
        return currentParticleCount;
    }

    /**
     * 动画实例类
     */
    private static class AnimationInstance {
        private final File file;
        private final Vec3d origin;
        private NblStreamer streamer;
        private Thread streamerThread;
        // 时间控制
        private double startSeconds;
        private int renderedFrames;
        private int targetFps;
        // 状态
        private boolean isStarted = false;
        private boolean isFinished = false;
        private boolean texturesLoaded = false;

        // 缓存的帧数据
        private ByteBuffer lastFrameData;

        public AnimationInstance(File file, Vec3d origin) throws IOException {
            this.file = file;
            this.origin = origin;
            this.streamer = new NblStreamer(file);
            this.targetFps = streamer.getTargetFps();
        }

        public void start() {
            if (isStarted)
                return;
            startStreamer();
            isStarted = true;
        }

        // 抽取启动逻辑，方便重启
        private void startStreamer() {
            if (streamerThread != null && streamerThread.isAlive()) {
                streamer.stop();
            }
            // 重置之前可能存在的 lastFrameData (归还 Buffer)
            if (lastFrameData != null) {
                NblStreamer.releaseBuffer(lastFrameData);
                lastFrameData = null;
            }

            // 重新创建 Streamer 以便重置状态
            try {
                if (isStarted) {
                    this.streamer = new NblStreamer(file);
                }
            } catch (IOException e) {
                Nebula.LOGGER.error("Failed to restart animation stream", e);
                isFinished = true;
                return;
            }

            streamerThread = new Thread(streamer, "Nbl-Streamer-" + file.getName());
            streamerThread.setDaemon(true);
            streamerThread.start();

            // 更新时间锚点
            this.startSeconds = ReplayModUtil.getCurrentAnimationTime();
            this.renderedFrames = 0;
            this.isFinished = false;
        }

        public void ensureTexturesLoaded() {
            if (!texturesLoaded) {
                streamer.loadTextures();
                texturesLoaded = true;
            }
        }

        public ByteBuffer getNextFrame() {
            if (!isStarted)
                return null;

            Double replayTime = ReplayModUtil.getReplayTime();
            double now = ReplayModUtil.getCurrentAnimationTime();
            double elapsed = now - startSeconds;

            int totalFrames = streamer.getTotalFrames();

            // 计算动画总时长
            double duration = (double) totalFrames / targetFps;

            // [修复] 统一的时间范围检查（适用于所有模式）
            // 原本只有 ReplayMod 环境会检查，导致普通游戏模式下动画超时后仍持续渲染

            // [倒带检测] ReplayMod 倒带时会从头重新发包，然后快进到目标位置
            // （replay 时间只会单调递增，不会跳变）
            // elapsed < 0 说明当前 replay 时间已倒退到此动画的创建时间之前
            // 直接销毁此实例，ReplayMod 快进时会重新发包创建新的实例
            if (replayTime != null && elapsed < 0) {
                stop();
                return null;
            }

            // [超时检测] 通用：动画已播放完毕，应当停止并销毁
            if (elapsed > duration) {
                stop();
                return null;
            }

            // Standard Playback Logic
            int expectedFrame = (int) Math.ceil(elapsed * targetFps);
            if (expectedFrame < 0)
                expectedFrame = 0;
            if (expectedFrame >= totalFrames)
                expectedFrame = totalFrames;

            // Simple Seek for jumps (forward or backward large jumps)
            if (Math.abs(renderedFrames - expectedFrame) > 30) {
                // Hard seek only on big jumps
                streamer.seek(expectedFrame);
                renderedFrames = expectedFrame;
                return lastFrameData; // Wait for seek
            }

            if (isFinished && lastFrameData == null)
                return null;

            ByteBuffer newData = null;

            if (ReplayModUtil.isRendering()) {
                // Rendering mode: Block wait
                if (renderedFrames < expectedFrame) {
                    try {
                        newData = streamer.getQueue().take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } else {
                // Standard mode: Non-blocking poll
                int framesToCatchUp = expectedFrame - renderedFrames;
                if (expectedFrame >= totalFrames && !isFinished) {
                    framesToCatchUp = Math.max(framesToCatchUp, 1);
                }
                int maxCatchUp = Math.min(framesToCatchUp, 5);

                for (int i = 0; i < maxCatchUp; i++) {
                    try {
                        ByteBuffer temp = streamer.getQueue().poll();
                        if (temp != null) {
                            if (newData != null) {
                                NblStreamer.releaseBuffer(newData);
                                renderedFrames++;
                            }
                            newData = temp;
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            }

            // 处理新数据
            if (newData != null) {
                if (newData.capacity() == 0) {
                    isFinished = true;
                    // Properly release the last frame so it stops rendering
                    if (lastFrameData != null) {
                        NblStreamer.releaseBuffer(lastFrameData);
                        lastFrameData = null;
                    }
                    NblStreamer.releaseBuffer(newData); // EOF buffer
                    return null;
                }

                // [CRITICAL] 切换到新帧时，归还旧帧的 Buffer
                if (lastFrameData != null) {
                    NblStreamer.releaseBuffer(lastFrameData);
                }

                lastFrameData = newData;
                renderedFrames++;
            }

            return lastFrameData;
        }

        public void stop() {
            if (streamer != null) {
                streamer.stop();
            }
            // 停止时归还持有的 Buffer
            if (lastFrameData != null) {
                NblStreamer.releaseBuffer(lastFrameData);
                lastFrameData = null;
            }
            isFinished = true;
        }

        public boolean isFinished() {
            return isFinished;
        }

        public Vec3d getOrigin() {
            return origin;
        }

        /**
         * 获取动画在世界坐标系中的 AABB
         * 用于视锥剔除检测
         * 
         * @return 世界坐标系的边界框，如果数据不可用则返回 null
         */
        public Box getWorldBoundingBox() {
            if (streamer == null)
                return null;
            float[] bboxMin = streamer.getBboxMin();
            float[] bboxMax = streamer.getBboxMax();
            if (bboxMin == null || bboxMax == null)
                return null;

            // 将相对于原点的 AABB 转换为世界坐标
            return new Box(
                    origin.x + bboxMin[0], origin.y + bboxMin[1], origin.z + bboxMin[2],
                    origin.x + bboxMax[0], origin.y + bboxMax[1], origin.z + bboxMax[2]);
        }
    }
}