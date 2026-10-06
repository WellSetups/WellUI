package com.wellsetups.wellui.platform.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable requested-locale to configured-locale to bundled-English fallback. */
public final class Messages {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private final Map<String, Object> english;
    private final Map<String, Map<String, Object>> locales;
    private final String configured;

    private Messages(Map<String, Object> english, Map<String, Map<String, Object>> locales, String configured) {
        this.english = english;
        this.locales = Map.copyOf(locales);
        this.configured = configured;
    }

    public static Messages load(Path root, String locale) throws IOException {
        if (!locale.matches("[a-z]{2}_[A-Z]{2}")) throw new IOException("config.locale");
        Map<String, Object> english;
        try (var stream = Messages.class.getResourceAsStream("/lang/en_US.yml")) {
            if (stream == null) throw new IOException("locale.bundled_missing");
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            english = Map.copyOf(yaml.getValues(true));
        } catch (InvalidConfigurationException invalid) {
            throw new IOException("locale.invalid", invalid);
        }
        Map<String, Map<String, Object>> locales = new HashMap<>();
        if (Files.isDirectory(root)) {
            try (var paths = Files.list(root)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-z]{2}_[A-Z]{2}\\.yml")).toList()) {
                    var yaml = new YamlConfiguration();
                    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        yaml.load(reader);
                    } catch (InvalidConfigurationException invalid) {
                        throw new IOException("locale.invalid", invalid);
                    }
                    String name = path.getFileName().toString();
                    locales.put(name.substring(0, 5), Map.copyOf(yaml.getValues(true)));
                }
            }
        }
        return new Messages(english, locales, locale);
    }

    public String text(String key, Object... pairs) { return textFor(configured, key, pairs); }
    public String textFor(String locale, String key, Object... pairs) {
        if (locale != null && locale.matches("[a-zA-Z]{2}_[a-zA-Z]{2}")) {
            locale = locale.substring(0, 2).toLowerCase(java.util.Locale.ROOT) + "_"
                    + locale.substring(3).toUpperCase(java.util.Locale.ROOT);
        } else locale = configured;
        Object fallback = english.getOrDefault(key, key);
        Object defaultValue = locales.getOrDefault(configured, Map.of()).getOrDefault(key, fallback);
        String value = String.valueOf(locales.getOrDefault(locale, Map.of()).getOrDefault(key, defaultValue));
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            value = value.replace("{" + pairs[index] + "}", String.valueOf(pairs[index + 1]));
        }
        return value;
    }
    public String prefixed(String locale, String key, Object... pairs) {
        String prefix = Boolean.parseBoolean(textFor(locale, "prefix.enabled")) ? textFor(locale, "prefix.value") : "";
        return color(prefix + textFor(locale, key, pairs));
    }
    public boolean contains(String key) { return english.containsKey(key); }
    public static String color(String source) {
        Matcher matcher = HEX.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char digit : matcher.group(1).toCharArray()) replacement.append('\u00a7').append(digit);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(result);
        return ChatColor.translateAlternateColorCodes('&', result.toString());
    }
}
