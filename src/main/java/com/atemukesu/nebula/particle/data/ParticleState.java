package com.atemukesu.nebula.particle.data;

/**
 * 内存中的粒子状态，用于计算 P-Frame 差值。
 * 这不是 GPU 数据，而是逻辑数据。
 * 按照 NBL 文档的 "Zero Basis Principle"，新生成的粒子默认全是 0。
 */
public class ParticleState {
    public float x, y, z;
    public int r, g, b, a;
    public float size;
    public int texID;
    public int seqID;

    public ParticleState() {
        // NBL 文档 "Zero Basis Principle": 新粒子的所有属性默认为 0
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.r = 0; // 修正：从 255 改为 0
        this.g = 0;
        this.b = 0;
        this.a = 0;
        this.size = 0;
        this.texID = 0;
        this.seqID = 0;
    }

    public ParticleState copy() {
        ParticleState copy = new ParticleState();
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        copy.r = this.r;
        copy.g = this.g;
        copy.b = this.b;
        copy.a = this.a;
        copy.size = this.size;
        copy.texID = this.texID;
        copy.seqID = this.seqID;
        return copy;
    }
}
