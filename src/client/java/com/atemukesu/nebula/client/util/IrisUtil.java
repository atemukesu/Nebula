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
}
