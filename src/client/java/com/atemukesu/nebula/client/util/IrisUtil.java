package com.atemukesu.nebula.client.util;

import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL11;
import net.irisshaders.iris.api.v0.IrisApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IrisUtil {

    private static Boolean irisAvailable = null;

    // 缓存反射得到的字段和方法，避免每一帧都进行查找
    private static final Map<Class<?>, Field> fboFieldCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> getProgramMethodCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> fboBindMethodCache = new ConcurrentHashMap<>();

    // 缓存 Iris 内部类和方法
    private static Class<?> irisInternalClass = null;
    private static Method getPipelineManagerMethod = null;
    private static Method getPipelineNullableMethod = null;

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
        if (!isIrisInstalled()) {
            return false;
        }
        // 直接使用 Iris API
        return IrisApi.getInstance().isShaderPackInUse();
    }

    /**
     * 核心修复方法：绑定 Iris 的半透明 Framebuffer
     * 进行了缓存优化，减少反射开销
     */
    public static void bindIrisTranslucentFramebuffer() {
        if (!isIrisRenderingActive()) {
            return;
        }

        try {
            // 策略 1: 尝试从当前 RenderSystem 获取激活的 Shader
            Object activeObject = RenderSystem.getShader();

            // 策略 2: 如果 RenderSystem 没有 Shader，直接从 Iris 获取当前管线 (后备)
            if (activeObject == null) {
                if (irisInternalClass == null) {
                    try {
                        irisInternalClass = Class.forName("net.irisshaders.iris.Iris");
                        getPipelineManagerMethod = irisInternalClass.getMethod("getPipelineManager");
                    } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                    }
                }

                if (getPipelineManagerMethod != null) {
                    Object pipelineManager = getPipelineManagerMethod.invoke(null);
                    if (pipelineManager != null) {
                        if (getPipelineNullableMethod == null) {
                            getPipelineNullableMethod = pipelineManager.getClass().getMethod("getPipelineNullable");
                        }
                        activeObject = getPipelineNullableMethod.invoke(pipelineManager);
                    }
                }
            }

            if (activeObject == null) {
                return;
            }

            // 查找 FBO 字段
            Field fboField = fboFieldCache.get(activeObject.getClass());

            // 如果在当前对象（如 Pipeline）找不到，尝试进入下一层（如 Program）
            if (fboField == null) {
                Method getProgram = getProgramMethodCache.computeIfAbsent(activeObject.getClass(), clazz -> {
                    try {
                        return clazz.getMethod("getProgram");
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                });

                if (getProgram != null) {
                    Object program = getProgram.invoke(activeObject);
                    if (program != null) {
                        activeObject = program; // 更新目标对象为 Program
                        fboField = fboFieldCache.get(activeObject.getClass()); // 尝试从缓存获取更新后类的字段
                    }
                }
            }

            // 如果还是找不到（或者刚才切换了对象），执行深度搜索并缓存
            if (fboField == null) {
                Class<?> currentClass = activeObject.getClass();
                Class<?> targetClass = currentClass;
                while (currentClass != null) {
                    try {
                        Field f = currentClass.getDeclaredField("writingToAfterTranslucent");
                        f.setAccessible(true);
                        fboFieldCache.put(targetClass, f);
                        fboField = f;
                        break;
                    } catch (NoSuchFieldException e) {
                        currentClass = currentClass.getSuperclass();
                    }
                }
            }

            if (fboField != null) {
                Object fbo = fboField.get(activeObject);
                if (fbo != null) {
                    // FBO Bind 方法也需要针对 Class 缓存，防止不同实现混淆
                    Method bindMethod = fboBindMethodCache.computeIfAbsent(fbo.getClass(), clazz -> {
                        try {
                            Method m = clazz.getMethod("bind");
                            m.setAccessible(true);
                            return m;
                        } catch (NoSuchMethodException e) {
                            return null;
                        }
                    });

                    if (bindMethod != null) {
                        bindMethod.invoke(fbo);
                        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                    }
                }
            }

        } catch (Exception e) {
            Nebula.LOGGER.error("[Nebula/Iris] Failed to bind translucent FBO: " + e.toString());
        }
    }
}
