package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.RendererCapabilities;
import org.bukkit.entity.Player;

/** Backend boundary. Rendering and refreshing run on the player's owning thread. */
public interface UiRenderer {
    RendererCapabilities capabilities();
    UiSession create(Player player, UiDocument document, String packAcknowledgment);
    boolean open(Player player, UiSession session);
    boolean active(Player player, UiSession session);
    void refresh(Player player, UiSession session, boolean pulse);
    /** Null player means retire state only (disconnect or non-owned shutdown context). */
    void close(Player player, UiSession session);
}
