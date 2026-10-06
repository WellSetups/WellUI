package com.wellsetups.wellui.core.animation;

import com.wellsetups.wellui.api.animation.UiAnimation;
import com.wellsetups.wellui.api.animation.UiAnimations;
import com.wellsetups.wellui.api.animation.UiAnimatedProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** One bounded, tick-driven coordinator. No scheduler, renderer, filesystem or platform dependencies. */
public final class UiAnimationPlayer {
    public record Property(String component, UiAnimatedProperty property) { }
    public interface Host {
        boolean active();
        default void event(String kind, String animation, int chain) { }
    }
    public record Playing(String id, int tick, String state, int loops, int segment, int properties) { }
    public record Diagnostic(List<Playing> animations, int pending, int properties, long segments,
                             long transformUpdates, long opacityUpdates, double segmentsPerSecond,
                             double transformsPerSecond, double opacityPerSecond, double averageMicros,
                             String lastCompletion, String failure) { }
    private static final class Playback {
        final UiAnimation definition;
        final Set<Property> owned = new HashSet<>();
        final int chain;
        int tick, loops;
        boolean paused, held, primed;
        Playback(UiAnimation definition, int chain) {
            this.definition = definition;
            this.chain = chain;
            definition.tracks().forEach(t -> owned.add(new Property(t.component(), t.property())));
        }
    }
    private final Map<String, UiAnimation> definitions = new LinkedHashMap<>();
    private final Map<String, Playback> playing = new LinkedHashMap<>();
    private final Map<String, Playback> latest = new LinkedHashMap<>();
    private final Host host;
    private boolean closed;
    private long revision, ticks, nanos, evaluations, segments, transforms, opacity, rateStart = System.nanoTime();
    private long lastSegments, lastTransforms, lastOpacity;
    private double segmentRate, transformRate, opacityRate;
    private String completion = "", failure = "";
    private volatile Diagnostic diagnostic = new Diagnostic(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "");
    public UiAnimationPlayer(UiAnimations definitions, Host host) {
        definitions.items().forEach(a -> this.definitions.put(a.id(), a));
        this.host = host;
    }
    public synchronized void control(String operation, String id, int chain) {
        if (closed || !host.active()) return;
        var definition = definitions.get(id);
        if (definition == null) throw fail("animation.reference");
        var current = playing.get(id);
        switch (operation) {
            case "play_animation", "restart_animation" -> {
                if (current == null && playing.size() >= AnimationValidator.MAX_ACTIVE) throw fail("animation.active_limit");
                var next = new Playback(definition, chain);
                for (var old : playing.values()) old.owned.removeAll(next.owned);
                var replaced = new ArrayList<String>();
                playing.forEach((key, old) -> { if (old.owned.isEmpty()) replaced.add(key); });
                replaced.forEach(playing::remove);
                playing.put(id, next);
                latest.put(id, next);
                revision++;
                host.event("start", id, chain);
            }
            case "pause_animation" -> { if (current != null && !current.held) { current.paused = true;
                revision++;
            } }
            case "resume_animation" -> { if (current != null && current.paused) { current.paused = false;
                current.primed = false;
                revision++;
            } }
            case "stop_animation" -> { latest.remove(id);
                if (playing.remove(id) != null) { revision++;
                host.event("stop", id, current.chain);
            } }
            default -> throw fail("animation.operation");
        }
        publish();
    }
    private IllegalArgumentException fail(String code) { failure = code;
        publish();
        return new IllegalArgumentException(code);
    }
    public synchronized void tick() {
        if (closed || !host.active()) return;
        long start = System.nanoTime();
        var completed = new ArrayList<Playback>();
        for (var p : playing.values()) {
            if (p.paused || p.held) continue;
            if (!p.primed) { p.primed = true;
                continue;
            }
            if (p.tick == p.definition.duration() && p.definition.loop() == UiAnimation.Loop.LOOP) { p.tick = 0;
                p.loops++;
                revision++;
            } else p.tick++;
            if (p.tick >= p.definition.duration() && p.definition.loop() == UiAnimation.Loop.NONE) {
                p.held = true;
                completed.add(p);
            }
        }
        for (var p : completed) {
            if (p.definition.end() == UiAnimation.End.RESTORE) playing.remove(p.definition.id());
            revision++;
        }
        ticks++;
        evaluations++;
        nanos += System.nanoTime() - start;
        // Events are outside collection iteration; replacement/close is checked before every callback.
        for (var p : completed) {
            if (closed || !host.active()) break;
            if (latest.get(p.definition.id()) != p) continue;
            completion = p.definition.id();
            host.event("complete", p.definition.id(), p.chain);
        }
        publish();
    }
    public synchronized Map<Property, Double> overlay(int ahead) {
        long start = System.nanoTime();
        var values = new LinkedHashMap<Property, Double>();
        for (var p : playing.values()) {
            int time = Math.min(p.definition.duration(), p.tick + (p.paused || p.held ? 0 : ahead));
            for (var t : p.definition.tracks()) {
                var key = new Property(t.component(), t.property());
                if (p.owned.contains(key)) values.put(key, AnimationMath.sample(t, time));
            }
        }
        var result = Map.copyOf(values);
        evaluations++;
        nanos += System.nanoTime() - start;
        return result;
    }
    public synchronized int horizon() {
        int duration = AnimationValidator.MAX_DURATION;
        boolean active = false;
        for (var p : playing.values()) {
            if (p.paused || p.held) continue;
            active = true;
            duration = Math.min(duration, Math.max(1, p.definition.duration() - p.tick));
            for (var t : p.definition.tracks()) if (p.owned.contains(new Property(t.component(), t.property())))
                duration = Math.min(duration, Math.max(1, AnimationMath.segment(t, p.tick, p.definition.duration())));
        }
        return active ? duration : 0;
    }
    public long revision() { return revision; }
    public long ticks() { return ticks; }
    public Diagnostic diagnostic() { return diagnostic; }
    public synchronized void packet(boolean transform) { if (transform) transforms++;
        else opacity++;
    }
    public synchronized void segment() { segments++; }
    private void publish() {
        long now = System.nanoTime();
        if (now - rateStart >= 1_000_000_000L) {
            double seconds = (now - rateStart) / 1_000_000_000.0;
            segmentRate = (segments - lastSegments) / seconds;
            transformRate = (transforms - lastTransforms) / seconds;
            opacityRate = (opacity - lastOpacity) / seconds;
            lastSegments = segments;
            lastTransforms = transforms;
            lastOpacity = opacity;
            rateStart = now;
        }
        var list = playing.values().stream().map(p -> new Playing(p.definition.id(), p.tick, p.held ? "HELD" : p.paused ? "PAUSED" : "PLAYING", p.loops,
                p.definition.tracks().stream().mapToInt(t -> (int) t.keyframes().stream().filter(k -> k.tick() <= p.tick).count() - 1).max().orElse(0), p.owned.size())).toList();
        diagnostic = new Diagnostic(list, (int) playing.values().stream().filter(p -> !p.paused && !p.held).count(),
                playing.values().stream().mapToInt(p -> p.owned.size()).sum(), segments, transforms, opacity, segmentRate, transformRate, opacityRate,
                evaluations == 0 ? 0 : nanos / (evaluations * 1000.0), completion, failure);
    }
    public synchronized void close() { closed = true;
        playing.clear();
        latest.clear();
        revision++;
        publish();
    }
}
