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
    private double renderTimeMs = 0;
    private double uploadTimeMs = 0;
    private double drawCallTimeMs = 0;
    private float tickDelta = 0;

    // ========== 历史数据（懒加载） ==========
    private LinkedList<Integer> particleCountHistory;
    private LinkedList<Double> renderTimeHistory;
    private LinkedList<Double> uploadTimeHistory;
    private LinkedList<Integer> usedBufferHistory;
    private LinkedList<Double> fpsHistory;

    // ========== FPS 计算 ==========
    private long lastFpsUpdateTime = 0;
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
        uploadTimeHistory = new LinkedList<>();
        usedBufferHistory = new LinkedList<>();
        fpsHistory = new LinkedList<>();

        for (int i = 0; i < HISTORY_SIZE; i++) {
            particleCountHistory.add(0);
            renderTimeHistory.add(0.0);
            uploadTimeHistory.add(0.0);
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
    }

    public void endFrame() {
        if (!enabled) {
            return;
        }

        lastFrameTimeNs = System.nanoTime() - frameStartTimeNs;
        renderTimeMs = lastFrameTimeNs / 1_000_000.0;

        // 更新历史数据
        addToHistory(particleCountHistory, particleCount);
        addToHistory(renderTimeHistory, renderTimeMs);
        addToHistory(uploadTimeHistory, uploadTimeMs);
        addToHistory(usedBufferHistory, usedBufferBytes);

        // 使用 MinecraftClient 的 FPS 计数器
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            // 从 MC 获取当前 FPS（更精确）
            currentFps = client.getCurrentFps();

            // 获取 tickDelta
            tickDelta = client.getTickDelta();
        }

        // 每 500ms 更新一次 FPS 历史
        long now = System.currentTimeMillis();
        if (now - lastFpsUpdateTime >= 500) {
            addToHistory(fpsHistory, currentFps);
            lastFpsUpdateTime = now;
        }
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

    // ========== 历史数据 Getters ==========

    public LinkedList<Integer> getParticleCountHistory() {
        return new LinkedList<>(particleCountHistory);
    }

    public LinkedList<Double> getRenderTimeHistory() {
        return new LinkedList<>(renderTimeHistory);
    }

    public LinkedList<Double> getUploadTimeHistory() {
        return new LinkedList<>(uploadTimeHistory);
    }

    public LinkedList<Integer> getUsedBufferHistory() {
        return new LinkedList<>(usedBufferHistory);
    }

    public LinkedList<Double> getFpsHistory() {
        return new LinkedList<>(fpsHistory);
    }

    // ========== 格式化方法 ==========

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
