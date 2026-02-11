package com.atemukesu.nebula;

import com.atemukesu.nebula.command.NebulaToolsCommand;
import com.atemukesu.nebula.client.ClientAnimationManager;
import com.atemukesu.nebula.client.DebugHud;
import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.loader.ClientAnimationLoader;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.config.ConfigManager;
import com.atemukesu.nebula.config.ModConfig;
import com.atemukesu.nebula.networking.ModPackets;
import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

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

        // 【渲染路径分离】
        // 原版模式：使用 WorldRenderEvents.LAST 事件进行渲染
        // Iris 模式：由 NebulaWorldRendererMixin 处理，此事件回调会跳过
        WorldRenderEvents.LAST.register(context -> {
            ClientAnimationManager.getInstance().renderTick(context);
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

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SYNC_DATA,
                (client, handler, buf, responseSender) -> {
                    // Thoroughly stop hash comparison in singleplayer
                    if (client.isInSingleplayer()) {
                        return;
                    }
                    int count = buf.readInt();
                    java.util.Map<String, String> hashes = new java.util.HashMap<>();
                    for (int i = 0; i < count; i++) {
                        String name = buf.readString();
                        String hash = buf.readString();
                        hashes.put(name, hash);
                    }
                    client.execute(() -> {
                        if (client.currentScreen instanceof com.atemukesu.nebula.client.gui.screen.NblSyncScreen) {
                            ((com.atemukesu.nebula.client.gui.screen.NblSyncScreen) client.currentScreen)
                                    .setServerHashes(hashes);
                        } else {
                            pendingSyncHashes = hashes;
                        }
                    });
                });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ModConfig.getInstance().getBlendMode() == BlendMode.OIT) {
                int w = client.getWindow().getFramebufferWidth();
                int h = client.getWindow().getFramebufferHeight();
                GpuParticleRenderer.preloadOIT(w, h);
                Nebula.LOGGER.info("OIT preloaded on world join.");
            }
            // Open Sync Screen if not singleplayer
            if (!client.isInSingleplayer()) {
                client.execute(() -> {
                    com.atemukesu.nebula.client.gui.screen.NblSyncScreen screen = new com.atemukesu.nebula.client.gui.screen.NblSyncScreen(
                            net.minecraft.text.Text.translatable("nebula.sync.title"));
                    if (pendingSyncHashes != null) {
                        screen.setServerHashes(pendingSyncHashes);
                        pendingSyncHashes = null;
                    }
                    client.setScreen(screen);
                });
            } else {
                Nebula.LOGGER.info("Singleplayer server detected, skipping animation sync.");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingSyncHashes = null;
        });
    }

    private static java.util.Map<String, String> pendingSyncHashes = null;
}
