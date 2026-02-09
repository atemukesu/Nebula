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

/**
 * Nebula Tools 客户端命令注册
 */
public class NebulaToolsCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(NebulaToolsCommand::registerCommands);
    }

    private static void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            CommandRegistryAccess registryAccess) {

        dispatcher.register(
                ClientCommandManager.literal("nebula_client")
                        .executes(context -> {
                            // 默认行为：切换 HUD 显示状态
                            ModConfig config = ModConfig.getInstance();
                            boolean newState = !config.getShowDebugHud();
                            config.setShowDebugHud(newState);

                            // 更新 PerformanceStats 状态
                            PerformanceStats.getInstance().setEnabled(newState);

                            // 发送反馈
                            String statusKey = newState ? "gui.nebula.config.on" : "gui.nebula.config.off";
                            context.getSource().sendFeedback(
                                    Text.translatable("gui.nebula.config.show_debug_hud")
                                            .append(": ")
                                            .append(Text.translatable(statusKey)
                                                    .formatted(newState ? net.minecraft.util.Formatting.GREEN
                                                            : net.minecraft.util.Formatting.RED)));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("settings")
                                .executes(context -> {
                                    // 打开设置界面
                                    MinecraftClient.getInstance().execute(() -> {
                                        MinecraftClient.getInstance().setScreen(
                                                NebulaYACLConfig.createConfigScreen(
                                                        MinecraftClient.getInstance().currentScreen));
                                    });
                                    return 1;
                                })));
    }
}
