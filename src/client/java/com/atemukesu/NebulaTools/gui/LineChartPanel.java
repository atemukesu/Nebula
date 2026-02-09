package com.atemukesu.NebulaTools.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.LinkedList;

/**
 * 折线图面板
 * 支持实时更新的性能数据可视化
 */
public class LineChartPanel extends JPanel {

    private LinkedList<? extends Number> data;
    private String title;
    private String unit;
    private Color lineColor;
    private Color fillColor;
    private double minValue = 0;
    private double maxValue = 100;
    private boolean autoScale = true;

    // 图表边距
    private static final int PADDING_LEFT = 60;
    private static final int PADDING_RIGHT = 20;
    private static final int PADDING_TOP = 30;
    private static final int PADDING_BOTTOM = 30;

    public LineChartPanel(String title, String unit, Color lineColor) {
        this.title = title;
        this.unit = unit;
        this.lineColor = lineColor;
        this.fillColor = new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 50);
        this.data = new LinkedList<>();

        setBackground(new Color(30, 30, 35));
        setPreferredSize(new Dimension(400, 150));
    }

    public void setData(LinkedList<? extends Number> data) {
        this.data = data;
        if (autoScale && data != null && !data.isEmpty()) {
            calculateScale();
        }
        repaint();
    }

    public void setAutoScale(boolean autoScale) {
        this.autoScale = autoScale;
    }

    public void setRange(double min, double max) {
        this.minValue = min;
        this.maxValue = max;
        this.autoScale = false;
    }

    private void calculateScale() {
        double max = 0;
        for (Number n : data) {
            if (n.doubleValue() > max) {
                max = n.doubleValue();
            }
        }
        // 添加 10% 余量
        this.maxValue = max * 1.1;
        if (this.maxValue < 1)
            this.maxValue = 1;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();
        int chartWidth = width - PADDING_LEFT - PADDING_RIGHT;
        int chartHeight = height - PADDING_TOP - PADDING_BOTTOM;

        // 绘制标题
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2d.drawString(title, PADDING_LEFT, 18);

        // 绘制当前值
        if (data != null && !data.isEmpty()) {
            Number current = data.getLast();
            String valueStr = formatValue(current.doubleValue()) + " " + unit;
            FontMetrics fm = g2d.getFontMetrics();
            g2d.setColor(lineColor);
            g2d.drawString(valueStr, width - PADDING_RIGHT - fm.stringWidth(valueStr), 18);
        }

        // 绘制网格背景
        g2d.setColor(new Color(50, 50, 55));
        g2d.fillRect(PADDING_LEFT, PADDING_TOP, chartWidth, chartHeight);

        // 绘制网格线
        g2d.setColor(new Color(70, 70, 75));
        int gridLines = 4;
        for (int i = 0; i <= gridLines; i++) {
            int y = PADDING_TOP + (chartHeight * i / gridLines);
            g2d.drawLine(PADDING_LEFT, y, width - PADDING_RIGHT, y);

            // Y轴刻度
            double value = maxValue - (maxValue - minValue) * i / gridLines;
            g2d.setColor(new Color(150, 150, 150));
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2d.drawString(formatValue(value), 5, y + 4);
            g2d.setColor(new Color(70, 70, 75));
        }

        // 绘制折线和填充区域
        if (data != null && data.size() > 1) {
            Path2D.Double linePath = new Path2D.Double();
            Path2D.Double fillPath = new Path2D.Double();

            double xStep = (double) chartWidth / (data.size() - 1);

            // 起始点
            double firstValue = data.getFirst().doubleValue();
            double firstY = PADDING_TOP + chartHeight - (firstValue - minValue) / (maxValue - minValue) * chartHeight;

            linePath.moveTo(PADDING_LEFT, firstY);
            fillPath.moveTo(PADDING_LEFT, PADDING_TOP + chartHeight);
            fillPath.lineTo(PADDING_LEFT, firstY);

            int i = 1;
            for (Number n : data) {
                if (i == 1) {
                    i++;
                    continue;
                } // 跳过第一个

                double x = PADDING_LEFT + (i - 1) * xStep;
                double value = n.doubleValue();
                double y = PADDING_TOP + chartHeight - (value - minValue) / (maxValue - minValue) * chartHeight;

                // 限制在图表范围内
                y = Math.max(PADDING_TOP, Math.min(PADDING_TOP + chartHeight, y));

                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
                i++;
            }

            // 完成填充路径
            fillPath.lineTo(PADDING_LEFT + chartWidth, PADDING_TOP + chartHeight);
            fillPath.closePath();

            // 绘制填充
            g2d.setColor(fillColor);
            g2d.fill(fillPath);

            // 绘制线条
            g2d.setColor(lineColor);
            g2d.setStroke(new BasicStroke(2f));
            g2d.draw(linePath);
        }

        // 绘制边框
        g2d.setColor(new Color(80, 80, 85));
        g2d.drawRect(PADDING_LEFT, PADDING_TOP, chartWidth, chartHeight);

        g2d.dispose();
    }

    private String formatValue(double value) {
        if (value >= 1000000) {
            return String.format("%.1fM", value / 1000000);
        } else if (value >= 1000) {
            return String.format("%.1fK", value / 1000);
        } else if (value >= 100) {
            return String.format("%.0f", value);
        } else if (value >= 10) {
            return String.format("%.1f", value);
        } else {
            return String.format("%.2f", value);
        }
    }
}
