package com.atemukesu.nebula.client.enums;

public enum RenderPipeline {
    GPU("gui.nebula.config.render_pipeline.gpu"),
    VANILLA_BATCH("gui.nebula.config.render_pipeline.vanilla_batch");

    private final String translationKey;

    RenderPipeline(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
