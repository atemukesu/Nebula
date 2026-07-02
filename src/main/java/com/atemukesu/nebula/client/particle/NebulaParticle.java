package com.atemukesu.nebula.client.particle;

import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;

public class NebulaParticle extends SpriteBillboardParticle {
    public NebulaParticle(ClientWorld world, double x, double y, double z, Sprite sprite) {
        super(world, x, y, z);
        this.sprite = sprite;
        this.gravityStrength = 0.0f;
        this.collidesWithWorld = false;
        this.maxAge = Integer.MAX_VALUE;
        this.setBoundingBoxSpacing(0.0f, 0.0f);
    }

    @Override
    public void tick() {
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getBrightness(float tint) {
        return 0x00F000F0;
    }

    @Override
    protected float getMinU() {
        return sprite.getMinU();
    }

    @Override
    protected float getMaxU() {
        return sprite.getMaxU();
    }

    @Override
    protected float getMinV() {
        return sprite.getMinV();
    }

    @Override
    protected float getMaxV() {
        return sprite.getMaxV();
    }

    public void setFrameData(double x, double y, double z, float size, int packedColor) {
        this.prevPosX = x;
        this.prevPosY = y;
        this.prevPosZ = z;
        this.x = x;
        this.y = y;
        this.z = z;

        this.scale = size;
        this.red = ((packedColor >> 0) & 0xFF) / 255.0f;
        this.green = ((packedColor >> 8) & 0xFF) / 255.0f;
        this.blue = ((packedColor >> 16) & 0xFF) / 255.0f;
        this.alpha = ((packedColor >> 24) & 0xFF) / 255.0f;
    }
}
