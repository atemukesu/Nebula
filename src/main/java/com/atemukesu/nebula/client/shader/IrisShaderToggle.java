package com.atemukesu.nebula.client.shader;

import com.atemukesu.nebula.Nebula;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.nio.FloatBuffer;

public final class IrisShaderToggle {

    private static final String UNIFORM_NAME = "NebulaIsActive";
    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);
    private static boolean loggedMissingUniform = false;
    private static boolean loggedMissingProgram = false;
    private static boolean loggedAppliedUniform = false;

    private IrisShaderToggle() {
    }

    public static int currentProgram() {
        return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    }

    public static void setActive(boolean active) {
        setActive(currentProgram(), active);
    }

    public static void setActive(int program, boolean active) {
        if (program <= 0) {
            if (active && !loggedMissingProgram) {
                Nebula.LOGGER.warn("[Nebula/Iris] Tried to toggle NebulaIsActive without an active GL program.");
                loggedMissingProgram = true;
            }
            return;
        }

        int location = GL20.glGetUniformLocation(program, UNIFORM_NAME);
        if (location >= 0) {
            GL20.glUniform1i(location, active ? 1 : 0);
            if (active && !loggedAppliedUniform) {
                Nebula.LOGGER.info("[Nebula/Iris] Applied NebulaIsActive=1 to GL program {} at location {}.", program, location);
                loggedAppliedUniform = true;
            }
            return;
        }

        if (active && !loggedMissingUniform) {
            Nebula.LOGGER.warn("[Nebula/Iris] Active GL program {} does not expose NebulaIsActive.", program);
            loggedMissingUniform = true;
        }
    }

    public static void bindParticleBufferBlock(int program, int bindingIndex) {
        if (program <= 0) {
            return;
        }
        int blockIndex = GL43.glGetProgramResourceIndex(program, GL43.GL_SHADER_STORAGE_BLOCK, "NebulaParticleBuffer");
        if (blockIndex != -1) {
            GL43.glShaderStorageBlockBinding(program, blockIndex, bindingIndex);
        }
    }

    public static void uploadNebulaUniforms(int program, Matrix4f modelViewMatrix, Matrix4f projMatrix,
            float originX, float originY, float originZ, float partialTicks) {
        uploadMatrix(program, "NebulaModelViewMat", modelViewMatrix);
        uploadMatrix(program, "NebulaProjMat", projMatrix);
        uploadVec3(program, "NebulaOrigin", originX, originY, originZ);
        uploadFloat(program, "NebulaPartialTicks", partialTicks);
    }

    public static void uploadNebulaMaterialUniforms(int program, boolean useTexture, float emissiveStrength,
            int renderPass, int textureUnit) {
        uploadInt(program, "NebulaUseTexture", useTexture ? 1 : 0);
        uploadFloat(program, "NebulaEmissiveStrength", emissiveStrength);
        uploadInt(program, "NebulaRenderPass", renderPass);
        uploadInt(program, "NebulaSampler0", textureUnit);
    }

    private static void uploadMatrix(int program, String name, Matrix4f matrix) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0 || matrix == null) {
            return;
        }
        MATRIX_BUFFER.clear();
        matrix.get(MATRIX_BUFFER);
        MATRIX_BUFFER.rewind();
        GL20.glUniformMatrix4fv(location, false, MATRIX_BUFFER);
    }

    private static void uploadVec3(int program, String name, float x, float y, float z) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20.glUniform3f(location, x, y, z);
        }
    }

    private static void uploadFloat(int program, String name, float value) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20.glUniform1f(location, value);
        }
    }

    private static void uploadInt(int program, String name, int value) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20.glUniform1i(location, value);
        }
    }
}
