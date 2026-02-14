package com.atemukesu.nebula.client.command;

import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.client.config.ModConfig;
import com.atemukesu.nebula.client.config.NebulaYACLConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class NebulaToolsCommand {

        public static void register() {
                ClientCommandRegistrationCallback.EVENT.register(NebulaToolsCommand::registerCommands);
        }

        private static void registerCommands(
                        CommandDispatcher<FabricClientCommandSource> dispatcher,
                        CommandRegistryAccess registryAccess) {

                dispatcher.register(
                                ClientCommandManager.literal("nebula_client")
                                                // 1. HUD 命令分支
                                                .then(ClientCommandManager.literal("hud")
                                                                .executes(context -> {
                                                                        ModConfig config = ModConfig.getInstance();
                                                                        boolean newState = !config.getShowDebugHud();

                                                                        config.setShowDebugHud(newState);

                                                                        PerformanceStats.getInstance()
                                                                                        .setEnabled(newState);

                                                                        String statusKey = newState
                                                                                        ? "gui.nebula.config.on"
                                                                                        : "gui.nebula.config.off";
                                                                        Formatting color = newState ? Formatting.GREEN
                                                                                        : Formatting.RED;

                                                                        context.getSource().sendFeedback(
                                                                                        Text.translatable(
                                                                                                        "gui.nebula.config.show_debug_hud")
                                                                                                        .append(": ")
                                                                                                        .append(Text.translatable(
                                                                                                                        statusKey)
                                                                                                                        .formatted(color)));
                                                                        return 1;
                                                                }))
                                                // 打开 GUI
                                                .then(ClientCommandManager.literal("settings")
                                                                .executes(context -> {
                                                                        ClientTickEvents.END_CLIENT_TICK
                                                                                        .register(
                                                                                                        new ClientTickEvents.EndTick() {
                                                                                                                private boolean opened = false;

                                                                                                                @Override
                                                                                                                public void onEndTick(
                                                                                                                                MinecraftClient client) {
                                                                                                                        if (!opened) {
                                                                                                                                client.setScreen(
                                                                                                                                                NebulaYACLConfig.createConfigScreen(
                                                                                                                                                                null));
                                                                                                                                opened = true;
                                                                                                                        }
                                                                                                                }
                                                                                                        });
                                                                        return 1;
                                                                }))
                                                .then(ClientCommandManager.literal("get_hash")
                                                                .then(ClientCommandManager
                                                                                .argument("animation",
                                                                                                com.mojang.brigadier.arguments.StringArgumentType
                                                                                                                .string())
                                                                                .executes(context -> {
                                                                                        String name = com.mojang.brigadier.arguments.StringArgumentType
                                                                                                        .getString(
                                                                                                                        context,
                                                                                                                        "animation");
                                                                                        java.nio.file.Path path = com.atemukesu.nebula.particle.loader.AnimationLoader
                                                                                                        .getAnimationPath(
                                                                                                                        name);
                                                                                        if (path != null && path
                                                                                                        .toFile()
                                                                                                        .exists()) {
                                                                                                try {
                                                                                                        String hash = com.atemukesu.nebula.util.NebulaHashUtils
                                                                                                                        .getSecureSampleHash(
                                                                                                                                        path);
                                                                                                        context.getSource()
                                                                                                                        .sendFeedback(Text
                                                                                                                                        .literal("Animation: "
                                                                                                                                                        + name
                                                                                                                                                        + "\nHash: "
                                                                                                                                                        + hash)
                                                                                                                                        .formatted(Formatting.GREEN));
                                                                                                } catch (java.io.IOException e) {
                                                                                                        context.getSource()
                                                                                                                        .sendError(
                                                                                                                                        Text.literal("Error hashing animation: "
                                                                                                                                                        + e.getMessage()));
                                                                                                }
                                                                                        } else {
                                                                                                context.getSource()
                                                                                                                .sendError(Text.literal(
                                                                                                                                "Animation not found: "
                                                                                                                                                + name));
                                                                                        }
                                                                                        return 1;
                                                                                }))));
        }
}