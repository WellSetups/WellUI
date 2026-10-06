package com.wellsetups.wellui.core;

import com.wellsetups.wellui.core.screen.ScreenProfile;
import java.util.EnumSet;
import java.util.Set;

/** Central runtime feature declaration. Availability is separate from pack readiness and live certification. */
public record EnvironmentCapabilities(String version, String provider, Set<Feature> features) {
    public enum Feature { SCREEN, RESOURCE_PACK_DELIVERY, TEXT_INPUT, SCROLL, ANIMATION, DYNAMIC_TEXT, DYNAMIC_COMPONENTS, INVENTORY, HUD }
    public EnvironmentCapabilities { features = Set.copyOf(features); }
    public static EnvironmentCapabilities detect(String version, boolean paperFamily, boolean transport, boolean delivery, String provider) {
        var features = EnumSet.of(Feature.INVENTORY, Feature.HUD);
        if (delivery) features.add(Feature.RESOURCE_PACK_DELIVERY);
        if (paperFamily && transport && ScreenProfile.named(version).isPresent()) features.addAll(EnumSet.of(Feature.SCREEN,
                Feature.TEXT_INPUT, Feature.SCROLL, Feature.ANIMATION, Feature.DYNAMIC_TEXT, Feature.DYNAMIC_COMPONENTS));
        return new EnvironmentCapabilities(version, provider, features);
    }
}
