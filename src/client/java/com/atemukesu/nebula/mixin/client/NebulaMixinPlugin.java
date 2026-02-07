package com.atemukesu.nebula.mixin.client;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.atemukesu.nebula.Nebula;

import java.util.List;
import java.util.Set;

/**
 * Mixin插件，用于实现对Replay Mod的“软依赖”。
 * 它的核心作用是在应用Mixin之前进行检查，确保只在Replay Mod存在时，
 * 我们针对它的Mixin（VideoRendererMixin）才会被应用，从而避免游戏崩溃。
 */
public class NebulaMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 如果是针对 Replay Mod 的 Mixin
        if (mixinClassName.endsWith("VideoRendererMixin")) {
            // 检查 Fabric 加载器是否加载了 replaymod
            boolean replayModLoaded = FabricLoader.getInstance().isModLoaded("replaymod");
            if (replayModLoaded) {
                Nebula.LOGGER.info("Replay Mod detected. Applying mixin: {}", mixinClassName);
            }
            return replayModLoaded;
        }
        // 对于所有其他的 Mixin，总是应用
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}