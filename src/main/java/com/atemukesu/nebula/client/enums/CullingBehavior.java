package com.atemukesu.nebula.client.enums;

public enum CullingBehavior {
    /**
     * 继续文件读取和粒子计算，但跳过渲染。
     * 默认值，对性能要求较高。
     */
    SIMULATE_ONLY,

    /**
     * 完全停止文件读取和粒子计算，以及渲染。进入视野后重新开始。
     * 适用于性能优化。但是可能需要等待寻址粒子，产生轻微卡顿。
     */
    PAUSE_AND_HIDE;

    /**
     * 从字符串名称获取枚举值
     * 
     * @param name 枚举名称
     * @return 枚举值
     */
    public static CullingBehavior fromString(String name) {
        for (CullingBehavior behavior : values()) {
            if (behavior.name().equalsIgnoreCase(name)) {
                return behavior;
            }
        }
        return SIMULATE_ONLY; // 默认返回 SIMULATE_ONLY
    }

    /**
     * 获取下一个枚举值
     * 
     * @return 下一个枚举值
     */
    public CullingBehavior next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * 获取翻译键
     * 
     * @return 翻译键
     */
    public String getTranslationKey() {
        return "gui.nebula.config.culling_behavior." + name().toLowerCase();
    }
}