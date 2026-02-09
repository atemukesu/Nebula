package com.atemukesu.nebula.client.gui;

import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;

public class NebulaDebugScreen extends Screen {

    private final PerformanceStats stats = PerformanceStats.getInstance();
    private int activeTab = 0; // 0=Performance, 1=Animations, 2=Settings

    // UI 常量
    private static final int SIDEBAR_WIDTH = 120;
    private static final int PADDING = 10;
    private static final int COLOR_BG = 0xCC1A1A1A; // 半透明黑色背景
    private static final int COLOR_SIDEBAR = 0xFF252525;

    public NebulaDebugScreen() {
        super(Text.literal("Nebula Tools"));
    }

    @Override
    protected void init() {
        super.init();
        stats.setEnabled(true); // 打开屏幕时开启统计

        // 左侧选项卡按钮
        int btnY = PADDING + 30;

        // Tab 0: Performance
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("tab.performance"), (button) -> {
            this.activeTab = 0;
        }).dimensions(PADDING, btnY, SIDEBAR_WIDTH - PADDING * 2, 20).build());
        btnY += 25;

        // Tab 1: Animations (Placeholder)
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("tab.animations"), (button) -> {
            this.activeTab = 1;
        }).dimensions(PADDING, btnY, SIDEBAR_WIDTH - PADDING * 2, 20).build());
        btnY += 25;

        // Tab 2: Settings (Placeholder)
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("tab.settings"), (button) -> {
            this.activeTab = 2;
        }).dimensions(PADDING, btnY, SIDEBAR_WIDTH - PADDING * 2, 20).build());
    }

    @Override
    public void close() {
        stats.setEnabled(false); // 关闭屏幕时停止统计
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 手动绘制背景，避免版本差异
        this.renderBackground(context);

        // 1. 绘制左侧侧边栏背景
        context.fill(0, 0, SIDEBAR_WIDTH, this.height, COLOR_SIDEBAR);

        // 2. 绘制标题
        context.drawText(this.textRenderer, Text.literal("Nebula Tools"), PADDING, PADDING, 0xFFFFFF, true);

        // 3. 绘制右侧内容区域背景
        context.fill(SIDEBAR_WIDTH, 0, this.width, this.height, COLOR_BG);

        // 4. 根据当前 Tab 绘制内容
        if (activeTab == 0) {
            renderPerformanceTab(context);
        } else {
            renderPlaceholderTab(context);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderPerformanceTab(DrawContext context) {
        int left = SIDEBAR_WIDTH + PADDING;
        int top = PADDING;
        int right = this.width - PADDING;
        int bottom = this.height - PADDING;

        // --- 上半部分：文本统计 ---
        int lineHeight = 12;
        int col1X = left;
        int col2X = left + 200;
        int y = top;

        // Column 1
        drawStatText(context, "perf.fps", String.format("%.1f", stats.getCurrentFps()), col1X, y, 0xFFFFAA);
        y += lineHeight;
        drawStatText(context, "perf.particles", String.format("%,d", stats.getParticleCount()), col1X, y, 0xAAAAFF);
        y += lineHeight;
        drawStatText(context, "perf.instances", String.valueOf(stats.getInstanceCount()), col1X, y, 0xAAAAFF);
        y += lineHeight;

        String modeKey = stats.isIrisMode() ? "perf.mode.iris" : "perf.mode.standard";
        Text modeText = Text.translatable("perf.render_mode").append(": ").append(Text.translatable(modeKey));
        context.drawText(this.textRenderer, modeText, col1X, y, 0xFFFFFF, true);
        y += lineHeight;

        // Column 2 (Reset Y)
        y = top;
        drawStatText(context, "perf.render_time", String.format("%.2f ms", stats.getRenderTimeMs()), col2X, y,
                0xFFAAAA);
        y += lineHeight;
        drawStatText(context, "perf.upload_time", String.format("%.2f ms", stats.getUploadTimeMs()), col2X, y,
                0xFFAAAA);
        y += lineHeight;
        drawStatText(context, "perf.buffer_size", stats.formatBufferSize(stats.getBufferSizeBytes()), col2X, y,
                0xAAFFAA);
        y += lineHeight;
        drawStatText(context, "perf.used_buffer", stats.formatBufferSize(stats.getUsedBufferBytes()), col2X, y,
                0xAAFFAA);
        y += lineHeight;

        String glErrText = (stats.getLastGlError() == 0 ? "OK" : String.format("0x%X", stats.getLastGlError()));
        int errColor = stats.getLastGlError() == 0 ? 0x55FF55 : 0xFF5555;
        Text errLabel = Text.translatable("perf.gl_error").append(": " + glErrText);
        context.drawText(this.textRenderer, errLabel, col2X, y, errColor, true);

        // --- 下半部分：折线图 ---
        int chartAreaTop = top + 80;
        int chartAreaHeight = bottom - chartAreaTop;
        int chartH = (chartAreaHeight - PADDING) / 2;
        int chartW = (right - left - PADDING) / 2;

        // Chart 1: Particles (Top-Left)
        drawChart(context, left, chartAreaTop, chartW, chartH,
                "perf.chart.particles", stats.getParticleCountHistory(), 0xFF5555FF);

        // Chart 2: Render Time (Top-Right)
        drawChart(context, left + chartW + PADDING, chartAreaTop, chartW, chartH,
                "perf.chart.render_time", stats.getRenderTimeHistory(), 0xFFFF5555);

        // Chart 3: Buffer Usage (Bottom-Left)
        // Convert Bytes to MB for chart
        List<Double> bufHistoryMb = stats.getUsedBufferHistory().stream()
                .map(b -> b / 1024.0 / 1024.0).toList();
        drawChart(context, left, chartAreaTop + chartH + PADDING, chartW, chartH,
                "perf.chart.buffer", bufHistoryMb, 0xFF55FF55);

        // Chart 4: FPS (Bottom-Right)
        drawChart(context, left + chartW + PADDING, chartAreaTop + chartH + PADDING, chartW, chartH,
                "perf.chart.fps", stats.getFpsHistory(), 0xFFFFAA00);
    }

    private void drawStatText(DrawContext context, String key, String value, int x, int y, int color) {
        Text text = Text.translatable(key).append(": " + value);
        context.drawText(this.textRenderer, text, x, y, color, true);
    }

    private void renderPlaceholderTab(DrawContext context) {
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("tab.coming_soon"),
                SIDEBAR_WIDTH + (this.width - SIDEBAR_WIDTH) / 2,
                this.height / 2,
                0x888888);
    }

    /**
     * 绘制折线图
     */
    private void drawChart(DrawContext context, int x, int y, int w, int h, String titleKey,
            List<? extends Number> data, int colorARGB) {
        // 背景
        context.fill(x, y, x + w, y + h, 0x80000000); // 黑色半透明背景
        context.drawBorder(x, y, w, h, 0xFF444444); // 边框

        // 标题
        context.drawText(this.textRenderer, Text.translatable(titleKey), x + 5, y + 5, 0xFFFFFF, true);

        if (data == null || data.size() < 2)
            return;

        // 计算最大值 (用于 Y 轴缩放)
        double maxVal = 0;
        for (Number n : data)
            maxVal = Math.max(maxVal, n.doubleValue());
        if (maxVal <= 0.0001)
            maxVal = 1.0; // 防止除零
        maxVal *= 1.1; // 留 10% 顶部

        // 当前值显示
        Number lastVal = data.get(data.size() - 1);
        String valStr = String.format("%.1f", lastVal.doubleValue());
        int valW = this.textRenderer.getWidth(valStr);
        context.drawText(this.textRenderer, valStr, x + w - valW - 5, y + 5, colorARGB, true);

        // 绘制折线
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 使用针对位置和颜色的 Shader
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.lineWidth(2.0f);

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);

        int count = data.size();
        float xStep = (float) w / (count - 1);

        float r = ((colorARGB >> 16) & 0xFF) / 255f;
        float g = ((colorARGB >> 8) & 0xFF) / 255f;
        float b = (colorARGB & 0xFF) / 255f;
        float a = ((colorARGB >> 24) & 0xFF) / 255f;

        // 图表绘图区域 (留出 padding: 标题高度 20)
        float graphY = y + 20;
        float graphH = h - 20;

        for (int i = 0; i < count; i++) {
            double val = data.get(i).doubleValue();
            float px = x + i * xStep;
            float py = graphY + graphH - (float) ((val / maxVal) * graphH);

            // 限制在区域内
            py = MathHelper.clamp(py, graphY, graphY + graphH);

            buffer.vertex(px, py, 0.0f).color(r, g, b, a).next();
        }

        tessellator.draw();

        // 恢复状态
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
    }
}
