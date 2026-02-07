package com.atemukesu.nebula.command;

import com.atemukesu.nebula.networking.ModPackets;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

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
                                                        // 向所有客户端发送重载包
                                                        for (ServerPlayerEntity player : context.getSource().getServer()
                                                                        .getPlayerManager()
                                                                        .getPlayerList()) {
                                                                ServerPlayNetworking.send(player,
                                                                                ModPackets.RELOAD_CLIENT_S2C,
                                                                                PacketByteBufs.empty());
                                                        }

                                                        context.getSource().sendFeedback(
                                                                        () -> Text.translatable(
                                                                                        "command.nebula.reload.success")
                                                                                        .formatted(Formatting.GREEN),
                                                                        true);
                                                        return 1;
                                                }))

                                .then(literal("clear")
                                                .executes(context -> {
                                                        // 向所有玩家发送清除数据包
                                                        for (ServerPlayerEntity player : context.getSource().getServer()
                                                                        .getPlayerManager()
                                                                        .getPlayerList()) {
                                                                ServerPlayNetworking.send(player,
                                                                                ModPackets.CLEAR_ANIMATIONS_S2C,
                                                                                PacketByteBufs.empty());
                                                        }
                                                        context.getSource().sendFeedback(
                                                                        () -> Text.translatable(
                                                                                        "command.nebula.clear.success")
                                                                                        .formatted(Formatting.GREEN),
                                                                        true);
                                                        return 1;
                                                })));

                // 同时注册别名 movparticles 保持兼容性
                dispatcher.register(literal("movparticles")
                                .requires(source -> source.hasPermissionLevel(2))
                                .redirect(dispatcher.getRoot().getChild("nebula")));
        }

        private static int executePlay(ServerCommandSource source, String animationName, Vec3d position) {
                if (!AnimationLoader.getAnimations().containsKey(animationName)) {
                        source.sendError(Text.translatable("command.nebula.play.not_found", animationName)
                                        .formatted(Formatting.RED));
                        return 0;
                }

                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeString(animationName);
                buf.writeDouble(position.x);
                buf.writeDouble(position.y);
                buf.writeDouble(position.z);

                for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                        ServerPlayNetworking.send(player, ModPackets.PLAY_ANIMATION_S2C, new PacketByteBuf(buf.copy()));
                }

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