package com.atemukesu.nebula.mixin.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.api.v0.IrisApi;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 到 Iris 的渲染管线，精确注入粒子渲染
 * 
 * 注入时机：beginTranslucents 方法的末尾
 * 此时 Iris 已经设置好 FBO 和渲染状态，正好适合注入半透明粒子
 * 
 * 注意：remap = false 因为 IrisRenderingPipeline 是 Iris 的类，不是 Minecraft 的
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisPipeline {

    @Unique
    private static boolean hasLoggedInjection = false;

    @Inject(method = "beginTranslucents", at = @At("TAIL"))
    private void nebula$injectParticles(CallbackInfo ci) {
        // 记录注入日志（只记录一次）
        if (!hasLoggedInjection) {
            Nebula.LOGGER.info("[Nebula/IrisMixin] ✓ Injected at IrisRenderingPipeline.beginTranslucents TAIL");
            hasLoggedInjection = true;
        }

        // 1. 现场保护
        // Iris 在这里已经开启了 Blend，这正是我们需要的
        RenderSystem.depthMask(false); // 粒子通常不写入深度，只进行深度测试

        // 2. 获取矩阵 - 直接从 CapturedRenderingState 单例获取，无需反射！
        Matrix4f modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
        Matrix4f projection = CapturedRenderingState.INSTANCE.getGbufferProjection();

        // 3. 执行渲染
        // 此时处于 Iris 的管线内部，FBO 已经正确绑定
        if (modelView != null && projection != null) {
            try {
                ClientAnimationManager.getInstance().renderTickDirect(modelView, projection);
            } catch (Exception e) {
                Nebula.LOGGER.error("[Nebula/IrisMixin] Rendering failed", e);
            }
        } else {
            Nebula.LOGGER.debug("[Nebula/IrisMixin] Matrices are null, skipping frame.");
        }

        // 4. 现场恢复
        // Iris 在这之后会继续渲染半透明物体，它会自己设置状态
        RenderSystem.depthMask(true);
    }
}
