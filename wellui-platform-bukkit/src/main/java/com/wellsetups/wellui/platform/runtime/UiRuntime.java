package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.OpenResult;
import com.wellsetups.wellui.api.WellUiApi;
import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.api.event.UiClickEvent;
import com.wellsetups.wellui.api.event.UiCloseEvent;
import com.wellsetups.wellui.api.event.UiEvent;
import com.wellsetups.wellui.api.event.UiOpenEvent;
import com.wellsetups.wellui.api.event.UiHoverEvent;
import com.wellsetups.wellui.core.logic.CompiledLogic;
import com.wellsetups.wellui.core.logic.LogicExecutor;
import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.event.UiLogicExecuteEvent;
import com.wellsetups.wellui.api.event.UiVariableChangeEvent;
import com.wellsetups.wellui.core.screen.RendererSelection;
import com.wellsetups.wellui.core.screen.RendererSelection.Mode;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.config.RuntimeConfig;
import com.wellsetups.wellui.platform.scheduling.PlatformScheduler;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Session/input orchestration. Disk and HTTP work never run on gameplay threads. */
public final class UiRuntime implements WellUiApi, Listener, AutoCloseable {
    private final PlatformScheduler scheduler;
    private final DeployedProjects projects;
    private final UiRenderer inventory;
    private final GlyphHudRenderer hud;
    private final EventHub events;
    private final PlaceholderService placeholders;
    private final Supplier<RuntimeConfig> config;
    private final Supplier<Messages> messages;
    private final Consumer<Exception> errors;
    private final InventoryViewAccess views = new InventoryViewAccess();
    private final PendingOperations pending = new PendingOperations();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();
    private final Map<UUID, UiSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<OpenResult>> openings = new ConcurrentHashMap<>();
    private ScreenRenderer screen;
    private volatile String lastSelection = "NONE";
    private volatile long updatesPerSecond;
    private volatile long inputsPerSecond;
    private long measuredAt = System.nanoTime();
    private long measuredUpdates;
    private long measuredInputs;
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    public UiRuntime(PlatformScheduler scheduler, DeployedProjects projects, UiRenderer inventory, GlyphHudRenderer hud,
                     EventHub events, PlaceholderService placeholders, Supplier<RuntimeConfig> config,
                     Supplier<Messages> messages, Consumer<Exception> errors) {
        this.scheduler = scheduler;
        this.projects = projects;
        this.inventory = inventory;
        this.hud = hud;
        this.events = events;
        this.placeholders = placeholders;
        this.config = config;
        this.messages = messages;
        this.errors = errors;
    }
    public void track(Player player) {
        players.put(player.getUniqueId(), player);
        names.put(player.getName().toLowerCase(java.util.Locale.ROOT), player.getUniqueId());
    }
    public Player player(String name) {
        UUID id = names.get(name.toLowerCase(java.util.Locale.ROOT));
        return id == null ? null : players.get(id);
    }
    public Set<String> playerNames() { return Set.copyOf(names.keySet()); }
    public int sessionCount() { return sessions.size(); }
    public int hudSessionCount() { return (int) sessions.values().stream().filter(s -> s.renderer == hud).count(); }
    public int componentCount() { return sessions.values().stream().mapToInt(s -> s.elements).sum(); }
    public void configureScreen(ScreenRenderer value) { screen = value; }
    public int screenSessionCount() { return (int) sessions.values().stream().filter(ScreenRenderer.Session.class::isInstance).count(); }
    public int screenHitboxes() { return sessions.values().stream().filter(ScreenRenderer.Session.class::isInstance)
            .map(ScreenRenderer.Session.class::cast).mapToInt(s -> s.pack.scene().buttons().size()).sum();
        }
    public String screenDiagnostic(UUID owner) { return screen == null ? "SCREEN unavailable" : screen.diagnostic(sessions.get(owner)); }
    public String lastSelection() { return lastSelection; }
    public long updatesPerSecond() { return updatesPerSecond; }
    public long inputsPerSecond() { return inputsPerSecond; }

