package com.wellsetups.wellui.core.screen;

import java.util.Map;
import java.util.Optional;

/** Explicit allowlist; an upstream packet library supporting a version does not validate this profile. */
public record ScreenProfile(String minecraft, int major, int minor) {
    public static Optional<ScreenProfile> select(String server, String client, boolean paperFamily) {
        if (paperFamily && server.equals(client)) return named(server);
        return Optional.empty();
    }
    public static ScreenProfile target() { return new ScreenProfile("1.21.11", 75, 0); }
    public static ScreenProfile modern() { return new ScreenProfile("26.3", 97, 1); }
    public static Optional<ScreenProfile> named(String minecraft) {
        return java.util.stream.Stream.of(target(), modern()).filter(p -> p.minecraft().equals(minecraft)).findFirst();
    }
    public Map<String, Object> shading() { return minecraft.equals("26.3") ? Map.of("shade_direction_override", "up") : Map.of("shade", false); }
    public Map<String, Object> metadata() {
        return Map.of("pack", Map.of("description", "WellUI modern screen", "min_format", java.util.List.of(major, minor),
                "max_format", java.util.List.of(major, minor)));
    }
}
