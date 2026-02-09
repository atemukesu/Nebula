#version 430 core

// 标准 Quad 属性
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;

// 粒子数据结构
struct Particle {
    float prevX, prevY, prevZ;
    float size;
    float curX, curY, curZ;
    uint colorPacked;
    float texLayer;
    float pad1, pad2, pad3;
};

layout(std430, binding = 0) buffer ParticleBuffer {
    Particle particles[];
};

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
out float vDistance;
flat out float vBloomFactor;

void main() {
    // 1. 正常获取数据 (无论是否剔除，都执行赋值，防止 AMD 驱动报错)
    Particle p = particles[gl_InstanceID];
    
    // 颜色解包
    vColor = unpackUnorm4x8(p.colorPacked);
    float alpha = vColor.a;

    // 传递其他 Varyings
    vUV = UV;
    vTexLayer = p.texLayer;
    vBloomFactor = 1.5;

    // 2. 正常计算位置 (Billboarding + Interpolation)
    vec3 prevPos = vec3(p.prevX, p.prevY, p.prevZ);
    vec3 currPos = vec3(p.curX, p.curY, p.curZ);
    vec3 interpolatedPos = mix(prevPos, currPos, PartialTicks);
    
    vec3 center = Origin + interpolatedPos;
    vec3 offset = (CameraRight * (Position.x - 0.5) + 
                   CameraUp * (Position.y - 0.5)) * p.size;
    vec3 finalPos = center + offset;
    
    // 计算距离
    vec4 viewPos = ModelViewMat * vec4(finalPos, 1.0);
    vDistance = length(viewPos.xyz);
    
    // 计算最终位置
    gl_Position = ProjMat * viewPos;

    // 3. 安全的剔除逻辑
    // 不要使用 return，而是直接覆盖 gl_Position
    // 这样所有的 out 变量都已经赋值了，驱动程序不会报错
    
    // Opaque Pass (只画不透明)
    if (uRenderPass == 0 && alpha < 0.99) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // 移出屏幕
    }
    // Translucent Pass (只画半透明)
    if (uRenderPass == 1 && alpha >= 0.99) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // 移出屏幕
    }
}