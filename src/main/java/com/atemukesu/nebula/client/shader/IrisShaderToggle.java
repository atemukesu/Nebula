package com.atemukesu.nebula.client.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public final class IrisShaderToggle {

    private static final String UNIFORM_NAME = "NebulaIsActive";

    private IrisShaderToggle() {
    }

    public static void setActive(boolean active) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program <= 0) {
            return;
        }

        int location = GL20.glGetUniformLocation(program, UNIFORM_NAME);
        if (location >= 0) {
            GL20.glUniform1i(location, active ? 1 : 0);
        }
    }
}
