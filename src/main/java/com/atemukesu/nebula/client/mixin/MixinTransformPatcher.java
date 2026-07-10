package com.atemukesu.nebula.client.mixin;

import com.atemukesu.nebula.client.shader.IrisShaderTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public class MixinTransformPatcher {

    @ModifyVariable(
            method = "transform",
            at = @At("HEAD"),
            ordinal = 1,
            argsOnly = true)
    private static String nebula$transformVertexSource(String vertexSource) {
        return IrisShaderTransformer.transformVertexSource(vertexSource);
    }
}
