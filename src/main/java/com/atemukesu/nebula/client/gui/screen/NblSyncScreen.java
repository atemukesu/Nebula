package com.atemukesu.nebula.client.gui.screen;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.particle.loader.AnimationLoader;
import com.atemukesu.nebula.util.NebulaHashUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NblSyncScreen extends Screen {

    private Map<String, String> serverHashes;
    private volatile boolean isSyncing = false;
    private volatile boolean syncCompleted = false;
    private final AtomicBoolean shouldAbort = new AtomicBoolean(false);

    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final List<LogData> logHistory = Collections.synchronizedList(new ArrayList<>());

    private LogListWidget logListWidget;
    private boolean showOnlyErrors = false;
    private ButtonWidget closeButton;

    public NblSyncScreen(Text title) {
        super(title);
    }

    public void setServerHashes(Map<String, String> hashes) {
        this.serverHashes = hashes;
        this.totalFiles.set(hashes.size());
        startSyncProcess();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int buttonWidth = 95;
        int buttonHeight = 20;
        int buttonY = this.height - 30;
        int spacing = 5;

        // 日志列表组件
        int logTop = 110;
        int logBottom = this.height - 60;
        this.logListWidget = new LogListWidget(this.client, this.width - 40, logBottom - logTop, logTop, logBottom, 12);
        updateLogUI(); // 恢复日志
        this.addSelectableChild(this.logListWidget);

        // 底部按钮栏
        int totalWidth = buttonWidth * 3 + spacing * 2;
        int startX = centerX - totalWidth / 2;

        // 关闭/中止按钮
        this.closeButton = this.addDrawableChild(ButtonWidget.builder(
                syncCompleted ? Text.translatable("nebula.sync.close") : Text.translatable("nebula.sync.abort"),
                button -> this.close()).dimensions(startX, buttonY, buttonWidth, buttonHeight).build());

        // 复制日志按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("nebula.sync.copy_log"),
                button -> copyLogsToClipboard())
                .dimensions(startX + buttonWidth + spacing, buttonY, buttonWidth, buttonHeight).build());

        // 过滤按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable(showOnlyErrors ? "nebula.sync.show_all" : "nebula.sync.show_errors"),
                button -> {
                    showOnlyErrors = !showOnlyErrors;
                    logListWidget.updateFilter(showOnlyErrors);
                    button.setMessage(
                            Text.translatable(showOnlyErrors ? "nebula.sync.show_all" : "nebula.sync.show_errors"));
                }).dimensions(startX + (buttonWidth + spacing) * 2, buttonY, buttonWidth, buttonHeight).build());
    }

    private void startSyncProcess() {
        if (serverHashes == null || isSyncing)
            return;

        isSyncing = true;
        processedFiles.set(0);
        successCount.set(0);
        failureCount.set(0);
        shouldAbort.set(false);

        addLog(Text.translatable("nebula.sync.log.start"), LogLevel.INFO);
        addLog(Text.translatable("nebula.sync.log.total_files", totalFiles.get()), LogLevel.INFO);

        CompletableFuture.runAsync(() -> {
            try {
                if (AnimationLoader.getAnimations().isEmpty()) {
                    AnimationLoader.discoverAnimations();
                }
            } catch (Exception e) {
                addLog(Text.translatable("nebula.sync.log.load_error", e.getMessage()), LogLevel.ERROR);
            }

            for (Map.Entry<String, String> entry : serverHashes.entrySet()) {
                if (shouldAbort.get()) {
                    addLog(Text.translatable("nebula.sync.log.aborted"), LogLevel.WARNING);
                    break;
                }

                String name = entry.getKey();
                String serverHash = entry.getValue();

                processedFiles.incrementAndGet();

                Nebula.LOGGER.info("Validating file [{}/{}]: {}", processedFiles.get(), totalFiles.get(), name);

                Path localPath = AnimationLoader.getAnimationPath(name);

                if (localPath == null || !localPath.toFile().exists()) {
                    addLog(Text.translatable("nebula.sync.log.missing", name), LogLevel.ERROR);
                    failureCount.incrementAndGet();
                    continue;
                }

                try {
                    String localHash = NebulaHashUtils.getSecureSampleHash(localPath);
                    if (localHash.equals(serverHash)) {
                        addLog(Text.translatable("nebula.sync.log.success", name, localHash), LogLevel.SUCCESS);
                        successCount.incrementAndGet();
                    } else {
                        addLog(Text.translatable("nebula.sync.log.hash_mismatch", name, localHash, serverHash),
                                LogLevel.ERROR);
                        failureCount.incrementAndGet();
                    }
                } catch (IOException e) {
                    addLog(Text.translatable("nebula.sync.log.io_error", name, e.getMessage()), LogLevel.ERROR);
                    failureCount.incrementAndGet();
                }
            }

            isSyncing = false;
            syncCompleted = true;

            if (shouldAbort.get()) {
                addLog(Text.translatable("nebula.sync.log.sync_aborted"), LogLevel.WARNING);
            } else if (failureCount.get() == 0) {
                addLog(Text.translatable("nebula.sync.log.all_success"), LogLevel.SUCCESS);
            } else {
                addLog(Text.translatable("nebula.sync.log.complete_with_errors", failureCount.get()), LogLevel.ERROR);
            }

            // 更新关闭按钮文本
            if (this.client != null) {
                this.client.execute(() -> {
                    if (this.closeButton != null) {
                        this.closeButton.setMessage(Text.translatable("nebula.sync.close"));
                    }
                });
            }
        });
    }

    private void addLog(Text text, LogLevel level) {
        logHistory.add(new LogData(text, level));
        if (this.client != null) {
            this.client.execute(this::updateLogUI);
        }
    }

    private void updateLogUI() {
        if (this.logListWidget != null) {
            int currentSize = this.logListWidget.getAllEntryCount();
            int targetSize = logHistory.size();

            // 只需要添加新增的日志
            if (targetSize > currentSize) {
                // 使用 synchronized 块来防止在遍历时 logHistory 被修改
                synchronized (logHistory) {
                    // 再次检查大小，防止并发修改
                    targetSize = logHistory.size();
                    for (int i = currentSize; i < targetSize; i++) {
                        LogData data = logHistory.get(i);
                        this.logListWidget.addEntry(data.text, data.level);
                    }
                }
                // 确保过滤器状态保持一致
                this.logListWidget.updateFilter(showOnlyErrors);
            }
        }
    }

    private void copyLogsToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Nebula Sync Log ===\n");
        sb.append("Total: ").append(totalFiles.get()).append(" files\n");
        sb.append("Success: ").append(successCount.get()).append("\n");
        sb.append("Failed: ").append(failureCount.get()).append("\n");
        sb.append("\nDetailed Log:\n");

        for (LogListWidget.LogEntry entry : logListWidget.children()) {
            sb.append("[").append(entry.level.name()).append("] ").append(entry.text.getString()).append("\n");
        }

        if (this.client != null) {
            this.client.keyboard.setClipboard(sb.toString());
        }
        addLog(Text.translatable("nebula.sync.log.copied"), LogLevel.INFO);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 保存当前渲染状态
        //? if >=1.21 {
        this.renderInGameBackground(context);
        //? } else {
         /*this.renderBackground(context);
        *///? }
        
        // 确保渲染状态正确恢复
        context.getMatrices().push();
        
        int centerX = this.width / 2;
        int currentY = 15;

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, currentY, 0xFFFFFF);
        currentY += 25;

        // 状态栏背景
        int statusBarHeight = 60;
        context.fill(20, currentY, this.width - 20, currentY + statusBarHeight, 0x80000000);
        currentY += 5;

        // 状态和进度
        if (isSyncing) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("nebula.sync.status.syncing").formatted(Formatting.YELLOW),
                    centerX, currentY, 0xFFFF00);
            currentY += 15;

            // 进度条
            int barWidth = this.width - 80;
            int barHeight = 10;
            int barX = (this.width - barWidth) / 2;

            context.fill(barX, currentY, barX + barWidth, currentY + barHeight, 0xFF333333);

            float progress = totalFiles.get() > 0 ? (float) processedFiles.get() / totalFiles.get() : 0;
            int fillWidth = (int) (barWidth * progress);
            context.fill(barX, currentY, barX + fillWidth, currentY + barHeight, 0xFF00AA00);

            String progressText = processedFiles.get() + " / " + totalFiles.get();
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(progressText),
                    centerX, currentY + 1, 0xFFFFFF);
            currentY += 15;
        } else {
            Text statusText;
            int color;
            if (shouldAbort.get()) {
                statusText = Text.translatable("nebula.sync.status.aborted").formatted(Formatting.YELLOW);
                color = 0xFFAA00;
            } else if (failureCount.get() == 0) {
                statusText = Text.translatable("nebula.sync.status.success").formatted(Formatting.GREEN);
                color = 0x00FF00;
            } else {
                statusText = Text.translatable("nebula.sync.status.failed").formatted(Formatting.RED);
                color = 0xFF0000;
            }
            context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, currentY, color);
            currentY += 20;
        }

        // 统计信息
        Text stats = Text.translatable("nebula.sync.stats", successCount.get(), failureCount.get(), totalFiles.get());
        context.drawCenteredTextWithShadow(this.textRenderer, stats, centerX, currentY, 0xAAAAAA);
        currentY += 20;

        // 日志标题
        Text logTitle = Text.translatable(showOnlyErrors ? "nebula.sync.log_title.errors" : "nebula.sync.log_title.all")
                .formatted(Formatting.UNDERLINE);
        context.drawCenteredTextWithShadow(this.textRenderer, logTitle, centerX, currentY, 0xFFFFFF);

        // 渲染日志列表
        this.logListWidget.render(context, mouseX, mouseY, delta);

        super.render(context, mouseX, mouseY, delta);
        
        // 恢复渲染状态
        context.getMatrices().pop();
    }

    @Override
    public void close() {
        if (isSyncing) {
            shouldAbort.set(true);
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // 日志数据类
    private static class LogData {
        final Text text;
        final LogLevel level;

        LogData(Text text, LogLevel level) {
            this.text = text;
            this.level = level;
        }
    }

    // 日志级别枚举
    private enum LogLevel {
        SUCCESS("✓", 0x00FF00),
        ERROR("✗", 0xFF5555),
        WARNING("⚠", 0xFFAA00),
        INFO("ℹ", 0xAAAAAA);

        private final String prefix;
        private final int color;

        LogLevel(String prefix, int color) {
            this.prefix = prefix;
            this.color = color;
        }

        String getPrefix() {
            return prefix;
        }

        int getColor() {
            return color;
        }
    }

    private static class LogListWidget extends AlwaysSelectedEntryListWidget<LogListWidget.LogEntry> {
        private final List<LogEntry> allEntries = new ArrayList<>();
        private boolean filterErrors = false;

        public int getAllEntryCount() {
            return allEntries.size();
        }

        public LogListWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            //? if >=1.21 {
            super(client, width, height, top, itemHeight);
            //? } else {
             /*super(client, width, height, top, bottom, itemHeight);
            *///? }
        }

        public void addEntry(Text text, LogLevel level) {
            LogEntry entry = new LogEntry(text, level);
            allEntries.add(entry);
            if (!filterErrors || level == LogLevel.ERROR || level == LogLevel.WARNING) {
                super.addEntry(entry);
            }
            // 自动滚动到底部
            this.setScrollAmount(this.getMaxScroll());
        }

        public void updateFilter(boolean showOnlyErrors) {
            this.filterErrors = showOnlyErrors;
            this.clearEntries();
            for (LogEntry entry : allEntries) {
                if (!showOnlyErrors || entry.level == LogLevel.ERROR || entry.level == LogLevel.WARNING) {
                    super.addEntry(entry);
                }
            }
            this.setScrollAmount(this.getMaxScroll());
        }

        @Override
        public int getRowWidth() {
            return this.width - 20;
        }

        //? if >=1.21 {
        @Override
        protected int getScrollbarX() {
            return this.width - 6;
        }
        //? } else {
         /*@Override
        protected int getScrollbarPositionX() {
            return this.width - 6;
        } 
        *///? }
        // 日志条目
        public static class LogEntry extends AlwaysSelectedEntryListWidget.Entry<LogEntry> {
            private final Text text;
            private final LogLevel level;
            private final MinecraftClient client;

            public LogEntry(Text text, LogLevel level) {
                this.text = text;
                this.level = level;
                this.client = MinecraftClient.getInstance();
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                    int mouseX, int mouseY, boolean hovered, float tickDelta) {
                String prefix = level.getPrefix();
                Text displayText = Text.literal(prefix + " ").append(text);

                // 处理过长文本
                String textStr = displayText.getString();
                int maxWidth = entryWidth - 10;

                if (client.textRenderer.getWidth(textStr) > maxWidth) {
                    while (client.textRenderer.getWidth(textStr + "...") > maxWidth && !textStr.isEmpty()) {
                        textStr = textStr.substring(0, textStr.length() - 1);
                    }
                    displayText = Text.literal(textStr + "...");
                }

                context.drawTextWithShadow(client.textRenderer, displayText, x + 5, y + 2, level.getColor());
            }

            @Override
            public Text getNarration() {
                return text;
            }
        }
    }
}