package com.atemukesu.nebula.client.util;

import com.atemukesu.nebula.Nebula;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Iris Shaders 工具类
 * 用于检测 Iris 是否存在以及光影是否开启
 */
public class IrisUtil {

    private static Boolean irisAvailable = null;
    private static boolean hasLoggedIrisDetected = false;
    private static boolean hasLoggedShaderActive = false;
    private static boolean hasLoggedShaderOff = false;

    /**
     * 检查 Iris mod 是否已安装（使用 Fabric Loader API，不需要反射）
     */
    public static boolean isIrisInstalled() {
        if (irisAvailable == null) {
            irisAvailable = FabricLoader.getInstance().isModLoaded("iris");
            if (irisAvailable && !hasLoggedIrisDetected) {
                Nebula.LOGGER.info("[Nebula/Iris] ✓ Iris Shaders detected! MixinIrisPipeline will handle rendering.");
                hasLoggedIrisDetected = true;
            }
        }
        return irisAvailable;
    }

    /**
     * 检查 Iris 光影是否真正激活（安装且光影包开启）
     * 
     * 返回 true 时，MixinIrisPipeline.beginTranslucents 会处理渲染
     * 返回 false 时，renderTick() 事件会处理渲染
     */
    public static boolean isIrisRenderingActive() {
        if (!isIrisInstalled()) {
            return false;
        }

        // 通过反射调用 IrisApi.getInstance().isShaderPackInUse()
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = irisApiClass.getMethod("getInstance").invoke(null);
            Object isActive = irisApiClass.getMethod("isShaderPackInUse").invoke(instance);

            boolean shaderActive = Boolean.TRUE.equals(isActive);

            // 日志记录（只记录一次）
            if (shaderActive && !hasLoggedShaderActive) {
                Nebula.LOGGER.info("[Nebula/Iris] ✓ Shader pack active. MixinIrisPipeline will handle rendering.");
                hasLoggedShaderActive = true;
                hasLoggedShaderOff = false; // 重置，以便下次关闭时能记录
            } else if (!shaderActive && !hasLoggedShaderOff) {
                Nebula.LOGGER.info("[Nebula/Iris] Shader pack OFF. Using standard WorldRenderEvents.");
                hasLoggedShaderOff = true;
                hasLoggedShaderActive = false; // 重置
            }

            return shaderActive;
        } catch (Exception e) {
            // 反射失败，假设光影未激活
            return false;
        }
    }

    /**
     * 重置缓存状态（用于热重载场景）
     */
    public static void resetCache() {
        irisAvailable = null;
        hasLoggedIrisDetected = false;
        hasLoggedShaderActive = false;
        hasLoggedShaderOff = false;
        Nebula.LOGGER.debug("[Nebula/Iris] Cache reset.");
    }

    /**
     * 尝试使用反射绑定 Iris 的半透明阶段 Framebuffer
     * 避免直接使用 bindAndShareDepth 导致的深度/缓冲区冲突问题
     */
    public static void bindIrisTranslucentFramebuffer() {
        if (!isIrisRenderingActive())
            return;

        try {
            // 1. 获取 Iris 主类与 PipelineManager
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            Object pipelineManager = irisClass.getMethod("getPipelineManager").invoke(null);
            if (pipelineManager == null)
                return;

            // 2. 获取当前 Pipeline
            Object pipeline = pipelineManager.getClass().getMethod("getPipeline").invoke(pipelineManager);
            if (pipeline == null)
                return;

            // 3. 尝试获取 writingToAfterTranslucent 字段
            // 注意：该字段可能位于 Pipeline 中，或者 Pipeline 是个 wrapper 包含 Program
            // 根据用户提示，我们在对象中查找该字段
            java.lang.reflect.Field field;
            try {
                field = pipeline.getClass().getDeclaredField("writingToAfterTranslucent");
            } catch (NoSuchFieldException e) {
                // 如果直接在 pipeline 里找不到，尝试获取 program (如果有 getProgram 方法)
                // 这里做一个假设性的尝试，但这取决于具体的 Iris 版本
                try {
                    Object program = pipeline.getClass().getMethod("getProgram").invoke(pipeline);
                    field = program.getClass().getDeclaredField("writingToAfterTranslucent");
                    pipeline = program; // 更新目标对象
                } catch (Exception ex) {
                    return; // 无法定位字段
                }
            }

            if (field == null)
                return;

            field.setAccessible(true);
            Object renderTarget = field.get(pipeline);

            // 4. 执行 bind() 并设置 DrawBuffer
            if (renderTarget != null) {
                renderTarget.getClass().getMethod("bind").invoke(renderTarget);
                org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
            }

        } catch (Exception e) {
            Nebula.LOGGER.debug("[Nebula/Iris] Failed to bind internal framebuffer: {}", e.getMessage());
        }
    }
}
