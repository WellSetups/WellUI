package com.wellsetups.wellui.platform.config;

import com.wellsetups.wellui.core.screen.RendererSelection.Mode;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;

/** Opt-in delivery listener; legacy configs select AUTO with explicit inventory fallback. */
public record ScreenConfig(Mode renderer, boolean fallbackInventory, Delivery delivery, String bind, int port,
                           String publicUrl, String externalUrl, Preload preload, Integration integration,
                           com.wellsetups.wellui.core.screen.RasterPolicy raster, String mergedPackFile, double distance) {
    public enum Delivery { EXPORT_ONLY, SELF_HOSTED, EXTERNAL_URL, ITEMSADDER_MERGED }
    public enum Preload { ON_JOIN, ON_FIRST_UI, MANUAL, EXTERNAL_OWNER }
    public enum Integration { AUTO, ITEMSADDER, ORAXEN, NEXO, STANDALONE, EXTERNAL_OWNER }
    public static ScreenConfig load(YamlConfiguration yaml) throws IOException {
        try {
            Mode renderer = Mode.valueOf(yaml.getString("renderer.default", "AUTO").toUpperCase(Locale.ROOT));
            Delivery delivery = Delivery.valueOf(yaml.getString("screen.packs.mode", "EXPORT_ONLY").toUpperCase(Locale.ROOT));
            if (yaml.contains("renderer.fallback-inventory") && !yaml.isBoolean("renderer.fallback-inventory")) throw new IllegalArgumentException();
            if (yaml.contains("screen.packs.port") && !yaml.isInt("screen.packs.port")) throw new IllegalArgumentException();
            int port = yaml.getInt("screen.packs.port", 8766);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String bind = yaml.getString("screen.packs.bind", "127.0.0.1");
            String publicUrl = yaml.getString("screen.packs.public-url", "http://127.0.0.1:8766").replaceAll("/$", "");
            String externalUrl = yaml.getString("screen.packs.external-url", "");
            if (bind == null || bind.isBlank()) throw new IllegalArgumentException();
            if (delivery == Delivery.SELF_HOSTED && !publicUrl.equals("AUTO")) validUrl(publicUrl);
            if (delivery == Delivery.EXTERNAL_URL) {
                if (!externalUrl.contains("{sha256}")) throw new IllegalArgumentException();
                validUrl(externalUrl.replace("{sha256}", "a".repeat(64)));
            }
            var preload = Preload.valueOf(yaml.getString("screen.packs.preload", "ON_JOIN").toUpperCase(Locale.ROOT));
            var integration = Integration.valueOf(yaml.getString("screen.packs.integration", "AUTO").toUpperCase(Locale.ROOT));
            if (yaml.contains("screen.raster.max-dimension") && !yaml.isInt("screen.raster.max-dimension")) throw new IllegalArgumentException();
            var raster = new com.wellsetups.wellui.core.screen.RasterPolicy(
                    com.wellsetups.wellui.core.screen.RasterPolicy.Quality.valueOf(yaml.getString("screen.raster.quality", "AUTO").toUpperCase(Locale.ROOT)),
                    yaml.getInt("screen.raster.max-dimension", 2048),
                    com.wellsetups.wellui.core.screen.RasterPolicy.Interpolation.valueOf(yaml.getString("screen.raster.images", "AUTO").toUpperCase(Locale.ROOT)));
            String merged = yaml.getString("screen.packs.provider-zip", yaml.getString("screen.packs.itemsadder-zip", "output/generated.zip"));
            if (merged == null || !merged.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_.-]+)*\\.zip") || merged.contains("..")) throw new IllegalArgumentException();
            if (yaml.contains("screen.presentation.distance") && !(yaml.get("screen.presentation.distance") instanceof Number)) throw new IllegalArgumentException();
            double distance = yaml.getDouble("screen.presentation.distance", com.wellsetups.wellui.core.screen.ScreenPlane.DEFAULT_DISTANCE);
            if (!Double.isFinite(distance) || distance < 1 || distance > 6) throw new IllegalArgumentException();
            return new ScreenConfig(renderer, yaml.getBoolean("renderer.fallback-inventory", true), delivery, bind, port, publicUrl, externalUrl,
                    preload, integration, raster, merged, distance);
        } catch (RuntimeException invalid) { throw new IOException("screen.config", invalid); }
    }
    private static void validUrl(String value) {
        URI uri = URI.create(value);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null
                || !value.equals(uri.toASCIIString())) throw new IllegalArgumentException();
    }
}
