package com.atemukesu.nebula.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Queue;

@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {
    @Accessor("particleAtlasTexture")
    SpriteAtlasTexture nebula$getParticleAtlasTexture();

    @Accessor("particles")
    Map<ParticleTextureSheet, Queue<Particle>> nebula$getParticles();
}
