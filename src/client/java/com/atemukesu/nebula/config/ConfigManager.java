package com.atemukesu.nebula.config;

import com.atemukesu.nebula.Nebula;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ConfigManager {

    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("nebula.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void loadConfig() {
        try {
            if (CONFIG_FILE.toFile().exists()) {
                try (FileReader reader = new FileReader(CONFIG_FILE.toFile())) {
                    ModConfig config = GSON.fromJson(reader, ModConfig.class);
                    if (config == null) {
                        config = new ModConfig();
                    }
                    ModConfig.setInstance(config);
                    Nebula.LOGGER.info("Config loaded successfully.");
                }
            } else {
                Nebula.LOGGER.info("No config file found, creating a new one with defaults.");
                ModConfig.setInstance(new ModConfig());
                saveConfig();
            }
        } catch (Exception e) {
            Nebula.LOGGER.error("Failed to load config file! Using defaults.", e);
            ModConfig.setInstance(new ModConfig());
        }
    }

    public static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
            GSON.toJson(ModConfig.getInstance(), writer);
        } catch (IOException e) {
            Nebula.LOGGER.error("Failed to save config file!", e);
        }
    }
}