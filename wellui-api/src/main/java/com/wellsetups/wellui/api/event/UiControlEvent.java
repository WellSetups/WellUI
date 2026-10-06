package com.wellsetups.wellui.api.event;

import com.wellsetups.wellui.api.logic.LogicValue;
import java.util.UUID;

/** Immutable session notification before the matching Logic event; callbacks run on the player owner thread. */
public record UiControlEvent(UUID playerId, String documentId, String componentId, Type type, LogicValue value) implements UiEvent {
    public enum Type { VALUE_CHANGE, FOCUS, BLUR, SUBMIT, SCROLL }
}
