package com.atemukesu.nebula.mixin.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 到 Iris 的渲染管线，精确注入粒子渲染
 * 
 * 注入时机：beginTranslucents 方法的末尾
 * 此时 Iris 已经设置好了渲染状态，但 FBO 尚未绑定
 * 我们需要手动绑定 Iris 的 translucent framebuffer
 * 
 * 注意：remap = false 因为 IrisRenderingPipeline 是 Iris 的类，不是 Minecraft 的
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisPipeline {

    // 通过 Shadow 访问 Iris 的内部字段
    @Shadow
    @Final
    private SodiumTerrainPipeline sodiumTerrainPipeline;

    @Unique
    private static boolean hasLoggedInjection = false;

    @Unique
    private static int debugLogCounter = 0;

    @Unique
    private static final int DEBUG_LOG_INTERVAL = 60;

    @Inject(method = "beginTranslucents", at = @At("TAIL"))
    private void nebula$injectParticles(CallbackInfo ci) {
        // 记录注入日志（只记录一次）
        if (!hasLoggedInjection) {
            Nebula.LOGGER.info("[Nebula/IrisMixin] ✓ Injected at IrisRenderingPipeline.beginTranslucents TAIL");
            hasLoggedInjection = true;
        }

        // 1. 保存当前状态
        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int irisShaderProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean oldDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        // 调试日志
        debugLogCounter++;
        boolean shouldLog = (debugLogCounter % DEBUG_LOG_INTERVAL == 1);

        // 2. 【关键】绑定 Iris 的 translucent framebuffer
        // beginTranslucents 没有绑定 FBO，我们需要手动绑定
        GlFramebuffer translucentFb = null;
        if (sodiumTerrainPipeline != null) {
            translucentFb = sodiumTerrainPipeline.getTranslucentFramebuffer();
            if (translucentFb != null) {
                translucentFb.bind();
                if (shouldLog) {
                    int newFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                    Nebula.LOGGER.info("[Nebula/IrisMixin DEBUG] Bound Iris translucent FBO: {} -> {}", previousFBO,
                            newFBO);
                }
            } else if (shouldLog) {
                Nebula.LOGGER.warn("[Nebula/IrisMixin DEBUG] Translucent framebuffer is null!");
            }
        } else if (shouldLog) {
            Nebula.LOGGER.warn("[Nebula/IrisMixin DEBUG] SodiumTerrainPipeline is null!");
        }

        RenderSystem.depthMask(false);

        // 3. 获取矩阵
        Matrix4f modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
        Matrix4f projection = CapturedRenderingState.INSTANCE.getGbufferProjection();

        if (shouldLog) {
            Nebula.LOGGER.info("[Nebula/IrisMixin DEBUG] Pre-render state:");
            Nebula.LOGGER.info("  - Previous FBO: {}, Current FBO: {}", previousFBO,
                    GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
            Nebula.LOGGER.info("  - Matrices valid: MV={}, P={}", modelView != null, projection != null);
        }

        // 4. 执行渲染
        if (modelView != null && projection != null) {
            try {
                ClientAnimationManager.getInstance().renderTickDirect(modelView, projection);

                if (shouldLog) {
                    int particlesAfter = ClientAnimationManager.getInstance().getParticleCount();
                    Nebula.LOGGER.info("[Nebula/IrisMixin DEBUG] Render completed:");
                    Nebula.LOGGER.info("  - Particles rendered: {}", particlesAfter);

                    int glError = GL11.glGetError();
                    if (glError != GL11.GL_NO_ERROR) {
                        Nebula.LOGGER.warn("  - GL Error: {}", glError);
                    }
                }
            } catch (Exception e) {
                Nebula.LOGGER.error("[Nebula/IrisMixin] Rendering failed", e);
            }
        }

        // 5. 恢复状态
        GL20.glUseProgram(irisShaderProgram);
        RenderSystem.depthMask(oldDepthMask);

        // 恢复之前的 FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);

        if (shouldLog) {
            Nebula.LOGGER.info("[Nebula/IrisMixin DEBUG] State restored. FBO: {}", previousFBO);
        }
    }
}
