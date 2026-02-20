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