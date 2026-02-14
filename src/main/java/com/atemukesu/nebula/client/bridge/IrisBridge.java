package com.atemukesu.nebula.client.bridge;

import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IrisBridge {

    private static final IrisBridge INSTANCE = new IrisBridge();

    private boolean available;
    private boolean initializedReflection = false;

    // Reflection handles (Mixin usage)
    private Object irisPipelineManager;
    private Method getPipelineMethod; // getPipelineNullable
    private Method getSodiumPipelineMethod; // getSodiumTerrainPipeline
    private Method getTranslucentFbMethod; // getTranslucentFramebuffer
    private Method bindFbMethod; // GlFramebuffer.bind

    // Reflection handles (Util usage)
    private Class<?> irisInternalClass;
    private Method getPipelineManagerMethod;

    // Caches (Util usage)
    private final Map<Class<?>, Field> fboFieldCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> getProgramMethodCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> fboBindMethodCache = new ConcurrentHashMap<>();

    private IrisBridge() {
        this.available = FabricLoader.getInstance().isModLoaded("iris");
    }

    public static IrisBridge getInstance() {
        return INSTANCE;
    }

    public boolean isIrisInstalled() {
        return available;
    }

    public boolean isIrisRenderingActive() {
        if (!isIrisInstalled())
            return false;
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Initializes reflection handles if not already done.
     * Safe to call multiple times.
     */
    public void initReflection() {
        if (initializedReflection || !available)
            return;

        try {
            // Common: Iris.getPipelineManager()
            irisInternalClass = Class.forName("net.irisshaders.iris.Iris");
            getPipelineManagerMethod = irisInternalClass.getMethod("getPipelineManager");
            irisPipelineManager = getPipelineManagerMethod.invoke(null);

            if (irisPipelineManager != null) {
                Class<?> managerClass = irisPipelineManager.getClass();
                getPipelineMethod = managerClass.getMethod("getPipelineNullable");
            }

            // Mixin specific targets
            try {
                Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
                getSodiumPipelineMethod = pipelineClass.getMethod("getSodiumTerrainPipeline");

                Class<?> sodiumPipelineClass = Class.forName("net.irisshaders.iris.pipeline.SodiumTerrainPipeline");
                getTranslucentFbMethod = sodiumPipelineClass.getMethod("getTranslucentFramebuffer");

                Class<?> fbClass = Class.forName("net.irisshaders.iris.gl.framebuffer.GlFramebuffer");
                bindFbMethod = fbClass.getMethod("bind");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                // Ignore specific pipeline failures, might be different version or setup
                // Logic will degrade gracefully
                Nebula.LOGGER.debug("[Nebula/IrisBridge] Specific pipeline methods not found: " + e.getMessage());
            }

            Nebula.LOGGER.info("[Nebula] Iris reflection initialized successfully.");

        } catch (ClassNotFoundException e) {
            Nebula.LOGGER.info("[Nebula/IrisBridge] Iris classes not found.");
            available = false;
        } catch (Exception e) {
            Nebula.LOGGER.error("[Nebula/IrisBridge] Failed to init Iris reflection", e);
            available = false;
        }

        initializedReflection = true;
    }

    public boolean bindTranslucentFramebuffer() {
        if (attemptBindSodiumTranslucentFramebuffer()) {
            return true;
        }
        return bindIrisTranslucentFramebufferGeneric();
    }

    /**
     * Tries one specific update or binding strategy:
     * pipeline -> sodiumPipeline -> translucentFramebuffer -> bind
     * Returns true if successful.
     */
    public boolean attemptBindSodiumTranslucentFramebuffer() {
        if (!available)
            return false;
        initReflection(); // Ensure init

        if (irisPipelineManager == null || getPipelineMethod == null ||
                getSodiumPipelineMethod == null || getTranslucentFbMethod == null || bindFbMethod == null) {
            return false;
        }

        try {
            Object pipeline = getPipelineMethod.invoke(irisPipelineManager);
            if (pipeline != null && pipeline.getClass().getName().contains("IrisRenderingPipeline")) {
                Object sodiumPipeline = getSodiumPipelineMethod.invoke(pipeline);
                if (sodiumPipeline != null) {
                    Object framebuffer = getTranslucentFbMethod.invoke(sodiumPipeline);
                    if (framebuffer != null) {
                        bindFbMethod.invoke(framebuffer);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Nebula.LOGGER.warn("[Nebula/IrisBridge] Failed to bind Sodium FBO: " + e.getMessage());
        }
        return false;
    }

    /**
     * General purpose strategy to bind specialized frame buffers using heuristics
     */
    public boolean bindIrisTranslucentFramebufferGeneric() {
        if (!available)
            return false;
        initReflection(); // Ensure init

        try {
            // Strategy 1: Active Shader from RenderSystem
            Object activeObject = RenderSystem.getShader();

            // Strategy 2: Pipeline from Iris
            if (activeObject == null && irisPipelineManager != null && getPipelineMethod != null) {
                try {
                    activeObject = getPipelineMethod.invoke(irisPipelineManager);
                } catch (Exception ignored) {
                }
            }

            if (activeObject == null)
                return false;

            // Search for FBO
            Field fboField = fboFieldCache.get(activeObject.getClass());

            // Try resolving Program from Pipeline if needed
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
                        activeObject = program;
                        fboField = fboFieldCache.get(activeObject.getClass());
                    }
                }
            }

            // Deep search for "writingToAfterTranslucent"
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
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            Nebula.LOGGER.error("[Nebula/IrisBridge] Failed to bind translucent FBO (Generic): " + e.toString());
        }
        return false;
    }
}
