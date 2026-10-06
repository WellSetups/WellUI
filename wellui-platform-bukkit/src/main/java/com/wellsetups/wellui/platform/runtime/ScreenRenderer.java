package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.RendererCapabilities;
import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.core.screen.HoverState;
import com.wellsetups.wellui.core.screen.ScreenInput;
import com.wellsetups.wellui.core.screen.ScreenPlane;
import com.wellsetups.wellui.core.screen.ScreenFrame;
import com.wellsetups.wellui.core.screen.ScreenProfile;
import com.wellsetups.wellui.core.screen.Vec3;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.screen.PackDelivery;
import com.wellsetups.wellui.platform.screen.ScreenAssets;
import com.wellsetups.wellui.platform.screen.ScreenTransport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Authored canvas on a per-viewer plane with direct look-derived cursor and hover. */
public final class ScreenRenderer implements UiRenderer {
    public record Update(ButtonComponent clicked, HoverState.Transition hover, com.wellsetups.wellui.core.control.ControlRouter.Activation activation) {
        public Update(ButtonComponent clicked, HoverState.Transition hover) { this(clicked, hover, null); }
    }
    private record Geometry(Vec3 eye, Vec3 center, Vec3 delta, ScreenPlane.Cursor cursor, long translations) { }
    static final class Session extends UiSession {
        final ScreenPackBuilder.Prepared pack;
        final ScreenTransport.Viewer viewer;
        final ScreenInput input;
        final DynamicScreen dynamic;
        final com.wellsetups.wellui.core.control.ControlRouter router;
        final ScreenOverlay overlay;
        String nativeInput = "";
        long nativeDeadline;
        long inputRevision = -1;
        ScreenPlane plane;
        final ScreenFrame frame;
        final String generation;
        Vec3 eye;
        final UUID world;
        final float yaw;
        final float pitch;
        final int canvasId;
        final int cursorId;
        final HoverState hover = new HoverState();
        final AtomicBoolean disposed = new AtomicBoolean();
        final long opened = System.nanoTime();
        ScreenInput.Look lastLook;
        Vec3 lastCursor;
        Vec3 translationDelta = new Vec3(0, 0, 0);
        long translations;
        volatile Geometry geometry;
        Session(Player player, ScreenPackBuilder.Prepared pack, ScreenTransport.Viewer viewer, ScreenRenderer renderer) {
            super(player.getUniqueId(), pack.document(), renderer, pack.scene().placements().size());
            this.pack = pack;
            this.viewer = viewer;
            Location origin = player.getEyeLocation();
            world = origin.getWorld().getUID();
            eye = new Vec3(origin.getX(), origin.getY(), origin.getZ());
            yaw = origin.getYaw();
            pitch = origin.getPitch();
            double distance = renderer.assets == null ? ScreenPlane.DEFAULT_DISTANCE : renderer.assets.config().distance();
            frame = ScreenFrame.create(eye, yaw, pitch, document.width(), document.height(), distance);
            plane = frame.reference();
            generation = renderer.assets == null || renderer.assets.generation() == null ? pack.sha256() : renderer.assets.generation().sha256();
            input = new ScreenInput(owner, yaw, pitch);
            canvasId = viewer.allocateEntityId();
            cursorId = viewer.allocateEntityId();
            dynamic = pack.layers().isEmpty() ? null : new DynamicScreen(this);
            router = new com.wellsetups.wellui.core.control.ControlRouter(document, state.controls());
            overlay = new ScreenOverlay(this);
            geometry = new Geometry(eye, plane.center(), translationDelta, plane.intersect(eye, frame.forward()).orElseThrow(), 0);
        }
    }
    private final ScreenAssets assets;
    private final ScreenTransport transport;
    private final PackDelivery delivery;
    private final String serverVersion;
    private final boolean paperFamily;
    private final Supplier<Messages> messages;
    private final LongAdder updates = new LongAdder();
    private final LongAdder inputs = new LongAdder();
    private volatile boolean available = true;
    private final String transportProblem;

