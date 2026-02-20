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

package com.atemukesu.nebula.client.loader;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 客户端动画加载器
 * 负责异步加载和管理动画文件
 */
public class ClientAnimationLoader {

    private static ExecutorService animationExecutor = null;

    private static void initializeExecutor() {
        if (animationExecutor == null || animationExecutor.isShutdown()) {
            // 自动选择合理的线程数（CPU 核心数的一半，最少 2 个）
            int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            Nebula.LOGGER.info("Initializing animation loader with {} threads.", threadCount);
            animationExecutor = Executors.newFixedThreadPool(threadCount);
        }
    }

    public static void shutdownExecutor() {
        if (animationExecutor != null && !animationExecutor.isShutdown()) {
            Nebula.LOGGER.info("Shutting down animation loader thread pool.");
            animationExecutor.shutdown();
            animationExecutor = null;
        }
    }

    /**
     * 异步加载动画文件
     */
    public static void loadAnimationsAsync(
            Runnable onComplete,
            Consumer<Exception> onError) {

        initializeExecutor();

        CompletableFuture.runAsync(() -> {
            try {
                // 重新发现动画文件
                AnimationLoader.discoverAnimations();

                // 获取动画文件列表
                List<Path> files = AnimationLoader.getAnimationFiles();
                Nebula.LOGGER.info("Found {} animation files", files.size());

            } catch (IOException e) {
                throw new RuntimeException("Failed to discover animation files", e);
            }
        }, animationExecutor)
                .thenRun(() -> {
                    MinecraftClient.getInstance().execute(onComplete);
                })
                .exceptionally(ex -> {
                    MinecraftClient.getInstance().execute(() -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        onError.accept((Exception) cause);
                    });
                    return null;
                })
                .whenComplete((res, err) -> {
                    // 不在这里关闭 executor，因为流式加载需要它
                });
    }

    /**
     * 获取指定动画的文件路径
     */
    public static Path getAnimationPath(String name) {
        return AnimationLoader.getAnimationPath(name);
    }

    /**
     * 获取所有已发现的动画
     */
    public static Map<String, Path> getAnimations() {
        return AnimationLoader.getAnimations();
    }
}