#version 430 core

uniform sampler2D uAccumTexture;
uniform sampler2D uRevealTexture;

in vec2 texCoord;

out vec4 fragColor;

float max3(vec3 v) {
    return max(max(v.x, v.y), v.z);
}

void main() {
    // 从 Reveal 纹理读取剩余可见度
    // 1.0 = 完全未被遮挡
    // 0.0 = 完全被遮挡
    float reveal = texture(uRevealTexture, texCoord).r;

    // 性能优化：如果没有像素被绘制 (reveal 接近 1)，直接丢弃
    if (reveal >= 0.99999) {
        discard;
    }

    // 从 Accum 纹理读取累积值
    // accum.rgb = sum(color * alpha * weight)
    // accum.a = sum(alpha * weight)
    vec4 accum = texture(uAccumTexture, texCoord);

    // 避免除以零
    // float weight = max(accum.a, 0.00001);

    // suppress overflow (抑制溢出)
    if (isinf(max3(abs(accum.rgb)))){
        accum.rgb = vec3(accum.a);
    }

    // prevent floating point precision bug (防止除零导致的花屏)
    vec3 avgColor = accum.rgb / max(accum.a, 0.00001);

    // 最终输出：
    // Color = avgColor
    // Alpha = 1.0 - reveal (即不透明度)
    //
    // 混合模式应为: ONE_MINUS_SRC_ALPHA (Target) + SRC_ALPHA (Source) ???
    // 不，标准混合是: Result = Src.rgb * Src.a + Dst.rgb * (1 - Src.a)
    // 这里 Src.a = 1.0 - reveal
    // Result = avgColor * (1 - reveal) + Background * reveal
    // 这正是我们期望的公式 (Background * reveal = Background * remaining_visibility)
    
    fragColor = vec4(avgColor, 1.0 - reveal);
}