    public ScreenRenderer(ScreenAssets assets, ScreenTransport transport, PackDelivery delivery, String serverVersion,
                          boolean paperFamily, String transportProblem, Supplier<Messages> messages) {
        this.assets = assets;
        this.transport = transport;
        this.delivery = delivery;
        this.serverVersion = serverVersion;
        this.paperFamily = paperFamily;
        this.transportProblem = transportProblem;
        this.messages = messages;
    }
    public String unavailable(Player player, UiDocument document) {
        if (!available || transport == null) return transportProblem.isEmpty() ? "screen.dependency" : transportProblem;
        if (ScreenProfile.select(serverVersion, transport.viewer(player).clientVersion(), paperFamily).isEmpty()) return "screen.version";
        if (!delivery.available()) return "screen.pack_api";
        if (!assets.hostProblem().isEmpty()) return assets.hostProblem();
        if (!assets.deliverable()) return "screen.export_only";
        if (assets.current(document) == null) return "screen.not_prepared";
        if (assets.external() && (assets.generation() == null || assets.verifiedHash(assets.generation().sha256()).isEmpty())) {
            return assets.readiness().reason().isEmpty() ? assets.integration().pendingReason() : assets.readiness().reason();
        }
        return "";
    }
    public boolean mayWait(String problem) {
        if (assets == null || !assets.external() || problem.isEmpty() || assets.integration() instanceof com.wellsetups.wellui.platform.screen.UnmanagedPackIntegration) return false;
        return assets.readiness().state() != com.wellsetups.wellui.core.screen.PackReadiness.State.FAILED
                && (problem.equals("screen.not_prepared") || problem.equals("screen.preparing") || problem.equals("screen.merge_pending")
                || problem.equals(assets.readiness().reason()));
    }
    public String waitingMessage() {
        return assets.external() && assets.readiness().state() == com.wellsetups.wellui.core.screen.PackReadiness.State.PROVIDER_BUILD_REQUIRED
                ? "screen.provider_build_waiting" : "screen.preparing";
    }
    public CompletableFuture<Boolean> require(Player player, UiDocument document) {
        if (assets.external()) return delivery.require(player, transport.viewer(player), false);
        var pack = assets.current(document);
        return pack == null ? CompletableFuture.completedFuture(false) : delivery.require(player, pack, transport.viewer(player));
    }
    public boolean preloadEligible(Player player) {
        return available && transport != null && ScreenProfile.select(serverVersion, transport.viewer(player).clientVersion(), paperFamily).isPresent();
    }
    public com.wellsetups.wellui.core.EnvironmentCapabilities environment() {
        return com.wellsetups.wellui.core.EnvironmentCapabilities.detect(serverVersion, paperFamily, available && transport != null,
                delivery.available() && assets.deliverable() && assets.hostProblem().isEmpty(), assets.owner());
    }
    public boolean backendAvailable() { return environment().features().contains(com.wellsetups.wellui.core.EnvironmentCapabilities.Feature.SCREEN); }
    public CompletableFuture<Boolean> preload(Player player, boolean manual) {
        return !preloadEligible(player) ? CompletableFuture.completedFuture(false) : delivery.require(player, transport.viewer(player), manual);
    }
    public PackDelivery delivery() { return delivery; }
    public long updates() { return updates.sum(); }
    public long inputs() { return inputs.sum(); }
    public long sends() { return transport == null ? 0 : transport.sends(); }
    public void unavailable() { available = false; }
    public RendererCapabilities capabilities() {
        return new RendererCapabilities("screen", Set.of(RendererCapabilities.Feature.REFERENCE_CANVAS,
                RendererCapabilities.Feature.IMAGES, RendererCapabilities.Feature.BAKED_TEXT,
                RendererCapabilities.Feature.BAKED_LAYERS, RendererCapabilities.Feature.BAKED_OPACITY,
                RendererCapabilities.Feature.CANVAS_CLIPPING, RendererCapabilities.Feature.LEFT_CLICK,
                RendererCapabilities.Feature.LOOK_CURSOR, RendererCapabilities.Feature.LOGICAL_HOVER,
                RendererCapabilities.Feature.CONTROLS, RendererCapabilities.Feature.FORMS, RendererCapabilities.Feature.FOCUS,
                RendererCapabilities.Feature.VIEWPORT_CLIPPING, RendererCapabilities.Feature.SCROLL,
                RendererCapabilities.Feature.TEXT_INPUT, RendererCapabilities.Feature.ANIMATIONS), List.of("screen.limits"));
    }
    @SuppressWarnings("deprecation")
    public UiSession create(Player player, UiDocument document, String acknowledgment) {
        String problem = unavailable(player, document);
        if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
        var pack = assets.current(document);
        if (assets.generation() == null || !delivery.loaded(player.getUniqueId(), assets.generation().sha256())) throw new IllegalArgumentException("screen.pack_unavailable");
        if (player.isInsideVehicle()) throw new IllegalArgumentException("screen.vehicle");
        if (player.isSleeping()) throw new IllegalArgumentException("screen.sleeping");
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && player.getSpectatorTarget() != null) throw new IllegalArgumentException("screen.camera");
        return new Session(player, pack, transport.viewer(player), this);
    }
    public boolean open(Player player, UiSession base) {
        Session session = (Session) base;
        player.closeInventory();
        session.viewer.listen(session.input);
        session.viewer.spawn(session.canvasId, session.plane.center(), session.plane.width(), session.plane.height(),
                session.yaw, session.pitch, session.pack.baseModel());
        if (session.dynamic != null) session.dynamic.open();
        session.lastCursor = session.plane.point(session.document.width() / 2, session.document.height() / 2, .015);
        double size = Math.min(session.plane.width(), session.plane.height()) * .035;
        session.viewer.spawn(session.cursorId, session.lastCursor, size, size, session.yaw, session.pitch, session.pack.cursorModel());
        player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.controls"));
        player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.limits"));
        return true;
    }
    public boolean active(Player player, UiSession base) {
        Session s = (Session) base;
        return available && !s.disposed.get() && !player.isDead() && player.getWorld().getUID().equals(s.world)
                && delivery.loaded(s.owner, s.generation) && !s.frame.outside(player.getEyeLocation().getYaw(), player.getEyeLocation().getPitch())
                && System.nanoTime() - s.opened < 180_000_000_000L;
    }
    public void refresh(Player player, UiSession base, boolean pulse) {
        Session session = (Session) base;
        if (session.dynamic != null) { session.dynamic.refresh(false);
            session.lastLook = null;
            }
    }
    public void translate(Player player, Session session) {
        var location = player.getEyeLocation();
        Vec3 eye = new Vec3(location.getX(), location.getY(), location.getZ());
        Vec3 offset = eye.subtract(session.eye);
        session.translationDelta = offset;
        if (eye.equals(session.eye)) return;
        session.eye = eye;
        session.plane = session.frame.at(eye);
        session.translations++;
        updates.increment();
        session.viewer.move(session.canvasId, session.plane.center());
        if (session.dynamic != null) session.dynamic.translate(offset);
        if (session.lastCursor != null) {
            session.lastCursor = session.lastCursor.add(offset);
            session.viewer.move(session.cursorId, session.lastCursor);
        }
    }
    public String diagnostic(UiSession base) {
        if (!(base instanceof Session s)) return "SCREEN session inactive";
        Geometry pose = s.geometry;
        // Immutable snapshot published only by the player/region owner; no Bukkit reads here.
        return "SCREEN session active | canvas=" + s.document.width() + "x" + s.document.height()
                + " | raster=" + s.pack.raster().width() + "x" + s.pack.raster().height() + " | scale=" + s.pack.raster().scale()
                + " | eye=" + pose.eye() + " | reference yaw/pitch=" + s.yaw + "/" + s.pitch
                + " | reference forward=" + s.frame.forward() + " | reference right=" + s.frame.reference().right()
                + " | reference up=" + s.frame.reference().up() + " | center=" + pose.center() + " | distance=" + s.frame.distance()
                + " | normal toward viewer=" + s.frame.reference().normal()
                + " | cursor=" + (pose.cursor() == null ? "NO_INTERSECTION" : pose.cursor().x() + "/" + pose.cursor().y() + " inside=" + pose.cursor().inside())
                + " | ray-plane intersection=" + (pose.cursor() == null ? "NONE" : pose.cursor().intersection())
                + " | translation delta=" + pose.delta() + " | total translation=" + pose.eye().subtract(s.frame.origin())
                + " | translation updates=" + pose.translations() + " | large-turn policy=CLOSE beyond 75.52 degrees";
    }
    public Update update(Session session) {
        if (!session.input.owns(session.owner, session.input.token())) return new Update(null, new HoverState.Transition(null, null, false));
        var sample = session.input.drain();
        inputs.add(sample.received());
        var currentScene = scene(session);
        boolean modalWasOpen = !session.nativeInput.isEmpty();
        session.state.controls().reconcile(id -> currentScene.opacity(id) >= 1.0 / 16);
        var reply = session.input.text().poll();
        if (reply != null && session.nativeInput.equals(reply.component())) {
            if (!reply.cancelled() && session.state.controls().overlay().equals(reply.component())) {
                session.state.controls().edit(reply.component(), com.wellsetups.wellui.api.logic.LogicValue.string(reply.value()));
            }
            session.state.controls().overlay("");
        }
        if (!session.nativeInput.isEmpty() && (!session.state.controls().overlay().equals(session.nativeInput) || System.nanoTime() >= session.nativeDeadline)) {
            session.input.text().cancel();
            session.viewer.clearInput();
            session.state.controls().overlay("");
            session.nativeInput = "";
        }
        var currentCursor = cursor(session, sample.look());
        if (currentCursor != null && currentCursor.inside()) {
            session.router.scroll(currentScene, currentCursor.x(), currentCursor.y(), sample.scroll());
            session.router.move(currentScene, currentCursor.x(), currentCursor.y());
        }
        if (session.dynamic != null) session.dynamic.refresh(false);
        var clickCursor = sample.click() == null || modalWasOpen ? null : cursor(session, sample.click());
        var activation = clickCursor != null ? session.router.click(scene(session), clickCursor.x(), clickCursor.y()) : null;
        ButtonComponent clicked = activation == null ? null : activation.button();
        HoverState.Transition transition = new HoverState.Transition(null, null, false);
        if (session.animations != null && !session.document.animations().isEmpty() || !sample.look().equals(session.lastLook) || session.inputRevision != session.state.revision()) {
            updates.increment();
            session.lastLook = sample.look();
            var cursor = cursor(session, sample.look());
            ButtonComponent hovered = cursor != null && cursor.inside() ? session.router.hover(scene(session), cursor.x(), cursor.y()) : null;
            transition = session.hover.update(hovered == null ? null : hovered.id());
            if (transition.changed() && session.dynamic == null) session.viewer.model(session.canvasId,
                    session.hover.current() == null ? session.pack.baseModel()
                            : session.pack.hoverModels().getOrDefault(session.hover.current(), session.pack.baseModel()));
            if (transition.changed() && session.dynamic != null) session.dynamic.refresh(false);
            if (cursor != null) {
                Vec3 point = session.plane.point(cursor.x(), cursor.y(), .015);
                if (!point.equals(session.lastCursor)) {
                    session.viewer.move(session.cursorId, point);
                    session.lastCursor = point;
                }
            }
        }
        session.geometry = new Geometry(session.eye, session.plane.center(), session.translationDelta, cursor(session, sample.look()), session.translations);
        session.inputRevision = session.state.revision();
        session.overlay.refresh(scene(session), currentCursor == null ? -1 : currentCursor.x(), currentCursor == null ? -1 : currentCursor.y());
        return new Update(clicked, transition, activation);
    }
    void activate(Session session, com.wellsetups.wellui.core.control.ControlRouter.Activation activation) {
        session.router.activate(activation);
        String id = session.state.controls().overlay();
        if (!id.isEmpty() && session.document.controls().get(id).kind() == com.wellsetups.wellui.api.document.UiControl.Kind.INPUT) {
            var control = session.document.controls().get(id);
            String label = activation.button().text();
            var request = session.input.text().begin(id, label.substring(0, Math.min(256, label.length())), session.state.controls().value(id).string(), (int) control.number("maxLength", 128));
            session.nativeInput = id;
            session.nativeDeadline = System.nanoTime() + 60_000_000_000L;
            session.viewer.input(request);
        }
        if (session.dynamic != null) session.dynamic.refresh(false);
    }
    private com.wellsetups.wellui.core.screen.ScreenScene scene(Session session) {
        return session.dynamic == null ? session.pack.scene() : session.dynamic.scene();
    }
    private ScreenPlane.Cursor cursor(Session s, ScreenInput.Look look) {
        return s.plane.intersect(s.eye, Vec3.look(look.yaw(), look.pitch())).orElse(null);
    }
    public void close(Player player, UiSession base) {
        Session session = (Session) base;
        if (!session.disposed.compareAndSet(false, true)) return;
        session.input.close();
        session.state.controls().close();
        try { if (!session.nativeInput.isEmpty()) session.viewer.clearInput(); }
        finally {
            try { session.viewer.unlisten(session.input); }
            finally {
                // Only immutable protocol state; safe during Folia disable without a Bukkit Player call.
                try { session.overlay.close(); }
                finally {
                    try { if (session.dynamic != null) session.dynamic.close(); }
                    finally { session.viewer.destroy(session.canvasId, session.cursorId); }
                }
            }
        }
    }
}
