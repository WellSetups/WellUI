package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.AssetStore;
import com.wellsetups.wellui.core.DocumentValidator;
import com.wellsetups.wellui.core.LayoutEngine;
import com.wellsetups.wellui.core.ProjectRepository;
import com.wellsetups.wellui.core.ResourcePackBuilder;
import com.wellsetups.wellui.core.RendererCapabilities;
import com.wellsetups.wellui.editor.DeploymentResult;
import com.wellsetups.wellui.editor.DeploymentService;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.screen.ScreenAssets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Deployment IO boundary; publishes immutable snapshots without touching any Bukkit objects. */
public final class DeployedProjects implements DeploymentService {
    private final ProjectRepository deployed;
    private final AssetStore assets;
    private final ResourcePackBuilder packs;
    private final int format;
    private final Supplier<Messages> messages;
    public record Revision(UiDocument document, com.wellsetups.wellui.core.logic.CompiledLogic logic) { }
    private record Snapshot(Map<String, UiDocument> documents, Map<String, Revision> revisions) { }
    private volatile Snapshot published = new Snapshot(Map.of(), Map.of());
    private void publish(Map<String, UiDocument> documents) {
        var revisions = new HashMap<String, Revision>();
        documents.forEach((id, document) -> {
            var previous = published.revisions().get(id);
            revisions.put(id, previous != null && previous.document().equals(document) ? previous
                    : new Revision(document, new com.wellsetups.wellui.core.logic.CompiledLogic(document)));
        });
        published = new Snapshot(Map.copyOf(documents), Map.copyOf(revisions));
    }
    public Revision revision(String id) { return published.revisions().get(id); }
    private java.util.function.BooleanSupplier placeholders = () -> false;
    public void placeholders(java.util.function.BooleanSupplier value) { placeholders = value; }
    public Boolean placeholdersAvailable() { return placeholders.getAsBoolean(); }
    private volatile String lastHash = "";
    private final Map<String, String> deployedHashes = new HashMap<>();
    private java.util.Set<String> deploying = java.util.Set.of();
    /** Validate the whole graph before publishing dependency-first. Completed dependencies survive a later IO failure. */
    public synchronized DeploymentResult deployAll(List<UiDocument> documents) throws Exception {
        if (documents.isEmpty() || documents.size() > 64) throw new IllegalArgumentException("deploy.dependencies_limit");
        var ids = new java.util.HashSet<>(snapshot().keySet());
        documents.forEach(d -> ids.add(d.id()));
        var problems = new ArrayList<String>();
        for (var document : documents) {
            for (var issue : new DocumentValidator().validate(document)) problems.add(issue.code());
            for (var issue : new com.wellsetups.wellui.core.logic.LogicValidator().validate(document, ids, null)) if (!issue.warning()) problems.add(issue.code());
        }
        if (!problems.isEmpty()) return new DeploymentResult(false, problems, "");
        deploying = java.util.Set.copyOf(ids);
        try { return DeploymentService.super.deployAll(documents); }
        finally { deploying = java.util.Set.of(); }
    }
    private Supplier<Map<String, String>> aliases = Map::of;
    private Supplier<List<UiDocument>> active = List::of;
    private java.util.function.Consumer<String> deleted = id -> { };
    public void lifecycle(Supplier<Map<String, String>> aliases, Supplier<List<UiDocument>> active, java.util.function.Consumer<String> deleted) {
        this.aliases = aliases;
        this.active = active;
        this.deleted = deleted;
    }
    public String resolve(String id) {
        if (snapshot().containsKey(id)) return id;
        return aliases.get().entrySet().stream().filter(e -> e.getValue().equals(id) && snapshot().containsKey(e.getKey())).map(Map.Entry::getKey).findFirst().orElse(id);
    }
    public String slug(String id) { return aliases.get().getOrDefault(id, id); }
    public List<UiDocument> activeDocuments() { return active.get(); }
    private ScreenAssets screenAssets;
    private java.util.function.BooleanSupplier screenAvailable = () -> false;
    private Supplier<Object> capabilities = () -> Map.of();
    public void capabilities(Supplier<Object> value) { capabilities = value; }
    private Supplier<Map<String, Long>> playerReadiness = Map::of;
    public void playerReadiness(Supplier<Map<String, Long>> value) { playerReadiness = value; }
    public void screenAvailable(java.util.function.BooleanSupplier value) { screenAvailable = value; }
    public synchronized void screenAssets(ScreenAssets value) { screenAssets = value; }
    /** Serialize warming with deployment so an older startup snapshot cannot replace a newer preparation. */
    public synchronized void prepareScreens() {
        if (screenAssets == null) return;
        try { screenAssets.prepareAll(snapshot().values()); }
        catch (IOException | IllegalArgumentException ignored) { /* Explicit export reports preparation failure. */ }
    }
    public synchronized com.wellsetups.wellui.core.ScreenPackBuilder.Prepared prepareScreen(String id) throws IOException {
        var document = snapshot().get(resolve(id));
        if (document == null) throw new IllegalArgumentException("open.not_found");
        return screenAssets.prepare(document);
    }
    public synchronized void repairScreenSources() throws IOException { screenAssets.repairExternal(snapshot().values()); }

