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
uniform vec3 Origin;
uniform float PartialTicks;
uniform int uRenderPass; // 0=Opaque, 1=Translucent, 2=All

// 移除 CameraRight 和 CameraUp uniform，改为在 Shader 内部进行视空间 Billboarding
// uniform vec3 CameraRight;
// uniform vec3 CameraUp;

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

    // 2. 计算中心点的世界坐标 (World Space Center)
    vec3 prevPos = vec3(p.prevX, p.prevY, p.prevZ);
    vec3 currPos = vec3(p.curX, p.curY, p.curZ);
    vec3 interpolatedPos = mix(prevPos, currPos, PartialTicks);
    vec3 centerWorld = Origin + interpolatedPos; 

    // ================== 核心修复开始 ==================
    
    // 3. 将中心点转换到【视空间】 (View Space)
    // 此时 viewCenter 是粒子中心相对于相机的坐标
    vec4 viewCenter = ModelViewMat * vec4(centerWorld, 1.0);

    // 4. 在视空间进行 Billboard 偏移
    // 在视空间中，相机永远正对屏幕，所以我们直接在 XY 平面上偏移即可
    // 这样粒子永远面朝相机，无需计算 Right/Up 向量
    vec2 offset = (Position.xy - 0.5) * p.size;
    
    // 直接把偏移加在 viewCenter 上 (相当于 Right=(1,0,0), Up=(0,1,0))
    vec3 finalViewPos = viewCenter.xyz;
    finalViewPos.x += offset.x;
    finalViewPos.y += offset.y;
    
    // 5. 计算最终裁切坐标
    // 这里的 distance 直接取 viewCenter 的长度即可 (这是粒子中心的距离，更稳定)
    vDistance = length(viewCenter.xyz);
    
    gl_Position = ProjMat * vec4(finalViewPos, 1.0);
    
    // ================== 核心修复结束 ==================
}