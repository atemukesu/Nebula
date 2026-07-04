package com.atemukesu.nebula.client.particle;

import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;

public class NebulaParticle extends SpriteBillboardParticle {
    private Sprite currentSprite;
    private int colCount = 1;
    private int rowCount = 1;
    private int currentSeq = 0;

    public NebulaParticle(ClientWorld world, double x, double y, double z, Sprite sprite) {
        super(world, x, y, z);
        this.currentSprite = sprite;
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

    public void setTextureData(Sprite sprite, int cols, int rows, int seq) {
        this.currentSprite = sprite;
        this.setSprite(sprite);
        this.colCount = Math.max(1, cols);
        this.rowCount = Math.max(1, rows);
        this.currentSeq = seq;
    }

    @Override
    protected float getMinU() {
        if (currentSprite == null) return super.getMinU();
        float baseMinU = currentSprite.getMinU();
        float width = (currentSprite.getMaxU() - baseMinU) / colCount;
        int col = currentSeq % colCount;
        return baseMinU + col * width;
    }

    @Override
    protected float getMaxU() {
        if (currentSprite == null) return super.getMaxU();
        float baseMinU = currentSprite.getMinU();
        float width = (currentSprite.getMaxU() - baseMinU) / colCount;
        int col = currentSeq % colCount;
        return baseMinU + (col + 1) * width;
    }

    @Override
    protected float getMinV() {
        if (currentSprite == null) return super.getMinV();
        float baseMinV = currentSprite.getMinV();
        float height = (currentSprite.getMaxV() - baseMinV) / rowCount;
        int row = (currentSeq / colCount) % rowCount;
        return baseMinV + row * height;
    }

    @Override
    protected float getMaxV() {
        if (currentSprite == null) return super.getMaxV();
        float baseMinV = currentSprite.getMinV();
        float height = (currentSprite.getMaxV() - baseMinV) / rowCount;
        int row = (currentSeq / colCount) % rowCount;
        return baseMinV + (row + 1) * height;
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

    public void resurrect(double x, double y, double z) {
        this.dead = false;
        this.prevPosX = x;
        this.prevPosY = y;
        this.prevPosZ = z;
        this.x = x;
        this.y = y;
        this.z = z;
        this.age = 0;
        this.maxAge = Integer.MAX_VALUE;
    }
}