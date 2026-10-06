package com.wellsetups.wellui.editor;

import com.wellsetups.wellui.api.document.UiDocument;

/** Publishes a validated document. Implementations must marshal live game work to its scheduler. */
@FunctionalInterface
public interface DeploymentService {
    DeploymentResult deploy(UiDocument document) throws Exception;
    default DeploymentResult deployAll(java.util.List<UiDocument> documents) throws Exception {
        var messages = new java.util.ArrayList<String>();
        String hash = "";
        for (var document : documents) {
            var result = deploy(document);
            messages.addAll(result.messages());
            hash = result.packHash();
            if (!result.success()) return new DeploymentResult(false, messages, hash);
        }
        return new DeploymentResult(true, messages, hash);
    }
    default java.util.Map<String, Object> status() { return java.util.Map.of("connected", false, "screenAvailable", false, "provider", "Export only", "packState", "NOT_PREPARED"); }
    default java.util.Map<String, UiDocument> deployedDocuments() { return java.util.Map.of(); }
    default java.util.List<UiDocument> retainedDocuments() throws Exception { return java.util.List.copyOf(deployedDocuments().values()); }
    default java.util.List<UiDocument> activeDocuments() { return java.util.List.of(); }
    default Boolean placeholdersAvailable() { return null; }
    default void delete(String id) throws Exception { }
}
