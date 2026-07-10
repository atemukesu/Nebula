package com.atemukesu.nebula.client.shader;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisShaderTransformer {

    private static final String TOGGLE_UNIFORM = "uniform int NebulaIsActive;";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\b.*$");
    private static final String[] TARGET_HINTS = {
            "gbuffer_textured",
            "gbuffer_textured_lit",
            "gbuffer_entities",
            "gbuffer_entity",
            "gbuffer_weather",
            "gbuffer_clouds"
    };

    private IrisShaderTransformer() {
    }

    public static String transformVertexSource(String source) {
        if (source == null || source.isBlank() || source.contains("NebulaIsActive")) {
            return source;
        }
        if (!looksLikeTargetShader(source)) {
            return source;
        }

        Matcher versionMatcher = VERSION_PATTERN.matcher(source);
        if (!versionMatcher.find()) {
            return TOGGLE_UNIFORM + "\n" + source;
        }

        int insertAt = versionMatcher.end();
        return source.substring(0, insertAt) + "\n\n" + TOGGLE_UNIFORM + source.substring(insertAt);
    }

    private static boolean looksLikeTargetShader(String source) {
        String normalized = source.toLowerCase(Locale.ROOT);
        if (normalized.contains("particles[gl_instanceid]") || normalized.contains("layout(std430")) {
            return false;
        }
        for (String hint : TARGET_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return normalized.contains("gl_position")
                && normalized.contains("sampler2d")
                && (normalized.contains("texture") || normalized.contains("texcoord"));
    }
}
