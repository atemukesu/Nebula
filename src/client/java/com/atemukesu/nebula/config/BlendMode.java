package com.atemukesu.nebula.config;

/**
 * 粒子混合模式枚举
 */
public enum BlendMode {
    /**
     * 加法混合 - 默认模式
     * 优点：粒子自带发光效果，性能最佳
     * 缺点：重叠区域会过亮
     */
    ADDITIVE,

    /**
     * 标准 Alpha 混合
     * 优点：颜色准确，适合不透明粒子
     * 缺点：透明粒子需要正确排序，否则会有渲染错误
     */
    ALPHA,

    /**
     * OIT (Order Independent Transparency) 与顺序无关的透明度
     * 优点：透明粒子无需排序，效果最佳
     * 缺点：需要额外的渲染 Pass，性能开销较大
     */
    OIT;

    /**
     * 获取下一个混合模式（用于循环切换）
     */
    public BlendMode next() {
        BlendMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * 获取翻译键
     */
    public String getTranslationKey() {
        return "gui.nebula.config.blend_mode." + name().toLowerCase();
    }

    /**
     * 从名称获取混合模式
     */
    public static BlendMode fromName(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ADDITIVE; // 默认返回加法混合
        }
    }
}
