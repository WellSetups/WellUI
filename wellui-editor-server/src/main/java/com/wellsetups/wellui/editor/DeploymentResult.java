package com.wellsetups.wellui.editor;

import java.util.List;

/** Deployment messages are localization keys, never exception details or filesystem paths. */
public record DeploymentResult(boolean success, List<String> messages, String packHash) {
    public DeploymentResult {
        messages = List.copyOf(messages);
    }
}
