package com.atemukesu.nebula.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class NebulaYACLConfig {
        public static Screen createConfigScreen(Screen parent) {
                ModConfig config = ModConfig.getInstance();

                return YetAnotherConfigLib.createBuilder()
                                .title(Text.translatable("gui.nebula.config.title"))
                                .category(ConfigCategory.createBuilder()
                                                .name(Text.translatable("gui.nebula.config.category.general"))
                                                .option(Option.<Boolean>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.render_in_game"))
                                                                .description(OptionDescription
                                                                                .of(Text.translatable(
                                                                                                "gui.nebula.config.render_in_game.desc")))
                                                                .binding(
                                                                                true,
                                                                                config::shouldRenderInGame,
                                                                                config::setRenderInGame)
                                                                .controller(BooleanControllerBuilder::create)
                                                                .build())
                                                .option(Option.<Boolean>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.show_debug_hud"))
                                                                .description(OptionDescription
                                                                                .of(Text.translatable(
                                                                                                "gui.nebula.config.show_debug_hud.desc")))
                                                                .binding(
                                                                                false,
                                                                                config::getShowDebugHud,
                                                                                config::setShowDebugHud)
                                                                .controller(BooleanControllerBuilder::create)
                                                                .build())
                                                .option(Option.<Boolean>createBuilder()
                                                                .name(Text.translatable("gui.nebula.config.show_stats"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                "gui.nebula.config.show_stats.desc")))
                                                                .binding(true, config::getShowPerformanceStats,
                                                                                config::setShowPerformanceStats)
                                                                .controller(BooleanControllerBuilder::create)
                                                                .build())
                                                .option(Option.<Boolean>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.show_charts"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                "gui.nebula.config.show_charts.desc")))
                                                                .binding(true, config::getShowCharts,
                                                                                config::setShowCharts)
                                                                .controller(BooleanControllerBuilder::create)
                                                                .build())
                                                .build())
                                .category(ConfigCategory.createBuilder()
                                                .name(Text.translatable("gui.nebula.config.category.rendering"))
                                                .option(Option.<BlendMode>createBuilder()
                                                                .name(Text.translatable("gui.nebula.config.blend_mode"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                "gui.nebula.config.blend_mode.desc")))
                                                                .binding(
                                                                                BlendMode.ADDITIVE,
                                                                                config::getBlendMode,
                                                                                config::setBlendMode)
                                                                .controller(opt -> EnumControllerBuilder.create(opt)
                                                                                .enumClass(BlendMode.class)
                                                                                .formatValue(mode -> Text.translatable(
                                                                                                mode.getTranslationKey())))
                                                                .build())
                                                .option(Option.<Float>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.emissive_strength"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                "gui.nebula.config.emissive_strength.desc")))
                                                                .binding(
                                                                                2.0f,
                                                                                config::getEmissiveStrength,
                                                                                config::setEmissiveStrength)
                                                                .controller(opt -> FloatSliderControllerBuilder
                                                                                .create(opt)
                                                                                .min(0.0f)
                                                                                .max(10.0f))
                                                                .build())
                                                .build())

                                .save(ConfigManager::saveConfig)
                                .build()
                                .generateScreen(parent);
        }
}
