package com.atemukesu.nebula.server;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.networking.ModPackets;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import com.atemukesu.nebula.util.NebulaHashUtils;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ServerAnimationSyncer {
    private static final Map<String, String> serverHashes = new HashMap<>();

    public static void reload() {
        serverHashes.clear();
        // Ensure animations are discovered
        if (AnimationLoader.getAnimations().isEmpty()) {
            AnimationLoader.discoverAnimations();
        }
        Map<String, Path> animations = AnimationLoader.getAnimations();

        Nebula.LOGGER.info("Server syncing hashes for {} animations...", animations.size());

        animations.forEach((name, path) -> {
            try {
                String hash = NebulaHashUtils.getSecureSampleHash(path);
                serverHashes.put(name, hash);
            } catch (IOException e) {
                Nebula.LOGGER.error("Failed to hash animation: {}", name, e);
                serverHashes.put(name, "ERROR");
            }
        });
        Nebula.LOGGER.info("Server hashes ready. Count: {}", serverHashes.size());
    }

    //? if >=1.21 {
    public static void sendToPlayer(ServerPlayerEntity player) {
        if (player.getServer() != null && player.getServer().isSingleplayer())
            return;
        // Ensure we have hashes
        if (serverHashes.isEmpty()) {
            reload();
        }
        ServerPlayNetworking.send(player, new ModPackets.SyncDataPayload(serverHashes));
    }
    //? } else {
    
    /*public static void sendToPlayer(ServerPlayerEntity player) {
        if (player.getServer() != null && player.getServer().isSingleplayer())
            return;

        // Ensure we have hashes
        if (serverHashes.isEmpty()) {
            reload();
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(serverHashes.size());
        serverHashes.forEach((name, hash) -> {
            buf.writeString(name);
            buf.writeString(hash);
        });

        ServerPlayNetworking.send(player, ModPackets.SYNC_DATA, buf);
    }
    
    *///? }

    public static void sendToAll(MinecraftServer server) {
        if (server.isSingleplayer())
            return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendToPlayer(player);
        }
    }

    public static Map<String, String> getHashes() {
        return serverHashes;
    }
}
