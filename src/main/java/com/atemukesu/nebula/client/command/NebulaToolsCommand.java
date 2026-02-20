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