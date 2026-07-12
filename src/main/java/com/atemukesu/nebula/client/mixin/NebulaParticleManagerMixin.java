package com.atemukesu.nebula.client.mixin;

import com.atemukesu.nebula.client.ClientAnimationManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.particle.ParticleManager")
public class NebulaParticleManagerMixin {

    @Inject(method = "renderParticles", at = @At("TAIL"))
    private void nebula$renderAfterVanillaParticles(
            //? if < 1.21 {
            /*net.minecraft.client.util.math.MatrixStack matrices,
            net.minecraft.client.render.VertexConsumerProvider.Immediate vertexConsumers,
            LightmapTextureManager lightmapTextureManager,
            Camera camera,
            float tickDelta,
            CallbackInfo ci
            *///? } else {
            LightmapTextureManager lightmapTextureManager,
            Camera camera,
            float tickDelta,
            CallbackInfo ci
            //? }
    ) {
        ClientAnimationManager.getInstance().renderTickFromParticlePhase(camera, tickDelta);
    }
}
