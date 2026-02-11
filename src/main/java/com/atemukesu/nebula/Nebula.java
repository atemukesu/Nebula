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