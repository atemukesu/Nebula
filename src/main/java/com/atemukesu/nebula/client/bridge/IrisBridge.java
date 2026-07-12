/**
 * [AI GENERATION & MODIFICATION NOTICE / AI 编写与调整声明]
 *
 * ENGLISH:
 * This code was authored, modified, optimized, or adjusted by one or more of the
 * following AI models: Gemini 3 Pro, Gemini 3 Flash, and Claude 3.5 Opus.
 * Although efforts have been made to ensure functionality through testing, the
 * code is provided "AS IS". Please perform a thorough code audit before using,
 * reading, distributing, or modifying.
 *
 * 中文版：
 * 本代码由以下一个或多个 AI 模型编写、修改、优化或调整：
 * Gemini 3 Pro, Gemini 3 Flash, 以及 Claude 3.5 Opus。
 * 代码虽经努力测试以确保其功能实现，但仍按“原样”提供。在您进行使用、阅读、
 * 分发或修改前，请务必进行仔细的代码审计与测试。
 *
 * ----------------------------------------------------------------------------------
 * [LICENSE & WARRANTY / 开源协议与免责声明]
 *
 * ENGLISH:
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details <https://www.gnu.org/licenses/>.
 *
 * 中文版：
 * 本程序为自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可协议（GPL）条款
 *（可以选择版本 3 或更高版本）对本程序进行重新分发和/或修改。
 *
 * 本程序的发布是希望其能发挥作用，但【不附带任何担保】，甚至不包括对【适销性】或
 * 【特定用途适用性】的暗示保证。开发者不对因使用本代码产生的任何损害承担责任。
 * 详情请参阅 GNU 通用公共许可协议官方页面 <https://www.gnu.org/licenses/>。
 * ----------------------------------------------------------------------------------
 */

package com.atemukesu.nebula.client.bridge;

