
package com.atemukesu.nebula.mixin.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.atemukesu.nebula.client.bridge.IrisBridge;
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

@Mixin(WorldRenderer.class)
public class NebulaWorldRendererMixin {

        /**
         * 视锥体（用于剔除）
         */
        @Shadow
        private Frustum frustum;

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

                // 1. Check Iris via Bridge
                if (!IrisBridge.getInstance().isIrisRenderingActive()) {
                        return;
                }

                int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                boolean isIrisMode = false;

                // 2. Bind Iris Translucent FBO via Bridge (Unified)
                if (IrisBridge.getInstance().bindTranslucentFramebuffer()) {
                        isIrisMode = true;

                        if (!hasLoggedIrisPath) {
                                Nebula.LOGGER.info("[Nebula/Render] ✓ Using Iris render path (Mixin + Iris FBO).");
                                hasLoggedIrisPath = true;
                                hasLoggedStandardPath = false;
                        }
                }

                if (!isIrisMode) {
                        return;
                }

                // 4. 渲染状态设置
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);

                // 5. 执行渲染
                // Iris 模式传入 bindFramebuffer=false (保持 Iris FBO)
                Matrix4f modelView = matrices.peek().getPositionMatrix();
                ClientAnimationManager.getInstance().renderTickMixin(
                                modelView,
                                projection,
                                camera,
                                this.frustum,
                                false); // Iris 模式不绑定 MC 主 FBO

                // 6. 恢复状态
                RenderSystem.enableCull();
                RenderSystem.depthMask(true);

                // 恢复 FBO
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        }

}