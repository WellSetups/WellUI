package com.wellsetups.wellui.core.animation;

import com.wellsetups.wellui.api.animation.UiAnimatedProperty;
import com.wellsetups.wellui.api.animation.UiAnimationTrack;
import com.wellsetups.wellui.api.animation.UiKeyframe;

public final class AnimationMath {
    private AnimationMath() { }
    public static double ease(UiKeyframe.Easing easing, double t) {
        t = Math.max(0, Math.min(1, t));
        return switch (easing) {
            case LINEAR -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> t * (2 - t);
            case EASE_IN_OUT -> t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
        };
    }
    public static double sample(UiAnimationTrack track, double tick) {
        var keys = track.keyframes();
        if (keys.isEmpty()) return track.property().baseline();
        if (tick <= keys.get(0).tick()) return keys.get(0).value();
        for (int i = 1; i < keys.size(); i++) {
            var end = keys.get(i);
            var start = keys.get(i - 1);
            if (tick < end.tick()) {
                if (track.property() == UiAnimatedProperty.VISIBILITY) return start.value();
                return start.value() + (end.value() - start.value()) * ease(start.easing(), (tick - start.tick()) / (end.tick() - start.tick()));
            }
        }
        return keys.get(keys.size() - 1).value();
    }
    /** Next transform boundary. Nonlinear/rotating/scaling paths are sampled once per tick. */
    public static int segment(UiAnimationTrack track, int tick, int duration) {
        if (!track.property().transform()) return duration - tick;
        var keys = track.keyframes();
        for (int i = 0; i < keys.size(); i++) {
            var k = keys.get(i);
            if (k.tick() > tick) {
                if (i == 0) return k.tick() - tick;
                var before = keys.get(i - 1);
                if (before.value() == k.value()) return k.tick() - tick;
                boolean translation = track.property() == UiAnimatedProperty.TRANSLATION_X || track.property() == UiAnimatedProperty.TRANSLATION_Y;
                return translation && before.easing() == UiKeyframe.Easing.LINEAR ? k.tick() - tick : 1;
            }
        }
        return duration - tick;
    }
}
