package com.atemukesu.nebula.command;

import com.atemukesu.nebula.client.gui.NebulaDebugScreen;
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
                ClientCommandManager.literal("nebula_gui")
                        .executes(context -> {
                            // 在主线程执行 setScreen
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient.getInstance().setScreen(new NebulaDebugScreen());
                            });

                            context.getSource().sendFeedback(
                                    Text.translatable("msg.window_opened"));
                            return 1;
                        }));
    }
}
