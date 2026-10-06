package com.wellsetups.wellui.platform.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable validated settings. Version-zero configuration migrates in memory without data loss. */
public record RuntimeConfig(String locale, boolean editorEnabled, String bind, int port,
                            boolean allowRemote, String publicUrl, int maxAssetBytes, int packFormat,
                            long placeholderRefreshMs, long clickCooldownMs, boolean pulseButtons,
                            boolean metricsEnabled, Map<String, String> commandLabels, ScreenConfig screen) {
    public static final java.util.List<String> COMMANDS = java.util.List.of(
            "help", "editor", "open", "close", "reload", "list", "info", "debug", "pack", "migrate", "hudpack", "preview", "screenpack", "packload", "packverify", "packrepair");

    public static RuntimeConfig load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("config.missing");
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(path.toFile()); }
        catch (org.bukkit.configuration.InvalidConfigurationException ex) { throw new IOException("config.invalid", ex); }
        for (String key : java.util.List.of("config-version", "editor.port", "assets.max-bytes", "packs.format",
                "runtime.placeholder-refresh-ms", "runtime.click-cooldown-ms")) {
            if (yaml.contains(key) && !(yaml.get(key) instanceof Integer)) throw new IOException("config.type." + key);
        }
        for (String key : java.util.List.of("editor.enabled", "editor.allow-remote", "runtime.pulse-buttons", "metrics.enabled")) {
            if (yaml.contains(key) && !(yaml.get(key) instanceof Boolean)) throw new IOException("config.type." + key);
        }
        int version = yaml.getInt("config-version", 0);
        if (version < 0 || version > 1) throw new IOException("config.version");
        String locale = yaml.getString("locale", yaml.getString("language", "en_US"));
        if (locale == null || !locale.matches("[a-z]{2}_[A-Z]{2}")) throw new IOException("config.locale");
        String bind = yaml.getString("editor.bind", "127.0.0.1");
        boolean remote = yaml.getBoolean("editor.allow-remote", false);
        if (!remote && !InetAddress.getByName(bind).isLoopbackAddress()) throw new IOException("config.remote");
        int port = bounded(yaml.getInt("editor.port", 8765), 1, 65535, "config.port");
        String publicUrl = yaml.getString("editor.public-url", "");
        if (publicUrl == null) publicUrl = "";
        if (!publicUrl.isBlank()) {
            URI uri;
            try { uri = URI.create(publicUrl); }
            catch (IllegalArgumentException ex) { throw new IOException("config.public_url", ex); }
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IOException("config.public_url");
            if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress() && !"https".equals(uri.getScheme())) {
                throw new IOException("config.https");
            }
        }
        if (remote && publicUrl.isBlank()) throw new IOException("config.public_url");
        Map<String, String> labels = new HashMap<>();
        for (String command : COMMANDS) {
            String label = yaml.getString("commands." + command, command);
            if (label == null || !label.matches("[a-z][a-z0-9_-]{0,24}") || labels.containsValue(label)) {
                throw new IOException("config.commands");
            }
            labels.put(command, label);
        }
        return new RuntimeConfig(locale, yaml.getBoolean("editor.enabled", true), bind, port, remote,
                publicUrl.replaceAll("/$", ""), bounded(yaml.getInt("assets.max-bytes", 2097152), 1024, 8388608, "config.assets"),
                bounded(yaml.getInt("packs.format", 15), 15, 64, "config.pack"),
                bounded(yaml.getInt("runtime.placeholder-refresh-ms", 1000), 500, 60000, "config.refresh"),
                bounded(yaml.getInt("runtime.click-cooldown-ms", 250), 100, 10000, "config.cooldown"),
                yaml.getBoolean("runtime.pulse-buttons", true), yaml.getBoolean("metrics.enabled", true), Map.copyOf(labels), ScreenConfig.load(yaml));
    }

    public static void migrate(Path path) throws IOException {
        load(path);
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(path.toFile()); }
        catch (org.bukkit.configuration.InvalidConfigurationException invalid) { throw new IOException("config.invalid", invalid); }
        if (yaml.getInt("config-version", 0) == 1) return;
        byte[] original = Files.readAllBytes(path);
        Path backup = path.resolveSibling(path.getFileName() + "." + java.time.Instant.now().toEpochMilli() + ".bak");
        com.wellsetups.wellui.core.AtomicFiles.write(backup, original);
        if (!yaml.contains("locale")) yaml.set("locale", yaml.getString("language", "en_US"));
        yaml.set("config-version", 1);
        com.wellsetups.wellui.core.AtomicFiles.write(path, yaml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int bounded(int value, int minimum, int maximum, String key) throws IOException {
        if (value < minimum || value > maximum) throw new IOException(key);
        return value;
    }

    public boolean restartRequired(RuntimeConfig next) {
        return editorEnabled != next.editorEnabled || !bind.equals(next.bind) || port != next.port
                || allowRemote != next.allowRemote || !publicUrl.equals(next.publicUrl)
                || maxAssetBytes != next.maxAssetBytes || packFormat != next.packFormat
                || metricsEnabled != next.metricsEnabled || !screen.equals(next.screen);
    }
}
