package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.RendererCapabilities;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.config.RuntimeConfig;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Native tooltip/click backend; images degrade to named items with an explicit explanation. */
public final class InventoryRenderer implements UiRenderer {
    private final Supplier<Messages> messages;
    private final Supplier<RuntimeConfig> config;
    private final PlaceholderService placeholders;
    private final InventoryViewAccess views = new InventoryViewAccess();

    public InventoryRenderer(Supplier<Messages> messages, Supplier<RuntimeConfig> config, PlaceholderService placeholders) {
        this.messages = messages;
        this.config = config;
        this.placeholders = placeholders;
    }
    public RendererCapabilities capabilities() { return RendererCapabilities.inventory(); }
    public InventorySession create(Player player, UiDocument document, String packAcknowledgment) {
        if (com.wellsetups.wellui.core.animation.AnimationVisuals.transforms(document)) throw new IllegalArgumentException("animation.inventory_transform");
        var session = new InventorySession(player.getUniqueId(), document, new SlotProjection().project(document).slots(), this);
        String title = document.name().length() > 32 ? document.name().substring(0, 32) : document.name();
        session.inventory = Bukkit.createInventory(session, 54, title);
        refresh(player, session, false);
        return session;
    }
    public boolean open(Player player, UiSession base) {
        player.openInventory(((InventorySession) base).inventory);
        return active(player, base);
    }
    public boolean active(Player player, UiSession base) {
        return views.top(player.getOpenInventory()) == ((InventorySession) base).inventory;
    }
    public void close(Player player, UiSession base) {
        if (player != null && active(player, base)) player.closeInventory();
    }
    public void refresh(Player player, UiSession base, boolean pulse) {
        InventorySession session = (InventorySession) base;
        var effective = session.state.apply(session.document);
        var scene = session.scene();
        var components = new java.util.HashMap<String, UiComponent>();
        new com.wellsetups.wellui.core.LayoutEngine().layout(effective, effective.width(), effective.height())
                .forEach(p -> components.put(p.component().id(), p.component()));
        for (var entry : session.slots.entrySet()) {
            UiComponent component = components.get(entry.getValue().id());
            if (scene.opacity(component.id()) <= 0) { session.inventory.setItem(entry.getKey(), null);
                continue;
                }
            String label;
            Material material;
            String lore;
            if (component instanceof ButtonComponent button) {
                label = button.text();
                material = pulse ? Material.LIME_DYE : Material.LIGHT_BLUE_DYE;
                lore = messages.get().text("render.button");
            } else if (component instanceof TextComponent text) {
                label = text.text();
                material = Material.PAPER;
                lore = messages.get().text("render.text");
            } else {
                label = messages.get().text("render.image");
                material = Material.PAINTING;
                lore = messages.get().text("render.image_hint");
            }
            String resolved = placeholders.resolve(player, label, config.get().placeholderRefreshMs());
            String[] lines = resolved.split("\n", -1);
            ItemStack item = new ItemStack(material);
            var meta = item.getItemMeta();
            if (meta == null) throw new IllegalStateException("render.metadata");
            meta.setDisplayName(Messages.color("&" + component.style().color() + lines[0]));
            var description = new java.util.ArrayList<String>();
            for (int index = 1; index < lines.length; index++) description.add(Messages.color("&7" + lines[index]));
            description.add(Messages.color(lore));
            meta.setLore(List.copyOf(description));
            item.setItemMeta(meta);
            if (!item.equals(session.inventory.getItem(entry.getKey()))) session.inventory.setItem(entry.getKey(), item);
        }
    }
}
