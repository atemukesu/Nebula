package com.atemukesu.nebula.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.atemukesu.nebula.Nebula;

import java.util.List;
import java.util.Set;

/**
 * Mixin 插件，用于实现对可选 Mod 的"软依赖"。
 * 
 * 核心作用是在应用 Mixin 之前进行检查，确保只在目标 Mod 存在时，
 * 针对它们的 Mixin 才会被应用，从而避免 ClassNotFoundException 导致游戏崩溃。
 * 
 * 当前支持的软依赖：
 * - Replay Mod: VideoRendererMixin
 */
public class NebulaMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // === Replay Mod 软依赖 ===
        if (mixinClassName.endsWith("VideoRendererMixin")) {
            boolean replayModLoaded = FabricLoader.getInstance().isModLoaded("replaymod");
            if (replayModLoaded) {
                Nebula.LOGGER.info("[Nebula/Mixin] ✓ Replay Mod detected. Applying mixin: {}", mixinClassName);
            } else {
                Nebula.LOGGER.debug("[Nebula/Mixin] Replay Mod not found. Skipping mixin: {}", mixinClassName);
            }
            return replayModLoaded;
        }

        // 对于所有其他的 Mixin，总是应用
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
        Nebula.LOGGER.info("[Nebula/Mixin] Mixin plugin loaded for package: {}", mixinPackage);
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