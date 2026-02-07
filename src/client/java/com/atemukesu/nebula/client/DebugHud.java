package com.atemukesu.nebula.client;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.config.ModConfig;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.resource.language.I18n;

public class DebugHud {
    public static void register() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (!ModConfig.getInstance().getShowDebugHud())
                return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.options.debugEnabled)
                return;

            TextRenderer textRenderer = client.textRenderer;
            ClientAnimationManager manager = ClientAnimationManager.getInstance();

            String modVersion = Nebula.MOD_VERSION;
            int instanceCount = manager.getInstanceCount();
            int particleCount = manager.getParticleCount();

            // High Performance Mode is now always ON
            String highPerfStatus = I18n.translate("gui.nebula.config.on");
            double totalMB = com.atemukesu.nebula.client.render.GpuParticleRenderer.getBufferSize() / 1024.0 / 1024.0;
            double usedMB = com.atemukesu.nebula.client.render.GpuParticleRenderer.getTypeSize() / 1024.0 / 1024.0;
            double ramMB = com.atemukesu.nebula.client.loader.NblStreamer.getTotalAllocatedMemory() / 1024.0 / 1024.0;

            // Display both GPU VRAM and RAM usage to clarify "Buffer Size"
            // (Orphaned) tag indicates the optimization is active
            String bufferInfo = String.format("GPU: %.2f/%.2fMB | RAM: %.2fMB", usedMB, totalMB, ramMB);

            String info = I18n.translate("hud.nebula.debug.info",
                    modVersion,
                    instanceCount,
                    particleCount,
                    highPerfStatus,
                    bufferInfo);

            drawContext.drawTextWithShadow(textRenderer, info, 5, 5, 0xFFFFFF);
        });
    }
}