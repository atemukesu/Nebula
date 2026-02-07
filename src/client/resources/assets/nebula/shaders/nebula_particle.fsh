#version 150

in vec4 vColor;
in vec2 vUV;
flat in float vTexLayer;

uniform sampler2DArray Sampler0;
uniform int UseTexture;

out vec4 fragColor;

void main() {
    vec4 texColor;
    
    if (UseTexture == 1) {
        // 从纹理数组采样
        texColor = texture(Sampler0, vec3(vUV, vTexLayer));
    } else {
        // 无纹理时使用软圆形粒子效果
        vec2 center = vUV - 0.5;
        float dist = length(center) * 2.0;
        float alpha = 1.0 - smoothstep(0.7, 1.0, dist);
        texColor = vec4(1.0, 1.0, 1.0, alpha);
    }
    
    fragColor = texColor * vColor;
}
