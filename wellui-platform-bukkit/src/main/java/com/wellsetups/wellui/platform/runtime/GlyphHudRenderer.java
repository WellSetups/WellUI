package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.HudPackBuilder;
import com.wellsetups.wellui.core.LayoutEngine;
import com.wellsetups.wellui.core.RendererCapabilities;
import com.wellsetups.wellui.platform.config.Messages;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/** Static native actionbar glyph with separate, honest chat activation controls. */
public final class GlyphHudRenderer implements UiRenderer {
    private final HudPackBuilder builder;
    private final Supplier<Messages> messages;
    private final Map<String, HudPackBuilder.Prepared> prepared = new ConcurrentHashMap<>();
    private final LongAdder sends = new LongAdder();
    private volatile HudPackBuilder.Prepared latest;
    private volatile boolean closed;

    static final class Session extends UiSession {
        final HudInput input;
        final Map<String, ButtonComponent> buttons = new LinkedHashMap<>();
        final int glyph;
        Session(Player player, HudPackBuilder.Prepared pack, UiRenderer renderer) {
            super(player.getUniqueId(), pack.document(), renderer, pack.elements());
            input = new HudInput(owner, System.nanoTime());
            glyph = pack.glyph();
            for (var item : new LayoutEngine().layout(document, 256, 128)) {
                if (item.component() instanceof ButtonComponent button) buttons.put(button.id(), button);
            }
        }
    }
    public GlyphHudRenderer(HudPackBuilder builder, Supplier<Messages> messages) {
        this.builder = builder;
        this.messages = messages;
    }
    /** IO executor only; publish after the entire pack and persistent allocation have been written. */
    public HudPackBuilder.Prepared prepare(UiDocument document) throws IOException {
        if (closed) throw new IOException("open.unavailable");
        var pack = builder.build(document);
        synchronized (this) {
            if (closed) throw new IOException("open.unavailable");
            prepared.put(document.id(), pack);
            latest = pack;
        }
        return pack;
    }
    public long sends() { return sends.sum(); }
    public HudPackBuilder.Prepared latest() { return latest; }
    public RendererCapabilities capabilities() { return RendererCapabilities.hud(); }
    public UiSession create(Player player, UiDocument document, String packAcknowledgment) {
        if (!document.animations().isEmpty()) throw new IllegalArgumentException("animation.hud");
        if (com.wellsetups.wellui.core.logic.LogicVisuals.dynamic(document)) throw new IllegalArgumentException("logic.hud_dynamic");
        var pack = prepared.get(document.id());
        if (closed || pack == null || !pack.matches(document, packAcknowledgment)) {
            throw new IllegalArgumentException("hud.prepare");
        }
        return new Session(player, pack, this);
    }
    public boolean open(Player player, UiSession base) {
        Session session = (Session) base;
        for (String warning : capabilities().warningKeys()) {
            player.sendMessage(messages.get().prefixed(player.getLocale(), warning));
        }
        for (ButtonComponent button : session.buttons.values()) {
            String label = button.text().replace('\n', ' ');
            if (label.length() > 80) label = label.substring(0, 80);
            TextComponent link = new TextComponent(messages.get().textFor(player.getLocale(), "hud.action", "label", label));
            link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/wellui:wellui _input " + session.input.token() + " " + button.id()));
            player.spigot().sendMessage(link);
        }
        refresh(player, session, false);
        return true;
    }
    public boolean active(Player player, UiSession base) {
        return !((Session) base).input.expired(System.nanoTime());
    }
    public void refresh(Player player, UiSession base, boolean pulse) {
        TextComponent glyph = new TextComponent(Character.toString(((Session) base).glyph));
        glyph.setFont("wellui:hud_poc");
        glyph.setColor(ChatColor.WHITE);
        send(player, glyph);
    }
    public void close(Player player, UiSession base) {
        Session session = (Session) base;
        session.input.close();
        if (player != null) send(player, new TextComponent(""));
    }
    private void send(Player player, TextComponent value) {
        sends.increment();
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, value);
    }
    public synchronized void clearPrepared() {
        closed = true;
        prepared.clear();
        latest = null;
    }
}