    public CompletionStage<OpenResult> open(UUID playerId, String documentId) {
        return open(playerId, documentId, config.get().screen().renderer());
    }
    public CompletionStage<OpenResult> open(UUID playerId, String documentId, Mode mode) {
        return requestOpen(playerId, projects.resolve(documentId), mode, "", 0);
    }
    public CompletionStage<OpenResult> preview(UUID playerId, String documentId, String acknowledgment) {
        return requestOpen(playerId, projects.resolve(documentId), Mode.HUD, acknowledgment, 0);
    }
    private CompletionStage<OpenResult> requestOpen(UUID playerId, String documentId, Mode mode, String acknowledgment, int chain) {
        if (closed) return CompletableFuture.completedFuture(OpenResult.UNAVAILABLE);
        Player player = players.get(playerId);
        if (player == null) return CompletableFuture.completedFuture(OpenResult.OFFLINE);
        var future = pending.create(OpenResult.UNAVAILABLE);
        if (future.isDone()) return future;
        try { scheduler.entity(player, () -> {
            try {
                var revision = projects.revision(documentId);
                var document = revision == null ? null : revision.document();
                if (closed) future.complete(OpenResult.UNAVAILABLE);
                else if (!player.isOnline()) future.complete(OpenResult.OFFLINE);
                else if (document == null) future.complete(OpenResult.NOT_FOUND);
                else if (!allowed(player, documentId)) future.complete(OpenResult.DENIED);
                else {
                    cancelOpening(playerId);
                    synchronized (lifecycle) {
                        if (closed) {
                            future.complete(OpenResult.UNAVAILABLE);
                            return;
                        }
                        openings.put(playerId, future);
                    }
                    var event = new UiOpenEvent(playerId, documentId);
                    events.fire(event);
                    if (event.cancelled()) {
                        future.complete(OpenResult.CANCELLED);
                        return;
                    }
                    String problem = mode == Mode.HUD || mode == Mode.INVENTORY ? ""
                            : screen == null ? "screen.dependency" : screen.unavailable(player, document);
                    if (screen != null && screen.mayWait(problem)) problem = "";
                    select(player, document, revision.logic(), chain, mode, acknowledgment, problem, future, false);
                }
            } catch (RuntimeException failure) {
                lastSelection = mode + " -> ERROR (" + failure.getMessage() + ")";
                if (!closed && player.isOnline()) player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.open_failed",
                        "renderer", mode, "reason", messages.get().text(failure.getMessage() == null ? "error.internal" : failure.getMessage())));
                errors.accept(failure);
                future.completeExceptionally(failure);
            } finally {
                if (future.isDone()) openings.remove(playerId, future);
            }
        }, () -> future.complete(OpenResult.OFFLINE));
            }
        catch (RuntimeException failure) { future.completeExceptionally(failure); }
        return future;
    }
    private void select(Player player, UiDocument document, CompiledLogic logic, int chain, Mode mode, String acknowledgment, String problem,
                        CompletableFuture<OpenResult> future, boolean ready) {
        UUID id = player.getUniqueId();
        if (closed || openings.get(id) != future || future.isDone()) return;
        var decision = RendererSelection.select(mode, problem, config.get().screen().fallbackInventory());
        lastSelection = mode + " -> " + (decision.selected() == null ? "UNAVAILABLE" : decision.selected())
                + (problem.isEmpty() ? "" : " (" + problem + ")");
        if (!problem.isEmpty()) player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.selection",
                "requested", mode, "selected", decision.selected() == null ? "UNAVAILABLE" : decision.selected(),
                "reason", messages.get().textFor(player.getLocale(), problem)));
        if (decision.selected() == null) {
            future.complete(OpenResult.UNAVAILABLE);
            return;
        }
        if (decision.selected() == Mode.SCREEN && !ready) {
            var resources = screen.require(player, document);
            if (!resources.isDone()) player.sendMessage(messages.get().prefixed(player.getLocale(), screen.waitingMessage()));
            resources.whenComplete((loaded, failure) -> {
                if (closed || openings.get(id) != future || future.isDone()) return;
                try { scheduler.entity(player, () -> {
                    try {
                        if (closed || openings.get(id) != future || future.isDone()) return;
                        if (!document.equals(projects.snapshot().get(document.id()))) {
                            player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.revision_changed"));
                            future.complete(OpenResult.CANCELLED);
                        } else if (!player.isOnline()) future.complete(OpenResult.OFFLINE);
                        else if (!allowed(player, document.id())) future.complete(OpenResult.DENIED);
                        else select(player, document, logic, chain, mode, acknowledgment,
                                failure == null && Boolean.TRUE.equals(loaded) ? "" : "screen.pack_unavailable", future, true);
                    } catch (RuntimeException error) {
                        errors.accept(error);
                        future.completeExceptionally(error);
                    } finally { if (future.isDone()) openings.remove(id, future);
                            }
                }, () -> {
                    openings.remove(id, future);
                    future.complete(OpenResult.OFFLINE);
                });
                    } catch (RuntimeException error) {
                    openings.remove(id, future);
                    future.completeExceptionally(error);
                }
            });
            return;
        }
        UiRenderer renderer = decision.selected() == Mode.SCREEN ? screen : decision.selected() == Mode.HUD ? hud : inventory;
        String unsupported = renderer.capabilities().unsupported(document);
        if (!unsupported.isEmpty()) {
            player.sendMessage(messages.get().prefixed(player.getLocale(), unsupported));
            future.complete(OpenResult.UNAVAILABLE);
            return;
        }
        finishOpen(player, document, logic, chain, renderer, acknowledgment, future);
    }
    private void finishOpen(Player player, UiDocument document, CompiledLogic logic, int chain, UiRenderer renderer, String acknowledgment,
                            CompletableFuture<OpenResult> future) {
        UUID id = player.getUniqueId();
        UiSession next = null;
        try {
            next = renderer.create(player, document, acknowledgment);
            bindLogic(player, next, logic, chain);
            if (closed || openings.get(id) != future) {
                next.logic.close();
                next.animations.close();
                renderer.close(null, next);
                future.complete(OpenResult.UNAVAILABLE);
                return;
            }
            end(player, id, UiCloseEvent.Reason.REPLACED);
            if (renderer.open(player, next)) {
                synchronized (lifecycle) { if (!closed && openings.get(id) == future) sessions.put(id, next); }
                if (closed || openings.get(id) != future) {
                    next.logic.close();
                    next.animations.close();
                    renderer.close(scheduler.folia() ? null : player, next);
                    future.complete(OpenResult.UNAVAILABLE);
                } else {
                    future.complete(OpenResult.OPENED);
                    next.logic.fire("on_open", "", chain);
                }
            } else {
                next.logic.close();
                next.animations.close();
                renderer.close(player, next);
                future.complete(OpenResult.CANCELLED);
            }
        } catch (RuntimeException failure) {
            if (next != null) {
                if (next.logic != null) next.logic.close();
                if (next.animations != null) next.animations.close();
                sessions.remove(id, next);
                try { renderer.close(player.isOnline() ? player : null, next); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            lastSelection = renderer.capabilities().id() + " -> ERROR (" + failure.getMessage() + ")";
            player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.open_failed",
                    "renderer", renderer.capabilities().id(), "reason", messages.get().text(failure.getMessage() == null ? "error.internal" : failure.getMessage())));
            errors.accept(failure);
            future.completeExceptionally(failure);
        }
    }
    private void cancelOpening(UUID id) {
        var opening = openings.remove(id);
        if (opening != null) opening.complete(OpenResult.CANCELLED);
        if (screen != null) screen.delivery().cancelWaiting(id);
    }
    public CompletionStage<Boolean> close(UUID playerId) {
        Player player = players.get(playerId);
        if (player == null || closed) return CompletableFuture.completedFuture(false);
        var future = pending.create(false);
        if (future.isDone()) return future;
        try { scheduler.entity(player, () -> {
            try {
                boolean found = !closed && sessions.containsKey(playerId);
                cancelOpening(playerId);
                if (found) end(player.isOnline() ? player : null, playerId, UiCloseEvent.Reason.CLOSED);
                future.complete(found);
            } catch (RuntimeException failure) { future.completeExceptionally(failure);
                    }
        }, () -> future.complete(false));
            }
        catch (RuntimeException failure) { future.completeExceptionally(failure); }
        return future;
    }
    public Set<String> documentIds() { return Set.copyOf(projects.snapshot().keySet()); }
    public String resolveDocument(String name) { return projects.resolve(name); }
    public Set<String> commandNames() { return documentIds().stream().map(projects::slug).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    public List<String> projectDescriptions() { return projects.snapshot().values().stream().map(d -> projects.slug(d.id()) + " — " + d.name()).sorted().toList(); }
    public List<UiDocument> activeDocuments() { return sessions.values().stream().map(s -> s.document).distinct().toList(); }
    /** Called from deployment IO. Each live close is marshalled to the owning player/region. */
    public void projectDeleted(String document) {
        sessions.values().stream().filter(s -> s.document.id().equals(document)).forEach(session -> {
            Player player = players.get(session.owner);
            if (player == null) { retire(session);
                return;
            }
            scheduler.entity(player, () -> {
                if (sessions.get(session.owner) == session) end(player, session.owner, UiCloseEvent.Reason.REPLACED);
            }, () -> retire(session));
        });
    }
    public <E extends UiEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> listener) {
        return events.subscribe(type, listener);
    }
    @EventHandler public void joined(PlayerJoinEvent event) { track(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cancelOpening(id);
        if (screen != null) screen.delivery().disconnect(id);
        try { end(null, id, UiCloseEvent.Reason.DISCONNECTED); }
        finally {
            players.remove(id);
            names.values().removeIf(id::equals);
            placeholders.clear(id);
        }
    }
    @EventHandler public void inventoryClosed(InventoryCloseEvent event) {
        var session = sessions.get(event.getPlayer().getUniqueId());
        if (session instanceof InventorySession nativeSession && nativeSession.inventory == event.getInventory()) {
            end(event.getPlayer() instanceof Player player ? player : null, session.owner, UiCloseEvent.Reason.CLOSED);
        }
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (views.top(event.getView()).getHolder() instanceof InventorySession) event.setCancelled(true);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(views.top(event.getView()).getHolder() instanceof InventorySession session)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (!(session.slots.get(event.getRawSlot()) instanceof ButtonComponent button)) return;
        activate(player, session, button, event.getClick() == ClickType.LEFT ? UiClickEvent.Button.LEFT : UiClickEvent.Button.RIGHT);
    }
    /** Private command transport: token is player/session-bound, never a document action supplied by the client. */
    public void hudInput(Player player, String token, String componentId) {
        var session = sessions.get(player.getUniqueId());
        if (!(session instanceof GlyphHudRenderer.Session hudSession)
                || !hudSession.input.accepts(player.getUniqueId(), token, System.nanoTime())) {
            player.sendMessage(messages.get().prefixed(player.getLocale(), "hud.expired"));
            return;
        }
        ButtonComponent button = hudSession.buttons.get(componentId);
        if (button != null) activate(player, session, button, UiClickEvent.Button.LEFT);
    }
    private boolean usable(Player player, UiSession session) {
        return !closed && player.isOnline() && session.owner.equals(player.getUniqueId())
                && sessions.get(session.owner) == session && allowed(player, session.document.id())
                && session.renderer.active(player, session);
    }
    private void activate(Player player, UiSession session, ButtonComponent button, UiClickEvent.Button input) {
        activate(player, session, button, input, new com.wellsetups.wellui.core.control.ControlRouter.Activation(button, null));
    }
    private void activate(Player player, UiSession session, ButtonComponent button, UiClickEvent.Button input,
                          com.wellsetups.wellui.core.control.ControlRouter.Activation activation) {
        if (!usable(player, session) || !visible(session, button.id())) return;
        long now = System.nanoTime();
        if (session.lastClick != 0 && now - session.lastClick < config.get().clickCooldownMs() * 1_000_000) return;
        session.lastClick = now;
        try { scheduler.entity(player, () -> {
            try {
                if (!usable(player, session)) return;
                var click = new UiClickEvent(session.owner, session.document.id(), button.id(), input);
                events.fire(click);
                if (click.cancelled() || !usable(player, session)) return;
                if (visible(session, button.id()) && !session.state.controls().disabled(button.id())) {
                    if (session instanceof ScreenRenderer.Session s && activation != null) screen.activate(s, activation);
                    session.logic.fire("on_click", button.id(), 0);
                    controlEvents(session);
                }
            } catch (RuntimeException failure) { failed(player, session, failure);
                    }
        }, () -> retire(session));
            }
        catch (RuntimeException failure) { failed(player, session, failure); }
    }
    public void refresh() {
        if (closed) return;
        sessions.forEach((id, session) -> {
            if (session instanceof ScreenRenderer.Session) return;
            Player player = players.get(id);
            if (player != null && session.refreshQueued.compareAndSet(false, true)) {
                try { scheduler.entity(player, () -> {
                    try {
                        if (!closed && sessions.get(id) == session) {
                            if (!usable(player, session)) end(player.isOnline() ? player : null, id, UiCloseEvent.Reason.CLOSED);
                            else {
                                session.nextPulse = !session.nextPulse;
                                session.renderer.refresh(player, session, config.get().pulseButtons() && session.nextPulse);
                            }
                        }
                    } catch (RuntimeException failure) { failed(player, session, failure);
                            }
                    finally { session.refreshQueued.set(false); }
                }, () -> retire(session));
                    }
                catch (RuntimeException failure) {
                    retire(session);
                    errors.accept(failure);
                }
            }
        });
    }
    /** One dispatcher, bounded to at most one outstanding owner callback per screen. */
    public void tickScreens() {
        if (closed) return;
        if (screen != null) screen.delivery().tick();
        long now = System.nanoTime();
        if (screen != null && now - measuredAt >= 1_000_000_000L) {
            long updates = screen.updates();
            long inputs = screen.inputs();
            updatesPerSecond = Math.round((updates - measuredUpdates) * 1_000_000_000.0 / (now - measuredAt));
            inputsPerSecond = Math.round((inputs - measuredInputs) * 1_000_000_000.0 / (now - measuredAt));
            measuredAt = now;
            measuredUpdates = updates;
            measuredInputs = inputs;
        }
        sessions.forEach((id, base) -> {
            UiSession session = base;
            if (!(base instanceof ScreenRenderer.Session) && base.document.animations().isEmpty()) return;
            Player player = players.get(id);
            if (player == null || !session.refreshQueued.compareAndSet(false, true)) return;
            try { scheduler.entity(player, () -> {
                try {
                    if (closed || sessions.get(id) != session) return;
                    if (!usable(player, session)) end(player.isOnline() ? player : null, id, UiCloseEvent.Reason.CLOSED);
                    else {
                        if (session.animations != null) session.animations.tick();
                        if (closed || sessions.get(id) != session) return;
                        if (!(session instanceof ScreenRenderer.Session screenSession)) { session.renderer.refresh(player, session, false);
                            return;
                        }
                        screen.translate(player, screenSession);
                        if (screenSession.dynamic != null) screenSession.dynamic.refresh(false);
                        var update = screen.update(screenSession);
                        if (update.hover().exited() != null) hover(session, update.hover().exited(), false);
                        if (sessions.get(id) == session && update.hover().entered() != null) hover(session, update.hover().entered(), true);
                        logicHover(session, screenSession.hover.current(), System.nanoTime());
                        if (update.clicked() != null) activate(player, session, update.clicked(), UiClickEvent.Button.LEFT, update.activation());
                        controlEvents(session);
                    }
                } catch (RuntimeException failure) { failed(player, session, failure);
                        }
                finally { session.refreshQueued.set(false); }
            }, () -> retire(session));
                }
            catch (RuntimeException failure) {
                retire(session);
                errors.accept(failure);
            }
        });
    }
    private void hover(UiSession session, String component, boolean entered) {
        events.fire(new UiHoverEvent(session.owner, session.document.id(), component, entered));
    }
    /** Hard per-dispatch budget also bounds control-event cycles that span separate acyclic graphs. */
    private void controlEvents(UiSession session) {
        if (session.logic == null || session.ending) return;
        for (int budget = 0; budget < 16; budget++) {
            var event = session.state.controls().poll();
            if (event == null) return;
            events.fire(new com.wellsetups.wellui.api.event.UiControlEvent(session.owner, session.document.id(), event.component(),
                    com.wellsetups.wellui.api.event.UiControlEvent.Type.valueOf(event.type().substring(3).toUpperCase(java.util.Locale.ROOT)),
                    session.state.controls().value(event.component())));
            if (session.ending) return;
            session.logic.fire(event.type(), event.component(), session.openChain);
            if (session.ending) return;
        }
        session.state.controls().clearEvents();
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void scroll(org.bukkit.event.player.PlayerItemHeldEvent event) {
        if (!(sessions.get(event.getPlayer().getUniqueId()) instanceof ScreenRenderer.Session session)) return;
        event.setCancelled(true);
        int delta = Math.floorMod(event.getNewSlot() - event.getPreviousSlot() + 4, 9) - 4;
        session.input.scroll(delta);
    }
    /** Coalesce rapid pointer changes, but always deliver the matching exit before the next enter. */
    void logicHover(UiSession session, String component, long now) {
        if (session.logic == null || session.ending || java.util.Objects.equals(session.logicHovered, component)) return;
        if (session.lastHover != 0 && now - session.lastHover < config.get().clickCooldownMs() * 1_000_000) return;
        session.lastHover = now;
        String previous = session.logicHovered;
        session.logicHovered = component;
        if (previous != null) session.logic.fire("on_hover_exit", previous, session.openChain);
        if (component != null) session.logic.fire("on_hover_enter", component, session.openChain);
    }
    private void leaveScreen(Player player) {
        UUID id = player.getUniqueId();
        cancelOpening(id);
        if (sessions.get(id) instanceof ScreenRenderer.Session || sessions.get(id) != null && !sessions.get(id).document.animations().isEmpty()) end(player, id, UiCloseEvent.Reason.CLOSED);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { leaveScreen(event.getPlayer()); }
    @EventHandler public void worldChanged(PlayerChangedWorldEvent event) { leaveScreen(event.getPlayer()); }
    @EventHandler public void respawn(PlayerRespawnEvent event) { leaveScreen(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { leaveScreen(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) leaveScreen(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && sessions.get(player.getUniqueId()) instanceof ScreenRenderer.Session) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) leaveScreen(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (sessions.get(event.getPlayer().getUniqueId()) instanceof ScreenRenderer.Session) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interactEntity(PlayerInteractEntityEvent event) {
        if (sessions.get(event.getPlayer().getUniqueId()) instanceof ScreenRenderer.Session) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventoryOpened(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && sessions.get(player.getUniqueId()) instanceof ScreenRenderer.Session) leaveScreen(player);
    }
    @EventHandler public void dependencyDisabled(PluginDisableEvent event) {
        if (screen != null && event.getPlugin().getName().equalsIgnoreCase("packetevents")) screen.unavailable();
    }
    private boolean visible(UiSession session, String component) {
        return session.scene().opacity(component) >= (session instanceof ScreenRenderer.Session ? 1.0 / 16 : Double.MIN_VALUE);
    }
    public String logicDiagnostic(UUID owner) {
        var session = sessions.get(owner);
        if (session == null || session.logic == null) return "Logic session inactive";
        var info = session.logic.diagnostic();
        return "Logic project=" + session.document.id() + " | graphNodes=" + session.document.logic().nodes().size()
                + " | event=" + info.event() + " | executed=" + info.nodes() + " | action=" + info.action()
                + " | variables=" + info.variables() + " | pending=" + info.continuations() + " | failure=" + info.failure() + " | animation=" + (session.animations == null ? "none" : session.animations.diagnostic());
    }
    private void bindLogic(Player player, UiSession session, CompiledLogic compiled, int chain) {
        session.openChain = chain;
        session.state.controls().messages(key -> messages.get().textFor(player.getLocale(), key));
        session.animations = new com.wellsetups.wellui.core.animation.UiAnimationPlayer(session.document.animations(),
                new com.wellsetups.wellui.core.animation.UiAnimationPlayer.Host() {
            public boolean active() { return !closed && !session.ending && usable(player, session); }
            public void event(String kind, String animation, int depth) {
                if (!active()) return;
                switch (kind) {
                    case "start" -> events.fire(new com.wellsetups.wellui.api.event.UiAnimationStartEvent(session.owner, session.document.id(), session.token, animation));
                    case "stop" -> events.fire(new com.wellsetups.wellui.api.event.UiAnimationStopEvent(session.owner, session.document.id(), session.token, animation));
                    case "complete" -> {
                        events.fire(new com.wellsetups.wellui.api.event.UiAnimationCompleteEvent(session.owner, session.document.id(), session.token, animation));
                        if (active()) session.logic.fire("on_animation_complete", animation, depth);
                    }
                    default -> throw new IllegalArgumentException("animation.operation");
                }
            }
        });
        session.logic = new LogicExecutor(compiled, session.state, new LogicExecutor.Host() {
            public boolean active() {
                return !closed && player.isOnline() && allowed(player, session.document.id())
                        && (session.ending || usable(player, session));
            }
            public boolean permission(String value) { return player.hasPermission(value); }
            public String placeholder(String value) {
                return placeholders.enabled() ? placeholders.resolve(player, value, config.get().placeholderRefreshMs()) : null;
            }
            public boolean sneaking() { return player.isSneaking(); }
            public boolean flying() { return player.isFlying(); }
            public String playerName() { return player.getName(); }
            public void message(String value) { player.sendMessage(Messages.color(value)); }
            public void command(String value) { player.performCommand(value); }
            public void close() { if (!session.ending) end(player, session.owner, UiCloseEvent.Reason.CLOSED); }
            public void open(String project, int depth) {
                if (session.ending && session.closePolicy != UiSession.UiCloseEventReason.NORMAL) return;
                if (projects.revision(project) == null) throw new IllegalArgumentException("logic.project");
                if (!allowed(player, project)) throw new IllegalArgumentException("logic.denied");
                requestOpen(session.owner, project, config.get().screen().renderer(), "", depth);
            }
            public AutoCloseable delay(long ticks, Runnable work) {
                if (session.ending) throw new IllegalArgumentException("logic.session_closed");
                return scheduler.delay(player, ticks, work, () -> retire(session));
            }
            public boolean before(String event, String node) {
                var callback = new UiLogicExecuteEvent(session.owner, session.document.id(), event, node);
                events.fire(callback);
                return !callback.cancelled();
            }
            public void variableChanged(String name, LogicValue old, LogicValue value) {
                events.fire(new UiVariableChangeEvent(session.owner, session.document.id(), name, old, value));
            }
            public void animation(String operation, String id, int depth) {
                if (session.ending) throw new IllegalArgumentException("logic.session_closed");
                session.animations.control(operation, id, depth);
            }
            public void refresh() { if (!session.ending && usable(player, session)) session.renderer.refresh(player, session, false); }
        });
    }
    private boolean allowed(Player player, String document) {
        return player.hasPermission("wellui.admin") || player.hasPermission("wellui.ui." + document);
    }
    private void end(Player player, UUID id, UiCloseEvent.Reason reason) {
        UiSession session = sessions.remove(id);
        if (session != null) {
            session.ending = true;
            session.closePolicy = reason == UiCloseEvent.Reason.CLOSED ? UiSession.UiCloseEventReason.NORMAL : UiSession.UiCloseEventReason.REPLACED;
            try {
                if (player != null && session.logic != null && player.isOnline() && allowed(player, session.document.id())) {
                    session.logic.fire("on_close", "", session.openChain);
                }
                if (session.logic != null) session.logic.close();
            if (session.animations != null) session.animations.close();
                if (session instanceof ScreenRenderer.Session s && s.hover.current() != null) hover(s, s.hover.current(), false);
                session.renderer.close(player, session);
            }
            finally { if (session.logic != null) session.logic.close();
            if (session.animations != null) session.animations.close();
                session.state.controls().close();
                events.fire(new UiCloseEvent(id, session.document.id(), reason));
                }
        }
    }
    private void failed(Player player, UiSession session, RuntimeException failure) {
        if (sessions.get(session.owner) == session) {
            try { end(player.isOnline() ? player : null, session.owner, UiCloseEvent.Reason.CLOSED); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        }
        errors.accept(failure);
        if (player.isOnline()) player.sendMessage(messages.get().prefixed(player.getLocale(), "hud.render_error"));
    }
    /** Retired scheduler callbacks have no player ownership: do not send or dispatch player events. */
    private void retire(UiSession session) {
        session.refreshQueued.set(false);
        if (sessions.remove(session.owner, session)) {
            if (session.logic != null) session.logic.close();
            if (session.animations != null) session.animations.close();
            try { session.renderer.close(null, session); }
            finally { session.state.controls().close(); }
        }
    }
    public void close() {
        java.util.List<UiSession> remaining;
        synchronized (lifecycle) {
            closed = true;
            remaining = java.util.List.copyOf(sessions.values());
            sessions.clear();
        }
        pending.close();
        openings.clear();
        for (var session : remaining) {
            if (session.logic != null) session.logic.close();
            if (session.animations != null) session.animations.close();
            Player player = scheduler.folia() ? null : players.get(session.owner);
            try { session.renderer.close(player != null && player.isOnline() ? player : null, session); }
            catch (RuntimeException failure) { errors.accept(failure); }
            finally { session.state.controls().close(); }
        }
        hud.clearPrepared();
        players.clear();
        names.clear();
        placeholders.clear();
        events.close();
    }
}
