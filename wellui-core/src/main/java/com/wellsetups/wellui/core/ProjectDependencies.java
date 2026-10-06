package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.UiDocument;
import com.wellsetups.wellui.core.logic.LogicCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded dependency traversal. Cyclic navigation is valid; runtime open-chain limits remain authoritative. */
public final class ProjectDependencies {
    private ProjectDependencies() { }
    public static List<UiDocument> ordered(String root, Map<String, UiDocument> documents) {
        var result = new ArrayList<UiDocument>();
        visit(root, documents, new HashSet<>(), result);
        return List.copyOf(result);
    }
    private static void visit(String id, Map<String, UiDocument> documents, Set<String> visited, List<UiDocument> result) {
        if (!visited.add(id)) return;
        if (visited.size() > 64) throw new IllegalArgumentException("deploy.dependencies_limit");
        var document = documents.get(id);
        if (document == null) throw new IllegalArgumentException("logic.project_missing");
        document.logic().nodes().stream().filter(n -> n.type().equals("open_ui")).map(n -> LogicCatalog.text(n, "project"))
                .distinct().sorted().forEach(target -> visit(target, documents, visited, result));
        result.add(document);
    }
}
