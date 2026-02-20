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
