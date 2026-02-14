package com.atemukesu.nebula.client;

import com.atemukesu.nebula.client.command.NebulaToolsCommand;
import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.gui.screen.NblSyncScreen;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Map;

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
                        if (client.currentScreen instanceof NblSyncScreen) {
                            ((NblSyncScreen) client.currentScreen)
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

        // TODO: 1.20.1 SYNC
        // 4. Sync Data
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SyncDataPayload.ID, (payload, context) -> {
            // 根据配置决定是否在单人模式下跳过同步
            if (context.client().isInSingleplayer() && !ModConfig.getInstance().getSyncSingleplayerAnimations()) {
                Nebula.LOGGER.info("Singleplayer mode detected and sync disabled in config, skipping animation sync.");
                return;
            }
            // 数据读取逻辑现在在 Payload 类内部完成，这里直接拿结果
            java.util.Map<String, String> hashes = payload.hashes();
            if (context.client().player != null) { // 存在玩家
                openSyncScreenIfNecessary(context.client(), hashes);
            } else { //不存在玩家
                pendingSyncHashes = hashes;
                Nebula.LOGGER.info("Received sync data during login, queued for JOIN event.");
            }

        });
        //? }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ModConfig.getInstance().getBlendMode() == BlendMode.OIT) {
                int w = client.getWindow().getFramebufferWidth();
                int h = client.getWindow().getFramebufferHeight();
                GpuParticleRenderer.preloadOIT(w, h);
                Nebula.LOGGER.info("OIT preloaded on world join.");
            }
            if (pendingSyncHashes != null) {
                openSyncScreenIfNecessary(client, pendingSyncHashes);
                pendingSyncHashes = null;
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pendingSyncHashes = null);
    }

    /**
     * 根据配置和当前状态决定是否弹出同步界面
     */
    private void openSyncScreenIfNecessary(MinecraftClient client, Map<String, String> hashes) {
        // 1. 检查配置：如果是单人模式且关闭了同步，则直接跳过
        if (client.isInSingleplayer() && !ModConfig.getInstance().getSyncSingleplayerAnimations()) {
            Nebula.LOGGER.info("Singleplayer detected and sync disabled, skipping UI.");
            return;
        }

        // 2. 执行弹窗（确保在渲染线程执行）
        client.execute(() -> {
            // 如果当前已经是同步界面，则直接更新数据，不重新打开（防止界面闪烁）
            if (client.currentScreen instanceof NblSyncScreen syncScreen) {
                syncScreen.setServerHashes(hashes);
            } else {
                // 否则，打开新的同步界面
                NblSyncScreen newScreen = new NblSyncScreen(
                        Text.translatable("nebula.sync.title"));
                newScreen.setServerHashes(hashes);
                client.setScreen(newScreen);
            }
        });
    }

    private static java.util.Map<String, String> pendingSyncHashes = null;
}
