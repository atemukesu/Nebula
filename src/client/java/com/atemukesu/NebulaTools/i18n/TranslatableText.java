package com.atemukesu.NebulaTools.i18n;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 自定义翻译文本类
 * 支持英语、中文、日语三语，自动回退到英语
 */
public class TranslatableText {

    private static final Map<String, Properties> LANG_CACHE = new HashMap<>();
    private static String currentLang = "en_us";
    private static boolean initialized = false;

    private final String key;
    private final Object[] args;

    public TranslatableText(String key, Object... args) {
        this.key = key;
        this.args = args;
    }

    /**
     * 初始化语言系统
     */
    public static void init() {
        if (initialized)
            return;

        // 预加载所有语言
        loadLanguage("en_us");
        loadLanguage("zh_cn");
        loadLanguage("ja_jp");

        initialized = true;
    }

    /**
     * 更新当前语言
     */
    public static void updateLanguage() {
        try {
            String mcLang = MinecraftClient.getInstance().getLanguageManager().getLanguage();
            if (mcLang != null) {
                currentLang = mcLang.toLowerCase();
            }
        } catch (Exception e) {
            currentLang = "en_us";
        }
    }

    /**
     * 加载语言文件
     */
    private static void loadLanguage(String lang) {
        if (LANG_CACHE.containsKey(lang))
            return;

        Properties props = new Properties();
        String path = "/assets/nebula/lang/tools/" + lang + ".properties";

        try (InputStream is = TranslatableText.class.getResourceAsStream(path)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // 忽略加载错误
        }

        LANG_CACHE.put(lang, props);
    }

    /**
     * 获取翻译后的字符串
     */
    public String getString() {
        if (!initialized)
            init();
        updateLanguage();

        String result = getTranslation(currentLang);

        // 回退到英语
        if (result == null && !currentLang.equals("en_us")) {
            result = getTranslation("en_us");
        }

        // 如果还是没有，返回 key
        if (result == null) {
            return key;
        }

        // 格式化参数
        if (args != null && args.length > 0) {
            try {
                result = String.format(result, args);
            } catch (Exception e) {
                // 格式化失败，返回原始字符串
            }
        }

        return result;
    }

    private String getTranslation(String lang) {
        Properties props = LANG_CACHE.get(lang);
        if (props == null) {
            loadLanguage(lang);
            props = LANG_CACHE.get(lang);
        }
        return props != null ? props.getProperty(key) : null;
    }

    /**
     * 快捷方法：直接获取翻译
     */
    public static String of(String key, Object... args) {
        return new TranslatableText(key, args).getString();
    }

    @Override
    public String toString() {
        return getString();
    }
}
