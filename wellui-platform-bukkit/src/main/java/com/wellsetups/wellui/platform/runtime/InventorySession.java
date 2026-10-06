package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Player-owned native view; identity, not title, identifies WellUI input. */
public final class InventorySession extends UiSession implements InventoryHolder {
    final Map<Integer, UiComponent> slots;
    Inventory inventory;

    InventorySession(UUID owner, UiDocument document, Map<Integer, UiComponent> slots, UiRenderer renderer) {
        super(owner, document, renderer, slots.size());
        this.slots = slots;
    }
    @Override public Inventory getInventory() { return inventory; }
}
