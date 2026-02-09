package com.atemukesu.nebula.mixin.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.atemukesu.nebula.client.util.IrisUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Mixin(WorldRenderer.class)
public class NebulaWorldRendererMixin {

        /**
         * 视锥体（用于剔除）
         */
        @Shadow
        private Frustum frustum;

        // 缓存 Iris 的方法，避免每帧反射
        @Unique
        private static boolean irisChecked = false;
        @Unique
        private static Object irisPipelineManager = null;
        @Unique
        private static Method getPipelineMethod = null;
        @Unique
        private static Method getSodiumPipelineMethod = null;
        @Unique
        private static Method getTranslucentFbMethod = null;
        @Unique
        private static Method bindFbMethod = null;

        // 日志控制：避免刷屏
        @Unique
        private static boolean hasLoggedIrisPath = false;
        @Unique
        private static boolean hasLoggedStandardPath = false;

        @Inject(method = "render", at = @At(value = "INVOKE",
                        // 目标：net.minecraft.client.particle.ParticleManager.renderParticles(...)
                        target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;F)V"))
        private void nebula$injectParticles(
                        MatrixStack matrices, float tickDelta, long limitTime,
                        boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                        LightmapTextureManager lightmapTextureManager, Matrix4f projection,
                        CallbackInfo ci) {

                // 1. 初始化反射 (仅运行一次)
                if (!irisChecked) {
                        initIrisReflection();
                        irisChecked = true;
                }

                int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                boolean isIrisMode = false;

                // 2. 检测 Iris 是否激活，并尝试绑定 Iris 的半透明 FBO
                boolean irisActive = IrisUtil.isIrisRenderingActive();

                if (irisActive && irisPipelineManager != null) {
                        try {
                                // pipeline = Iris.getPipelineManager().getPipelineNullable();
                                Object pipeline = getPipelineMethod.invoke(irisPipelineManager);

                                // if (pipeline instanceof IrisRenderingPipeline)
                                if (pipeline != null
                                                && pipeline.getClass().getName().contains("IrisRenderingPipeline")) {
                                        // sodiumPipeline = pipeline.getSodiumTerrainPipeline();
                                        Object sodiumPipeline = getSodiumPipelineMethod.invoke(pipeline);

                                        if (sodiumPipeline != null) {
                                                // fb = sodiumPipeline.getTranslucentFramebuffer();
                                                Object framebuffer = getTranslucentFbMethod.invoke(sodiumPipeline);

                                                if (framebuffer != null) {
                                                        // fb.bind();
                                                        bindFbMethod.invoke(framebuffer);
                                                        isIrisMode = true;

                                                        if (!hasLoggedIrisPath) {
                                                                Nebula.LOGGER.info(
                                                                                "[Nebula/Render] ✓ Using Iris render path (Mixin + Iris FBO).");
                                                                hasLoggedIrisPath = true;
                                                                hasLoggedStandardPath = false;
                                                        }
                                                }
                                        }
                                }
                        } catch (Exception e) {
                                // 出错则降级到普通模式
                                Nebula.LOGGER.warn("[Nebula] Failed to bind Iris FBO: " + e.getMessage());
                                irisPipelineManager = null; // 停止尝试
                        }
                }

                // 普通模式日志
                if (!isIrisMode && !hasLoggedStandardPath) {
                        Nebula.LOGGER.info("[Nebula/Render] ✓ Using standard render path (Mixin only).");
                        hasLoggedStandardPath = true;
                        hasLoggedIrisPath = false;
                }

                // 3. 渲染状态设置
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);

                // 4. 执行渲染
                // isIrisMode = true 时传入 bindFramebuffer=false (保持 Iris FBO)
                // isIrisMode = false 时传入 bindFramebuffer=true (绑定 MC 主 FBO)
                Matrix4f modelView = matrices.peek().getPositionMatrix();
                ClientAnimationManager.getInstance().renderTickMixin(
                                modelView,
                                projection,
                                camera,
                                this.frustum,
                                !isIrisMode); // bindFramebuffer: 普通模式 true, Iris 模式 false

                // 5. 恢复状态
                RenderSystem.enableCull();
                RenderSystem.depthMask(true);

                // 恢复 FBO (如果绑定了 Iris FBO)
                if (isIrisMode) {
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
                }
        }

        @Unique
        private void initIrisReflection() {
                try {
                        // 获取 Iris.getPipelineManager()
                        Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                        Method getManager = irisClass.getMethod("getPipelineManager");
                        irisPipelineManager = getManager.invoke(null);

                        // 获取 PipelineManager.getPipelineNullable()
                        Class<?> managerClass = irisPipelineManager.getClass();
                        getPipelineMethod = managerClass.getMethod("getPipelineNullable");

                        // 获取 IrisRenderingPipeline 类
                        Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
                        getSodiumPipelineMethod = pipelineClass.getMethod("getSodiumTerrainPipeline");

                        // 获取 SodiumTerrainPipeline 类
                        Class<?> sodiumPipelineClass = Class
                                        .forName("net.irisshaders.iris.pipeline.SodiumTerrainPipeline");
                        getTranslucentFbMethod = sodiumPipelineClass.getMethod("getTranslucentFramebuffer");

                        // 获取 GlFramebuffer.bind()
                        Class<?> fbClass = Class.forName("net.irisshaders.iris.gl.framebuffer.GlFramebuffer");
                        bindFbMethod = fbClass.getMethod("bind");

                        Nebula.LOGGER.info("[Nebula] Iris reflection initialized successfully.");
                } catch (ClassNotFoundException e) {
                        Nebula.LOGGER.info("[Nebula] Iris not found, using standard render path.");
                } catch (Exception e) {
                        Nebula.LOGGER.error("[Nebula] Failed to init Iris reflection", e);
                }
        }
}