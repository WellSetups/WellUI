package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.core.ScreenGenerationBuilder;
import com.wellsetups.wellui.core.screen.PackReadiness;
import com.wellsetups.wellui.editor.PackHttpServer;
import com.wellsetups.wellui.platform.config.ScreenConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Preparation runs at the IO boundary. Runtime accessors use immutable exports and never read pack files. */
public final class ScreenAssets implements AutoCloseable {
    private final ScreenPackBuilder builder;
    private final ScreenConfig config;
    private final ResourcePackIntegration integration;
    private final String detected;
    private volatile ScreenGenerationBuilder.Generation generation;
    private final PackHttpServer host;
    private final String hostProblem;
    private volatile Map<String, ScreenPackBuilder.Prepared> prepared = Map.of();
    private final LongAdder buildMillis = new LongAdder();
    private final LongAdder builds = new LongAdder();
    private volatile boolean closed;
    private volatile long verificationRevision;
    private volatile ScreenPackBuilder.Prepared latest;
    private volatile String maintenanceProblem = "";
    public String maintenanceProblem() { return maintenanceProblem; }
    private volatile PackReadiness.Provider readiness = new PackReadiness.Provider(PackReadiness.State.NOT_PREPARED, "", "", "screen.not_prepared");
    private volatile Runnable readinessChanged = () -> { };
    public void onReadinessChanged(Runnable listener) { readinessChanged = listener; }
    public PackReadiness.Provider readiness() { return readiness; }
    public long verificationRevision() { return verificationRevision; }
    private void readiness(PackReadiness.State state, String id, String hash, String reason) {
        readiness = new PackReadiness.Provider(state, id, hash, reason);
        readinessChanged.run();
    }
    public String verifiedHash(String id) {
        var state = readiness;
        return state.verified() && state.generation().equals(id) ? state.sha1() : "";
    }
    public synchronized void providerChanged() {
        if (!closed && generation != null) readiness(PackReadiness.State.PROVIDER_BUILDING, generation.sha256(), "", "screen.preparing");
    }
    public ScreenAssets(ScreenPackBuilder builder, ScreenConfig config) throws IOException {
        this(builder, config, null, "none");
    }
    public ScreenAssets(ScreenPackBuilder builder, ScreenConfig config, ResourcePackIntegration integration, String detected) throws IOException {
        this.integration = integration;
        this.detected = detected;
        this.builder = builder;
        this.config = config;
        PackHttpServer candidate = null;
        String problem = "";
        if (integration == null && config.delivery() == ScreenConfig.Delivery.SELF_HOSTED) {
            try {
                candidate = new PackHttpServer(new InetSocketAddress(config.bind(), config.port()));
                candidate.start();
            } catch (IOException | RuntimeException failure) {
                if (candidate != null) candidate.close();
                candidate = null;
                problem = "screen.host_failed";
            }
        }
        host = candidate;
        hostProblem = problem;
    }
    public synchronized ScreenPackBuilder.Prepared prepare(UiDocument document) throws IOException {
        var old = current(document);
        if (old != null) return old;
        var next = new java.util.TreeMap<>(prepared);
        var result = build(document);
        next.put(document.id(), result);
        publish(next);
        return result;
    }
    public synchronized void prepareAll(java.util.Collection<UiDocument> documents) throws IOException {
        var next = new java.util.TreeMap<String, ScreenPackBuilder.Prepared>();
        for (var document : documents) {
            try { next.put(document.id(), build(document)); }
            catch (IllegalArgumentException unsupported) { /* Invalid SCREEN documents retain the other renderers. */ }
        }
        if (!next.isEmpty()) publish(next);
        else {
            prepared = Map.of();
            latest = null;
            generation = null;
            readiness(PackReadiness.State.NOT_PREPARED, "", "", "screen.not_prepared");
        }
    }
    private ScreenPackBuilder.Prepared build(UiDocument document) throws IOException {
        if (closed) throw new IOException("open.unavailable");
        var old = prepared.get(document.id());
        if (old != null && visualKey(old.document()).equals(visualKey(document))) {
            return new ScreenPackBuilder.Prepared(document, new com.wellsetups.wellui.core.screen.ScreenScene(document), old.path(), old.sha1(), old.sha256(),
                    old.baseModel(), old.cursorModel(), old.hoverModels(), old.bytes(), 0, old.validation(), old.raster(), old.layers());
        }
        return builder.build(document);
    }
    private String visualKey(UiDocument document) {
        var json = com.google.gson.JsonParser.parseString(new com.wellsetups.wellui.core.DocumentCodec().encode(document)).getAsJsonObject();
        json.remove("name");
        json.remove("logic");
        json.remove("animations");
        json.addProperty("animated", !document.animations().isEmpty());
        if (!document.animations().isEmpty()) json.add("independent", new com.google.gson.Gson().toJsonTree(
                new java.util.TreeSet<>(com.wellsetups.wellui.core.animation.AnimationVisuals.independent(document))));
        json.addProperty("dynamic", com.wellsetups.wellui.core.logic.LogicVisuals.dynamic(document));
        json.add("dynamicText", new com.google.gson.Gson().toJsonTree(new java.util.TreeSet<>(com.wellsetups.wellui.core.logic.LogicVisuals.text(document))));
        stripActions(json);
        return json.toString();
    }
    private void stripActions(com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().remove("action");
            value.getAsJsonObject().entrySet().forEach(e -> stripActions(e.getValue()));
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(this::stripActions);
    }
    private void publish(Map<String, ScreenPackBuilder.Prepared> next) throws IOException {
        if (closed) throw new IOException("open.unavailable");
        var identities = new java.util.TreeMap<String, String>();
        next.forEach((id, pack) -> identities.put(id, pack.sha256()));
        if (generation != null && generation.projects().equals(identities)) {
            prepared = Map.copyOf(next);
            latest = next.values().stream().reduce((a, b) -> b).orElseThrow();
            return;
        }
        var first = next.values().iterator().next();
        var candidate = new ScreenGenerationBuilder(first.path().getParent().resolve("generations")).build(next.values());
        if (generation == null || !generation.sha256().equals(candidate.sha256())) {
            readiness(PackReadiness.State.SOURCE_READY, candidate.sha256(), "", "screen.preparing");
            if (integration != null) {
                try { integration.export(candidate); }
                catch (IOException failure) {
                    readiness(PackReadiness.State.FAILED, candidate.sha256(), "", failure.getMessage());
                    throw failure;
                }
            }
            if (host != null) host.publish(candidate.sha256(), candidate.path());
        }
        prepared = Map.copyOf(next);
        latest = next.values().stream().reduce((a, b) -> b).orElseThrow();
        generation = candidate;
        try {
            var protectedFiles = next.values().stream().map(ScreenPackBuilder.Prepared::path).collect(java.util.stream.Collectors.toSet());
            com.wellsetups.wellui.core.GeneratedPackRetention.prune(first.path().getParent(), protectedFiles);
            com.wellsetups.wellui.core.GeneratedPackRetention.prune(candidate.path().getParent(), java.util.Set.of(candidate.path()));
            if (host != null) host.removeExpired();
            maintenanceProblem = "";
        } catch (IOException failure) { maintenanceProblem = failure.getMessage(); }
        readiness(integration == null ? PackReadiness.State.VERIFIED : PackReadiness.State.PROVIDER_BUILD_REQUIRED,
                candidate.sha256(), integration == null ? candidate.sha1() : "", integration == null ? "" : "screen.provider_build_waiting");
        buildMillis.add(latest.millis());
        builds.increment();
    }
    public ScreenGenerationBuilder.Generation generation() { return generation; }
    public ScreenConfig config() { return config; }
    public ResourcePackIntegration integration() { return integration; }
    public String owner() { return integration == null ? "WellUI" : integration.owner(); }
    public String detected() { return detected; }
    public boolean external() { return integration != null; }
    public boolean contains(ScreenPackBuilder.Prepared pack) {
        var current = generation;
        return current != null && pack.sha256().equals(current.projects().get(pack.document().id()));
    }
    public synchronized String verifyExternal() throws IOException {
        if (closed) throw new IOException("open.unavailable");
        if (generation == null && integration != null && integration.report().problem() != null) throw integration.report().problem();
        if (generation == null || integration == null) throw new IOException("screen.merge_pending");
        readiness(PackReadiness.State.VERIFYING, generation.sha256(), "", "screen.preparing");
        try {
            String hash = integration.verify(generation);
            readiness(PackReadiness.State.VERIFIED, generation.sha256(), hash, "");
            return hash;
        } catch (IOException failure) {
            readiness(failure instanceof PackProblem problem && problem.buildRequired() ? PackReadiness.State.PROVIDER_BUILD_REQUIRED
                    : PackReadiness.State.FAILED, generation.sha256(), "", failure.getMessage());
            throw failure;
        }
    }
    /** Explicit recovery also works when startup export failed before any generation was published. */
    public synchronized void repairExternal(java.util.Collection<UiDocument> documents) throws IOException {
        if (integration == null) throw new IOException("screen.external_owner");
        var next = new java.util.TreeMap<String, ScreenPackBuilder.Prepared>();
        for (var document : documents) {
            try { next.put(document.id(), build(document)); }
            catch (IllegalArgumentException unsupported) { /* Other renderers retain unsupported projects. */ }
        }
        if (next.isEmpty()) throw new IOException("screen.not_prepared");
        var first = next.values().iterator().next();
        var candidate = new ScreenGenerationBuilder(first.path().getParent().resolve("generations")).build(next.values());
        readiness(PackReadiness.State.SOURCE_READY, candidate.sha256(), "", "screen.preparing");
        verificationRevision++;
        try { integration.repair(candidate); }
        catch (IOException failure) {
            readiness(PackReadiness.State.FAILED, candidate.sha256(), "", failure.getMessage());
            throw failure;
        }
        publish(next);
        readiness(PackReadiness.State.PROVIDER_BUILD_REQUIRED, candidate.sha256(), "", "screen.provider_build_waiting");
    }
    public ScreenPackBuilder.Prepared current(UiDocument document) {
        var result = prepared.get(document.id());
        return result != null && result.document().equals(document) ? result : null;
    }
    public String url(String sha256) {
        if (external() || !hostProblem.isEmpty()) return "";
        return switch (config.delivery()) {
            case EXPORT_ONLY, ITEMSADDER_MERGED -> "";
            case EXTERNAL_URL -> config.externalUrl().replace("{sha256}", sha256);
            case SELF_HOSTED -> config.publicUrl().equals("AUTO") ? "" : config.publicUrl() + "/packs/" + sha256 + ".zip";
        };
    }
    public String url(String sha256, org.bukkit.entity.Player player) {
        if (!external() && hostProblem.isEmpty() && config.delivery() == ScreenConfig.Delivery.SELF_HOSTED && config.publicUrl().equals("AUTO")) {
            String origin = PackAddress.origin(player, config.port());
            return origin.isEmpty() ? "" : origin + "/packs/" + sha256 + ".zip";
        }
        return url(sha256);
    }
    public boolean deliverable() { return external() || config.delivery() != ScreenConfig.Delivery.EXPORT_ONLY; }
    public String hostProblem() { return hostProblem; }
    public long averageMillis() { return builds.sum() == 0 ? 0 : buildMillis.sum() / builds.sum(); }
    public ScreenPackBuilder.Prepared latest() { return latest; }
    public int validatedCount() { return prepared.size(); }
    public synchronized void close() {
        closed = true;
        readiness(PackReadiness.State.NOT_PREPARED, "", "", "open.unavailable");
        prepared = Map.of();
        latest = null;
        if (host != null) host.close();
    }
}
