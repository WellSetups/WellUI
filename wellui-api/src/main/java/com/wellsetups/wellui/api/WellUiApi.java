package com.wellsetups.wellui.api;

import com.wellsetups.wellui.api.event.UiEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Supported 1.0 service obtained from Bukkit's ServicesManager. Document/Logic records remain versioned data contracts. */
public interface WellUiApi {
    /** Schedules a permission-checked open on the player's owning thread. Safe from any thread. */
    CompletionStage<OpenResult> open(UUID playerId, String documentId);

    /** Closes a WellUI session on its owning thread. Safe from any thread. */
    CompletionStage<Boolean> close(UUID playerId);

    /** Returns an immutable snapshot of deployed document identifiers. */
    Set<String> documentIds();

    /**
     * Registers a callback. Callbacks run on the player's owning thread, may run concurrently
     * for different players, and must not block. Close the subscription when your addon disables.
     */
    <E extends UiEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> listener);
}
