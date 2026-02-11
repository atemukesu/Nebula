package com.atemukesu.nebula.networking;

import com.atemukesu.nebula.Nebula;
import net.minecraft.util.Identifier;

public class ModPackets {
    // S2C (Server to Client)
    public static final Identifier PLAY_ANIMATION_S2C = new Identifier(Nebula.MOD_ID, "play_animation");
    public static final Identifier CLEAR_ANIMATIONS_S2C = new Identifier(Nebula.MOD_ID, "clear_animations");
    public static final Identifier RELOAD_CLIENT_S2C = new Identifier(Nebula.MOD_ID, "reload_client");
    public static final Identifier SYNC_DATA = new Identifier(Nebula.MOD_ID, "sync_data");

    public static void registerC2SPackets() {
        // No C2S packets yet
    }
}