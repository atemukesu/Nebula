package com.atemukesu.nebula.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
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
                                                .build())
                                .save(ConfigManager::saveConfig)
                                .build()
                                .generateScreen(parent);
        }
}
