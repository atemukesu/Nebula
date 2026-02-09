package com.atemukesu.nebula;

import com.atemukesu.nebula.command.NebulaToolsCommand;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.atemukesu.nebula.client.DebugHud;
import com.atemukesu.nebula.client.loader.ClientAnimationLoader;
import com.atemukesu.nebula.config.ConfigManager;
import com.atemukesu.nebula.networking.ModPackets;
import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.util.math.Vec3d;

public class NebulaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();

        registerS2CPackets();

        // 注册 nebula_gui 命令
        NebulaToolsCommand.register();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ClientAnimationLoader.shutdownExecutor();
        });

        // 负责驱动"常规模式"下的动画更新
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientAnimationManager.getInstance().tick(client);
        });

        DebugHud.register();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
        });

        // Initial animation discovery
        ClientAnimationLoader.loadAnimationsAsync(
                () -> Nebula.LOGGER.info("Initial animation discovery complete"),
                ex -> Nebula.LOGGER.error("Initial animation discovery failed", ex));
    }

    private void registerS2CPackets() {
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.PLAY_ANIMATION_S2C,
                (client, handler, buf, responseSender) -> {
                    String animationName = buf.readString();
                    Vec3d origin = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                    client.execute(() -> ClientAnimationManager.getInstance().playAnimation(animationName, origin));
                });

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.CLEAR_ANIMATIONS_S2C,
                (client, handler, buf, responseSender) -> {
                    client.execute(() -> ClientAnimationManager.getInstance().clearAllInstances());
                });

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.RELOAD_CLIENT_S2C,
                (client, handler, buf, responseSender) -> {
                    client.execute(() -> {
                        ClientAnimationLoader.loadAnimationsAsync(
                                () -> { // Success
                                    if (client.player != null) {
                                        client.player.sendMessage(net.minecraft.text.Text
                                                .translatable("command.nebula.client.reload.success")
                                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                                    }
                                },
                                (ex) -> { // Failure
                                    if (client.player != null) {
                                        client.player.sendMessage(net.minecraft.text.Text
                                                .translatable("command.nebula.client.reload.failed")
                                                .formatted(net.minecraft.util.Formatting.RED), false);
                                    }
                                    Nebula.LOGGER.error("Asynchronous animation reload failed from packet",
                                            ex);
                                });
                    });
                });
    }
}