import com.atemukesu.nebula.Nebula;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
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
    private Method pipelineSetPhaseMethod;
    private Class<?> pipelineSetPhaseOwner;
    private Class<?> worldRenderingPhaseClass;
    private Object particlesPhase;
    private Object nonePhase;
    private int particlePhaseRestoreFbo = -1;

    // Reflection handles (Util usage)
    private Class<?> irisInternalClass;
    private Method getPipelineManagerMethod;
    private Object irisApiInstance;
    private Method isShaderPackInUseMethod;
    private Method getParticleTranslucentShaderMethod;
    private Method getShaderMapMethod;
    private Method getShaderMethod;
    private Object particleOpaqueShaderKey;
    private Object particleTranslucentShaderKey;
    private Object activeParticleShader;
    private final Map<Class<?>, Method> shaderBindMethodCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> shaderUnbindMethodCache = new ConcurrentHashMap<>();
    private boolean loggedSodiumFramebufferBinding = false;
    private boolean loggedGenericFramebufferBinding = false;
    private boolean loggedFramebufferBindingFailure = false;
    private boolean loggedParticleShaderBinding = false;
    private boolean loggedParticleShaderNoProgram = false;
    private boolean loggedParticleShaderBindingFailure = false;
    private boolean loggedParticlePhaseBinding = false;
    private boolean loggedParticlePhaseFailure = false;

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
        initReflection();
        if (irisApiInstance == null || isShaderPackInUseMethod == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
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
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getIrisApiMethod = irisApiClass.getMethod("getInstance");
            irisApiInstance = getIrisApiMethod.invoke(null);
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");

            // Iris changed ShaderAccess between releases.  Neither optional lookup
            // is allowed to disable the complete bridge: the shader map is a
            // compatible fallback for both PARTICLES and PARTICLES_TRANS.
            try {
                Class<?> shaderAccessClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderAccess");
                getParticleTranslucentShaderMethod = shaderAccessClass.getMethod("getParticleTranslucentShader");
            } catch (ReflectiveOperationException ignored) {
                Nebula.LOGGER.debug("[Nebula/IrisBridge] ShaderAccess particle lookup is unavailable; using ShaderMap.");
            }
            try {
                Class<?> shaderRenderingPipelineClass = Class.forName("net.irisshaders.iris.pipeline.ShaderRenderingPipeline");
                Class<?> shaderKeyClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
                Class<?> shaderMapClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderMap");
                getShaderMapMethod = shaderRenderingPipelineClass.getMethod("getShaderMap");
                getShaderMethod = shaderMapClass.getMethod("getShader", shaderKeyClass);
                particleOpaqueShaderKey = shaderKeyClass.getField("PARTICLES").get(null);
                particleTranslucentShaderKey = shaderKeyClass.getField("PARTICLES_TRANS").get(null);
            } catch (ReflectiveOperationException ignored) {
                Nebula.LOGGER.debug("[Nebula/IrisBridge] ShaderMap particle lookup is unavailable.");
            }

            // Common: Iris.getPipelineManager()
            irisInternalClass = Class.forName("net.irisshaders.iris.Iris");
            getPipelineManagerMethod = irisInternalClass.getMethod("getPipelineManager");
            irisPipelineManager = getPipelineManagerMethod.invoke(null);

            if (irisPipelineManager != null) {
                Class<?> managerClass = irisPipelineManager.getClass();
                getPipelineMethod = managerClass.getMethod("getPipelineNullable");
            }

            worldRenderingPhaseClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class phaseEnumClass = worldRenderingPhaseClass.asSubclass(Enum.class);
            particlesPhase = Enum.valueOf(phaseEnumClass, "PARTICLES");
            nonePhase = Enum.valueOf(phaseEnumClass, "NONE");

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
            if (!loggedSodiumFramebufferBinding) {
                Nebula.LOGGER.info("[Nebula/IrisBridge] Bound Iris translucent framebuffer via Sodium pipeline.");
                loggedSodiumFramebufferBinding = true;
            }
            return true;
        }
        boolean bound = bindIrisTranslucentFramebufferGeneric();
        if (bound) {
            if (!loggedGenericFramebufferBinding) {
                Nebula.LOGGER.info("[Nebula/IrisBridge] Bound Iris translucent framebuffer via generic reflection path.");
                loggedGenericFramebufferBinding = true;
            }
        } else if (!loggedFramebufferBindingFailure) {
            Nebula.LOGGER.warn("[Nebula/IrisBridge] Failed to bind a dedicated Iris translucent framebuffer; falling back to current framebuffer.");
            loggedFramebufferBindingFailure = true;
        }
        return bound;
    }

    public boolean bindParticleTranslucentShader() {
        return bindParticleShader(true) > 0;
    }

    /**
     * Binds Iris' real opaque/translucent particle program and returns its GL id.
     */
    public int bindParticleShader(boolean translucent) {
        if (!available) {
            return 0;
        }
        initReflection();
        if (getPipelineMethod == null) {
            return 0;
        }

        try {
            Object shader;
            if (translucent && getParticleTranslucentShaderMethod != null) {
                shader = getParticleTranslucentShaderMethod.invoke(null);
            } else {
                Object pipeline = getPipelineMethod.invoke(irisPipelineManager);
                Object shaderKey = translucent ? particleTranslucentShaderKey : particleOpaqueShaderKey;
                if (pipeline == null || getShaderMapMethod == null || getShaderMethod == null || shaderKey == null) {
                    return 0;
                }
                Object shaderMap = getShaderMapMethod.invoke(pipeline);
                shader = getShaderMethod.invoke(shaderMap, shaderKey);
            }
            if (shader == null) {
                return 0;
            }
            Method bindMethod = shaderBindMethodCache.computeIfAbsent(
                    shader.getClass(),
                    clazz -> findNoArgMethod(clazz, "bind", "method_34586"));
            if (bindMethod == null) {
                return 0;
            }
            bindMethod.invoke(shader);
            int activeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            if (activeProgram <= 0) {
                activeParticleShader = null;
                if (!loggedParticleShaderNoProgram) {
                    Nebula.LOGGER.warn("[Nebula/IrisBridge] Iris particle shader bind completed without an active GL program.");
                    loggedParticleShaderNoProgram = true;
                }
                return 0;
            }
            activeParticleShader = shader;
            if (!loggedParticleShaderBinding) {
                Nebula.LOGGER.info("[Nebula/IrisBridge] Bound Iris particle translucent shader: {}", shader.getClass().getName());
                loggedParticleShaderBinding = true;
            }
            return activeProgram;
        } catch (Exception e) {
            if (!loggedParticleShaderBindingFailure) {
                Nebula.LOGGER.warn("[Nebula/IrisBridge] Failed to bind Iris particle translucent shader: {}", e.toString());
                loggedParticleShaderBindingFailure = true;
            }
            return 0;
        }
    }

    public boolean beginParticlePhase() {
        // ExtendedShader.bind() selects an Iris framebuffer.  Keep the caller's
        // target so a late particle draw (for example just before weather) does
        // not leave subsequent vanilla/Iris passes bound to the particle target.
        if (particlePhaseRestoreFbo < 0) {
            particlePhaseRestoreFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        }
        boolean phaseSet = setWorldRenderingPhase(particlesPhase);
        boolean shaderBound = bindParticleTranslucentShader();
        if (phaseSet && shaderBound && !loggedParticlePhaseBinding) {
            Nebula.LOGGER.info("[Nebula/IrisBridge] Entered Iris PARTICLES phase and bound particles_trans shader.");
            loggedParticlePhaseBinding = true;
        }
        return phaseSet || shaderBound;
    }

    public void endParticlePhase() {
        unbindParticleTranslucentShader();
        setWorldRenderingPhase(nonePhase);
        int restoreFbo = particlePhaseRestoreFbo;
        particlePhaseRestoreFbo = -1;
        if (restoreFbo >= 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getFramebuffer() != null && restoreFbo == client.getFramebuffer().fbo) {
                client.getFramebuffer().beginWrite(false);
            } else {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, restoreFbo);
            }
        }
    }

    private boolean setWorldRenderingPhase(Object phase) {
        if (!available || phase == null) {
            return false;
        }
        initReflection();
        if (irisPipelineManager == null || getPipelineMethod == null || worldRenderingPhaseClass == null) {
            return false;
        }
        try {
            Object pipeline = getPipelineMethod.invoke(irisPipelineManager);
            if (pipeline == null) {
                return false;
            }
            if (pipelineSetPhaseMethod == null || pipelineSetPhaseOwner != pipeline.getClass()) {
                pipelineSetPhaseMethod = pipeline.getClass().getMethod("setPhase", worldRenderingPhaseClass);
                pipelineSetPhaseMethod.setAccessible(true);
                pipelineSetPhaseOwner = pipeline.getClass();
            }
            pipelineSetPhaseMethod.invoke(pipeline, phase);
            return true;
        } catch (Exception e) {
            if (!loggedParticlePhaseFailure) {
                Nebula.LOGGER.warn("[Nebula/IrisBridge] Failed to set Iris particle phase: {}", e.toString());
                loggedParticlePhaseFailure = true;
            }
            return false;
        }
    }

    public void unbindParticleTranslucentShader() {
        Object shader = activeParticleShader;
        activeParticleShader = null;
        if (shader == null) {
            return;
        }
        try {
            Method unbindMethod = shaderUnbindMethodCache.computeIfAbsent(
                    shader.getClass(),
                    clazz -> findNoArgMethod(clazz, "unbind", "method_34585"));
            if (unbindMethod != null) {
                unbindMethod.invoke(shader);
            }
        } catch (Exception e) {
            Nebula.LOGGER.debug("[Nebula/IrisBridge] Failed to unbind Iris particle shader: {}", e.toString());
        }
    }

    private Method findNoArgMethod(Class<?> type, String... names) {
        Class<?> current = type;
        while (current != null) {
            for (String name : names) {
                try {
                    Method method = current.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
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
