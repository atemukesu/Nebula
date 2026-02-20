/**
 * [AI GENERATION & MODIFICATION NOTICE / AI 编写与调整声明]
 *
 * ENGLISH:
 * This code was authored, modified, optimized, or adjusted by one or more of the
 * following AI models: Gemini 3 Pro, Gemini 3 Flash, and Claude 3.5 Opus.
 * Although efforts have been made to ensure functionality through testing, the
 * code is provided "AS IS". Please perform a thorough code audit before using,
 * reading, distributing, or modifying.
 *
 * 中文版：
 * 本代码由以下一个或多个 AI 模型编写、修改、优化或调整：
 * Gemini 3 Pro, Gemini 3 Flash, 以及 Claude 3.5 Opus。
 * 代码虽经努力测试以确保其功能实现，但仍按“原样”提供。在您进行使用、阅读、
 * 分发或修改前，请务必进行仔细的代码审计与测试。
 *
 * ----------------------------------------------------------------------------------
 * [LICENSE & WARRANTY / 开源协议与免责声明]
 *
 * ENGLISH:
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details <https://www.gnu.org/licenses/>.
 *
 * 中文版：
 * 本程序为自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可协议（GPL）条款
 *（可以选择版本 3 或更高版本）对本程序进行重新分发和/或修改。
 *
 * 本程序的发布是希望其能发挥作用，但【不附带任何担保】，甚至不包括对【适销性】或
 * 【特定用途适用性】的暗示保证。开发者不对因使用本代码产生的任何损害承担责任。
 * 详情请参阅 GNU 通用公共许可协议官方页面 <https://www.gnu.org/licenses/>。
 * ----------------------------------------------------------------------------------
 */

package com.atemukesu.nebula.client;

