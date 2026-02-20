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

package com.atemukesu.nebula.client.gui.tools;

import net.minecraft.client.MinecraftClient;

import java.util.LinkedList;

/**
 * 性能统计数据收集器
 * 使用环形缓冲区存储历史数据用于绘制折线图
 * 
 * 注意：只有当 enabled=true 时才会收集数据，避免不必要的性能开销
 */
public class PerformanceStats {

    private static final PerformanceStats INSTANCE = new PerformanceStats();

    // 历史数据容量（帧数）
    public static final int HISTORY_SIZE = 300;

    // ========== 启用开关 ==========
    private boolean enabled = false;
    private boolean historyInitialized = false;

    // ========== 渲染器统计 ==========
    private int particleCount = 0;
    private int instanceCount = 0;
    private int bufferSizeBytes = 0;
    private int usedBufferBytes = 0;
    private boolean pmbSupported = false;
    private boolean usingFallback = false;
    private int shaderProgram = 0;
    private int vao = 0;
    private int ssbo = 0;
    private boolean isIrisMode = false;

    // ========== 帧时间统计 ==========
    private long lastFrameTimeNs = 0;
    private long frameStartTimeNs = 0;
    private long uploadStartTimeNs = 0;
    private double renderTimeMs = 0;
    private double uploadTimeMs = 0;
    private double drawCallTimeMs = 0;
    private float tickDelta = 0;

    // ========== 历史数据（懒加载） ==========
    private LinkedList<Integer> particleCountHistory;
    private LinkedList<Double> renderTimeHistory;
    private LinkedList<Integer> usedBufferHistory;
    private LinkedList<Double> fpsHistory;

    private long lastHistoryUpdateTime = 0;
    private double currentFps = 0;

    // ========== GL 错误 ==========
    private int lastGlError = 0;
    private String lastGlErrorMessage = "";

    private PerformanceStats() {
        // 不在构造函数中初始化历史数据，懒加载
    }

    public static PerformanceStats getInstance() {
        return INSTANCE;
    }

