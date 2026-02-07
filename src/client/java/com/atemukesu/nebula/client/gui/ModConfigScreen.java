package com.atemukesu.nebula.client.gui;

import com.atemukesu.nebula.config.ConfigManager;
import com.atemukesu.nebula.config.ModConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.lwjgl.glfw.GLFW;

public class ModConfigScreen extends Screen {
    private final Screen parent;
    private final ModConfig config;
    private ButtonWidget renderInGameButton;
    private ButtonWidget debugHudButton;

    public ModConfigScreen(Screen parent) {
        super(Text.translatable("gui.nebula.config.title"));
        this.parent = parent;
        this.config = ModConfig.getInstance();
    }

    @Override
    protected void init() {
        super.init();

        int fieldWidth = 200;
        int fieldX = this.width / 2 - fieldWidth / 2;
        int startY = this.height / 2 - 60;

        // --- Debug HUD 开关按钮 ---
        this.debugHudButton = ButtonWidget.builder(getDebugHudButtonText(), button -> {
            config.setShowDebugHud(!config.getShowDebugHud());
            button.setMessage(getDebugHudButtonText());
        }).dimensions(fieldX, startY, fieldWidth, 20).build();
        this.addDrawableChild(debugHudButton);

        // --- "游戏内渲染" 开关按钮 ---
        this.renderInGameButton = ButtonWidget.builder(getRenderInGameButtonText(), button -> {
            config.setRenderInGame(!config.shouldRenderInGame());
            button.setMessage(getRenderInGameButtonText());
        }).dimensions(fieldX, startY + 25, fieldWidth, 20).build();
        this.addDrawableChild(renderInGameButton);

        // --- 完成按钮 ---
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.nebula.config.done"), button -> {
            this.close();
        }).dimensions(this.width / 2 - 100, this.height / 2 + 60, 200, 20).build());
    }

    private Text getRenderInGameButtonText() {
        boolean enabled = config.shouldRenderInGame();
        Text status = Text.translatable(enabled ? "gui.nebula.config.on" : "gui.nebula.config.off")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED);
        return Text.translatable("gui.nebula.config.render_in_game", status);
    }

    private Text getDebugHudButtonText() {
        boolean enabled = config.getShowDebugHud();
        Text status = Text.translatable(enabled ? "gui.nebula.config.on" : "gui.nebula.config.off")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED);
        return Text.translatable("gui.nebula.config.show_debug_hud", status);
    }

    @Override
    public void close() {
        ConfigManager.saveConfig();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}