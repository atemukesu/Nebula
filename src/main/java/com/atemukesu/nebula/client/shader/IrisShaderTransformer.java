package com.atemukesu.nebula.client.shader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisShaderTransformer {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)\\b.*$");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("(?m)^\\s*#extension\\b.*$");

    // 寻找主输出和 main 变量
    private static final Pattern OUT0_PATTERN = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final Pattern OUT_ANY_PATTERN = Pattern.compile("out\\s+vec4\\s+(\\w+)\\s*;");
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");

    private IrisShaderTransformer() {
    }

    private static String maskComments(String source) {
        char[] chars = source.toCharArray();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        char stringQuoteChar = 0;

        for (int i = 0; i < chars.length; i++) {
            if (inLineComment) {
                if (chars[i] == '\n') {
                    inLineComment = false;
                } else {
                    chars[i] = ' ';
                }
            } else if (inBlockComment) {
                if (chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    inBlockComment = false;
                    i++;
                } else if (chars[i] != '\n') {
                    chars[i] = ' ';
                }
            } else if (inString) {
                if (chars[i] == '\\' && i + 1 < chars.length) {
                    i++;
                } else if (chars[i] == stringQuoteChar) {
                    inString = false;
                }
            } else {
                if (chars[i] == '"' || chars[i] == '\'') {
                    inString = true;
                    stringQuoteChar = chars[i];
                } else if (chars[i] == '/' && i + 1 < chars.length) {
                    if (chars[i + 1] == '/') {
                        inLineComment = true;
                        chars[i] = ' ';
                        chars[i + 1] = ' ';
                        i++;
                    } else if (chars[i + 1] == '*') {
                        inBlockComment = true;
                        chars[i] = ' ';
                        chars[i + 1] = ' ';
                        i++;
                    }
                }
            }
        }
        return new String(chars);
    }
    public static String transformVertexSource(String programName, String source) {
        if (!isTargetProgram(programName) || !looksLikeParticleVertexShader(source) || source.contains("NebulaVColor")) {
            return source;
        }

        int version = shaderVersion(source);
        if (version < 120) return source;

        String transformed = injectShaderBlock(source, vertexExtensions(version), vertexBlock(version));
        if (transformed == null) return source;

        // 在排除了所有注释的安全代码里寻找 main
        String masked = maskComments(transformed);
        Matcher mainMatcher = MAIN_PATTERN.matcher(masked);
        if (!mainMatcher.find()) {
            return source;
        }

        // 把原版的 void main() { 重命名为 void nebula_original_main() {
        int start = mainMatcher.start();
        int end = mainMatcher.end();
        String beforeMain = transformed.substring(0, start);
        String afterMain = transformed.substring(end);
        String result = beforeMain + "void nebula_original_main() {" + afterMain;

        String instanceId = version >= 140 ? "gl_InstanceID" : "gl_InstanceIDARB";
        String injectedMain = """
                void main() {
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
                        return;
                    }
                    nebula_original_main();
                }
            """.formatted(instanceId);

        result += "\n// Nebula Injected Main\n" + injectedMain;

        Nebula.LOGGER.info("[Nebula/Iris] Injected {} vertex: instanceId={}, length={}", programName, instanceId, result.length());
        if (ModConfig.getInstance().getSaveInjectedCode()) {
            saveInjectedCode(programName, "vert", result);
        }
        return result;
    }

    public static String transformFragmentSource(String programName, String source) {
        if (!isTargetProgram(programName) || !looksLikeParticleFragmentShader(source) || source.contains("NebulaVColor")) {
            return source;
        }

        int version = shaderVersion(source);
        if (version < 120) return source;

        String safeMaskedOriginal = maskComments(source);
        String primaryOutput = findPrimaryOutput(safeMaskedOriginal);
        if (primaryOutput == null) return source;

        boolean hasOutput3 = hasOutputVariable(safeMaskedOriginal, "gbufferOutput3");
        boolean hasOutput5 = hasOutputVariable(safeMaskedOriginal, "gbufferOutput5");

        String transformed = injectShaderBlock(source, fragmentExtensions(version), fragmentBlock(version));
        if (transformed == null) return source;

        String safeMaskedTransformed = maskComments(transformed);
        Matcher mainMatcher = MAIN_PATTERN.matcher(safeMaskedTransformed);
        if (!mainMatcher.find()) return source;

        int start = mainMatcher.start();
        int end = mainMatcher.end();
        String beforeMain = transformed.substring(0, start);
        String afterMain = transformed.substring(end);
        String result = beforeMain + "void nebula_original_main() {" + afterMain;

        String textureFunction = version >= 130 ? "texture" : "texture2DArray";
        String injectedMainBody = buildDynamicFragmentBody(primaryOutput, textureFunction, hasOutput3, hasOutput5);
        String injectedMain = "\n// Nebula Injected Main\nvoid main() {\n" + injectedMainBody + "    nebula_original_main();\n}\n";

        result += injectedMain;

        Nebula.LOGGER.info("[Nebula/Iris] Injected {} fragment: primary={}, hasOut3={}, hasOut5={}, length={}",
                programName, primaryOutput, hasOutput3, hasOutput5, result.length());
        if (ModConfig.getInstance().getSaveInjectedCode()) {
            saveInjectedCode(programName, "frag", result);
        }
        return result;
    }

    private static String findPrimaryOutput(String maskedSource) {
        if (maskedSource.contains("gl_FragData")) return "gl_FragData[0]";
        if (maskedSource.contains("gl_FragColor")) return "gl_FragColor";

        Matcher m1 = OUT0_PATTERN.matcher(maskedSource);
        if (m1.find()) return m1.group(1);

        Matcher m2 = OUT_ANY_PATTERN.matcher(maskedSource);
        if (m2.find()) return m2.group(1);

        return null;
    }

    private static boolean hasOutputVariable(String maskedSource, String varName) {
        return Pattern.compile("\\b" + varName + "\\b").matcher(maskedSource).find();
    }

    private static String buildDynamicFragmentBody(String primaryOutput, String textureFunction,
                                                   boolean hasOut3, boolean hasOut5) {
        StringBuilder body = new StringBuilder();
        body.append("    if (NebulaIsActive == 1) {\n");
        body.append("        const float nebulaAlphaCutoff = 0.001;\n");
        body.append("        vec4 nebulaTexColor;\n");
        body.append("        if (NebulaUseTexture == 1) {\n");
        body.append("            nebulaTexColor = ").append(textureFunction).append("(NebulaSampler0, vec3(NebulaVUV, NebulaVTexLayer));\n");
        body.append("        } else {\n");
        body.append("            vec2 nebulaCenter = NebulaVUV - 0.5;\n");
        body.append("            float nebulaDist = length(nebulaCenter) * 2.0;\n");
        body.append("            float nebulaAlpha = 1.0 - smoothstep(0.5, 1.0, nebulaDist);\n");
        body.append("            float nebulaCoreBrightness = 1.0 + 0.5 * (1.0 - smoothstep(0.0, 0.3, nebulaDist));\n");
        body.append("            nebulaTexColor = vec4(vec3(nebulaCoreBrightness), nebulaAlpha);\n");
        body.append("        }\n");
        body.append("        vec4 nebulaBaseColor = nebulaTexColor * NebulaVColor;\n");

        body.append("        if (NebulaRenderPass == 0) {\n");
        body.append("            if (nebulaBaseColor.a < 0.5) discard;\n");
        body.append("        } else if (NebulaRenderPass == 1 || NebulaRenderPass == 3) {\n");
        body.append("            if (nebulaBaseColor.a < nebulaAlphaCutoff || nebulaBaseColor.a >= 0.5) discard;\n");
        body.append("        } else {\n");
        body.append("            if (nebulaBaseColor.a < nebulaAlphaCutoff) discard;\n");
        body.append("        }\n");

        body.append("        vec3 nebulaOutputColor = nebulaBaseColor.rgb;\n");
        body.append("        vec2 nebulaNormalEnc = vec2(0.5, 0.5);\n");
        body.append("        float nebulaPackDefault = 1.0 / 65535.0;\n");

        body.append("        if (NebulaRenderPass == 1) {\n");
        body.append("            float nebulaAlpha = clamp(nebulaBaseColor.a, 0.0, 1.0);\n");
        body.append("            float nebulaWeight = clamp(pow(min(1.0, nebulaAlpha * 5.0) + 0.01, 3.0) * pow(1.0 - gl_FragCoord.z * 0.9, 3.0) * 250.0, 1e-2, 100.0);\n");
        body.append("            ").append(primaryOutput).append(" = vec4(nebulaOutputColor * nebulaAlpha * nebulaWeight, nebulaAlpha * nebulaWeight);\n");
        if (hasOut3) body.append("            gbufferOutput3 = vec4(nebulaNormalEnc, 1.0, 1.0);\n");
        if (hasOut5) body.append("            gbufferOutput5 = vec4(0.0, 0.0, 40.0 / 255.0, nebulaPackDefault);\n");

        body.append("        } else {\n");
        body.append("            vec3 nebulaHdrColor = nebulaOutputColor * NebulaVBloomFactor * NebulaEmissiveStrength;\n");
        body.append("            ").append(primaryOutput).append(" = vec4(nebulaHdrColor * nebulaBaseColor.a, nebulaBaseColor.a);\n");
        if (hasOut3) body.append("            gbufferOutput3 = vec4(nebulaNormalEnc, 1.0, 1.0);\n");
        if (hasOut5) body.append("            gbufferOutput5 = vec4(0.0, 0.0, 40.0 / 255.0, nebulaPackDefault);\n");
        body.append("        }\n");
        body.append("        return;\n");
        body.append("    }\n");

        return body.toString();
    }

    private static String injectShaderBlock(String source, String extensions, String block) {
        String masked = maskComments(source);
        Matcher versionMatcher = VERSION_PATTERN.matcher(masked);
        if (!versionMatcher.find()) return null;

        int versionEnd = versionMatcher.end();
        String withExtensions = source.substring(0, versionEnd) + "\n" + extensions + source.substring(versionEnd);

        String maskedWithExtensions = maskComments(withExtensions);
        Matcher extensionMatcher = EXTENSION_PATTERN.matcher(maskedWithExtensions);
        int declarationsAt = versionEnd + extensions.length() + 1;
        while (extensionMatcher.find()) {
            declarationsAt = Math.max(declarationsAt, extensionMatcher.end());
        }
        return withExtensions.substring(0, declarationsAt) + "\n" + block + "\n" + withExtensions.substring(declarationsAt);
    }

    private static String vertexExtensions(int version) {
        StringBuilder extensions = new StringBuilder();
        extensions.append("#extension GL_ARB_shader_storage_buffer_object : require\n");
        if (version < 140) extensions.append("#extension GL_ARB_draw_instanced : require\n");
        if (version < 130) extensions.append("#extension GL_EXT_gpu_shader4 : require\n");
        return extensions.toString();
    }

    private static String fragmentExtensions(int version) {
        if (version < 130) return "#extension GL_EXT_texture_array : require\n#extension GL_EXT_gpu_shader4 : require\n";
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

    private static int shaderVersion(String source) {
        Matcher matcher = VERSION_PATTERN.matcher(maskComments(source));
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean looksLikeParticleVertexShader(String source) {
        if (source == null || source.isBlank()) return false;
        String normalized = source.toLowerCase(Locale.ROOT);
        return (normalized.contains("gl_position") || normalized.contains("position"))
                && (normalized.contains("texcoord") || normalized.contains("uv") || normalized.contains("texture"))
                && (normalized.contains("color") || normalized.contains("vertexcolor") || normalized.contains("particle"));
    }

    private static boolean looksLikeParticleFragmentShader(String source) {
        if (source == null || source.isBlank()) return false;
        String normalized = source.toLowerCase(Locale.ROOT);
        return (normalized.contains("gl_fragcolor") || normalized.contains("gl_fragdata") || normalized.contains("layout(location"))
                && (normalized.contains("texture") || normalized.contains("sampler"))
                && (normalized.contains("alpha") || normalized.contains("discard") || normalized.contains("color"));
    }

    private static boolean isTargetProgram(String programName) {
        if (programName == null) return false;
        String normalized = programName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("shadow")) return false;
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        int extension = normalized.indexOf('.');
        if (extension >= 0) normalized = normalized.substring(0, extension);
        return normalized.contains("particle")
                || normalized.equals("particles")
                || normalized.equals("particles_trans")
                || normalized.equals("shadow_particles");
    }

    private static void saveInjectedCode(String programName, String ext, String code) {
        try {
            String safeName = programName.replace('\\', '/').replaceAll("[^a-zA-Z0-9._/\\-]", "_");
            int lastSlash = safeName.lastIndexOf('/');
            if (lastSlash >= 0) safeName = safeName.substring(lastSlash + 1);
            int dotIdx = safeName.lastIndexOf('.');
            if (dotIdx >= 0) safeName = safeName.substring(0, dotIdx);

            Path dir = FabricLoader.getInstance().getGameDir().resolve("nebula").resolve("injected_code");
            Files.createDirectories(dir);
            Path file = dir.resolve(safeName + "." + ext);
            Files.writeString(file, code);
            Nebula.LOGGER.info("[Nebula/Iris] Saved injected shader code to {}", file);
        } catch (IOException e) {
            Nebula.LOGGER.warn("[Nebula/Iris] Failed to save injected shader code for {}: {}", programName, e.getMessage());
        }
    }
}