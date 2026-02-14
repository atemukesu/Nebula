package com.atemukesu.nebula.client.render;

import java.util.List;

/**
 * 纹理图集映射表 (Immutable Snapshot)
 * <p>
 * 这个类在创建后就是不可变的。
 * 它被传递给 NblStreamer，确保 Streamer 拥有独立的纹理索引数据，
 * 不受主线程重置全局 TextureManager 的影响。
 * </p>
 */
public class TextureAtlasMap {
    private final LayerData[] layers;

    /**
     * 构造函数
     * 
     * @param infoList 纹理图集信息列表
     */
    public TextureAtlasMap(List<ParticleTextureManager.TextureLayerInfo> infoList) {
        // 将 List 转为数组，进一步提升访问性能 (去虚方法调用)
        this.layers = new LayerData[infoList.size()];
        for (int i = 0; i < infoList.size(); i++) {
            ParticleTextureManager.TextureLayerInfo info = infoList.get(i);
            this.layers[i] = new LayerData(info.startLayer, info.totalFrames);
        }
    }

    /**
     * 获取图集中的纹理坐标
     * 
     * @param texId  纹理ID
     * @param seqIdx 序列索引
     * @return 纹理坐标
     */
    public float getLayer(int texId, int seqIdx) {
        if (texId < 0 || texId >= layers.length)
            return 0;

        LayerData data = layers[texId];
        return data.start + (seqIdx % data.count);
    }

    private static class LayerData {
        final int start;
        final int count;

        LayerData(int start, int count) {
            this.start = start;
            this.count = count;
        }
    }

    public static final TextureAtlasMap EMPTY = new TextureAtlasMap(java.util.Collections.emptyList());
}
