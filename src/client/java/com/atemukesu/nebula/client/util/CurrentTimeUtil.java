package com.atemukesu.nebula.client.util;

import com.atemukesu.nebula.Nebula;

/**
 * 获取当前动画时间的工具类
 */
public class CurrentTimeUtil {

    private static boolean isRendering = false;
    private static int cachedVideoFps = 60;
    private static boolean hasLoggedWarning = false;

    /**
     * 获取当前动画的时间（秒）
     * 这是一个"智能"方法，自动判断环境：
     * 1. Replay 预览/渲染模式 -> 从 ReplaySender 获取精确时间
     * 2. 普通游戏模式 -> 使用真实墙钟时间（避免 TPS 波动导致的时间跳变）
     */
    public static double getCurrentAnimationTime() {
        // 1. 尝试从 Replay Mod 获取时间（预览或渲染模式）
        Double replayTime = getReplayTime();
        if (replayTime != null) {
            return replayTime;
        }

        if (isRendering && !hasLoggedWarning) {
            Nebula.LOGGER.warn(
                    "Replay Mod is rendering but failed to get replay time. Falling back to system time. This may cause animation speed issues.");
            hasLoggedWarning = true;
        }

        // 2. 普通游戏模式：使用真实墙钟时间
        // 【修复】不再使用 world.getTime()，因为它会因 TPS 波动而跳变
        // 导致 expectedFrame 突然跳跃，触发不必要的 Seek，造成卡顿
        return System.nanoTime() / 1_000_000_000.0;
    }

    /**
     * 尝试从 Replay Mod 获取当前时间
     * 使用反射避免编译时依赖
     */
    public static Double getReplayTime() {
        try {
            // 获取 ReplayModReplay 单例
            Class<?> replayModClass = Class.forName("com.replaymod.replay.ReplayModReplay");
            Object instance = replayModClass.getField("instance").get(null);

            if (instance == null)
                return null;

            // 获取 ReplayHandler
            java.lang.reflect.Method getReplayHandler = replayModClass.getMethod("getReplayHandler");
            Object handler = getReplayHandler.invoke(instance);

            if (handler == null)
                return null;

            // 获取 ReplaySender
            java.lang.reflect.Method getReplaySender = handler.getClass().getMethod("getReplaySender");
            Object sender = getReplaySender.invoke(handler);

            if (sender == null)
                return null;

            // 获取 currentTimeStamp() - 返回毫秒
            java.lang.reflect.Method currentTimeStamp = sender.getClass().getMethod("currentTimeStamp");
            Object timeObj = currentTimeStamp.invoke(sender);

            if (timeObj instanceof Number) {
                return ((Number) timeObj).doubleValue() / 1000.0;
            }
        } catch (ClassNotFoundException e) {
            // Replay Mod 未安装，静默忽略
        } catch (Exception e) {
            // 其他错误（版本不兼容等），记录一次后静默
            Nebula.LOGGER.debug("Failed to get Replay time: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 检查是否在 Replay 环境中
     */
    public static boolean isInReplay() {
        try {
            Class<?> replayModClass = Class.forName("com.replaymod.replay.ReplayModReplay");
            Object instance = replayModClass.getField("instance").get(null);
            if (instance == null)
                return false;

            java.lang.reflect.Method getReplayHandler = replayModClass.getMethod("getReplayHandler");
            return getReplayHandler.invoke(instance) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * [事件驱动] 由 VideoRendererMixin 在渲染开始时调用
     */
    public static void onRenderStart(int fps) {
        Nebula.LOGGER.info("Replay Mod rendering started. FPS: {}", fps);
        isRendering = true;
        cachedVideoFps = fps;
    }

    /**
     * [事件驱动] 由 VideoRendererMixin 在渲染结束时调用
     */
    public static void onRenderEnd() {
        Nebula.LOGGER.info("Replay Mod rendering stopped.");
        isRendering = false;
        cachedVideoFps = 60;
    }

    /**
     * 检查当前是否正在进行 Replay Mod 的离线渲染
     */
    public static boolean isRendering() {
        return isRendering;
    }

    /**
     * 获取渲染视频的目标 FPS
     */
    public static int getVideoFps() {
        return isRendering ? cachedVideoFps : 60;
    }
}