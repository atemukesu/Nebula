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

package com.atemukesu.nebula.client;

import com.atemukesu.nebula.client.command.NebulaToolsCommand;
import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.gui.screen.NblSyncScreen;
import com.atemukesu.nebula.client.loader.ClientAnimationLoader;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;
import com.atemukesu.nebula.client.config.ConfigManager;
import com.atemukesu.nebula.client.config.ModConfig;
import com.atemukesu.nebula.client.util.CurrentTimeUtil;
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
//? if < 1.21 {
/*import net.minecraft.util.math.Vec3d;
*///? }

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
                (client, handler, buf, responseSender) -> client.execute(
                        () -> {
                            isReloading = true;
                            ClientAnimationLoader.loadAnimationsAsync(
                                    () -> { // Success
                                        client.execute(() -> {
                                            isReloading = false;
                                            if (client.player != null) {
                                                client.player.sendMessage(net.minecraft.text.Text
                                                        .translatable("command.nebula.client.reload.success")
                                                        .formatted(net.minecraft.util.Formatting.GREEN), false);
                                            }
                                            if (pendingSyncHashes != null) {
                                                Nebula.LOGGER.info("Reload finished, processing queued sync data...");
                                                openSyncScreenIfNecessary(client, pendingSyncHashes);
                                                pendingSyncHashes = null; // 清空队列
                                            }
                                        });
                                    },
                                    (ex) -> { // Failure
                                        client.execute(() -> {
                                            isReloading = false;
                                            if (client.player != null) {
                                                client.player.sendMessage(net.minecraft.text.Text
                                                        .translatable("command.nebula.client.reload.failed")
                                                        .formatted(net.minecraft.util.Formatting.RED), false);
                                            }
                                            Nebula.LOGGER.error("Asynchronous animation reload failed from packet",
                                                    ex);
                                        });
                                    });
                        }));


        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SYNC_DATA,
                (client, handler, buf, responseSender) -> {
                    if (CurrentTimeUtil.isInReplay()) {
                        return;
                    }
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
                        if (NebulaClient.isReloading) {
                            pendingSyncHashes = hashes;
                            return;
                        }
                        if (client.player != null) {
                            // 一切正常，打开界面
                            openSyncScreenIfNecessary(client, hashes);
                        } else {
                            // 玩家还没进服（登录中），存起来等 JOIN 事件
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
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.ReloadClientPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    isReloading = true;
                    ClientAnimationLoader.loadAnimationsAsync(
                            () -> { // Success
                                isReloading = false;
                                if (context.player() != null) {
                                    context.player().sendMessage(net.minecraft.text.Text
                                            .translatable("command.nebula.client.reload.success")
                                            .formatted(net.minecraft.util.Formatting.GREEN), false);
                                }
                                if (pendingSyncHashes != null) {
                                    Nebula.LOGGER.info("Reload finished, processing queued sync data...");
                                    openSyncScreenIfNecessary(context.client(), pendingSyncHashes);
                                    pendingSyncHashes = null; // 清空队列
                                }
                            },
                            (ex) -> { // Failure
                                isReloading = false;
                                if (context.player() != null) {
                                    context.player().sendMessage(net.minecraft.text.Text
                                            .translatable("command.nebula.client.reload.failed")
                                            .formatted(net.minecraft.util.Formatting.RED), false);
                                }
                                Nebula.LOGGER.error("Asynchronous animation reload failed from packet", ex);
                            });
                }));

        // 4. Sync Data
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.SyncDataPayload.ID, (payload, context) -> {
            // 检测到 Replay 环境直接跳过同步
            if (CurrentTimeUtil.isInReplay()) {
                return;
            }
            // 根据配置决定是否在单人模式下跳过同步
            if (context.client().isInSingleplayer() && !ModConfig.getInstance().getSyncSingleplayerAnimations()) {
                Nebula.LOGGER.info("Singleplayer mode detected and sync disabled in config, skipping animation sync.");
                return;
            }
            java.util.Map<String, String> hashes = payload.hashes();

            context.client().execute(() -> {
                if (NebulaClient.isReloading) {
                    Nebula.LOGGER.info("Client is reloading, queuing sync data until finished.");
                    pendingSyncHashes = hashes;
                    return;
                }
                if (context.client().player != null) {
                    openSyncScreenIfNecessary(context.client(), hashes);
                } else {
                    pendingSyncHashes = hashes;
                    Nebula.LOGGER.info("Received sync data during login, queued for JOIN event.");
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

        if (CurrentTimeUtil.isInReplay()) {
            Nebula.LOGGER.info("Replay Mod detected, suppressing sync screen.");
            return;
        }

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

    /**
     * 标记是否正在重载
     */
    public static boolean isReloading = false;
}
