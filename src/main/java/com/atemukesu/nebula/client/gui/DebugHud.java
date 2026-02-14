package com.atemukesu.nebula.client.gui;

import com.atemukesu.nebula.Nebula;
import com.atemukesu.nebula.client.gui.tools.PerformanceStats;
import com.atemukesu.nebula.client.config.ModConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.util.math.MathHelper;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public class DebugHud {

    // HUD 常量
    private static final int BG_COLOR = 0x90000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int CHART_WIDTH = 100;
    private static final int CHART_HEIGHT = 40;
    private static final int PADDING = 5;

    // 文本缓存结构
    private static class CachedLine {
        String text;
        int color;

        CachedLine(String t, int c) {
            text = t;
            color = c;
        }
    }

    private static final java.util.List<CachedLine> cachedLines = new java.util.ArrayList<>();

    // 图表缓存实例
    private static final ChartCache particlesCache = new ChartCache();
    private static final ChartCache renderTimeCache = new ChartCache();
    private static final ChartCache bufferCache = new ChartCache();
    private static final ChartCache fpsCache = new ChartCache();
    private static long lastUpdateTime = -1;

    public static void register() {
        HudRenderCallback.EVENT.register(DebugHud::render);
    }

    //? if >=1.21 {
    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        float tickDelta = tickCounter.getTickDelta(false);
        //? } else {
         /*private static void render(DrawContext context, float tickDelta) { 
        *///? }
        ModConfig config = ModConfig.getInstance();
        PerformanceStats stats = PerformanceStats.getInstance();

        // 1. 控制是否开启统计
        boolean showHud = config.getShowDebugHud();
        stats.setEnabled(showHud);

        if (!showHud)
            return;

        // 2. 驱动数据采集 (每 500ms 一次)
        stats.update();

        // 3. 检查是否需要更新视图缓存 (基于 stats 的更新时间戳)
        long statsTime = stats.getLastHistoryUpdateTime();
        if (statsTime != lastUpdateTime) {
            updateCaches(stats, config);
            lastUpdateTime = statsTime;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        //? if >=1.21 {
        if (client.getDebugHud().shouldShowDebugHud()) {
            return;
        }
        //? } else {
         /*if (client.options.debugEnabled) {
            return;
            } 
        *///? }

        TextRenderer textRenderer = client.textRenderer;
        int y = PADDING;
        int x = PADDING;

        // 绘制缓存的文本
        for (CachedLine line : cachedLines) {
            context.drawTextWithShadow(textRenderer, line.text, x, y, line.color);
            y += 10;
        }

        // 绘制图表 (如果启用)
        // 注意：cachedLines 包含了所有文本，所以图表紧接在文本之后
        if (config.getShowCharts()) {
            // 增加一点间距
            y += PADDING;
            drawPerformanceCharts(context, textRenderer, x, y);
        }
    }

    /**
     * 更新缓存
     * 
     * @param stats  统计
     * @param config 配置
     */
    private static void updateCaches(PerformanceStats stats, ModConfig config) {
        // --- 1. 更新文本缓存 ---
        cachedLines.clear();

        // Header
        cachedLines.add(new CachedLine(
                String.format("Nebula %s | FPS: %.0f", Nebula.MOD_VERSION, stats.getCurrentFps()), TEXT_COLOR));

        MinecraftClient client = MinecraftClient.getInstance();
        Camera camera = client.gameRenderer.getCamera();

        if (camera != null && camera.isReady()) {
            Quaternionf q = camera.getRotation();
            // 1. 显示原始四元数
            // 格式: Q(x, y, z, w)
            cachedLines.add(new CachedLine(
                    String.format("Q: (%.2f, %.2f, %.2f, %.2f)", q.x(), q.y(), q.z(), q.w()),
                    0xFF00FF // 紫色方便观察
            ));

            // 2. 模拟 calculateCameraVectors 的计算过程并显示结果
            Vector3f vRight = new Vector3f(1.0f, 0.0f, 0.0f);
            Vector3f vUp = new Vector3f(0.0f, 1.0f, 0.0f);
            Quaternionf qCopy = new Quaternionf(q);
            vRight.rotate(qCopy);
            vUp.rotate(qCopy);
            cachedLines.add(new CachedLine(
                    String.format("Right: (%.2f, %.2f, %.2f)", vRight.x(), vRight.y(), vRight.z()),
                    0xFFAAAA // 红色代表 X轴
            ));
            cachedLines.add(new CachedLine(
                    String.format("Up:    (%.2f, %.2f, %.2f)", vUp.x(), vUp.y(), vUp.z()),
                    0xAAFFAA // 绿色代表 Y轴
            ));
        }

        if (config.getShowPerformanceStats()) {
            cachedLines.add(new CachedLine(
                    String.format("Particles: %,d | Instances: %d", stats.getParticleCount(), stats.getInstanceCount()),
                    0xAAAAFF));
            cachedLines.add(
                    new CachedLine(String.format("Buffer: %s / %s", stats.formatBufferSize(stats.getUsedBufferBytes()),
                            stats.formatBufferSize(stats.getBufferSizeBytes())), 0xAAFFAA));
            cachedLines.add(new CachedLine(
                    String.format("Render: %.2fms | Upload: %.2fms", stats.getRenderTimeMs(), stats.getUploadTimeMs()),
                    0xFFAAAA));

            int err = stats.getLastGlError();
            cachedLines.add(new CachedLine(
                    String.format("Mode: %s | GL Err: 0x%X", stats.isIrisMode() ? "Iris" : "Standard", err),
                    err == 0 ? 0xFFFFFF : 0xFF5555));

            // 显示当前混合模式
            String blendModeStr = config.getBlendMode().name();
            String cullingBehaviorStr = config.getCullingBehavior().name();
            cachedLines.add(new CachedLine(
                    String.format("Blend: %s | Culling: %s", blendModeStr, cullingBehaviorStr),
                    0xFFAA55));

            cachedLines.add(new CachedLine(
                    String.format("Shader program: %d (ours: %d)", stats.getShaderProgram(), stats.getShaderProgram()),
                    0xCCCCCC));
            cachedLines.add(new CachedLine(String.format("VAO: %d", stats.getVao()), 0xCCCCCC));
            cachedLines.add(new CachedLine(
                    String.format("SSBO: %d, size: %d bytes", stats.getSsbo(), stats.getUsedBufferBytes()), 0xCCCCCC));
            cachedLines.add(new CachedLine(String.format("Origin: (%.2f, %.2f, %.2f)", stats.getOriginX(),
                    stats.getOriginY(), stats.getOriginZ()), 0xCCCCCC));
            cachedLines.add(new CachedLine(String.format("Target FBO: %d", stats.getTargetFboId()),
                    stats.getTargetFboId() >= 0 ? 0xAAFFAA : 0xFFAAAA));
            cachedLines.add(new CachedLine(
                    String.format("Emissive: %.1f | Game Render: %b", stats.getEmissiveStrength(),
                            config.shouldRenderInGame()),
                    config.shouldRenderInGame() ? 0xAAFFAA : 0xFFAAAA));
        }

        if (config.getShowCharts()) {
            particlesCache.update(stats.getParticleCountHistory(), 1.0);
            renderTimeCache.update(stats.getRenderTimeHistory(), 1.0);
            bufferCache.update(stats.getUsedBufferHistory(), 1.0 / (1024.0 * 1024.0));
            fpsCache.update(stats.getFpsHistory(), 1.0);
        }
    }

    private static void drawPerformanceCharts(DrawContext context, TextRenderer textRenderer, int x, int startY) {
        int currentY = startY;

        drawChart(context, textRenderer, x, currentY, "Particles", particlesCache, 0xFF5555FF, true);
        drawChart(context, textRenderer, x + CHART_WIDTH + PADDING, currentY, "Render Time", renderTimeCache,
                0xFFFF5555, false);

        currentY += CHART_HEIGHT + PADDING;

        drawChart(context, textRenderer, x, currentY, "Buffer (MB)", bufferCache, 0xFF55FF55, false);
        drawChart(context, textRenderer, x + CHART_WIDTH + PADDING, currentY, "FPS", fpsCache, 0xFFFFAA00, false);
    }

    /**
     * 绘制图表
     * 
     * @param context      绘图上下文
     * @param textRenderer 文本渲染器
     * @param x            X 坐标
     * @param y            Y 坐标
     * @param label        标题
     * @param cache        缓存
     * @param colorARGB    颜色
     * @param isInteger    是否为整数
     */
    private static void drawChart(DrawContext context, TextRenderer textRenderer, int x, int y, String label,
            ChartCache cache, int colorARGB, boolean isInteger) {
        // 背景
        context.fill(x, y, x + CHART_WIDTH, y + CHART_HEIGHT, BG_COLOR);

        // 标题
        context.drawTextWithShadow(textRenderer, label, x + 2, y + 2, 0xFFFFFF);

        if (cache.isEmpty())
            return;

        // 当前值文本 (使用缓存的 lastValue)
        String valStr;
        if (isInteger) {
            valStr = String.format("%.0f", cache.lastValue);
        } else {
            valStr = String.format("%.1f", cache.lastValue);
        }
        context.drawTextWithShadow(textRenderer, valStr, x + CHART_WIDTH - textRenderer.getWidth(valStr) - 2, y + 2,
                colorARGB);

// 绘制折线
        Tessellator tessellator = Tessellator.getInstance();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.lineWidth(1.0f);

        float r = ((colorARGB >> 16) & 0xFF) / 255f;
        float g = ((colorARGB >> 8) & 0xFF) / 255f;
        float b = (colorARGB & 0xFF) / 255f;
        float a = ((colorARGB >> 24) & 0xFF) / 255f;

        float chartAreaH = CHART_HEIGHT - 12;
        float chartAreaY = y + 12;
        int count = cache.normalizedValues.size();
        float xStep = (float) CHART_WIDTH / (Math.max(1, count - 1));

        //? if >=1.21 {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            float valNorm = cache.normalizedValues.get(i);
            float px = x + i * xStep;
            float py = chartAreaY + chartAreaH - (valNorm * chartAreaH);
            py = MathHelper.clamp(py, chartAreaY, chartAreaY + chartAreaH);
            buffer.vertex(px, py, 0.0f).color(r, g, b, a);
        }
        // 1.21.1 必须通过 BufferRenderer 绘制
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        //? } else {
         /*BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            float valNorm = cache.normalizedValues.get(i);
            float px = x + i * xStep;
            float py = chartAreaY + chartAreaH - (valNorm * chartAreaH);
            py = MathHelper.clamp(py, chartAreaY, chartAreaY + chartAreaH);

            buffer.vertex(px, py, 0.0f).color(r, g, b, a).next();
        }
        tessellator.draw(); 
        *///? }
        RenderSystem.disableBlend();
    }

    // 简单图表数据缓存类
    private static class ChartCache {
        // 存储归一化后的 Y 值 (0.0 - 1.0)
        final java.util.ArrayList<Float> normalizedValues = new java.util.ArrayList<>(300);
        double lastValue = 0;

        boolean isEmpty() {
            return normalizedValues.isEmpty();
        }

        void update(List<? extends Number> data, double scale) {
            normalizedValues.clear();
            if (data == null || data.isEmpty())
                return;

            // 1. 获取最新值
            lastValue = data.getLast().doubleValue() * scale;

            // 2. 计算最大值 (O(N))
            double maxVal = 0;
            // 必须使用 for-each 遍历 LinkedList，避免 O(N^2)
            for (Number n : data) {
                double v = n.doubleValue() * scale;
                if (v > maxVal)
                    maxVal = v;
            }
            if (maxVal <= 0.0001)
                maxVal = 1.0;
            maxVal *= 1.1; // padding

            // 3. 计算并存储归一化值 (O(N))
            for (Number n : data) {
                double v = n.doubleValue() * scale;
                normalizedValues.add((float) (v / maxVal));
            }
        }
    }
}