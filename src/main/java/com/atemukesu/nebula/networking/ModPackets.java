package com.atemukesu.nebula.networking;

import com.atemukesu.nebula.Nebula;
import net.minecraft.util.Identifier;

//? if >= 1.21 {
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.util.math.Vec3d;
import java.util.Map;
import java.util.HashMap;
//? }

public class ModPackets {

    //? if < 1.21 {
    
    /*// --- 1.20.x 及旧版本：只定义 Identifier ---
    public static final Identifier PLAY_ANIMATION_S2C = new Identifier(Nebula.MOD_ID, "play_animation");
    public static final Identifier CLEAR_ANIMATIONS_S2C = new Identifier(Nebula.MOD_ID, "clear_animations");
    public static final Identifier RELOAD_CLIENT_S2C = new Identifier(Nebula.MOD_ID, "reload_client");
    public static final Identifier SYNC_DATA = new Identifier(Nebula.MOD_ID, "sync_data");

    public static void registerCommon() {
    }
    

    *///? } else {
    // 1.21+
    // 1. 播放动画包
    public record PlayAnimationPayload(String animationName, Vec3d origin) implements CustomPayload {
        public static final CustomPayload.Id<PlayAnimationPayload> ID = new CustomPayload.Id<>(Identifier.of(Nebula.MOD_ID, "play_animation"));
        // 定义如何编码/解码：String + Double + Double + Double -> Vec3d
        public static final PacketCodec<RegistryByteBuf, PlayAnimationPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, PlayAnimationPayload::animationName,
                PacketCodecs.DOUBLE, p -> p.origin().x,
                PacketCodecs.DOUBLE, p -> p.origin().y,
                PacketCodecs.DOUBLE, p -> p.origin().z,
                (name, x, y, z) -> new PlayAnimationPayload(name, new Vec3d(x, y, z))
        );

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    // 2. 清除动画包 (空包)
    public record ClearAnimationsPayload() implements CustomPayload {
        public static final CustomPayload.Id<ClearAnimationsPayload> ID = new CustomPayload.Id<>(Identifier.of(Nebula.MOD_ID, "clear_animations"));
        public static final PacketCodec<RegistryByteBuf, ClearAnimationsPayload> CODEC = PacketCodec.unit(new ClearAnimationsPayload());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    // 3. 重载客户端包 (空包)
    public record ReloadClientPayload() implements CustomPayload {
        public static final CustomPayload.Id<ReloadClientPayload> ID = new CustomPayload.Id<>(Identifier.of(Nebula.MOD_ID, "reload_client"));
        public static final PacketCodec<RegistryByteBuf, ReloadClientPayload> CODEC = PacketCodec.unit(new ReloadClientPayload());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    // 4. 同步数据包
    public record SyncDataPayload(Map<String, String> hashes) implements CustomPayload {
        public static final CustomPayload.Id<SyncDataPayload> ID = new CustomPayload.Id<>(Identifier.of(Nebula.MOD_ID, "sync_data"));
        public static final PacketCodec<RegistryByteBuf, SyncDataPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.map(HashMap::new, PacketCodecs.STRING, PacketCodecs.STRING), SyncDataPayload::hashes,
                SyncDataPayload::new
        );

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(PlayAnimationPayload.ID, PlayAnimationPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClearAnimationsPayload.ID, ClearAnimationsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ReloadClientPayload.ID, ReloadClientPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncDataPayload.ID, SyncDataPayload.CODEC);
    }
    //? }

    public static void registerC2SPackets() {
        // No C2S packets yet
    }
}