import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.enums.CullingBehavior;
import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.loader.NblStreamer;
import com.atemukesu.nebula.client.render.AnimationFrame;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.client.render.ParticleTextureManager;
import com.atemukesu.nebula.client.render.SharedTextureResource;
import com.atemukesu.nebula.client.render.TextureCacheSystem;
import com.atemukesu.nebula.client.util.IrisUtil;
import com.atemukesu.nebula.client.util.CurrentTimeUtil;
import com.atemukesu.nebula.client.config.ModConfig;
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

        if (!MinecraftClient.getInstance().isOnThread()) {
            MinecraftClient.getInstance().execute(() -> playAnimation(name, origin));
            Nebula.LOGGER.error("[Nebula/Animation] playAnimation called on wrong thread, deferring to main thread.");
            return;
        }

        Path animationPath = AnimationLoader.getAnimationPath(name);
        if (animationPath == null) {
            Nebula.LOGGER.warn("Animation not found: {}", name);
            return;
        }

        // [Config Control]
        // IsReplayModRendering True -> 强制渲染
        // IsReplayModRendering False, GameRendering True -> 渲染
        // IsReplayModRendering False, GameRendering False -> 不渲染且不加载
        if (!CurrentTimeUtil.isRendering() && !ModConfig.getInstance().shouldRenderInGame()) {
            // Discard: 不读取文件，不创建实例
            return;
        }

        try {
            File file = animationPath.toFile();

            // 1. 预扫描纹理列表
            List<ParticleTextureManager.TextureEntry> entries = NblStreamer.preScanTextures(file);

            // 2. 获取共享纹理资源 (TextureCacheSystem 负责缓存和加载)
            SharedTextureResource resource = TextureCacheSystem.acquire(file.getAbsolutePath(), entries);

            // 3. 创建实例并传递资源
            // 注意：resource 引用计数已由 acquire 增加 (ref=1)
            // AnimationInstance 会持有这个引用，直到销毁
            AnimationInstance instance = new AnimationInstance(file, origin, resource);
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
     * 统一的 Mixin 渲染入口（每帧调用）
     * 由 NebulaWorldRendererMixin 调用，支持 Iris 模式
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

        ModConfig config = ModConfig.getInstance();
        // 开始帧计时
        boolean shouldCollectStats = config.getShowDebugHud();
        PerformanceStats stats = null;
        if (shouldCollectStats) {
            stats = PerformanceStats.getInstance();
            stats.beginFrame();
        }

        // [Config Control] 如果不在 Replay 渲染模式且配置关闭了游戏内渲染，则跳过
        if (!CurrentTimeUtil.isRendering() && !config.shouldRenderInGame()) {
            return;
        }

        // 没有粒子，跳过
        if (this.getInstanceCount() == 0) {
            return;
        }

        // 记录日志
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

        // 定义一个矩阵变量
        Matrix4f mvMatrix;

        //? if >= 1.21 {
        
        // 1.21.1 自己构建矩阵
        mvMatrix = new Matrix4f();
        mvMatrix.rotate(camera.getRotation().conjugate(new Quaternionf()));
        
        //? } else {
        /*mvMatrix = new Matrix4f(modelViewMatrix);
        mvMatrix.m30(0.0f);
        mvMatrix.m31(0.0f);
        mvMatrix.m32(0.0f);
        *///?}

        // [DEBUG] 打印构建的矩阵信息
        if (!hasLoggedStandardRenderPath && !hasLoggedIrisRenderPath) {
            Nebula.LOGGER.info("[Matrix Debug - Mixin] Built View Rotation Matrix:\n{}", mvMatrix.toString());
        }

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        // Global OIT Setup or Standard Batch Setup
        boolean isOIT = config.getBlendMode() == BlendMode.OIT;
        CullingBehavior behavior = config.getCullingBehavior();
        double now = CurrentTimeUtil.getCurrentAnimationTime();
        int targetFboId = -1;
        if (!renderList.isEmpty()) {
            if (isOIT) {
                targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginOIT(targetFboId, client.getWindow().getFramebufferWidth(),
                        client.getWindow().getFramebufferHeight());
            } else {
                int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginStandardRendering(mvMatrix, projectionMatrix,
                        currentFbo);
            }
        }

        for (AnimationInstance instance : renderList) {
            boolean isVisible = true;
            // 视锥剔除
            if (frustum != null) {
                Box worldBbox = instance.getWorldBoundingBox();
                if (worldBbox != null && !frustum.isVisible(worldBbox)) {
                    isVisible = false;
                }
            }
            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            // [核心] 智能休眠控制
            if (!isVisible) {
                // Determine behavior based on config
                if (behavior == CullingBehavior.PAUSE_AND_HIDE) {
                    continue;
                }
            }

            // [核心] 唤醒与同步
            // 当再次可见时，检查是否需要 Seek
            double elapsed = now - instance.startSeconds;
            int expectedFrame = (int) (elapsed * instance.targetFps);

            if (Math.abs(instance.renderedFrames - expectedFrame) > 5) {
                instance.streamer.seek(expectedFrame);
                instance.renderedFrames = expectedFrame;
            }

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
                // (now, elapsed 均已在上文计算)
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
                            relX,
                            relY,
                            relZ,
                            true,
                            instance.getTextureId(),
                            partialTicks);
                } else {
                    GpuParticleRenderer.renderStandardBatch(
                            readBuffer,
                            particleCount,
                            relX,
                            relY,
                            relZ,
                            true,
                            instance.getTextureId(),
                            partialTicks);
                }
            }
        }

        currentParticleCount = totalParticles;

        if (shouldCollectStats && stats != null) {
            // 更新性能统计
            stats.setParticleCount(totalParticles);
            stats.setInstanceCount(renderedInstancesCount);
            float effectiveEmissive = IrisUtil.isIrisRenderingActive()
                    ? config.getEmissiveStrength()
                    : 1.0f;

            stats.setEmissiveStrength(effectiveEmissive);
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
     * <p>
     * 由 WorldRenderEvents.LAST 事件触发，仅在原版模式（非 Iris）下执行。
     * Iris 模式下由 NebulaWorldRendererMixin 处理，此方法会跳过。
     * 
     * @param context 世界渲染上下文
     */
    public void renderTick(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null)
            return;

        ModConfig config = ModConfig.getInstance();
        // 开始帧计时
        boolean shouldCollectStats = config.getShowDebugHud();
        PerformanceStats stats = null;
        if (shouldCollectStats) {
            stats = PerformanceStats.getInstance();
            stats.beginFrame();
        }

        // [Config Control] 如果不在 Replay 渲染模式且配置关闭了游戏内渲染，则跳过
        if (!CurrentTimeUtil.isRendering() && !config.shouldRenderInGame()) {
            return;
        }

        // 没有粒子，跳过
        if (this.getInstanceCount() == 0) {
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

        // 定义矩阵
        Matrix4f modelViewMatrix;

        //? if >= 1.21 {
        
        // 1.21.1
        // 这能保证无论是在原版还是模组环境下，旋转矩阵都是绝对正确的
        modelViewMatrix = new Matrix4f();
        modelViewMatrix.rotate(camera.getRotation().conjugate(new Quaternionf()));
        
        //? } else {
        /*modelViewMatrix = new Matrix4f(context.matrixStack().peek().getPositionMatrix());
        modelViewMatrix.m30(0.0f);
        modelViewMatrix.m31(0.0f);
        modelViewMatrix.m32(0.0f);
        *///? }
        if (!hasLoggedStandardRenderPath) {
            Nebula.LOGGER.info("[Matrix Debug] Built View Rotation Matrix:\n{}", modelViewMatrix);
        }

        Matrix4f projMatrix = context.projectionMatrix();

        // 3. 渲染状态设置
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);

        int totalParticles = 0;
        int renderedInstancesCount = 0;

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        // Global OIT Setup or Standard Batch Setup
        boolean isOIT = config.getBlendMode() == BlendMode.OIT;
        CullingBehavior behavior = config.getCullingBehavior();
        double now = CurrentTimeUtil.getCurrentAnimationTime();
        int targetFboId = -1;
        if (!renderList.isEmpty()) {
            if (isOIT) {
                targetFboId = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginOIT(targetFboId, client.getWindow().getFramebufferWidth(),
                        client.getWindow().getFramebufferHeight());
            } else {
                int currentFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GpuParticleRenderer.beginStandardRendering(modelViewMatrix, projMatrix,
                        // 移除 cameraRight 和 cameraUp 参数
                        // cameraRight, cameraUp,
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
            }

            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            // [核心] 智能休眠控制
            if (!isVisible) {
                // Determine behavior based on config
                if (behavior == CullingBehavior.PAUSE_AND_HIDE) {
                    continue;
                }
            }

            // [核心] 唤醒与同步
            // 当再次可见时，检查是否需要 Seek
            double elapsed = now - instance.startSeconds;
            int expectedFrame = (int) (elapsed * instance.targetFps);

            if (Math.abs(instance.renderedFrames - expectedFrame) > 5) {
                instance.streamer.seek(expectedFrame);
                instance.renderedFrames = expectedFrame;
            }

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
                // (now, elapsed 均已在上文计算)
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
                            relX,
                            relY,
                            relZ,
                            true,
                            instance.getTextureId(),
                            partialTicks);
                } else {
                    GpuParticleRenderer.renderStandardBatch(
                            readBuffer,
                            particleCount,
                            relX,
                            relY,
                            relZ,
                            true,
                            instance.getTextureId(),
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

        if (shouldCollectStats && stats != null) {
            // 更新性能统计
            stats.setParticleCount(totalParticles);
            stats.setInstanceCount(renderedInstancesCount);
            float effectiveEmissive = IrisUtil.isIrisRenderingActive()
                    ? config.getEmissiveStrength()
                    : 1.0f;

            stats.setEmissiveStrength(effectiveEmissive);
            stats.endFrame();
        }
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
        private volatile boolean isFinished = false;
        private boolean texturesLoaded = false;

        // 缓存的帧数据
        private ByteBuffer lastFrameData;

        // 【架构变更】持有共享纹理资源
        private final SharedTextureResource textureResource;

        public AnimationInstance(File file, Vec3d origin, SharedTextureResource resource) throws IOException {
            this.file = file;
            this.origin = origin;
            this.textureResource = resource;
            // 既然构造时已经有了 Resource，说明纹理已加载
            this.texturesLoaded = true;

            // 创建 Streamer (Streamer 也会持有 resource 引用)
            this.streamer = new NblStreamer(file, textureResource);
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
                    this.streamer = new NblStreamer(file, textureResource);
                }
            } catch (IOException e) {
                Nebula.LOGGER.error("Failed to restart animation stream", e);
                stop(); // 停止并释放资源
                return;
            }

            streamerThread = new Thread(streamer, "Nbl-Streamer-" + file.getName());
            streamerThread.setDaemon(true);
            streamerThread.start();

            // 更新时间锚点
            this.startSeconds = CurrentTimeUtil.getCurrentAnimationTime();
            this.renderedFrames = 0;
            this.isFinished = false;
        }

        public void ensureTexturesLoaded() {
            // 已在构造函数中通过 SharedTextureResource 保证
            if (!texturesLoaded) {
                texturesLoaded = true;
            }
        }

        public int getTextureId() {
            return textureResource != null ? textureResource.getGlTextureId() : 0;
        }

        public ByteBuffer getNextFrame() {
            if (!isStarted || isFinished)
                return null;

            Double replayTime = CurrentTimeUtil.getReplayTime();
            double now = CurrentTimeUtil.getCurrentAnimationTime();
            double elapsed = now - startSeconds;

            int totalFrames = streamer.getTotalFrames();

            // 计算动画总时长
            double duration = (double) totalFrames / targetFps;

            // [修复] 统一的时间范围检查（适用于所有模式）
            // [倒带检测] ReplayMod 倒带时不应手动销毁，而是由 ReplayMod 重新创建实例?
            // 实际上这里的逻辑是如果时间倒退，销毁当前实例，等待新的播放指令。
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
                streamer.seek(expectedFrame);
                renderedFrames = expectedFrame;
                return lastFrameData; // Wait for seek
            }

            ByteBuffer newData = null;

            if (CurrentTimeUtil.isRendering()) {
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
                    // EOF buffer
                    stop(); // Finish
                    NblStreamer.releaseBuffer(newData); // Release EOF buffer
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
            if (isFinished)
                return;
            isFinished = true;

            if (streamer != null) {
                streamer.stop();
            }
            // 停止时归还持有的 Buffer
            if (lastFrameData != null) {
                NblStreamer.releaseBuffer(lastFrameData);
                lastFrameData = null;
            }

            // 释放纹理资源引用 (Reference Counting)
            // 只有当实例彻底销毁时才释放
            if (textureResource != null) {
                TextureCacheSystem.release(textureResource);
            }
        }

        public boolean isFinished() {
            return isFinished;
        }

        public Vec3d getOrigin() {
            return origin;
        }

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