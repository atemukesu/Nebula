package com.atemukesu.nebula.mixin.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.loader.ClientAnimationLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class ExampleClientMixin {

	private static boolean hasTriggeredInitialLoad = false;

	/**
	 * 【核心修改】注入 setScreen 方法，但不再显示任何屏幕。
	 * 当游戏准备显示主菜单时，我们以此为信号，在后台开始加载动画。
	 */
	@Inject(method = "setScreen", at = @At("HEAD"))
	private void onSetScreen(Screen screen, CallbackInfo ci) {
		if (screen instanceof TitleScreen && !hasTriggeredInitialLoad) {
			hasTriggeredInitialLoad = true;
			Nebula.LOGGER.info("Triggering initial background animation load.");

			// 调用简化的加载器
			ClientAnimationLoader.loadAnimationsAsync(
					() -> { // 加载成功的回调
						Nebula.LOGGER.info("Initial background animation load completed.");
					},
					(ex) -> { // 加载失败的回调
						Nebula.LOGGER.error("Asynchronous animation load failed on startup", ex);
					});
		}
	}
}