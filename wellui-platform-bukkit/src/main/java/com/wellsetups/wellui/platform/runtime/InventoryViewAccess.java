package com.wellsetups.wellui.platform.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Bukkit changed InventoryView from class to interface after the compile baseline.
 * Resolve its public method once to avoid emitting an incompatible invokevirtual instruction.
 */
public final class InventoryViewAccess {
    private final Method top;

    public InventoryViewAccess() { this(InventoryView.class); }

    InventoryViewAccess(Class<?> viewType) {
        try { top = viewType.getMethod("getTopInventory"); }
        catch (NoSuchMethodException missing) { throw new IllegalStateException("platform.inventory_view", missing); }
    }

    public Inventory top(Object view) {
        try { return (Inventory) top.invoke(view); }
        catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("platform.inventory_view", failure);
        }
    }
}
