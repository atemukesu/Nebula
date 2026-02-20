/**
 * [AI GENERATION & MODIFICATION NOTICE / AI 编写与调整声明]
 *
 * ENGLISH:
 * This code was authored, modified, optimized, or adjusted by one or more of the
 * following AI models: Gemini 3 Pro, Gemini 3 Flash, and Claude 3.5 Opus.
 * Although efforts have been made to ensure functionality through testing, the
 * code is provided "AS IS". Please perform a thorough code audit before using,
 * reading, distributing, or modifying.
 *
 * 中文版：
 * 本代码由以下一个或多个 AI 模型编写、修改、优化或调整：
 * Gemini 3 Pro, Gemini 3 Flash, 以及 Claude 3.5 Opus。
 * 代码虽经努力测试以确保其功能实现，但仍按“原样”提供。在您进行使用、阅读、
 * 分发或修改前，请务必进行仔细的代码审计与测试。
 *
 * ----------------------------------------------------------------------------------
 * [LICENSE & WARRANTY / 开源协议与免责声明]
 *
 * ENGLISH:
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details <https://www.gnu.org/licenses/>.
 *
 * 中文版：
 * 本程序为自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可协议（GPL）条款
 *（可以选择版本 3 或更高版本）对本程序进行重新分发和/或修改。
 *
 * 本程序的发布是希望其能发挥作用，但【不附带任何担保】，甚至不包括对【适销性】或
 * 【特定用途适用性】的暗示保证。开发者不对因使用本代码产生的任何损害承担责任。
 * 详情请参阅 GNU 通用公共许可协议官方页面 <https://www.gnu.org/licenses/>。
 * ----------------------------------------------------------------------------------
 */

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
    
    // 测试选项
    private boolean syncSingleplayerAnimations;

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
        // 默认关闭单人模式动画同步（测试用）
        this.syncSingleplayerAnimations = false;
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
    
    /**
     * 获取单人模式是否启用动画同步
     * 
     * @return 单人模式动画同步开关
     */
    public boolean getSyncSingleplayerAnimations() {
        return this.syncSingleplayerAnimations;
    }

    /**
     * 设置单人模式动画同步开关
     * 
     * @param syncSingleplayerAnimations 是否启用单人模式动画同步
     */
    public void setSyncSingleplayerAnimations(Boolean syncSingleplayerAnimations) {
        this.syncSingleplayerAnimations = syncSingleplayerAnimations;
    }
}
