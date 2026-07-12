package com.atemukesu.nebula.client.shader;

import com.atemukesu.nebula.Nebula;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisShaderTransformer {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)\\b.*$");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("(?m)^\\s*#extension\\b.*$");
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern OUT0_PATTERN = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final Pattern OUT1_PATTERN = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*1\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final String TEXTURE_SAMPLE_PATTERN =
            "(?m)(?<lhs>\\b%s\\s*=\\s*)(?<sample>texture(?:2D)?\\s*\\([^;]+\\)(?:\\s*\\*\\s*[^;]+)?);";

    private IrisShaderTransformer() {
    }

    public static String transformVertexSource(String programName, String source) {
        if (!isTargetProgram(programName) || !looksLikeParticleVertexShader(source) || source.contains("NebulaVColor")) {
            return source;
        }

        int version = shaderVersion(source);
        if (version < 120) {
            Nebula.LOGGER.warn("[Nebula/Iris] Skipping {} vertex hook because GLSL {} is unsupported.", programName, version);
            return source;
        }

        String transformed = injectShaderBlock(source, vertexExtensions(version), vertexBlock(version));
        if (transformed == null) {
            Nebula.LOGGER.warn("[Nebula/Iris] Skipping {} vertex hook because the shader has no #version directive.", programName);
            return source;
        }

        Matcher mainMatcher = MAIN_PATTERN.matcher(transformed);
        if (!mainMatcher.find()) {
            return source;
        }

        String instanceId = version >= 140 ? "gl_InstanceID" : "gl_InstanceIDARB";
        int bodyStart = mainMatcher.end();
        String injectedMain = """

                if (NebulaIsActive == 1) {
                    NebulaParticle nebulaParticle = nebulaParticles[%s];
                    uint nebulaColor = nebulaParticle.colorPacked;
                    NebulaVColor = vec4(
                        float(nebulaColor & 255u),
                        float((nebulaColor >> 8u) & 255u),
                        float((nebulaColor >> 16u) & 255u),
                        float((nebulaColor >> 24u) & 255u)
                    ) / 255.0;
                    NebulaVTexLayer = nebulaParticle.texLayer;
                    NebulaVBloomFactor = 1.5;
                    NebulaVUV = vec2((gl_VertexID == 1 || gl_VertexID == 2) ? 1.0 : 0.0,
                                     (gl_VertexID <= 1) ? 1.0 : 0.0);
                    vec3 nebulaPrevPos = vec3(nebulaParticle.prevX, nebulaParticle.prevY, nebulaParticle.prevZ);
                    vec3 nebulaCurrPos = vec3(nebulaParticle.curX, nebulaParticle.curY, nebulaParticle.curZ);
                    vec3 nebulaInterpolatedPos = mix(nebulaPrevPos, nebulaCurrPos, NebulaPartialTicks);
                    vec3 nebulaCenterWorld = NebulaOrigin + nebulaInterpolatedPos;
                    vec4 nebulaViewCenter = NebulaModelViewMat * vec4(nebulaCenterWorld, 1.0);
                    vec2 nebulaCorner = vec2((gl_VertexID == 0 || gl_VertexID == 3) ? -0.5 : 0.5,
                                             (gl_VertexID <= 1) ? -0.5 : 0.5) * nebulaParticle.size;
                    vec3 nebulaFinalViewPos = nebulaViewCenter.xyz + vec3(nebulaCorner, 0.0);
                    NebulaVDistance = length(nebulaViewCenter.xyz);
                    gl_Position = NebulaProjMat * vec4(nebulaFinalViewPos, 1.0);
                    %s
                    return;
                }
            """.formatted(instanceId, buildCompatibilityVertexAssignments(transformed));
        return transformed.substring(0, bodyStart) + injectedMain + transformed.substring(bodyStart);
    }

    public static String transformFragmentSource(String programName, String source) {
        if (!isTargetProgram(programName) || !looksLikeParticleFragmentShader(source) || source.contains("NebulaVColor")) {
            return source;
        }

        int version = shaderVersion(source);
        if (version < 120) {
            return source;
        }

        String primaryOutput = findPrimaryOutput(source);
        if (primaryOutput == null) {
            Nebula.LOGGER.warn("[Nebula/Iris] Skipping {} fragment hook because no color output was recognized.", programName);
            return source;
        }

        String transformed = injectShaderBlock(source, fragmentExtensions(version), fragmentBlock(version));
        if (transformed == null) {
            return source;
        }

        String withNebulaSample = replacePrimaryTextureSample(transformed, primaryOutput);
        boolean usesOriginalPipeline = !withNebulaSample.equals(transformed);
        transformed = withNebulaSample;

        Matcher mainMatcher = MAIN_PATTERN.matcher(transformed);
        if (!mainMatcher.find()) {
            return source;
        }

        String textureFunction = version >= 130 ? "texture" : "texture2DArray";
        String secondaryOutput = findSecondaryOutput(transformed);
        String secondaryOutputLine = secondaryOutput == null ? "" : secondaryOutput + " = vec4(nebulaAlpha);";
        String injectedMain = usesOriginalPipeline
                ? originalPipelineFragmentPrefix(textureFunction)
                : fallbackFragmentPrefix(textureFunction, primaryOutput, secondaryOutputLine);

        int bodyStart = mainMatcher.end();
        return transformed.substring(0, bodyStart) + injectedMain + transformed.substring(bodyStart);
    }

    private static String buildCompatibilityVertexAssignments(String source) {
        StringBuilder assignments = new StringBuilder();
        appendIfDeclared(assignments, source, "out vec2 uv", "uv = NebulaVUV;");
        appendIfDeclared(assignments, source, "varying vec2 uv", "uv = NebulaVUV;");
        appendIfDeclared(assignments, source, "out vec4 tint", "tint = NebulaVColor;");
        appendIfDeclared(assignments, source, "varying vec4 tint", "tint = NebulaVColor;");
        appendIfDeclared(assignments, source, "out vec2 light_levels", "light_levels = vec2(1.0);");
        appendIfDeclared(assignments, source, "varying vec2 light_levels", "light_levels = vec2(1.0);");
        appendIfDeclared(assignments, source, "out vec3 position_view", "position_view = nebulaFinalViewPos;");
        appendIfDeclared(assignments, source, "varying vec3 position_view", "position_view = nebulaFinalViewPos;");
        if (declares(source, "out vec3 position_scene") || declares(source, "varying vec3 position_scene")) {
            if (source.contains("view_to_scene_space")) {
                assignments.append("position_scene = view_to_scene_space(nebulaFinalViewPos);\n");
            } else {
                assignments.append("position_scene = nebulaFinalViewPos;\n");
            }
        }
        if (declares(source, "flat out uint material_mask")) {
            assignments.append("material_mask = 0u;\n");
        } else if (declares(source, "flat out int material_mask") || declares(source, "out int material_mask")) {
            assignments.append("material_mask = 0;\n");
        }
        appendIfDeclared(assignments, source, "flat out mat3 tbn",
                "tbn = mat3(vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));");
        if (source.contains("fog_params") && source.contains("get_fog_parameters") && source.contains("get_weather")) {
            assignments.append("fog_params = get_fog_parameters(get_weather());\n");
        }
        return assignments.toString();
    }

    private static void appendIfDeclared(StringBuilder builder, String source, String declaration, String assignment) {
        if (declares(source, declaration)) {
            builder.append(assignment).append('\n');
        }
    }

    private static boolean declares(String source, String declaration) {
        return source.contains(declaration);
    }

    private static String replacePrimaryTextureSample(String source, String primaryOutput) {
        Matcher mainMatcher = MAIN_PATTERN.matcher(source);
        if (!mainMatcher.find()) {
            return source;
        }

        String outputPattern = Pattern.quote(primaryOutput)
                .replace("\\Qgl_FragData[0]\\E", "gl_FragData\\s*\\[\\s*0\\s*\\]");
        Pattern pattern = Pattern.compile(TEXTURE_SAMPLE_PATTERN.formatted(outputPattern));
        String beforeMainBody = source.substring(0, mainMatcher.end());
        String mainBodyAndRest = source.substring(mainMatcher.end());
        Matcher matcher = pattern.matcher(mainBodyAndRest);
        if (!matcher.find()) {
            return source;
        }

        String originalAssignment = matcher.group();
        String replacement = """
                if (NebulaIsActive == 1) {
                    %s = nebulaBaseColor;
                } else {
                    %s
                }""".formatted(primaryOutput, originalAssignment);
        return beforeMainBody + matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String originalPipelineFragmentPrefix(String textureFunction) {
        return """

                vec4 nebulaBaseColor = vec4(1.0);
                if (NebulaIsActive == 1) {
                    const float nebulaAlphaCutoff = 0.001;
                    vec4 nebulaTexColor;
                    if (NebulaUseTexture == 1) {
                        nebulaTexColor = %s(NebulaSampler0, vec3(NebulaVUV, NebulaVTexLayer));
                    } else {
                        vec2 nebulaCenter = NebulaVUV - 0.5;
                        float nebulaDist = length(nebulaCenter) * 2.0;
                        float nebulaAlpha = 1.0 - smoothstep(0.5, 1.0, nebulaDist);
                        float nebulaCoreBrightness = 1.0 + 0.5 * (1.0 - smoothstep(0.0, 0.3, nebulaDist));
                        nebulaTexColor = vec4(vec3(nebulaCoreBrightness), nebulaAlpha);
                    }
                    nebulaBaseColor = nebulaTexColor * NebulaVColor;
                    nebulaBaseColor.rgb *= NebulaVBloomFactor * NebulaEmissiveStrength;
                    if (NebulaRenderPass == 0) {
                        if (nebulaBaseColor.a < 0.5) {
                            discard;
                        }
                    } else if (NebulaRenderPass == 1 || NebulaRenderPass == 3) {
                        if (nebulaBaseColor.a < nebulaAlphaCutoff || nebulaBaseColor.a >= 0.5) {
                            discard;
                        }
                    } else {
                        if (nebulaBaseColor.a < nebulaAlphaCutoff) {
                            discard;
                        }
                    }
                }
            """.formatted(textureFunction);
    }

    private static String fallbackFragmentPrefix(String textureFunction, String primaryOutput, String secondaryOutputLine) {
        return """
                if (NebulaIsActive == 1) {
                    const float nebulaAlphaCutoff = 0.001;
                    vec4 nebulaTexColor;
                    if (NebulaUseTexture == 1) {
                        nebulaTexColor = %s(NebulaSampler0, vec3(NebulaVUV, NebulaVTexLayer));
                    } else {
                        vec2 nebulaCenter = NebulaVUV - 0.5;
                        float nebulaDist = length(nebulaCenter) * 2.0;
                        float nebulaAlpha = 1.0 - smoothstep(0.5, 1.0, nebulaDist);
                        float nebulaCoreBrightness = 1.0 + 0.5 * (1.0 - smoothstep(0.0, 0.3, nebulaDist));
                        nebulaTexColor = vec4(vec3(nebulaCoreBrightness), nebulaAlpha);
                    }

                    vec4 nebulaBaseColor = nebulaTexColor * NebulaVColor;
                    if (NebulaRenderPass == 0) {
                        if (nebulaBaseColor.a < 0.5) {
                            discard;
                        }
                    } else if (NebulaRenderPass == 1 || NebulaRenderPass == 3) {
                        if (nebulaBaseColor.a < nebulaAlphaCutoff || nebulaBaseColor.a >= 0.5) {
                            discard;
                        }
                    } else {
                        if (nebulaBaseColor.a < nebulaAlphaCutoff) {
                            discard;
                        }
                    }

                    vec3 nebulaOutputColor = nebulaBaseColor.rgb;
                    if (NebulaRenderPass == 1) {
                        float nebulaAlpha = clamp(nebulaBaseColor.a, 0.0, 1.0);
                        float nebulaWeight = clamp(
                            pow(min(1.0, nebulaAlpha * 5.0) + 0.01, 3.0)
                            * pow(1.0 - gl_FragCoord.z * 0.9, 3.0)
                            * 250.0,
                            1e-2, 100.0
                        );
                        %s = vec4(nebulaOutputColor * nebulaAlpha * nebulaWeight, nebulaAlpha * nebulaWeight);
                        %s
                    } else {
                        vec3 nebulaHdrColor = nebulaOutputColor * NebulaVBloomFactor * NebulaEmissiveStrength;
                        %s = vec4(nebulaHdrColor, nebulaBaseColor.a);
                    }
                    return;
                }
            """.formatted(textureFunction, primaryOutput, secondaryOutputLine, primaryOutput);
    }

    private static String vertexExtensions(int version) {
        StringBuilder extensions = new StringBuilder();
        extensions.append("#extension GL_ARB_shader_storage_buffer_object : require\n");
        if (version < 140) {
            extensions.append("#extension GL_ARB_draw_instanced : require\n");
        }
        if (version < 130) {
            extensions.append("#extension GL_EXT_gpu_shader4 : require\n");
        }
        return extensions.toString();
    }

    private static String fragmentExtensions(int version) {
        if (version < 130) {
            return "#extension GL_EXT_texture_array : require\n#extension GL_EXT_gpu_shader4 : require\n";
        }
        return "";
    }

    private static String vertexBlock(int version) {
        String varying = version >= 130 ? "out" : "varying";
        return """
                struct NebulaParticle {
                    float prevX, prevY, prevZ;
                    float size;
                    float curX, curY, curZ;
                    uint colorPacked;
                    float texLayer;
                    float pad1, pad2, pad3;
                };

                layout(std430) buffer NebulaParticleBuffer {
                    NebulaParticle nebulaParticles[];
                };

                uniform int NebulaIsActive;
                uniform mat4 NebulaModelViewMat;
                uniform mat4 NebulaProjMat;
                uniform vec3 NebulaOrigin;
                uniform float NebulaPartialTicks;

                %s vec4 NebulaVColor;
                %s vec2 NebulaVUV;
                %s float NebulaVTexLayer;
                %s float NebulaVDistance;
                %s float NebulaVBloomFactor;
                """.formatted(varying, varying, varying, varying, varying);
    }

    private static String fragmentBlock(int version) {
        String varying = version >= 130 ? "in" : "varying";
        return """
                uniform int NebulaIsActive;
                uniform sampler2DArray NebulaSampler0;
                uniform int NebulaUseTexture;
                uniform float NebulaEmissiveStrength;
                uniform int NebulaRenderPass;

                %s vec4 NebulaVColor;
                %s vec2 NebulaVUV;
                %s float NebulaVTexLayer;
                %s float NebulaVDistance;
                %s float NebulaVBloomFactor;
                """.formatted(varying, varying, varying, varying, varying);
    }

    private static String injectShaderBlock(String source, String extensions, String block) {
        Matcher versionMatcher = VERSION_PATTERN.matcher(source);
        if (!versionMatcher.find()) {
            return null;
        }

        int versionEnd = versionMatcher.end();
        String withExtensions = source.substring(0, versionEnd) + "\n" + extensions + source.substring(versionEnd);
        Matcher extensionMatcher = EXTENSION_PATTERN.matcher(withExtensions);
        int declarationsAt = versionEnd + extensions.length() + 1;
        while (extensionMatcher.find()) {
            declarationsAt = Math.max(declarationsAt, extensionMatcher.end());
        }
        return withExtensions.substring(0, declarationsAt) + "\n" + block + "\n" + withExtensions.substring(declarationsAt);
    }

    private static int shaderVersion(String source) {
        Matcher matcher = VERSION_PATTERN.matcher(source);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String findPrimaryOutput(String source) {
        String namedOutput = findOutputName(source, OUT0_PATTERN);
        if (namedOutput != null) {
            return namedOutput;
        }
        if (source.contains("gl_FragData")) {
            return "gl_FragData[0]";
        }
        if (source.contains("gl_FragColor")) {
            return "gl_FragColor";
        }
        return null;
    }

    private static String findSecondaryOutput(String source) {
        String namedOutput = findOutputName(source, OUT1_PATTERN);
        if (namedOutput != null) {
            return namedOutput;
        }
        if (source.contains("gl_FragData[1]")) {
            return "gl_FragData[1]";
        }
        return null;
    }

    private static String findOutputName(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean looksLikeParticleVertexShader(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return (normalized.contains("gl_position") || normalized.contains("position"))
                && (normalized.contains("texcoord") || normalized.contains("uv") || normalized.contains("texture"))
                && (normalized.contains("color") || normalized.contains("vertexcolor") || normalized.contains("particle"));
    }

    private static boolean looksLikeParticleFragmentShader(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return (normalized.contains("gl_fragcolor") || normalized.contains("gl_fragdata") || normalized.contains("layout(location"))
                && (normalized.contains("texture") || normalized.contains("sampler"))
                && (normalized.contains("alpha") || normalized.contains("discard") || normalized.contains("color"));
    }

    private static boolean isTargetProgram(String programName) {
        if (programName == null) {
            return false;
        }
        String normalized = programName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("shadow")) {
            return false;
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        int extension = normalized.indexOf('.');
        if (extension >= 0) {
            normalized = normalized.substring(0, extension);
        }
        return normalized.contains("particle")
                || normalized.equals("particles")
                || normalized.equals("particles_trans")
                || normalized.equals("shadow_particles");
    }
}
