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

package com.atemukesu.nebula;

import com.atemukesu.nebula.command.NebulaCommand;
import com.atemukesu.nebula.networking.ModPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Nebula implements ModInitializer {
	public static final String MOD_ID = "nebula";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final String MOD_VERSION = getModVersion();

	public static String getModVersion() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("Unknown");
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Nebula {} initializing...", MOD_VERSION);
		// 创建动画目录
		createAnimationsDir();
		LOGGER.info("Animations directory created.");

		// 释放动画文件
		extractAnimationsIfNeeded();
		LOGGER.info("Animations extracted.");

		// 注册命令
		CommandRegistrationCallback.EVENT
				.register((dispatcher, registryAccess, environment) -> NebulaCommand.register(dispatcher));

		// 注册网络包
		ModPackets.registerC2SPackets();
        ModPackets.registerCommon();

		// 注册服务端事件
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			com.atemukesu.nebula.server.ServerAnimationSyncer.reload();
		});

		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			com.atemukesu.nebula.server.ServerAnimationSyncer.sendToPlayer(handler.getPlayer());
		});

		LOGGER.info("Nebula initialized. Animation directory: .minecraft/nebula/animations/");
	}

	/**
	 * 自动释放 Jar 内的动画文件到 游戏目录/nebula/animations
	 */
	private void extractAnimationsIfNeeded() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path targetDir = gameDir.resolve("nebula").resolve("animations");

		ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
		Path resourcePath = mod.findPath("assets/" + MOD_ID + "/animations").orElse(null);

		if (resourcePath == null) {
			LOGGER.error("Failed to find animations resource path");
			return;
		}
		try {
			if (!Files.exists(targetDir)) {
				Files.createDirectories(targetDir);
			}
			try (Stream<Path> paths = Files.walk(resourcePath)) {
				paths.filter(Files::isRegularFile).forEach(path -> {
					String fileName = path.getFileName().toString();
					Path targetFile = targetDir.resolve(fileName);
					if (!Files.exists(targetFile)) {
						try {
							Files.copy(path, targetFile);
							System.out.println("Extracted animation: " + fileName);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				});
			}
		} catch (IOException e) {
			Nebula.LOGGER.error("Failed to extract animations", e);
		}
	}

	/**
	 * 创建动画目录
	 */
	private void createAnimationsDir() {
		Path animationsDir = FabricLoader.getInstance().getGameDir().resolve("nebula").resolve("animations");
		try {
			Files.createDirectories(animationsDir); // 创建动画目录
		} catch (IOException e) {
			Nebula.LOGGER.error("Failed to create animations directory", e);
		}
	}
}