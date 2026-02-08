#version 430 core

in vec4 vColor;
in vec2 vUV;
flat in float vTexLayer;
in float vDistance; // 从顶点着色器传入的距离

uniform sampler2DArray Sampler0;
uniform int UseTexture;
uniform float EmissiveStrength; // 发光强度 (0.0 - 2.0+)
uniform int IrisMRT;            // 是否启用 Iris MRT 输出 (0=原版, 1=Iris光影)

// === 输出 ===
// 在 Iris 光影模式下使用 MRT 输出到多个缓冲区
// 在原版模式下只使用 fragColor
layout(location = 0) out vec4 fragColor;      // 主颜色输出
layout(location = 1) out vec4 fragData1;      // 光照/法线数据 (仅 Iris)
layout(location = 2) out vec4 fragData2;      // 高光/发光数据 (仅 Iris)

void main() {
    vec4 texColor;
    
    if (UseTexture == 1) {
        // 从纹理数组采样
        texColor = texture(Sampler0, vec3(vUV, vTexLayer));
    } else {
        // 无纹理时使用软圆形粒子效果，增强中心亮度
        vec2 center = vUV - 0.5;
        float dist = length(center) * 2.0;
        // 使用更柔和的衰减曲线，让粒子整体更亮
        float alpha = 1.0 - smoothstep(0.5, 1.0, dist);
        // 中心区域额外增亮
        float coreBrightness = 1.0 + 0.5 * (1.0 - smoothstep(0.0, 0.3, dist));
        texColor = vec4(vec3(coreBrightness), alpha);
    }
    
    vec4 baseColor = texColor * vColor;
    
    // === 距离补偿 ===
    // 适度增强远处粒子的可见性，但避免过度发光导致"虚幻"感
    // 最大增加 30% 亮度 (factor 1.0 ~ 1.3)
    float distanceFactor = 1.0 + clamp(vDistance / 128.0, 0.0, 1.0) * 0.3;
    
    // === 发光强度 ===
    // 默认发光强度为 1.0，可通过 uniform 调整
    float emissive = (EmissiveStrength > 0.0) ? EmissiveStrength : 1.0;
    
    // 应用发光和距离补偿
    vec3 glowColor = baseColor.rgb * emissive * distanceFactor;
    
    // === 输出颜色 ===
    fragColor = vec4(glowColor, baseColor.a);
    
    // === MRT 输出 (仅 Iris 光影模式) ===
    if (IrisMRT == 1) {
        // 光照数据：设置为最大光照 (模拟完全暴露在光源下)
        // lmcoord 格式: (blockLight, skyLight) 归一化到 [0,1]
        fragData1 = vec4(1.0, 1.0, 0.0, 1.0);
        
        // 发光/高光数据 (对于 labPBR/oldPBR 格式)
        // r: smoothness, g: metalness, b: emissiveness
        // 将发光度设为最大，让 Iris 知道这是发光物体
        fragData2 = vec4(0.0, 0.0, emissive, 1.0);
    }
    // 原版模式下不需要额外输出，fragData1/fragData2 会被忽略
}
