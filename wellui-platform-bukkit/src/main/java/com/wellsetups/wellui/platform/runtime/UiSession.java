package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.document.UiDocument;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One player-owned renderer session; registry access may occur on the global dispatcher. */
public abstract class UiSession {
    final UUID owner;
    final UiDocument document;
    final UiRenderer renderer;
    final AtomicBoolean refreshQueued = new AtomicBoolean();
    final int elements;
    final com.wellsetups.wellui.core.logic.UiSessionState state;
    com.wellsetups.wellui.core.logic.LogicExecutor logic;
    com.wellsetups.wellui.core.animation.UiAnimationPlayer animations;
    final UUID token = UUID.randomUUID();
    int openChain;
    boolean ending;
    UiCloseEventReason closePolicy = UiCloseEventReason.NORMAL;
    enum UiCloseEventReason { NORMAL, REPLACED }
    long lastHover;
    String logicHovered;
    long lastClick;
    boolean nextPulse;
    com.wellsetups.wellui.core.screen.ScreenScene scene() {
        return new com.wellsetups.wellui.core.screen.ScreenScene(state.scene(document), animations == null ? java.util.Map.of() : animations.overlay(0));
    }
    UiSession(UUID owner, UiDocument document, UiRenderer renderer, int elements) {
        this.owner = owner;
        this.document = document;
        this.renderer = renderer;
        this.elements = elements;
        this.state = new com.wellsetups.wellui.core.logic.UiSessionState(document);
    }
}
