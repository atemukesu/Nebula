#version 430 core

// 标准 Quad 属性 (Per-Vertex)
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;

// 粒子数据结构 (std430 对齐: 16字节步长)
struct Particle {
    // block 0 (offset 0)
    float prevX, prevY, prevZ;
    float size;
    
    // block 1 (offset 16)
    float curX, curY, curZ;
    uint colorPacked; // RGBA8 打包
    
    // block 2 (offset 32)
    float texLayer;   // texID + seqID 预计算
    float pad1, pad2, pad3;
};

// SSBO 绑定 (对应 Java SSBO_BINDING_INDEX = 0)
layout(std430, binding = 0) buffer ParticleBuffer {
    Particle particles[];
};

// Uniforms
// Uniforms
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 CameraRight;
uniform vec3 CameraUp;
uniform vec3 Origin;
uniform float PartialTicks;
uniform int uRenderPass; // 0=Opaque, 1=Translucent, 2=All

// Outputs
out vec4 vColor;
out vec2 vUV;
flat out float vTexLayer;
out float vDistance;           // 粒子到摄像机的距离
flat out float vBloomFactor;   // 逐粒子的 Bloom 亮度因子 (传递给片元着色器)

void main() {
    // 通过 InstanceID 获取粒子数据
    Particle p = particles[gl_InstanceID];
     
    // 解包颜色获取 Alpha
    vec4 color = unpackUnorm4x8(p.colorPacked);
    float alpha = color.a;
    
    // Pass 剔除逻辑
    // 0: Opaque Pass (只画不透明粒子, alpha > 0.99)
    if (uRenderPass == 0 && alpha < 0.99) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // NDC 剔除 (移出屏幕)
        return;
    }
    // 1: Translucent Pass (只画半透明粒子, alpha <= 0.99)
    if (uRenderPass == 1 && alpha >= 0.99) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // NDC 剔除 (移出屏幕)
        return;
    }
    
    // 1. 位置插值 (Linear Interpolation)
    vec3 prevPos = vec3(p.prevX, p.prevY, p.prevZ);
    vec3 currPos = vec3(p.curX, p.curY, p.curZ);
    vec3 interpolatedPos = mix(prevPos, currPos, PartialTicks);
    
    // 2. 颜色解包 (uint ABGR -> vec4 RGBA normalized)
    // unpackUnorm4x8 将 32位uint 拆解为 4个 0.0-1.0 的 float
    vColor = unpackUnorm4x8(p.colorPacked);
    
    // 3. 计算 Billboarding
    vec3 center = Origin + interpolatedPos;
    vec3 offset = (CameraRight * (Position.x - 0.5) + 
                   CameraUp * (Position.y - 0.5)) * p.size;
                   
    vec3 finalPos = center + offset;
    
    // 4. 计算到摄像机的距离 (用于片元着色器的距离补偿)
    vec4 viewPos = ModelViewMat * vec4(finalPos, 1.0);
    vDistance = length(viewPos.xyz);
    
    gl_Position = ProjMat * viewPos;
    
    vUV = UV;
    vTexLayer = p.texLayer;
    
    vBloomFactor = 1.5;
}