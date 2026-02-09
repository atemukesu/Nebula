package com.atemukesu.nebula.config;

public class ModConfig {
    private static ModConfig INSTANCE;

    private boolean renderInGame;

    // HUD 选项
    private boolean showDebugHud;
    private boolean showPerformanceStats;
    private boolean showCharts;

    public ModConfig() {
        // 默认在游戏内渲染粒子
        this.renderInGame = true;
        // 默认不显示调试HUD
        this.showDebugHud = false;
        this.showPerformanceStats = true;
        this.showCharts = true;
    }

    public static ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
        }
        return INSTANCE;
    }

    public static void setInstance(ModConfig config) {
        INSTANCE = config;
    }

    public boolean shouldRenderInGame() {
        return this.renderInGame;
    }

    public void setRenderInGame(boolean renderInGame) {
        this.renderInGame = renderInGame;
    }

    public boolean getShowDebugHud() {
        return this.showDebugHud;
    }

    public void setShowDebugHud(boolean showDebugHud) {
        this.showDebugHud = showDebugHud;
    }

    public boolean getShowPerformanceStats() {
        return this.showPerformanceStats;
    }

    public void setShowPerformanceStats(boolean show) {
        this.showPerformanceStats = show;
    }

    public boolean getShowCharts() {
        return this.showCharts;
    }

    public void setShowCharts(boolean show) {
        this.showCharts = show;
    }
}