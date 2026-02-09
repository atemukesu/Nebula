package com.atemukesu.NebulaTools.gui;

import com.atemukesu.NebulaTools.i18n.TranslatableText;
import com.atemukesu.NebulaTools.stats.PerformanceStats;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Nebula 工具主窗口
 * 包含多个选项卡用于不同功能
 */
public class NebulaToolsWindow extends JFrame {

    private static NebulaToolsWindow instance;
    private JTabbedPane tabbedPane;
    private PerformancePanel performancePanel;

    private NebulaToolsWindow() {
        TranslatableText.init();

        setTitle(TranslatableText.of("window.title"));
        setSize(900, 600);
        setMinimumSize(new Dimension(700, 500));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        // 设置深色主题外观
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // 忽略
        }

        // 自定义深色主题颜色
        getContentPane().setBackground(new Color(35, 35, 40));

        initComponents();

        // 窗口事件处理
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // 禁用性能统计收集
                PerformanceStats.getInstance().setEnabled(false);
                if (performancePanel != null) {
                    performancePanel.stopUpdateTimer();
                }
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // 启用性能统计收集
                PerformanceStats.getInstance().setEnabled(true);
            }
        });
    }

    /**
     * 确保图形环境可用
     * Minecraft/Fabric 可能会设置 java.awt.headless=true，我们需要覆盖它
     */
    private static void ensureGraphicsEnvironment() {
        // 强制禁用无头模式
        System.setProperty("java.awt.headless", "false");
    }

    /**
     * 检查是否支持图形界面
     */
    public static boolean isGraphicsSupported() {
        ensureGraphicsEnvironment();
        try {
            return !java.awt.GraphicsEnvironment.isHeadless()
                    && java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment() != null
                    && java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static NebulaToolsWindow getInstance() {
        if (instance == null) {
            if (!isGraphicsSupported()) {
                throw new UnsupportedOperationException("Graphics environment not available (headless mode)");
            }
            instance = new NebulaToolsWindow();
        }
        return instance;
    }

    /**
     * 显示工具窗口
     * 
     * @return true 如果成功打开，false 如果不支持图形界面
     */
    public static boolean showWindow() {
        if (!isGraphicsSupported()) {
            return false;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                NebulaToolsWindow window = getInstance();
                TranslatableText.updateLanguage();
                window.updateTexts();

                // 启用性能统计收集
                PerformanceStats.getInstance().setEnabled(true);

                window.setVisible(true);
                window.toFront();
                window.requestFocus();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return true;
    }

    public static void hideWindow() {
        if (instance != null) {
            // 禁用性能统计收集
            PerformanceStats.getInstance().setEnabled(false);
            instance.setVisible(false);
        }
    }

    private void initComponents() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(new Color(45, 45, 50));
        tabbedPane.setForeground(Color.WHITE);
        tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 13));

        // 性能选项卡
        performancePanel = new PerformancePanel();
        tabbedPane.addTab(TranslatableText.of("tab.performance"),
                createTabIcon(new Color(100, 200, 255)),
                performancePanel);

        // 预留其他选项卡
        JPanel placeholderPanel = createPlaceholderPanel(TranslatableText.of("tab.coming_soon"));
        tabbedPane.addTab(TranslatableText.of("tab.animations"),
                createTabIcon(new Color(255, 150, 100)),
                placeholderPanel);

        JPanel settingsPanel = createPlaceholderPanel(TranslatableText.of("tab.coming_soon"));
        tabbedPane.addTab(TranslatableText.of("tab.settings"),
                createTabIcon(new Color(150, 255, 150)),
                settingsPanel);

        add(tabbedPane);
    }

    private Icon createTabIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(color);
                g2d.fillOval(x + 2, y + 2, 8, 8);
                g2d.dispose();
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        };
    }

    private JPanel createPlaceholderPanel(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(40, 40, 45));

        JLabel label = new JLabel(message, SwingConstants.CENTER);
        label.setForeground(new Color(150, 150, 150));
        label.setFont(new Font("SansSerif", Font.ITALIC, 16));
        panel.add(label, BorderLayout.CENTER);

        return panel;
    }

    private void updateTexts() {
        setTitle(TranslatableText.of("window.title"));
        if (tabbedPane.getTabCount() > 0) {
            tabbedPane.setTitleAt(0, TranslatableText.of("tab.performance"));
        }
        if (tabbedPane.getTabCount() > 1) {
            tabbedPane.setTitleAt(1, TranslatableText.of("tab.animations"));
        }
        if (tabbedPane.getTabCount() > 2) {
            tabbedPane.setTitleAt(2, TranslatableText.of("tab.settings"));
        }
    }
}
