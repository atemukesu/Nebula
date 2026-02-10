#version 430 core

uniform sampler2D uAccumTexture;
uniform sampler2D uRevealTexture;

in vec2 texCoord;

out vec4 fragColor;

const float EPSILON = 0.00001;

float max3(vec3 v) {
    return max(max(v.x, v.y), v.z);
}

void main() {
    // 使用 texelFetch 避免纹理过滤导致的精度问题
    ivec2 coords = ivec2(gl_FragCoord.xy);
    
    // 从 Reveal 纹理读取剩余可见度
    // 1.0 = 完全未被遮挡 (没有透明粒子)
    // 0.0 = 完全被遮挡
    float reveal = texelFetch(uRevealTexture, coords, 0).r;

    // 性能优化：如果没有像素被绘制 (reveal 接近 1)，直接丢弃
    if (reveal > 0.999) {
        discard;
    }

    // 从 Accum 纹理读取累积值
    // accum.rgb = sum(color * alpha * weight)
    // accum.a = sum(alpha * weight)
    vec4 accum = texelFetch(uAccumTexture, coords, 0);

    // suppress overflow (抑制溢出)
    if (isinf(max3(abs(accum.rgb)))) {
        accum.rgb = vec3(accum.a);
    }

    // prevent floating point precision bug (防止除零)
    vec3 avgColor = accum.rgb / max(accum.a, EPSILON);

    // 最终输出：
    // Color = avgColor
    // Alpha = 1.0 - reveal (即不透明度)
    // 混合模式 SRC_ALPHA, ONE_MINUS_SRC_ALPHA 会产生：
    // Result = avgColor * (1 - reveal) + Background * reveal
    fragColor = vec4(avgColor, 1.0 - reveal);
}
