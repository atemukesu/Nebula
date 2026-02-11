package com.atemukesu.nebula.client.gui.screen;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import com.atemukesu.nebula.util.NebulaHashUtils;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class NblSyncScreen extends Screen {

    private final List<Text> logs = new ArrayList<>();
    private Map<String, String> serverHashes;
    private boolean isSyncing = true;
    private final List<String> errorLogs = new ArrayList<>();
    private final List<String> successLogs = new ArrayList<>();
    private long successTime = 0;

    public NblSyncScreen(Text title) {
        super(title);
    }

    public void setServerHashes(Map<String, String> hashes) {
        this.serverHashes = hashes;
        startSyncProcess();
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("nebula.sync.copy"), button -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Nebula Sync Log:\n");
            for (Text log : logs) {
                sb.append(log.getString()).append("\n");
            }
            this.client.keyboard.setClipboard(sb.toString());
        }).dimensions(centerX - 100, this.height - 40, 200, 20).build());

        // Add Abort button (bottom, below copy)
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("nebula.sync.abort"), button -> {
            this.close();
        }).dimensions(centerX - 100, this.height - 70, 200, 20).build());

        // Add close button (top right)
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.close"), button -> {
            this.close();
        }).dimensions(this.width - 60, 10, 50, 20).build());
    }

    private void startSyncProcess() {
        if (serverHashes == null)
            return;

        CompletableFuture.runAsync(() -> {
            int total = serverHashes.size();
            int current = 0;

            // Ensure local animations are loaded
            try {
                if (AnimationLoader.getAnimations().isEmpty()) {
                    AnimationLoader.discoverAnimations();
                }
            } catch (Exception e) {
                addLog(Text.literal("Error loading local animations: " + e.getMessage()).formatted(Formatting.RED));
            }

            for (Map.Entry<String, String> entry : serverHashes.entrySet()) {
                current++;
                String name = entry.getKey();
                String serverHash = entry.getValue();

                Nebula.LOGGER.info("Validating sync for {}", name);
                addLog(Text.translatable("nebula.sync.processing", name, current, total, serverHash)
                        .formatted(Formatting.WHITE));

                Path localPath = AnimationLoader.getAnimationPath(name);
                if (localPath == null || !localPath.toFile().exists()) {
                    String msg = "Validation failed: " + name + " does not match server requirement (MISSING).";
                    Nebula.LOGGER.error(msg);
                    addLog(Text.translatable("nebula.sync.failed", name, serverHash, "MISSING")
                            .formatted(Formatting.RED));
                    errorLogs.add(name);
                    continue;
                }

                try {
                    String localHash = NebulaHashUtils.getSecureSampleHash(localPath);
                    if (localHash.equals(serverHash)) {
                        addLog(Text.translatable("nebula.sync.success", name, localHash).formatted(Formatting.GREEN));
                        successLogs.add(name);
                    } else {
                        String msg = "Validation failed: " + name + " does not match server requirement.";
                        Nebula.LOGGER.error(msg);
                        addLog(Text.translatable("nebula.sync.failed", name, serverHash, localHash)
                                .formatted(Formatting.RED));
                        errorLogs.add(name);
                    }
                } catch (IOException e) {
                    addLog(Text.translatable("nebula.sync.failed", name, serverHash, "IO_ERROR")
                            .formatted(Formatting.RED));
                    errorLogs.add(name);
                }
            }
            isSyncing = false;
            if (errorLogs.isEmpty()) {
                successTime = System.currentTimeMillis();
            }
        });
    }

    private void addLog(Text text) {
        synchronized (logs) {
            logs.add(text);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 20, 0xFFFFFF);

        if (isSyncing) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("nebula.sync.syncing"), centerX, 40,
                    0xAAAAAA);
        } else {
            if (errorLogs.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.translatable("nebula.sync.complete_success").formatted(Formatting.GREEN), centerX, 40,
                        0x00FF00);

                // Auto close check
                if (successTime > 0 && System.currentTimeMillis() - successTime > 3000) {
                    this.close();
                }

            } else {
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.translatable("nebula.sync.complete_failed", errorLogs.size()).formatted(Formatting.RED),
                        centerX, 40, 0xFF0000);
            }
        }

        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("nebula.sync.log_title"), centerX, 60,
                0xFFFFFF);

        // Render logs
        int y = 80;
        int lineHeight = 10;
        int bottomLimit = this.height - 80;
        int maxLines = Math.max(0, (bottomLimit - y) / lineHeight);

        synchronized (logs) {
            int start = Math.max(0, logs.size() - maxLines);
            for (int i = start; i < logs.size(); i++) {
                context.drawCenteredTextWithShadow(this.textRenderer, logs.get(i), centerX, y, 0xFFFFFF);
                y += lineHeight;
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
