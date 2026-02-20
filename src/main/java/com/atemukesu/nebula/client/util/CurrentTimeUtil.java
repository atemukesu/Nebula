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