    /**
     * 启用性能统计收集
     * 由 NebulaToolsWindow 打开时调用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && !historyInitialized) {
            initializeHistory();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void initializeHistory() {
        if (historyInitialized)
            return;

        particleCountHistory = new LinkedList<>();
        renderTimeHistory = new LinkedList<>();
        usedBufferHistory = new LinkedList<>();
        fpsHistory = new LinkedList<>();

        for (int i = 0; i < HISTORY_SIZE; i++) {
            particleCountHistory.add(0);
            renderTimeHistory.add(0.0);
            usedBufferHistory.add(0);
            fpsHistory.add(0.0);
        }
        historyInitialized = true;
    }

    // ========== 帧生命周期 ==========

    public void beginFrame() {
        if (!enabled) {
            return;
        }
        frameStartTimeNs = System.nanoTime();

        // 重置数据
        uploadTimeMs = 0;
        renderTimeMs = 0;
    }

    public void endFrame() {
        if (!enabled) {
            return;
        }

        lastFrameTimeNs = System.nanoTime() - frameStartTimeNs;
        renderTimeMs = lastFrameTimeNs / 1_000_000.0;
    }

    public void beginDataUpload() {
        if (!enabled)
            return;
        uploadStartTimeNs = System.nanoTime();
    }

    public void endDataUpload() {
        if (!enabled)
            return;
        long duration = System.nanoTime() - uploadStartTimeNs;
        uploadTimeMs += (duration / 1_000_000.0);
    }

    /**
     * 每帧更新逻辑，由 DebugHud 调用
     * 只有当 HUD 开启时才会调用，符合“不显示不统计”的要求
     */
    public void update() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastHistoryUpdateTime < 500) {
            return;
        }
        lastHistoryUpdateTime = now;

        // 使用 MinecraftClient 的 FPS 计数器
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            currentFps = client.getCurrentFps();
            //? if >=1.21 {
            tickDelta = client.getRenderTickCounter().getTickDelta(false);
            //? } else {
            
            /*tickDelta = client.getTickDelta();
            
            *///? }
        }

        // 更新历史数据
        addToHistory(particleCountHistory, particleCount);
        addToHistory(renderTimeHistory, renderTimeMs);
        addToHistory(usedBufferHistory, usedBufferBytes);
        addToHistory(fpsHistory, currentFps);
    }

    private <T> void addToHistory(LinkedList<T> list, T value) {
        if (list == null)
            return; // 安全检查
        if (list.size() >= HISTORY_SIZE) {
            list.removeFirst();
        }
        list.addLast(value);
    }

    // ========== Setters ==========

    public void setParticleCount(int count) {
        this.particleCount = count;
    }

    public void setInstanceCount(int count) {
        this.instanceCount = count;
    }

    public void setBufferSizeBytes(int size) {
        this.bufferSizeBytes = size;
    }

    public void setUsedBufferBytes(int size) {
        this.usedBufferBytes = size;
    }

    public void setPmbSupported(boolean supported) {
        this.pmbSupported = supported;
    }

    public void setUsingFallback(boolean fallback) {
        this.usingFallback = fallback;
    }

    public void setShaderProgram(int program) {
        this.shaderProgram = program;
    }

    public void setVao(int vao) {
        this.vao = vao;
    }

    public void setSsbo(int ssbo) {
        this.ssbo = ssbo;
    }

    public void setIrisMode(boolean irisMode) {
        this.isIrisMode = irisMode;
    }

    public void setUploadTimeMs(double time) {
        this.uploadTimeMs = time;
    }

    public void setDrawCallTimeMs(double time) {
        this.drawCallTimeMs = time;
    }

    public void setLastGlError(int error, String message) {
        this.lastGlError = error;
        this.lastGlErrorMessage = message;
    }

    // ========== 调试数据 ==========
    private double originX, originY, originZ;
    private int targetFboId = -1;

    public void setOrigin(double x, double y, double z) {
        this.originX = x;
        this.originY = y;
        this.originZ = z;
    }

    public void setTargetFboId(int id) {
        this.targetFboId = id;
    }

    public double getOriginX() {
        return originX;
    }

    public double getOriginY() {
        return originY;
    }

    public double getOriginZ() {
        return originZ;
    }

    public int getTargetFboId() {
        return targetFboId;
    }

    private float emissiveStrength = 1.0f;

    public void setEmissiveStrength(float strength) {
        this.emissiveStrength = strength;
    }

    public float getEmissiveStrength() {
        return emissiveStrength;
    }

    // ========== Getters ==========

    public int getParticleCount() {
        return particleCount;
    }

    public int getInstanceCount() {
        return instanceCount;
    }

    public int getBufferSizeBytes() {
        return bufferSizeBytes;
    }

    public int getUsedBufferBytes() {
        return usedBufferBytes;
    }

    public boolean isPmbSupported() {
        return pmbSupported;
    }

    public boolean isUsingFallback() {
        return usingFallback;
    }

    public int getShaderProgram() {
        return shaderProgram;
    }

    public int getVao() {
        return vao;
    }

    public int getSsbo() {
        return ssbo;
    }

    public boolean isIrisMode() {
        return isIrisMode;
    }

    public double getRenderTimeMs() {
        return renderTimeMs;
    }

    public double getUploadTimeMs() {
        return uploadTimeMs;
    }

    public double getDrawCallTimeMs() {
        return drawCallTimeMs;
    }

    public float getTickDelta() {
        return tickDelta;
    }

    public double getCurrentFps() {
        return currentFps;
    }

    public int getLastGlError() {
        return lastGlError;
    }

    public String getLastGlErrorMessage() {
        return lastGlErrorMessage;
    }

    public long getLastHistoryUpdateTime() {
        return lastHistoryUpdateTime;
    }

    // ========== 历史数据 Getters ==========

    // 直接返回 List，避免每帧复制带来的性能开销
    // 调用者只能在渲染线程读取，且不应修改

    public LinkedList<Integer> getParticleCountHistory() {
        return particleCountHistory;
    }

    public LinkedList<Double> getRenderTimeHistory() {
        return renderTimeHistory;
    }

    public LinkedList<Integer> getUsedBufferHistory() {
        return usedBufferHistory;
    }

    public LinkedList<Double> getFpsHistory() {
        return fpsHistory;
    }

    // ========== 格式化方法 ==========

    /**
     * 格式化字节大小
     * 
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    public String formatBufferSize(int bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else if (bytes >= 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }
}
