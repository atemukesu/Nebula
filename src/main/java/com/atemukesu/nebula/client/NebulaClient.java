package com.atemukesu.nebula.client;

import com.atemukesu.nebula.client.command.NebulaToolsCommand;
import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.loader.ClientAnimationLoader;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.client.config.ConfigManager;
import com.atemukesu.nebula.client.config.ModConfig;
import com.atemukesu.nebula.networking.ModPackets;
import com.atemukesu.nebula.client.gui.DebugHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

public class NebulaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ConfigManager.loadConfig();

        registerS2CPackets();

        // 注册 nebula_gui 命令
        NebulaToolsCommand.register();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientAnimationLoader.shutdownExecutor());

        // 负责驱动"常规模式"下的动画更新
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientAnimationManager.getInstance().tick(client));

        // 【渲染路径分离】
        // 原版模式：使用 WorldRenderEvents.LAST 事件进行渲染
        // Iris 模式：由 NebulaWorldRendererMixin 处理，此事件回调会跳过
        WorldRenderEvents.LAST.register(context -> ClientAnimationManager.getInstance().renderTick(context));

        DebugHud.register();

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
        });

        // Initial animation discovery
        ClientAnimationLoader.loadAnimationsAsync(
                () -> Nebula.LOGGER.info("Initial animation discovery complete"),
                ex -> Nebula.LOGGER.error("Initial animation discovery failed", ex));
    }

    @SuppressWarnings("resource")
    private void registerS2CPackets() {
        //? if < 1.21 {
        
        /*ClientPlayNetworking.registerGlobalReceiver(ModPackets.PLAY_ANIMATION_S2C,
                (client, handler, buf, responseSender) -> {
                    String animationName = buf.readString();
                    Vec3d origin = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                    client.execute(() -> ClientAnimationManager.getInstance().playAnimation(animationName, origin));
                });

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.CLEAR_ANIMATIONS_S2C,
                (client, handler, buf, responseSender) -> client.execute(() -> ClientAnimationManager.getInstance().clearAllInstances()));

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.RELOAD_CLIENT_S2C,
                (client, handler, buf, responseSender) -> client.execute(() -> ClientAnimationLoader.loadAnimationsAsync(
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
                        })));

        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SYNC_DATA,
                (client, handler, buf, responseSender) -> {
                    // 根据配置决定是否在单人模式下跳过同步
                    if (client.isInSingleplayer() && !ModConfig.getInstance().getSyncSingleplayerAnimations()) {
                        Nebula.LOGGER.info("Singleplayer mode detected and sync disabled in config, skipping animation sync.");
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
        
        *///? } else {
        // 1.21+ Payload
        // 1. Play Animation
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.PlayAnimationPayload.ID, (payload, context) -> context.client().execute(() ->
                ClientAnimationManager.getInstance().playAnimation(payload.animationName(), payload.origin())
        ));

        // 2. Clear Animations
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.ClearAnimationsPayload.ID, (payload, context) -> context.client().execute(() ->
                ClientAnimationManager.getInstance().clearAllInstances()
        ));

        // 3. Reload Client
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.ReloadClientPayload.ID, (payload, context) -> context.client().execute(() -> ClientAnimationLoader.loadAnimationsAsync(
                () -> { // Success
                    if (context.player() != null) {
                        context.player().sendMessage(net.minecraft.text.Text
                                .translatable("command.nebula.client.reload.success")
                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                    }
                },
                (ex) -> { // Failure
                    if (context.player() != null) {
                        context.player().sendMessage(net.minecraft.text.Text
                                .translatable("command.nebula.client.reload.failed")
                                .formatted(net.minecraft.util.Formatting.RED), false);
                    }
                    Nebula.LOGGER.error("Asynchronous animation reload failed from packet", ex);
                })));

        // 4. Sync Data
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SyncDataPayload.ID, (payload, context) -> {
            // 根据配置决定是否在单人模式下跳过同步
            if (context.client().isInSingleplayer() && !ModConfig.getInstance().getSyncSingleplayerAnimations()) {
                Nebula.LOGGER.info("Singleplayer mode detected and sync disabled in config, skipping animation sync.");
                return;
            }
            // 数据读取逻辑现在在 Payload 类内部完成，这里直接拿结果
            java.util.Map<String, String> hashes = payload.hashes();
            context.client().execute(() -> {
                if (context.client().currentScreen instanceof com.atemukesu.nebula.client.gui.screen.NblSyncScreen syncScreen) {
                    syncScreen.setServerHashes(hashes);
                } else {
                    pendingSyncHashes = hashes;
                }
            });
        });
        //? }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ModConfig.getInstance().getBlendMode() == BlendMode.OIT) {
                int w = client.getWindow().getFramebufferWidth();
                int h = client.getWindow().getFramebufferHeight();
                GpuParticleRenderer.preloadOIT(w, h);
                Nebula.LOGGER.info("OIT preloaded on world join.");
            }
            // Open Sync Screen if not singleplayer or if sync is enabled in config
            if (!client.isInSingleplayer() || ModConfig.getInstance().getSyncSingleplayerAnimations()) {
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
                Nebula.LOGGER.info("Singleplayer server detected and sync disabled in config, skipping animation sync.");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pendingSyncHashes = null);
    }

    private static java.util.Map<String, String> pendingSyncHashes = null;
}
