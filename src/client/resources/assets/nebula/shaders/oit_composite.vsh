#version 150

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

void main() {
    // 简单的全屏四边形映射 (Input 0..1 -> NDC -1..1)
    vec2 pos = Position.xy * 2.0 - 1.0;
    gl_Position = vec4(pos, 0.0, 1.0);
    texCoord = UV0;
}
