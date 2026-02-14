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