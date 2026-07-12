package com.atemukesu.nebula.client.mixin;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.shader.IrisShaderTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public class MixinTransformPatcher {

    @Unique
    private static final ThreadLocal<String> nebula$programName = new ThreadLocal<>();

    @ModifyVariable(
            method = "transform",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true)
    private static String nebula$captureProgramName(String programName) {
        nebula$programName.set(programName);
        return programName;
    }

    @ModifyVariable(
            method = "transform",
            at = @At("HEAD"),
            ordinal = 1,
            argsOnly = true)
    private static String nebula$transformVertexSource(String vertexSource) {
        String programName = nebula$programName.get();
        String transformed = IrisShaderTransformer.transformVertexSource(programName, vertexSource);
        if (transformed != vertexSource) {
            Nebula.LOGGER.info("[Nebula/Iris] Injected Nebula vertex hook into {}.", programName);
        }
        return transformed;
    }

    @ModifyVariable(
            method = "transform",
            at = @At("HEAD"),
            ordinal = 5,
            argsOnly = true)
    private static String nebula$transformFragmentSource(String fragmentSource) {
        String programName = nebula$programName.get();
        try {
            String transformed = IrisShaderTransformer.transformFragmentSource(programName, fragmentSource);
            if (transformed != fragmentSource) {
                Nebula.LOGGER.info("[Nebula/Iris] Injected Nebula fragment hook into {}.", programName);
            }
            return transformed;
        } finally {
            nebula$programName.remove();
        }
    }
}
