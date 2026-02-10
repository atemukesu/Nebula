package com.atemukesu.nebula.client.util;

import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class IrisUtil {

    private static Boolean irisAvailable = null;

    /**
     * 检查 Iris 是否已安装
     * 
     * @return 是否已安装 Iris
     */
    public static boolean isIrisInstalled() {
        if (irisAvailable == null) {
            irisAvailable = FabricLoader.getInstance().isModLoaded("iris");
        }
        return irisAvailable;
    }

    /**
     * 检查 Iris 是否正在渲染
     * 
     * @return 是否正在渲染
     */
    public static boolean isIrisRenderingActive() {
        if (!isIrisInstalled())
            return false;
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = irisApiClass.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(irisApiClass.getMethod("isShaderPackInUse").invoke(instance));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 核心修复方法：绑定 Iris 的半透明 Framebuffer
     * 支持 ExtendedShader, FallbackShader 以及通过 Pipeline 直接获取
     * 增强了健壮性，防止 RenderSystem.getShader() 为空时失效
     */
    public static void bindIrisTranslucentFramebuffer() {
        if (!isIrisRenderingActive())
            return;

        try {
            // 策略 1: 尝试从当前 RenderSystem 获取激活的 Shader (用户提供的方案)
            // 适用于标准渲染流程中已激活 Shader 的情况
            Object targetObject = null;
            try {
                targetObject = RenderSystem.getShader();
            } catch (Throwable ignored) {
            }

            // 策略 2: 如果 RenderSystem 没有 Shader (例如在 Mixin 注入点或自定义渲染管线中)
            // 直接从 Iris 获取当前管线 (更稳健的后备方案)
            if (targetObject == null) {
                try {
                    Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                    Object pipelineManager = irisClass.getMethod("getPipelineManager").invoke(null);
                    if (pipelineManager != null) {
                        targetObject = pipelineManager.getClass().getMethod("getPipelineNullable")
                                .invoke(pipelineManager);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (targetObject == null)
                return;

            // 反射获取 writingToAfterTranslucent 字段
            // 该字段可能存在于 ExtendedShader, FallbackShader 或 IrisRenderingPipeline 中
            Field fboField = null;
            Class<?> currentClass = targetObject.getClass();

            // 向上递归查找字段 (防止字段定义在父类)
            while (currentClass != null) {
                try {
                    fboField = currentClass.getDeclaredField("writingToAfterTranslucent");
                    break;
                } catch (NoSuchFieldException e) {
                    currentClass = currentClass.getSuperclass();
                }
            }

            // 如果在当前对象找不到，且对象是 Pipeline，尝试获取其 Program 再找一次
            if (fboField == null) {
                try {
                    Method getProgram = targetObject.getClass().getMethod("getProgram");
                    Object program = getProgram.invoke(targetObject);
                    if (program != null) {
                        fboField = program.getClass().getDeclaredField("writingToAfterTranslucent");
                        targetObject = program; // 更新目标对象为 Program

                        // 不需要再次递归查找，通常 Program 是一级结构，或者我们可以简单递归下
                        try {
                            fboField = targetObject.getClass().getDeclaredField("writingToAfterTranslucent");
                        } catch (NoSuchFieldException ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (fboField != null) {
                fboField.setAccessible(true);
                Object fbo = fboField.get(targetObject); // 获取 net.irisshaders.iris.gl.framebuffer.GlFramebuffer 对象

                if (fbo != null) {
                    // 3. 调用 GlFramebuffer.bind()
                    Method bindMethod = fbo.getClass().getMethod("bind");
                    bindMethod.setAccessible(true);
                    bindMethod.invoke(fbo);

                    // 4. 确保绘制到颜色附件 0 (Iris G-buffer 标准)
                    // 使用 GL30 常量确保兼容性
                    GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                }
            }

        } catch (Exception e) {
            Nebula.LOGGER.error("[Nebula/Iris] Failed to bind translucent FBO: " + e.toString());
        }
    }
}
