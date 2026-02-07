package com.atemukesu.nebula;

import com.atemukesu.nebula.command.NebulaCommand;
import com.atemukesu.nebula.networking.ModPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
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

		// 注册命令
		CommandRegistrationCallback.EVENT
				.register((dispatcher, registryAccess, environment) -> NebulaCommand.register(dispatcher));

		// 注册网络包
		ModPackets.registerC2SPackets();

		LOGGER.info("Nebula initialized. Animation directory: .minecraft/nebula/animations/");
	}
}