package com.atemukesu.nebula.command;

import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.config.ModConfig;
import com.atemukesu.nebula.config.NebulaYACLConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
                                    // 【重要】如果 config 类没有自动保存功能，这里记得手动调用 save()
                                    // config.save();

                                    PerformanceStats.getInstance().setEnabled(newState);

                                    String statusKey = newState ? "gui.nebula.config.on" : "gui.nebula.config.off";
                                    Formatting color = newState ? Formatting.GREEN : Formatting.RED;

                                    context.getSource().sendFeedback(
                                            Text.translatable("gui.nebula.config.show_debug_hud")
                                                    .append(": ")
                                                    .append(Text.translatable(statusKey).formatted(color)));
                                    return 1;
                                }))
                        // 2. Settings 命令分支
                        .then(ClientCommandManager.literal("settings")
                                .executes(context -> {
                                    // 获取当前命令执行时的屏幕（通常是 ChatScreen）
                                    net.minecraft.client.gui.screen.Screen parent = MinecraftClient
                                            .getInstance().currentScreen;

                                    MinecraftClient.getInstance().execute(() -> {
                                        MinecraftClient.getInstance().setScreen(
                                                NebulaYACLConfig.createConfigScreen(parent));
                                    });
                                    return 1;
                                })));
    }
}