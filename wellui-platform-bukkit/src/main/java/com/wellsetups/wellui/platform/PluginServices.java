package com.wellsetups.wellui.platform;

import com.wellsetups.wellui.api.WellUiApi;
import com.wellsetups.wellui.core.AssetStore;
import com.wellsetups.wellui.core.DocumentCodec;
import com.wellsetups.wellui.core.DocumentValidator;
import com.wellsetups.wellui.core.ExampleProject;
import com.wellsetups.wellui.core.HudPackBuilder;
import com.wellsetups.wellui.core.ProjectRepository;
import com.wellsetups.wellui.core.ResourcePackBuilder;
import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.editor.EditorServer;
import com.wellsetups.wellui.platform.command.WellUiCommands;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.config.RuntimeConfig;
import com.wellsetups.wellui.platform.runtime.DeployedProjects;
import com.wellsetups.wellui.platform.runtime.EventHub;
import com.wellsetups.wellui.platform.runtime.InventoryRenderer;
import com.wellsetups.wellui.platform.runtime.GlyphHudRenderer;
import com.wellsetups.wellui.platform.runtime.PlaceholderService;
import com.wellsetups.wellui.platform.runtime.UiRuntime;
import com.wellsetups.wellui.platform.runtime.ScreenRenderer;
import com.wellsetups.wellui.platform.screen.ScreenAssets;
import com.wellsetups.wellui.platform.screen.ScreenTransport;
import com.wellsetups.wellui.platform.screen.PacketEventsTransport;
import com.wellsetups.wellui.platform.screen.PackDelivery;
import com.wellsetups.wellui.platform.scheduling.BukkitSchedulerBridge;
import com.wellsetups.wellui.platform.scheduling.FoliaSchedulerBridge;
import com.wellsetups.wellui.platform.scheduling.PlatformScheduler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import org.bstats.bukkit.Metrics;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Composition root and lifecycle owner. All dependencies are assembled in one place. */
public final class PluginServices implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path root;
    private volatile RuntimeConfig config;
    private volatile Messages messages;
    private PlatformScheduler scheduler;
    private UiRuntime runtime;
    private GlyphHudRenderer hud;
    private ScreenRenderer screen;
    private ScreenAssets screenAssets;
    private ScreenTransport transport;
    private PackDelivery delivery;
    private com.wellsetups.wellui.platform.screen.ExternalPackMonitor externalMonitor;
    private com.wellsetups.wellui.platform.screen.PackLifecycleCoordinator packLifecycle;
    private DeployedProjects deployed;
    private ProjectRepository drafts;
    private EditorServer editor;
    private Metrics metrics;
    private PlaceholderService placeholders;
    private String platform;
    private String version;

    public PluginServices(JavaPlugin plugin) {
        this.plugin = plugin;
        root = plugin.getDataFolder().toPath();
    }
    public void start() throws Exception {
        config = RuntimeConfig.load(root.resolve("config.yml"));
        messages = Messages.load(root.resolve("lang"), config.locale());
        scheduler = FoliaSchedulerBridge.available() ? new FoliaSchedulerBridge(plugin) : new BukkitSchedulerBridge(plugin);
        platform = plugin.getServer().getName();
        version = plugin.getServer().getBukkitVersion();
        var codec = new DocumentCodec();
        drafts = new ProjectRepository(root.resolve("projects"), codec);
        var published = new ProjectRepository(root.resolve("deployed"), codec);
        var assets = new AssetStore(root.resolve("assets"), config.maxAssetBytes());
        var packs = new ResourcePackBuilder(assets, root.resolve("packs"));
        deployed = new DeployedProjects(published, assets, packs, config.packFormat(), this::messages);
        if (drafts.list().isEmpty()) drafts.save(ExampleProject.create());
        if (published.list().isEmpty()) deployed.deploy(ExampleProject.create());
        deployed.reload();
        boolean papi = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        try {
            placeholders = new PlaceholderService(papi, failure -> plugin.getLogger().warning(messages.text("integration.failed")));
        } catch (ReflectiveOperationException unsupported) {
            plugin.getLogger().warning(messages.text("integration.failed"));
            placeholders = new PlaceholderService(false, failure -> plugin.getLogger().warning(messages.text("integration.failed")));
        }
        var events = new EventHub(failure -> plugin.getLogger().warning(messages.text("event.failed")));
        hud = new GlyphHudRenderer(new HudPackBuilder(assets, root.resolve("hud-packs")), this::messages);
        deployed.placeholders(placeholders::enabled);
        runtime = new UiRuntime(scheduler, deployed, new InventoryRenderer(this::messages, this::config, placeholders), hud,
                events, placeholders, this::config, this::messages, this::logFailure);
        deployed.lifecycle(drafts::slugs, runtime::activeDocuments, runtime::projectDeleted);
        var providers = com.wellsetups.wellui.platform.screen.ResourcePackIntegration.detect(name -> plugin.getServer().getPluginManager().getPlugin(name) != null);
        com.wellsetups.wellui.platform.screen.ResourcePackIntegration integration = null;
        var preference = config.screen().integration();
        boolean merged = config.screen().delivery() == com.wellsetups.wellui.platform.config.ScreenConfig.Delivery.ITEMSADDER_MERGED
                || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.ITEMSADDER
                || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.AUTO && providers.size() == 1 && providers.contains("ItemsAdder");
        if (merged && providers.contains("ItemsAdder")) {
            integration = new com.wellsetups.wellui.platform.screen.ItemsAdderIntegration(
                    plugin.getServer().getPluginManager().getPlugin("ItemsAdder").getDataFolder().toPath(), config.screen().mergedPackFile());
        } else if (!merged && (preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.ORAXEN
                || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.NEXO
                || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.AUTO && providers.size() == 1
                && (providers.contains("Oraxen") || providers.contains("Nexo")))) {
            boolean nexo = preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.NEXO
                    || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.AUTO && providers.contains("Nexo");
            String name = nexo ? "Nexo" : "Oraxen";
            var provider = plugin.getServer().getPluginManager().getPlugin(name);
            integration = provider == null ? new com.wellsetups.wellui.platform.screen.UnmanagedPackIntegration(name)
                    : new com.wellsetups.wellui.platform.screen.ImportedPackIntegration(
                            nexo ? com.wellsetups.wellui.platform.screen.ImportedPackIntegration.Provider.NEXO : com.wellsetups.wellui.platform.screen.ImportedPackIntegration.Provider.ORAXEN,
                            provider.getDataFolder().toPath(), config.screen().mergedPackFile().equals("output/generated.zip") ? "pack/pack.zip" : config.screen().mergedPackFile());
        } else if (merged || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.EXTERNAL_OWNER
                || preference == com.wellsetups.wellui.platform.config.ScreenConfig.Integration.AUTO && !providers.isEmpty()) {
            integration = new com.wellsetups.wellui.platform.screen.UnmanagedPackIntegration(providers.toString());
        }
        screenAssets = new ScreenAssets(new ScreenPackBuilder(assets, root.resolve("screen-packs"), config.screen().raster(), com.wellsetups.wellui.core.screen.ScreenProfile.named(version.split("-")[0]).orElse(com.wellsetups.wellui.core.screen.ScreenProfile.target())),
                config.screen(), integration, providers.isEmpty() ? "none" : providers.toString());
        if (!screenAssets.hostProblem().isEmpty()) plugin.getLogger().warning(messages.text(screenAssets.hostProblem()));
        deployed.screenAssets(screenAssets);
        delivery = new PackDelivery(screenAssets, scheduler, this::messages);
        deployed.playerReadiness(delivery::readinessCounts);
        externalMonitor = new com.wellsetups.wellui.platform.screen.ExternalPackMonitor(screenAssets, scheduler);
        scheduler.repeatGlobal(externalMonitor::tick, 20);
        boolean paperFamily;
        try {
            Class.forName("com.destroystokyo.paper.ParticleBuilder", false, plugin.getClass().getClassLoader());
            paperFamily = true;
        } catch (ClassNotFoundException legacy) { paperFamily = false; }
        String minecraft = version.split("-")[0];
        String transportProblem = "screen.dependency";
        var dependency = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (!paperFamily || com.wellsetups.wellui.core.screen.ScreenProfile.named(minecraft).isEmpty()) transportProblem = "screen.version";
        else if (dependency != null && dependency.isEnabled() && dependency.getDescription().getVersion().equals("2.14.0")) {
            try { transport = new PacketEventsTransport(delivery, locale -> messages.textFor(locale, "control.clear")); }
            catch (RuntimeException | LinkageError unavailable) {
                plugin.getLogger().warning(messages.text("screen.dependency") + " " + unavailable.getClass().getSimpleName());
            }
        }
        screen = new ScreenRenderer(screenAssets, transport, delivery, minecraft, paperFamily, transportProblem, this::messages);
        runtime.configureScreen(screen);
        deployed.screenAvailable(screen::backendAvailable);
        deployed.capabilities(screen::environment);
        packLifecycle = new com.wellsetups.wellui.platform.screen.PackLifecycleCoordinator(screenAssets, screen, scheduler);
        plugin.getServer().getPluginManager().registerEvents(packLifecycle, plugin);
        plugin.getServer().getOnlinePlayers().forEach(packLifecycle::track);
        scheduler.repeatGlobal(packLifecycle::tick, 20);
        plugin.getServer().getPluginManager().registerEvents(delivery, plugin);
        plugin.getServer().getOnlinePlayers().forEach(runtime::track);
        plugin.getServer().getPluginManager().registerEvents(runtime, plugin);
        plugin.getServer().getServicesManager().register(WellUiApi.class, runtime, plugin, ServicePriority.Normal);
        var commands = new WellUiCommands(this);
        var command = Objects.requireNonNull(plugin.getCommand("wellui"), "startup.command_missing");
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        scheduler.repeatGlobal(runtime::refresh, 20);
        scheduler.repeatGlobal(runtime::tickScreens, 1);
        scheduler.async(() -> {
            deployed.prepareScreens();
            if (screenAssets.external()) {
                try { screenAssets.verifyExternal(); }
                catch (IOException pending) { plugin.getLogger().info(packFailure(pending, config.locale())); }
            }
        });
        if (config.editorEnabled()) {
            editor = new EditorServer(new InetSocketAddress(config.bind(), config.port()), root, drafts,
                    assets, codec, new DocumentValidator(), deployed,
                    config.publicUrl().isBlank() ? null : URI.create(config.publicUrl()));
            editor.start();
        }
        if (config.metricsEnabled()) metrics = new Metrics(plugin, 34331);
        plugin.getLogger().info(messages.text("startup.ready", "version", plugin.getDescription().getVersion(),
                "platform", platform, "minecraft", version, "count", runtime.documentIds().size()));
        plugin.getLogger().info(messages.text("startup.next"));
        plugin.getLogger().info(messages.text("screen.startup", "mode", config.screen().renderer(),
                "delivery", config.screen().delivery(), "dependency", transport != null));
    }
    public RuntimeConfig config() { return config; }
    public Messages messages() { return messages; }
    public PlatformScheduler scheduler() { return scheduler; }
    public UiRuntime runtime() { return runtime; }
    public String editorUrl() {
        if (editor == null) return null;
        String host = config.bind().contains(":") ? "[" + config.bind() + "]" : config.bind();
        String url = config.publicUrl().isBlank() ? "http://" + host + ":" + editor.port() : config.publicUrl();
        return url + "/#token=" + editor.issueToken();
    }
    /** Called only on the IO worker. Invalid candidates never replace the live state. */
    public synchronized boolean reload() throws IOException {
        RuntimeConfig next = RuntimeConfig.load(root.resolve("config.yml"));
        if (config.restartRequired(next)) return false;
        Messages nextMessages = Messages.load(root.resolve("lang"), next.locale());
        deployed.reload();
        deployed.prepareScreens();
        config = next;
        messages = nextMessages;
        return true;
    }
    public void migrate() throws IOException {
        RuntimeConfig.migrate(root.resolve("config.yml"));
        for (var document : drafts.list()) drafts.save(document);
    }
    public void exportPacks() throws IOException {
        for (var document : deployed.snapshot().values()) deployed.deploy(document);
    }
    public HudPackBuilder.Prepared exportHud(String id) throws IOException {
        var document = deployed.snapshot().get(deployed.resolve(id));
        if (document == null) throw new IllegalArgumentException("open.not_found");
        return hud.prepare(document);
    }
    public com.wellsetups.wellui.core.ScreenGenerationBuilder.Generation exportScreen(String id) throws IOException {
        deployed.prepareScreen(id);
        return screenAssets.generation();
    }
    public String verifyMergedPack() throws IOException { return screenAssets.verifyExternal(); }
    public void repairMergedSource() throws IOException { deployed.repairScreenSources(); }
    public String packFailure(Exception failure, String locale) {
        String message = messages.textFor(locale, failure.getMessage() == null ? "error.internal" : failure.getMessage(),
                "resource", failure instanceof com.wellsetups.wellui.platform.screen.PackProblem problem ? problem.resource() : "");
        return failure instanceof com.wellsetups.wellui.platform.screen.PackProblem problem && !problem.detail().isEmpty()
                ? message + " " + messages.textFor(locale, "screen.verify.difference", "detail", problem.detail()) : message;
    }
    public String integrationReport(String locale) {
        var integration = screenAssets.integration();
        if (integration == null) return messages.textFor(locale, "screen.verify.not_external");
        var report = integration.report();
        var problem = report.problem();
        return messages.textFor(locale, "screen.verify.report", "provider", integration.owner(), "owner", screenAssets.owner(),
                "generation", report.generation(), "sourceGeneration", report.sourceGeneration(), "compiledGeneration", report.compiledGeneration(),
                "root", integration.sourceRoot(), "files", report.files(),
                "source", messages.textFor(locale, "screen.verify.state_" + report.source().toLowerCase(java.util.Locale.ROOT)),
                "output", messages.textFor(locale, "screen.verify.state_" + report.output().toLowerCase(java.util.Locale.ROOT)),
                "atlas", messages.textFor(locale, "screen.verify.state_" + report.atlas().toLowerCase(java.util.Locale.ROOT)),
                "models", report.models(), "pixels", report.pixels(), "rewrites", report.rewrites(),
                "build", problem != null && problem.buildRequired(), "conflict", problem != null && problem.conflict(),
                "reason", problem == null ? messages.textFor(locale, "screen.verify.none") : packFailure(problem, locale), "legacy", report.legacy());
    }
    public java.util.concurrent.CompletableFuture<Boolean> preload(org.bukkit.entity.Player player) { return screen.preload(player, true); }
    public String debug(org.bukkit.entity.Player player) {
        return debug() + "\n" + delivery.diagnostic(player.getUniqueId()) + "\n" + runtime.screenDiagnostic(player.getUniqueId()) + "\n" + runtime.logicDiagnostic(player.getUniqueId());
    }
    public String screenPackUrl(String hash) {
        if (!screenAssets.hostProblem().isEmpty()) return messages.text(screenAssets.hostProblem());
        if (screenAssets.external()) return messages.text("screen.merge_pending");
        if (config.screen().publicUrl().equals("AUTO") && config.screen().delivery() == com.wellsetups.wellui.platform.config.ScreenConfig.Delivery.SELF_HOSTED) return messages.text("screen.auto_url");
        String url = screenAssets.url(hash);
        return url.isEmpty() ? messages.text("screen.export_only") : url;
    }
    public String debug() {
        var pack = hud.latest();
        return messages.text("debug.report", "version", plugin.getDescription().getVersion(), "platform", platform,
                "minecraft", version, "java", System.getProperty("java.version"), "folia", scheduler.folia(),
                "sessions", runtime.sessionCount(), "components", runtime.componentCount(), "projects", runtime.documentIds().size(),
                "editor", editor != null, "papi", placeholders.enabled(), "metrics", metrics != null,
                "hash", deployed.lastHash().isEmpty() ? messages.text("debug.no_pack") : deployed.lastHash())
                + "\n" + messages.text("hud.debug", "sessions", runtime.hudSessionCount(), "sends", hud.sends(),
                "millis", pack == null ? 0 : pack.buildMillis(), "bytes", pack == null ? 0 : pack.bytes(),
                "hash", pack == null ? messages.text("debug.no_pack") : pack.sha256())
                + "\n" + messages.text("screen.debug", "mode", config.screen().renderer(), "selection", runtime.lastSelection(),
                "sessions", runtime.screenSessionCount(), "visuals", runtime.screenSessionCount() * 2, "hitboxes", runtime.screenHitboxes(),
                "updates", runtime.updatesPerSecond(), "inputs", runtime.inputsPerSecond(), "sends", screen.sends(),
                "millis", screenAssets.averageMillis(), "delivery", config.screen().delivery(), "states", delivery.summary(),
                "graphs", screenAssets.validatedCount()) + "\n" + delivery.diagnostic(new java.util.UUID(0, 0)) + screenResources()
                + "\n" + integrationReport(config.locale()) + "\nCapabilities: " + screen.environment()
                + "\nPack retention: " + (screenAssets.maintenanceProblem().isEmpty() ? "OK" : screenAssets.maintenanceProblem());
    }
    private String screenResources() {
        var pack = screenAssets.latest();
        if (pack == null) return "\n" + messages.text("screen.no_graph");
        var report = pack.validation();
        return "\n" + messages.text("screen.resources", "project", pack.document().id(), "hash", pack.sha256(),
                "profile", report.profile(), "path", pack.path(), "bytes", pack.bytes(), "atlas", report.atlasStrategy(),
                "baseItem", report.base().itemModel(), "baseModel", report.base().model(), "baseTexture", report.base().texture(),
                "cursorItem", report.cursor().itemModel(), "cursorModel", report.cursor().model(), "cursorTexture", report.cursor().texture(),
                "hovers", report.hover().size(), "sprites", report.sprites())
                + " | canvas=" + pack.document().width() + "x" + pack.document().height() + " | raster=" + pack.raster().width() + "x" + pack.raster().height()
                + " | render scale=" + pack.raster().scale();
    }
    public void logFailure(Exception failure) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, messages.text("error.operation", "reason", messages.text(failure.getMessage() == null
                ? "error.internal" : failure.getMessage())), failure);
    }
    @Override public void close() {
        if (editor != null) editor.close();
        if (metrics != null) metrics.shutdown();
        if (packLifecycle != null) packLifecycle.close();
        if (externalMonitor != null) externalMonitor.close();
        if (runtime != null) runtime.close();
        if (delivery != null) delivery.close();
        if (transport != null) {
            try { transport.close(); }
            catch (RuntimeException | LinkageError failure) { plugin.getLogger().log(java.util.logging.Level.WARNING, messages.text("screen.shutdown"), failure); }
        }
        if (screenAssets != null) screenAssets.close();
        HandlerList.unregisterAll(plugin);
        plugin.getServer().getServicesManager().unregisterAll(plugin);
        if (scheduler != null) scheduler.close();
    }
}
