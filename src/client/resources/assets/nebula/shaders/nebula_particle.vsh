#version 150

// Quad 顶点属性
in vec3 Position;
in vec2 UV;

// 实例化数据 (Per-Instance)
in vec3 iPos;      // 粒子位置
in vec4 iColor;    // 粒子颜色 (归一化)
in float iSize;    // 粒子大小
in float iTexID;   // 纹理 ID
in float iSeqID;   // 序列帧 ID

// Uniforms
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 CameraRight;
uniform vec3 CameraUp;
uniform vec3 Origin;

// 输出到片元着色器
out vec4 vColor;
out vec2 vUV;
flat out float vTexLayer;

void main() {
    vColor = iColor;
    vUV = UV;
    // 纹理层 = texID + seqID (已在 CPU 端预计算)
    vTexLayer = iTexID + iSeqID;
    
    // World position of the particle center
    vec3 center = Origin + iPos;
    
    // Billboard offset based on camera vectors and particle size
    vec3 offset = (CameraRight * (Position.x - 0.5) + CameraUp * (Position.y - 0.5)) * iSize;
    
    // Final vertex position in world space
    vec3 finalPos = center + offset;
    
    gl_Position = ProjMat * ModelViewMat * vec4(finalPos, 1.0);
}