    public DeployedProjects(ProjectRepository deployed, AssetStore assets, ResourcePackBuilder packs,
                            int format, Supplier<Messages> messages) {
        this.deployed = deployed;
        this.assets = assets;
        this.packs = packs;
        this.format = format;
        this.messages = messages;
    }

    public synchronized void reload() throws IOException {
        Map<String, UiDocument> next = new HashMap<>();
        for (UiDocument document : deployed.list()) {
            if (!new DocumentValidator().validate(document).isEmpty()) throw new IOException("validation.document");
            next.put(document.id(), document);
        }
        publish(next);
    }

    public synchronized DeploymentResult deploy(UiDocument document) throws IOException {
        Messages locale = messages.get();
        List<String> issues = new ArrayList<>();
        for (var issue : new DocumentValidator().validate(document)) issues.add(locale.text(issue.code()));
        if (!issues.isEmpty()) return new DeploymentResult(false, issues, "");
        for (var item : new LayoutEngine().layout(document, document.width(), document.height())) {
            if (item.component() instanceof ImageComponent image && !assets.exists(image.asset())) {
                issues.add(locale.text("deploy.missing_asset", "id", image.id()));
            }
        }
        if (!issues.isEmpty()) return new DeploymentResult(false, issues, "");
        var ids = new java.util.HashSet<>(snapshot().keySet());
        ids.add(document.id());
        ids.addAll(deploying);
        for (var issue : new com.wellsetups.wellui.core.logic.LogicValidator().validate(document, ids, null)) {
            if (!issue.warning()) issues.add(issue.code() + " [" + issue.nodeId() + "]");
        }
        if (!issues.isEmpty()) return new DeploymentResult(false, issues, "");
        new com.wellsetups.wellui.core.logic.CompiledLogic(document);
        if (document.equals(snapshot().get(document.id())) && (screenAssets == null || screenAssets.current(document) != null)) {
            return new DeploymentResult(true, List.of(locale.text("deploy.unchanged")), deployedHashes.getOrDefault(document.id(), ""));
        }
        var pack = packs.build(document, format);
        if (screenAssets != null) {
            try { screenAssets.prepare(document); }
            catch (IOException | IllegalArgumentException failure) {
                issues.add(locale.text("screen.deploy_warning", "reason", locale.text(failure.getMessage(), "resource",
                        failure instanceof com.wellsetups.wellui.platform.screen.PackProblem problem ? problem.resource() : "")));
                if (!document.controls().isEmpty()) return new DeploymentResult(false, List.copyOf(issues), "");
            }
        }
        deployed.save(document);
        Map<String, UiDocument> next = new HashMap<>(snapshot());
        next.put(document.id(), document);
        publish(next);
        lastHash = pack.sha256();
        deployedHashes.put(document.id(), lastHash);
        if (document.controls().isEmpty()) {
            issues.add(locale.text("deploy.inventory"));
            for (String warning : RendererCapabilities.hud().warningKeys()) issues.add(locale.text(warning));
            if (!new SlotProjection().project(document).collisions().isEmpty()) issues.add(locale.text("deploy.collisions"));
        } else issues.add(locale.text("control.renderer_unsupported"));
        return new DeploymentResult(true, List.copyOf(issues), lastHash);
    }

    public Map<String, UiDocument> snapshot() { return published.documents(); }
    public Map<String, UiDocument> deployedDocuments() { return published.documents(); }
    public synchronized List<UiDocument> retainedDocuments() throws IOException {
        var result = new ArrayList<>(deployed.retained());
        result.addAll(activeDocuments());
        return List.copyOf(result);
    }
    public synchronized void delete(String id) throws IOException {
        deployed.delete(id);
        var next = new HashMap<>(snapshot());
        next.remove(id);
        deployedHashes.remove(id);
        publish(next);
        deleted.accept(id);
        prepareScreens();
    }
    public Map<String, Object> status() {
        var state = screenAssets == null ? null : screenAssets.readiness();
        return Map.of("connected", true, "screenAvailable", screenAvailable.getAsBoolean(),
                "provider", screenAssets == null ? "None" : screenAssets.owner(), "detected", screenAssets == null ? "none" : screenAssets.detected(),
                "generation", state == null ? "" : state.generation(), "packState", state == null ? "NOT_PREPARED" : state.state().name(),
                "reason", state == null ? "screen.not_prepared" : state.reason(), "playerReadiness", playerReadiness.get(),
                "compiledPack", screenAssets == null || !screenAssets.external() ? "" : screenAssets.integration().compiledPack(), "capabilities", capabilities.get());
    }
    public String lastHash() { return lastHash; }
}
