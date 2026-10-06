package com.wellsetups.wellui.api.event;

import com.wellsetups.wellui.api.logic.LogicValue;
import java.util.UUID;

/** Session-local value changed on its player owner; immutable notification, never persistence. */
public record UiVariableChangeEvent(UUID playerId, String documentId, String variable,
                                    LogicValue previous, LogicValue value) implements UiEvent { }
