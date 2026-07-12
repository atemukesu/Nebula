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

package com.atemukesu.nebula.client.mixin;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.atemukesu.nebula.client.bridge.IrisBridge;
import com.atemukesu.nebula.client.config.ModConfig;
import com.atemukesu.nebula.client.enums.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.*;
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
import net.minecraft.client.particle.ParticleManager;

//? if >= 1.21 {

import net.minecraft.client.render.RenderTickCounter;

//? }

@Mixin(WorldRenderer.class)
public class NebulaWorldRendererMixin {

    @Shadow
    private Frustum frustum;

    @Unique
    private static boolean hasLoggedIrisPath = false;

    @Inject(method = "render", at = @At(value = "INVOKE",
            shift = At.Shift.AFTER,
            //? if < 1.21 {
            
            /*target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;F)V"
            
            *///? } else {
            target = "Lnet/minecraft/client/particle/ParticleManager;renderParticles(Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;F)V"
            //? }
    ))
    private void nebula$injectParticles(
            //? if < 1.21 {
            
            /*MatrixStack matrices, float tickDelta, long limitTime,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager, Matrix4f projection,
            CallbackInfo ci
            
            *///? } else {
            RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager, Matrix4f positionMatrix, Matrix4f projectionMatrix,
            CallbackInfo ci

            //? }
    ) {
        //? if >= 1.21 {
        float tickDelta = tickCounter.getTickDelta(true);
        Matrix4f modelView = positionMatrix;
        Matrix4f projection = projectionMatrix;

        //? } else {
        /*// 1.21 环境下：从 MatrixStack 获取 modelView
        // tickDelta 和 projection 已经是参数了，不需要额外定义
        
        Matrix4f modelView = matrices.peek().getPositionMatrix();
        
        *///? }

                if (ModConfig.getInstance().getRenderPipeline() != RenderPipeline.VANILLA_BATCH) {
                        return;
                }

                ClientAnimationManager.getInstance().updateParticlePositions(camera, this.frustum, tickDelta);
        }

    /**
     * In the fabulous/transparency-post-process path Minecraft renders particles
     * before the translucent terrain pass.  GPU Nebula particles used to be
     * injected at ParticleManager.TAIL, so low-alpha fragments (which correctly
     * do not write depth) were overwritten by that later pass.  Render them just
     * before weather instead: terrain translucency is complete, while Iris can
     * still select its after-translucent particle framebuffer.
     */
    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
            target = "Lnet/minecraft/client/render/WorldRenderer;renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V"), require = 0)
    private void nebula$renderIrisParticlesAfterTranslucency(
            //? if < 1.21 {
            /*MatrixStack matrices, float tickDelta, long limitTime,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager, Matrix4f projection,
            CallbackInfo ci
            *///? } else {
            RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager, Matrix4f positionMatrix, Matrix4f projectionMatrix,
            CallbackInfo ci
            //? }
    ) {
        if (ModConfig.getInstance().getRenderPipeline() != RenderPipeline.GPU
                || !IrisBridge.getInstance().isIrisRenderingActive()) {
            return;
        }

        if (!hasLoggedIrisPath) {
            Nebula.LOGGER.info("[Nebula/Render] Rendering Iris GPU particles after translucent terrain.");
            hasLoggedIrisPath = true;
        }

        //? if >= 1.21 {
        ClientAnimationManager.getInstance().renderTickFromParticlePhase(camera, tickCounter.getTickDelta(true));
        //? } else {
        /*ClientAnimationManager.getInstance().renderTickFromParticlePhase(camera, tickDelta);
        *///? }
    }

}
