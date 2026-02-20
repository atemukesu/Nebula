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
