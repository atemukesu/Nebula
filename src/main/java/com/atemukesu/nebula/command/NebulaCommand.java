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