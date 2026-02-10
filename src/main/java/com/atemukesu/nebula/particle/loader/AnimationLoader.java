package com.atemukesu.nebula.particle.loader;

import com.atemukesu.nebula.Nebula;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import java.util.stream.Stream;

/**
 * 服务端/共享动画加载器
 * 负责发现和管理动画文件
 */
public class AnimationLoader {

    private static final Map<String, Path> animations = new ConcurrentHashMap<>();
    private static Path animationsDir = FabricLoader.getInstance().getGameDir().resolve("nebula").resolve("animations");

    /**
     * 发现并索引所有动画文件
     */
    public static void discoverAnimations() {
        animations.clear();

        if (!Files.exists(animationsDir)) {
            Nebula.LOGGER.warn("Animations directory does not exist: {}", animationsDir);
            return;
        }

        try (Stream<Path> stream = Files.walk(animationsDir, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".nbl"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        name = name.substring(0, name.lastIndexOf('.'));
                        animations.put(name, path);
                        Nebula.LOGGER.info("Discovered animation: {}", name);
                    });
        } catch (IOException e) {
            Nebula.LOGGER.error("Failed to discover animations", e);
        }

        Nebula.LOGGER.info("Discovered {} animations", animations.size());
    }

    /**
     * 获取所有动画文件列表
     */
    public static List<Path> getAnimationFiles() throws IOException {
        if (animations.isEmpty()) {
            discoverAnimations();
        }
        return new ArrayList<>(animations.values());
    }

    /**
     * 获取动画名称到路径的映射
     */
    public static Map<String, Path> getAnimations() {
        if (animations.isEmpty()) {
            discoverAnimations();
        }
        return Collections.unmodifiableMap(animations);
    }

    /**
     * 设置动画映射（用于客户端加载后更新）
     */
    public static void setAnimations(Map<String, ?> newAnimations) {
        // 此方法由客户端加载器调用，暂不处理
    }

    /**
     * 获取动画目录路径
     */
    public static Path getAnimationsDir() {
        return animationsDir;
    }

    /**
     * 获取指定动画的文件路径
     */
    public static Path getAnimationPath(String name) {
        return animations.get(name);
    }

    /**
     * 加载单个动画文件（保留兼容性，实际使用流式加载）
     */
    public static Object loadAnimationFromFile(Path path, Consumer<Float> progressCallback) throws IOException {
        // 流式加载不需要预加载整个文件
        // 返回 null，实际加载由 NblStreamer 处理
        return null;
    }
}
