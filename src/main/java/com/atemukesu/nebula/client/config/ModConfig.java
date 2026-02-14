package com.atemukesu.nebula.client.config;

import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.enums.CullingBehavior;
import com.atemukesu.nebula.client.render.GpuParticleRenderer;

import net.minecraft.client.MinecraftClient;

public class ModConfig {
    private static ModConfig INSTANCE;

    private boolean renderInGame;

    // HUD 选项
    private boolean showDebugHud;
    private boolean showPerformanceStats;
    private boolean showCharts;

    // 渲染选项
    private BlendMode blendMode;
    private float emissiveStrength;
    private CullingBehavior cullingBehavior;

    public ModConfig() {
        // 默认在游戏内渲染粒子
        this.renderInGame = true;
        // 默认不显示调试HUD
        this.showDebugHud = false;
        this.showPerformanceStats = true;
        this.showCharts = true;
        // 默认使用普通混合
        this.blendMode = BlendMode.ALPHA;
        // 默认使用用户自定义的亮度
        this.emissiveStrength = 2.0f;
        this.cullingBehavior = CullingBehavior.SIMULATE_ONLY;
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

    public void setRenderInGame(Boolean renderInGame) {
        this.renderInGame = renderInGame;
    }

    public boolean getShowDebugHud() {
        return this.showDebugHud;
    }

    public void setShowDebugHud(Boolean showDebugHud) {
        this.showDebugHud = showDebugHud;
    }

    public boolean getShowPerformanceStats() {
        return this.showPerformanceStats;
    }

    public void setShowPerformanceStats(Boolean show) {
        this.showPerformanceStats = show;
    }

    public boolean getShowCharts() {
        return this.showCharts;
    }

    public void setShowCharts(Boolean show) {
        this.showCharts = show;
    }

    /**
     * 获取粒子混合模式
     */
    public BlendMode getBlendMode() {
        return this.blendMode;
    }

    /**
     * 设置粒子混合模式
     */
    public void setBlendMode(BlendMode blendMode) {
        this.blendMode = blendMode;
        // 预加载 OIT
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.blendMode == BlendMode.OIT && client.getWindow() != null) {
            int w = client.getWindow().getFramebufferWidth();
            int h = client.getWindow().getFramebufferHeight();
            if (w > 0 && h > 0) {
                GpuParticleRenderer.preloadOIT(w, h);
            }
        }
    }

    /**
     * 获取粒子发光强度
     * 
     * @return 粒子发光强度
     */
    public float getEmissiveStrength() {
        return emissiveStrength;
    }

    /**
     * 设置粒子发光强度
     * 
     * @param emissiveStrength 粒子发光强度
     */
    public void setEmissiveStrength(Float emissiveStrength) {
        this.emissiveStrength = emissiveStrength;
    }

    // 粒子被 AABB 包围盒测试剔除时的处理方式

    /**
     * 获取粒子被 AABB 包围盒测试剔除时的处理方式
     * 
     * @return 粒子被 AABB 包围盒测试剔除时的处理方式
     */
    public CullingBehavior getCullingBehavior() {
        return cullingBehavior;
    }

    /**
     * 设置粒子被 AABB 包围盒测试剔除时的处理方式
     * 
     * @param cullingBehavior 粒子被 AABB 包围盒测试剔除时的处理方式
     */
    public void setCullingBehavior(CullingBehavior cullingBehavior) {
        this.cullingBehavior = cullingBehavior;
    }
}
