package com.atemukesu.NebulaTools.gui;

import com.atemukesu.NebulaTools.i18n.TranslatableText;
import com.atemukesu.NebulaTools.stats.PerformanceStats;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 性能选项卡面板
 * 显示实时性能数据和折线图
 */
public class PerformancePanel extends JPanel {

    private final PerformanceStats stats = PerformanceStats.getInstance();

    // 状态标签
    private JLabel lblParticleCount;
    private JLabel lblInstanceCount;
    private JLabel lblBufferSize;
    private JLabel lblUsedBuffer;
    private JLabel lblBufferMode;
    private JLabel lblRenderMode;
    private JLabel lblRenderTime;
    private JLabel lblUploadTime;
    private JLabel lblFps;
    private JLabel lblGlError;

    // 折线图
    private LineChartPanel chartParticles;
    private LineChartPanel chartRenderTime;
    private LineChartPanel chartBuffer;
    private LineChartPanel chartFps;

    // 更新定时器
    private Timer updateTimer;

    public PerformancePanel() {
        setLayout(new BorderLayout(10, 10));
        setBackground(new Color(40, 40, 45));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // 上部：状态信息
        JPanel infoPanel = createInfoPanel();
        add(infoPanel, BorderLayout.NORTH);

        // 中部：折线图
        JPanel chartsPanel = createChartsPanel();
        add(chartsPanel, BorderLayout.CENTER);

        // 启动更新定时器
        startUpdateTimer();
    }

    private JPanel createInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
        panel.setOpaque(false);

        // 第一行：渲染统计
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        row1.setOpaque(false);

        lblParticleCount = createInfoLabel("particles", "0");
        lblInstanceCount = createInfoLabel("instances", "0");
        lblFps = createInfoLabel("fps", "0");
        lblRenderTime = createInfoLabel("render_time", "0 ms");
        lblUploadTime = createInfoLabel("upload_time", "0 ms");

        row1.add(lblParticleCount);
        row1.add(lblInstanceCount);
        row1.add(lblFps);
        row1.add(lblRenderTime);
        row1.add(lblUploadTime);

        // 第二行：GPU 信息
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        row2.setOpaque(false);

        lblBufferMode = createInfoLabel("buffer_mode", "PMB");
        lblBufferSize = createInfoLabel("buffer_size", "0 MB");
        lblUsedBuffer = createInfoLabel("used_buffer", "0 MB");
        lblRenderMode = createInfoLabel("render_mode", "Standard");
        lblGlError = createInfoLabel("gl_error", "None");

        row2.add(lblBufferMode);
        row2.add(lblBufferSize);
        row2.add(lblUsedBuffer);
        row2.add(lblRenderMode);
        row2.add(lblGlError);

        panel.add(row1);
        panel.add(row2);

        return panel;
    }

    private JLabel createInfoLabel(String keyBase, String defaultValue) {
        JLabel label = new JLabel();
        label.setForeground(Color.WHITE);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        updateInfoLabel(label, keyBase, defaultValue);
        return label;
    }

    private void updateInfoLabel(JLabel label, String keyBase, String value) {
        String name = TranslatableText.of("perf." + keyBase);
        label.setText(name + ": " + value);
    }

    private JPanel createChartsPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 2, 10, 10));
        panel.setOpaque(false);

        // 粒子数量图表
        chartParticles = new LineChartPanel(
                TranslatableText.of("perf.chart.particles"),
                "",
                new Color(100, 200, 255));

        // 渲染时间图表
        chartRenderTime = new LineChartPanel(
                TranslatableText.of("perf.chart.render_time"),
                "ms",
                new Color(255, 150, 100));

        // 缓冲区使用图表
        chartBuffer = new LineChartPanel(
                TranslatableText.of("perf.chart.buffer"),
                "MB",
                new Color(150, 255, 150));

        // FPS 图表
        chartFps = new LineChartPanel(
                TranslatableText.of("perf.chart.fps"),
                "",
                new Color(255, 200, 100));
        chartFps.setRange(0, 120);
        chartFps.setAutoScale(false);

        panel.add(chartParticles);
        panel.add(chartRenderTime);
        panel.add(chartBuffer);
        panel.add(chartFps);

        return panel;
    }

    private void startUpdateTimer() {
        updateTimer = new Timer(100, e -> updateData());
        updateTimer.start();
    }

    public void stopUpdateTimer() {
        if (updateTimer != null) {
            updateTimer.stop();
        }
    }

    private void updateData() {
        // 更新标签
        updateInfoLabel(lblParticleCount, "particles",
                String.format("%,d", stats.getParticleCount()));
        updateInfoLabel(lblInstanceCount, "instances",
                String.valueOf(stats.getInstanceCount()));
        updateInfoLabel(lblFps, "fps",
                String.format("%.1f", stats.getCurrentFps()));
        updateInfoLabel(lblRenderTime, "render_time",
                String.format("%.2f ms", stats.getRenderTimeMs()));
        updateInfoLabel(lblUploadTime, "upload_time",
                String.format("%.2f ms", stats.getUploadTimeMs()));

        String bufferMode = stats.isPmbSupported() && !stats.isUsingFallback()
                ? "PMB (Triple Buffer)"
                : "Fallback (glBufferSubData)";
        updateInfoLabel(lblBufferMode, "buffer_mode", bufferMode);

        updateInfoLabel(lblBufferSize, "buffer_size",
                stats.formatBufferSize(stats.getBufferSizeBytes()));
        updateInfoLabel(lblUsedBuffer, "used_buffer",
                stats.formatBufferSize(stats.getUsedBufferBytes()));

        String renderMode = stats.isIrisMode()
                ? TranslatableText.of("perf.mode.iris")
                : TranslatableText.of("perf.mode.standard");
        updateInfoLabel(lblRenderMode, "render_mode", renderMode);

        if (stats.getLastGlError() != 0) {
            lblGlError.setForeground(Color.RED);
            updateInfoLabel(lblGlError, "gl_error",
                    String.format("0x%X", stats.getLastGlError()));
        } else {
            lblGlError.setForeground(new Color(100, 255, 100));
            updateInfoLabel(lblGlError, "gl_error", "OK");
        }

        // 更新折线图
        chartParticles.setData(stats.getParticleCountHistory());
        chartRenderTime.setData(stats.getRenderTimeHistory());

        // 转换缓冲区历史为 MB
        var bufferHistory = stats.getUsedBufferHistory();
        var bufferMbHistory = new java.util.LinkedList<Double>();
        for (Integer bytes : bufferHistory) {
            bufferMbHistory.add(bytes / (1024.0 * 1024.0));
        }
        chartBuffer.setData(bufferMbHistory);

        chartFps.setData(stats.getFpsHistory());
    }
}
