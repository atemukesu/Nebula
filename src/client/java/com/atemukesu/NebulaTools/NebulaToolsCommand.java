package com.atemukesu.NebulaTools;

import com.atemukesu.NebulaTools.gui.NebulaToolsWindow;
import com.atemukesu.NebulaTools.i18n.TranslatableText;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
                            try {
                                NebulaToolsWindow.showWindow();
                                context.getSource().sendFeedback(
                                        Text.literal("§a" + TranslatableText.of("msg.window_opened")));
                                return 1;
                            } catch (Exception e) {
                                context.getSource().sendError(
                                        Text.literal("§c" + TranslatableText.of("msg.error_opening") + ": "
                                                + e.getMessage()));
                                return 0;
                            }
                        }));
    }
}
