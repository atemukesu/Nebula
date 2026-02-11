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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NblSyncScreen extends Screen {

    private final List<LogEntry> logs = new ArrayList<>();
    private Map<String, String> serverHashes;
    private boolean isSyncing = false;
    private boolean syncCompleted = false;
    private final AtomicBoolean shouldAbort = new AtomicBoolean(false);

    private final AtomicInteger totalFiles = new AtomicInteger(0);
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    private int scrollOffset = 0;
    private static final int LOG_LINE_HEIGHT = 12;
    private static final int MAX_VISIBLE_LOGS = 15;

    private boolean showOnlyErrors = false;

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

        // 底部按钮栏 - 左到右排列
        int totalWidth = buttonWidth * 3 + spacing * 2;
        int startX = centerX - totalWidth / 2;

        // 关闭按钮
        this.addDrawableChild(ButtonWidget.builder(
                syncCompleted ? Text.translatable("nebula.sync.close") : Text.translatable("nebula.sync.abort"),
                button -> this.close()).dimensions(startX, buttonY, buttonWidth, buttonHeight).build());

        // 复制日志按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("nebula.sync.copy"),
                button -> copyLogsToClipboard())
                .dimensions(startX + buttonWidth + spacing, buttonY, buttonWidth, buttonHeight).build());

        // 过滤按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(showOnlyErrors ? "显示全部" : "仅错误"),
                button -> {
                    showOnlyErrors = !showOnlyErrors;
                    scrollOffset = 0;
                    this.clearAndInit();
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

        addLog("开始同步验证...", LogLevel.INFO);
        addLog("服务器要求文件数: " + totalFiles.get(), LogLevel.INFO);

        CompletableFuture.runAsync(() -> {
            try {
                // 加载本地动画
                if (AnimationLoader.getAnimations().isEmpty()) {
                    AnimationLoader.discoverAnimations();
                }
            } catch (Exception e) {
                addLog("加载本地动画失败: " + e.getMessage(), LogLevel.ERROR);
            }

            for (Map.Entry<String, String> entry : serverHashes.entrySet()) {
                if (shouldAbort.get()) {
                    addLog("用户取消同步", LogLevel.WARNING);
                    break;
                }

                String name = entry.getKey();
                String serverHash = entry.getValue();

                processedFiles.incrementAndGet();

                Nebula.LOGGER.info("验证文件 [{}/{}]: {}", processedFiles.get(), totalFiles.get(), name);

                Path localPath = AnimationLoader.getAnimationPath(name);

                if (localPath == null || !localPath.toFile().exists()) {
                    addLog(name + " - 缺失文件", LogLevel.ERROR);
                    failureCount.incrementAndGet();
                    continue;
                }

                try {
                    String localHash = NebulaHashUtils.getSecureSampleHash(localPath);
                    if (localHash.equals(serverHash)) {
                        addLog(name + " - 验证通过", LogLevel.SUCCESS);
                        successCount.incrementAndGet();
                    } else {
                        addLog(name + " - 哈希不匹配 (本地:" + localHash.substring(0, 8) + "... 服务器:"
                                + serverHash.substring(0, 8) + "...)", LogLevel.ERROR);
                        failureCount.incrementAndGet();
                    }
                } catch (IOException e) {
                    addLog(name + " - IO错误: " + e.getMessage(), LogLevel.ERROR);
                    failureCount.incrementAndGet();
                }
            }

            isSyncing = false;
            syncCompleted = true;

            if (shouldAbort.get()) {
                addLog("同步已中止", LogLevel.WARNING);
            } else if (failureCount.get() == 0) {
                addLog("全部文件验证成功!", LogLevel.SUCCESS);
            } else {
                addLog("同步完成，但有 " + failureCount.get() + " 个文件失败", LogLevel.ERROR);
            }
        });
    }

    private void addLog(String message, LogLevel level) {
        synchronized (logs) {
            logs.add(new LogEntry(message, level));
        }
    }

    private void copyLogsToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Nebula 同步日志 ===\n");
        sb.append("总计: ").append(totalFiles.get()).append(" 文件\n");
        sb.append("成功: ").append(successCount.get()).append("\n");
        sb.append("失败: ").append(failureCount.get()).append("\n");
        sb.append("\n详细日志:\n");

        synchronized (logs) {
            for (LogEntry log : logs) {
                sb.append("[").append(log.level.name()).append("] ").append(log.message).append("\n");
            }
        }

        this.client.keyboard.setClipboard(sb.toString());
        addLog("日志已复制到剪贴板", LogLevel.INFO);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        int centerX = this.width / 2;
        int currentY = 15;

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, currentY, 0xFFFFFF);
        currentY += 25;

        // 状态栏背景
        int statusBarHeight = 45;
        context.fill(20, currentY, this.width - 20, currentY + statusBarHeight, 0x80000000);
        currentY += 5;

        // 进度条
        if (isSyncing) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("同步中...").formatted(Formatting.YELLOW),
                    centerX, currentY, 0xFFFF00);
            currentY += 15;

            // 绘制进度条
            int barWidth = this.width - 80;
            int barHeight = 10;
            int barX = (this.width - barWidth) / 2;

            // 进度条背景
            context.fill(barX, currentY, barX + barWidth, currentY + barHeight, 0xFF333333);

            // 进度条填充
            float progress = totalFiles.get() > 0 ? (float) processedFiles.get() / totalFiles.get() : 0;
            int fillWidth = (int) (barWidth * progress);
            context.fill(barX, currentY, barX + fillWidth, currentY + barHeight, 0xFF00AA00);

            // 进度文字
            String progressText = processedFiles.get() + " / " + totalFiles.get();
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(progressText),
                    centerX, currentY + 1, 0xFFFFFF);
            currentY += 15;
        } else {
            // 完成状态
            Text statusText;
            int color;
            if (shouldAbort.get()) {
                statusText = Text.literal("已中止").formatted(Formatting.YELLOW);
                color = 0xFFAA00;
            } else if (failureCount.get() == 0) {
                statusText = Text.literal("✓ 同步成功").formatted(Formatting.GREEN);
                color = 0x00FF00;
            } else {
                statusText = Text.literal("✗ 同步失败").formatted(Formatting.RED);
                color = 0xFF0000;
            }
            context.drawCenteredTextWithShadow(this.textRenderer, statusText, centerX, currentY, color);
            currentY += 20;
        }

        // 统计信息
        String stats = String.format("成功: %d  |  失败: %d  |  总计: %d",
                successCount.get(), failureCount.get(), totalFiles.get());
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(stats),
                centerX, currentY, 0xAAAAAA);
        currentY += 25;

        // 日志区域
        int logAreaTop = currentY;
        int logAreaBottom = this.height - 60;
        int logAreaHeight = logAreaBottom - logAreaTop;

        // 日志区域背景
        context.fill(20, logAreaTop, this.width - 20, logAreaBottom, 0x80000000);

        // 日志标题
        String logTitle = showOnlyErrors ? "错误日志" : "全部日志";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(logTitle).formatted(Formatting.UNDERLINE),
                centerX, logAreaTop + 5, 0xFFFFFF);

        // 绘制日志
        List<LogEntry> displayLogs = getFilteredLogs();
        int startY = logAreaTop + 20;
        int visibleLines = Math.min(MAX_VISIBLE_LOGS, (logAreaHeight - 25) / LOG_LINE_HEIGHT);

        synchronized (logs) {
            int startIndex = Math.max(0, displayLogs.size() - visibleLines - scrollOffset);
            int endIndex = Math.min(displayLogs.size(), startIndex + visibleLines);

            for (int i = startIndex; i < endIndex; i++) {
                LogEntry entry = displayLogs.get(i);
                int lineY = startY + (i - startIndex) * LOG_LINE_HEIGHT;

                String prefix = entry.level.getPrefix();
                String text = prefix + " " + entry.message;

                // 截断过长文本
                int maxWidth = this.width - 50;
                if (this.textRenderer.getWidth(text) > maxWidth) {
                    while (this.textRenderer.getWidth(text + "...") > maxWidth && text.length() > 0) {
                        text = text.substring(0, text.length() - 1);
                    }
                    text += "...";
                }

                context.drawTextWithShadow(this.textRenderer, Text.literal(text),
                        30, lineY, entry.level.getColor());
            }

            // 滚动提示
            if (displayLogs.size() > visibleLines) {
                String scrollHint = String.format("(%d-%d / %d) 使用鼠标滚轮查看更多",
                        startIndex + 1, endIndex, displayLogs.size());
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(scrollHint).formatted(Formatting.GRAY),
                        centerX, logAreaBottom - 12, 0x888888);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        List<LogEntry> displayLogs = getFilteredLogs();
        int maxScroll = Math.max(0, displayLogs.size() - MAX_VISIBLE_LOGS);

        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) amount));
        return true;
    }

    private List<LogEntry> getFilteredLogs() {
        if (!showOnlyErrors) {
            return logs;
        }

        List<LogEntry> filtered = new ArrayList<>();
        synchronized (logs) {
            for (LogEntry entry : logs) {
                if (entry.level == LogLevel.ERROR || entry.level == LogLevel.WARNING) {
                    filtered.add(entry);
                }
            }
        }
        return filtered;
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

    // 日志条目类
    private static class LogEntry {
        final String message;
        final LogLevel level;

        LogEntry(String message, LogLevel level) {
            this.message = message;
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
}