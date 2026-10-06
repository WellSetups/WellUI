package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** Owner-thread animation notification; identifiers only, no renderer internals. */
public record UiAnimationStopEvent(UUID playerId, String documentId, UUID sessionId, String animationId) implements UiEvent { }
