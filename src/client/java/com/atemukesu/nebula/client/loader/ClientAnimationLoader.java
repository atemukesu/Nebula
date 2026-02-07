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