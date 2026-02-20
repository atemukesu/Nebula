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

package com.atemukesu.nebula.command;

import com.atemukesu.nebula.networking.ModPackets;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import com.atemukesu.nebula.server.ServerAnimationSyncer;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

//? if < 1.21 {
/*import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
*///? }

public class NebulaCommand {
        public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
                dispatcher.register(literal("nebula")
                                .requires(source -> source.hasPermissionLevel(2))

                                .then(literal("play")
                                                .then(argument("animation", StringArgumentType.word())
                                                                .suggests(NebulaCommand::getAnimationSuggestions)
                                                                .then(argument("position", Vec3ArgumentType.vec3(true))
                                                                                .executes(context -> executePlay(
                                                                                                context.getSource(),
                                                                                                StringArgumentType
                                                                                                                .getString(context,
                                                                                                                                "animation"),
                                                                                                Vec3ArgumentType.getVec3(
                                                                                                                context,
                                                                                                                "position"))))
                                                                .executes(context -> executePlay(context.getSource(),
                                                                                StringArgumentType.getString(context,
                                                                                                "animation"),
                                                                                context.getSource().getPosition()))))

                                .then(literal("reload")
                                                .executes(context -> {
                                                        AnimationLoader.discoverAnimations();
                                                        ServerAnimationSyncer.reload(); // Reload server hashes
                                                        
                                                        // 发送同步数据到所有玩家（包括单人模式）
                                                        ServerAnimationSyncer.sendToAll(context.getSource().getServer());
                                                        
                                                        // 向所有玩家发送重载通知
                                                        for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                                            //? if < 1.21 {
                                                            
                                                            /*ServerPlayNetworking.send(player, ModPackets.RELOAD_CLIENT_S2C, PacketByteBufs.empty());
                                                            
                                                            *///? } else {
                                                            ServerPlayNetworking.send(player, new ModPackets.ReloadClientPayload());
                                                            //? }
                                                        }

                                                    context.getSource().sendFeedback(
                                                            () -> Text.translatable(
                                                                                        "command.nebula.reload.success")
                                                                    .formatted(Formatting.GREEN),
                                                            true);
                                                    return 1;
                                                }))

                                .then(literal("get_hash")
                                                .then(argument("animation", StringArgumentType.word())
                                                                .suggests(NebulaCommand::getAnimationSuggestions)
                                                                .executes(context -> {
                                                                        String name = StringArgumentType.getString(
                                                                                        context, "animation");
                                                                        String hash = ServerAnimationSyncer.getHashes()
                                                                                        .get(name);
                                                                        if (hash != null) {
                                                                                context.getSource().sendFeedback(
                                                                                                () -> Text.literal(
                                                                                                                "Animation: " + name
                                                                                                                                + "\nHash: "
                                                                                                                                + hash)
                                                                                                                .formatted(Formatting.GREEN),
                                                                                                false);
                                                                        } else {
                                                                                context.getSource().sendError(Text
                                                                                                .literal("Animation not found or not hashed: "
                                                                                                                + name));
                                                                        }
                                                                        return 1;
                                                                })))

                                .then(literal("clear")
                                                .executes(context -> {
                                                        // 向所有玩家发送清除数据包
                                                    for (ServerPlayerEntity player : context.getSource().getServer().getPlayerManager().getPlayerList()) {
                                                        //? if < 1.21 {
                                                        
                                                        /*ServerPlayNetworking.send(player, ModPackets.CLEAR_ANIMATIONS_S2C, PacketByteBufs.empty());
                                                        
                                                        *///? } else {
                                                        ServerPlayNetworking.send(player, new ModPackets.ClearAnimationsPayload());
                                                        //? }
                                                    }
                                                        context.getSource().sendFeedback(
                                                                        () -> Text.translatable(
                                                                                        "command.nebula.clear.success")
                                                                                        .formatted(Formatting.GREEN),
                                                                        true);
                                                        return 1;
                                                })));
        }

    private static int executePlay(ServerCommandSource source, String animationName, Vec3d position) {
        if (!AnimationLoader.getAnimations().containsKey(animationName)) {
            source.sendError(Text.translatable("command.nebula.play.not_found", animationName)
                    .formatted(Formatting.RED));
            return 0;
        }

        //? if < 1.21 {
        
        /*PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(animationName);
        buf.writeDouble(position.x);
        buf.writeDouble(position.y);
        buf.writeDouble(position.z);

        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, ModPackets.PLAY_ANIMATION_S2C, new PacketByteBuf(buf.copy()));
        }
        
        *///? } else {
        // 1.21 写法：创建一个 Payload 对象，然后发给所有人
        ModPackets.PlayAnimationPayload payload = new ModPackets.PlayAnimationPayload(animationName, position);

        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
        //? }

        source.sendFeedback(() -> Text.translatable("command.nebula.play.success", animationName)
                .formatted(Formatting.GREEN), true);
        return 1;
    }

        private static CompletableFuture<Suggestions> getAnimationSuggestions(
                        CommandContext<ServerCommandSource> context,
                        SuggestionsBuilder builder) {
                return CommandSource.suggestMatching(AnimationLoader.getAnimations().keySet(), builder);
        }
}