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

package com.atemukesu.nebula.client.mixin;

import com.atemukesu.nebula.client.util.CurrentTimeUtil;
import com.replaymod.render.RenderSettings;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replaystudio.pathing.path.Timeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 这是一个针对Replay Mod的“软依赖”Mixin。
 * 它通过nebulaMixinPlugin来保证只在Replay Mod存在时才被应用。
 * 它的作用是精确地监听渲染的开始和结束事件，并将这些事件通知给ReplayModUtil。
 *
 * remap = false 是必须的，因为我们Mixin的目标是另一个Mod，而不是经过映射的Minecraft本身的代码。
 */
@Mixin(value = com.replaymod.render.rendering.VideoRenderer.class, remap = false)
public abstract class VideoRendererMixin {

    /**
     * 注入到 VideoRenderer 的构造函数执行完毕后。
     * 当一个 VideoRenderer 对象被创建时，就标志着渲染即将开始。
     * 这是触发 onRenderStart 的完美时机。
     * 
     * @param settings 构造函数参数，我们可以直接用它来获取FPS，无需任何反射。
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onRenderStart(RenderSettings settings, ReplayHandler replayHandler, Timeline timeline,
            CallbackInfo ci) {
        // 直接从构造函数参数中获取FPS
        int fps = settings.getFramesPerSecond();
        // 通知我们的工具类：渲染已开始，并传递准确的FPS
        CurrentTimeUtil.onRenderStart(fps);
    }

    /**
     * 注入到 VideoRenderer 的 finish() 方法执行完毕后。
     * finish() 方法在渲染循环结束后被调用，用于各种清理工作。
     * 这是触发 onRenderEnd 的完美时机。
     */
    @Inject(method = "finish", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        // 通知我们的工具类：渲染已结束
        CurrentTimeUtil.onRenderEnd();
    }
}