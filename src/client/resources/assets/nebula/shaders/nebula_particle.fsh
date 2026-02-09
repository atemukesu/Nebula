#version 430 core

in vec4 vColor;
in vec2 vUV;
flat in float vTexLayer;
in float vDistance;           // 从顶点着色器传入的距离
flat in float vBloomFactor;   // 接收顶点传入的亮度 (逐粒子 Bloom 因子)

uniform sampler2DArray Sampler0;
uniform int UseTexture;
uniform float EmissiveStrength; // 发光强度 (0.0 - 2.0+)
uniform int IrisMRT;
uniform int uRenderPass; // 0=Opaque, 1=Translucent, 2=All

// === 输出 ===
// 在 Iris 光影模式下使用 MRT 输出到多个缓冲区
// 在原版模式下只使用 fragColor
layout(location = 0) out vec4 fragColor;      // 主颜色输出 (OIT: Accum)
layout(location = 1) out vec4 fragData1;      // 光照/法线数据 (OIT: Reveal)
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
    
    // [借鉴点]：不要只依赖 EmissiveStrength，而是乘上逐粒子的 vBloomFactor
    // MadParticles 逻辑：vertexColor * sizeExtraLight.y
    vec4 baseColor = texColor * vColor;
    
    // 允许 RGB 值超过 1.0 (HDR)
    // 这种 "过曝" 的颜色会被光影包捕捉并产生强烈的辉光
    vec3 hdrColor = baseColor.rgb * vBloomFactor * EmissiveStrength;
    
    if (uRenderPass == 1) { // Translucent Pass (OIT)
        float alpha = clamp(baseColor.a, 0.0, 1.0);
        
        // Weighted Blended OIT Weight Function
        // weight = alpha * max(0.01, min(1.0, 3000.0 / (1e-5 + pow(abs(z) / 200.0, 4.0) + ...)))
        // 简化版权重函数:
        float weight = clamp(pow(min(1.0, alpha * 10.0) + 0.01, 3.0) * 1e8 * pow(1.0 - gl_FragCoord.z * 0.9, 3.0), 1e-2, 3e3) * min(2.0, gl_FragCoord.w);

        // Accumulator (Location 0)
        // Store: rgb * alpha * weight, alpha * weight
        fragColor = vec4(hdrColor.rgb * alpha * weight, alpha * weight);
        
        // Revealage (Location 1)
        // Store: alpha (Blend mode will enable ONE_MINUS_SRC_COLOR to achieve product(1-a))
        fragData1 = vec4(alpha);
        
        // fragData2 unused in OIT pass
    } else {
        // === 标准输出 (Opaque / Normal) ===
        fragColor = vec4(hdrColor, baseColor.a);
        
        // === Iris MRT 兼容 (保留 Nebula 的优势) ===
        if (IrisMRT == 1) {
            fragData1 = vec4(1.0, 1.0, 0.0, 1.0);
            // 让 fragData2 的发光强度也受 vBloomFactor 影响
            fragData2 = vec4(0.0, 0.0, min(1.0, EmissiveStrength * vBloomFactor * 0.5), 1.0);
        }
    }
}
