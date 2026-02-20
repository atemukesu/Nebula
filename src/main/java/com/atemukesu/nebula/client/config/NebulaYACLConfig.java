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

package com.atemukesu.nebula.client.config;

import com.atemukesu.nebula.client.enums.BlendMode;
import com.atemukesu.nebula.client.enums.CullingBehavior;

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
                                                .option(Option.<Boolean>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.sync_singleplayer"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                                "gui.nebula.config.sync_singleplayer.desc")))
                                                                .binding(false, config::getSyncSingleplayerAnimations,
                                                                                config::setSyncSingleplayerAnimations)
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
                                                                                BlendMode.ALPHA,
                                                                                config::getBlendMode,
                                                                                config::setBlendMode)
                                                                .controller(opt -> EnumControllerBuilder.create(opt)
                                                                                .enumClass(BlendMode.class)
                                                                                .formatValue(mode -> Text.translatable(
                                                                                                mode.getTranslationKey())))
                                                                .build())
                                                .option(Option.<CullingBehavior>createBuilder()
                                                                .name(Text.translatable(
                                                                                "gui.nebula.config.culling_behavior"))
                                                                .description(OptionDescription.of(Text.translatable(
                                                                                "gui.nebula.config.culling_behavior.desc")))
                                                                .binding(
                                                                                CullingBehavior.SIMULATE_ONLY,
                                                                                config::getCullingBehavior,
                                                                                config::setCullingBehavior)
                                                                .controller(opt -> EnumControllerBuilder.create(opt)
                                                                                .enumClass(CullingBehavior.class)
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
                                                                                .range(1.0f, 10.0f)
                                                                                .step(0.1f))
                                                                .build())
                                                .build())

                                .save(ConfigManager::saveConfig)
                                .build()
                                .generateScreen(parent);
        }
}
