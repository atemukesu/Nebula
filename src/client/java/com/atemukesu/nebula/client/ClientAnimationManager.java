package com.atemukesu.nebula.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.loader.NblStreamer;
import com.atemukesu.nebula.client.render.AnimationFrame;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.client.util.ReplayModUtil;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.util.math.Vec3d;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

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

    private ClientAnimationManager() {
        // 初始化 GPU 渲染器
        GpuParticleRenderer.init();
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
     */
    public void tick(MinecraftClient client) {
        // 清理已完成的动画实例
        synchronized (activeInstances) {
            activeInstances.removeIf(AnimationInstance::isFinished);
            currentInstanceCount = activeInstances.size();
        }
    }

    /**
     * 渲染 Tick（每帧调用）
     * 在 WorldRenderEvents.AFTER_TRANSLUCENT 中调用
     */
    public void renderTick(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null)
            return;

        // 1. 获取相机信息
        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();

        // 2. 获取模型视图矩阵并移除平移分量
        Matrix4f modelViewMatrix = new Matrix4f(context.matrixStack().peek().getPositionMatrix());

        // -------------------------------------------------------------
        // 【修正】: 不再强制绑定 Main Framebuffer
        // we draw fast on the current framebuffer (which iris has prepared)
        // switching FBOs without updating viewport/matrices causes the "giant
        // flickering planes" issue
        // -------------------------------------------------------------
        // client.getFramebuffer().beginWrite(false); // REMOVED

        // -------------------------------------------------------------
        // 【修改点 2】: 开启混合模式，实现“辉光叠加”效果
        // SRC_ALPHA + ONE = 典型的加法混合 (Additive Blending)，自带发光感
        // -------------------------------------------------------------
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);

        // 【关键】保留深度测试，但设为 LEQUAL (小于等于)
        // 这样粒子会被前面的墙挡住，但能画在天空和背景上
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        // 锁定深度写入 (只画颜色，不污染深度，防止半透明排序错误)
        RenderSystem.depthMask(false);

        // 强制移除矩阵的平移分量 (第4列的前3行)，只保留旋转和缩放
        modelViewMatrix.m30(0.0f);
        modelViewMatrix.m31(0.0f);
        modelViewMatrix.m32(0.0f);

        Matrix4f projMatrix = context.projectionMatrix();

        // 3. 计算相机方向向量 (用于 Billboard 朝向)
        float[] cameraRight = new float[3];
        float[] cameraUp = new float[3];
        calculateCameraVectors(camera, cameraRight, cameraUp);

        int totalParticles = 0;

        // 创建渲染列表副本，避免长时间持有锁阻塞主线程
        List<AnimationInstance> renderList;
        synchronized (activeInstances) {
            renderList = new ArrayList<>(activeInstances);
        }

        for (AnimationInstance instance : renderList) {
            // 确保纹理已加载（第一次渲染时）
            instance.ensureTexturesLoaded();

            ByteBuffer frameData = instance.getNextFrame();

            // 4. 使用 slice() 创建缓冲区视图
            if (frameData != null && frameData.remaining() > 0) {
                ByteBuffer readBuffer = frameData.slice();

                int particleCount = readBuffer.remaining() / AnimationFrame.BYTES_PER_PARTICLE;
                totalParticles += particleCount;

                Vec3d origin = instance.getOrigin();

                // 计算粒子系统原点相对于相机的偏移
                // 必须转为 float 传给 GPU (Large Coordinate Problem 解决方案)
                float relX = (float) (origin.x - cameraPos.x);
                float relY = (float) (origin.y - cameraPos.y);
                float relZ = (float) (origin.z - cameraPos.z);

                // 使用智能渲染
                GpuParticleRenderer.render(
                        readBuffer, // 传入切片副本
                        particleCount,
                        modelViewMatrix, // 传入去掉了平移的矩阵
                        projMatrix,
                        cameraRight,
                        cameraUp,
                        relX,
                        relY,
                        relZ);
            }
        }

        currentParticleCount = totalParticles;
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

        // Seek 冷却控制
        private long lastSeekTime = 0;
        private static final long SEEK_COOLDOWN_MS = 500;
        private int lastSeekTarget = -1;

        public ByteBuffer getNextFrame() {
            if (!isStarted)
                return null;

            Double replayTime = ReplayModUtil.getReplayTime();
            double now = ReplayModUtil.getCurrentAnimationTime();
            double elapsed = now - startSeconds;

            int totalFrames = streamer.getTotalFrames();

            // 检查时间范围 (仅针对 Replay Mod 模式)
            if (replayTime != null) {
                double duration = (double) totalFrames / targetFps;

                if (elapsed < 0 || elapsed > duration + 0.2) {
                    if (lastFrameData != null) {
                        Nebula.LOGGER.debug("Time out of range (elapsed={}s, duration={}s). Clearing animation.",
                                String.format("%.2f", elapsed), String.format("%.2f", duration));
                        NblStreamer.releaseBuffer(lastFrameData);
                        lastFrameData = null;
                        // 重置 renderedFrames 为 -1，确保下次进入范围时必定触发 Seek (因 expectedFrame >= 0 > -1)
                        renderedFrames = -1;
                    }
                    return null; // 隐藏动画
                }
            }

            // Replay Mod 拖动时间轴支持
            int expectedFrame = (int) (elapsed * targetFps);
            if (expectedFrame < 0)
                expectedFrame = 0;
            if (expectedFrame >= totalFrames)
                expectedFrame = totalFrames; // Allow "waiting for EOF"

            // 检测是否需要 Seek
            int seekThreshold = ReplayModUtil.isRendering() ? 10 : 30;
            boolean needSeek = expectedFrame < renderedFrames || expectedFrame > renderedFrames + seekThreshold;

            if (needSeek) {
                long currentTime = System.currentTimeMillis();

                boolean isRewind = expectedFrame < renderedFrames;
                boolean cooledDown = (currentTime - lastSeekTime) > SEEK_COOLDOWN_MS;
                boolean sameTarget = (expectedFrame == lastSeekTarget);

                if (isRewind || (cooledDown && !sameTarget)) {
                    streamer.seek(expectedFrame);
                    lastSeekTime = currentTime;
                    lastSeekTarget = expectedFrame;
                    renderedFrames = expectedFrame; // 乐观更新

                    Nebula.LOGGER.debug("Seek to frame {} (rewind: {})", expectedFrame, isRewind);
                }

                // Seek 时继续显示当前帧(不要归还)，直到新帧到来
                return lastFrameData;
            }

            if (isFinished && lastFrameData == null) // 如果结束了且没有最后一帧
                return null;

            ByteBuffer newData = null;

            if (ReplayModUtil.isRendering()) {
                // 渲染模式：严格等待
                if (renderedFrames < expectedFrame) {
                    try {
                        newData = streamer.getQueue().take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } else {
                // 普通模式：尝试获取
                int framesToCatchUp = expectedFrame - renderedFrames;
                int maxCatchUp = Math.min(framesToCatchUp, 5);

                for (int i = 0; i < maxCatchUp; i++) {
                    try {
                        ByteBuffer temp = streamer.getQueue().poll(2, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (temp != null) {
                            // 如果有多帧积压，我们只取最新的，中间的直接归还(跳帧)
                            // 注意：这里的"最新的"是指在本次循环中最后取到的那个
                            if (newData != null) {
                                NblStreamer.releaseBuffer(newData); // 丢弃中间帧
                            }
                            newData = temp; // 更新为最新
                        } else {
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
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
                    // EOF buffer 也要归还
                    NblStreamer.releaseBuffer(newData);
                    return null;
                }

                // [CRITICAL] 切换到新帧时，归还旧帧的 Buffer
                if (lastFrameData != null) {
                    NblStreamer.releaseBuffer(lastFrameData);
                }

                lastFrameData = newData;
                renderedFrames++;
            }
            // 如果 newData 为空，说明还不需要更新或没读到，继续返回 lastFrameData

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
    }
}