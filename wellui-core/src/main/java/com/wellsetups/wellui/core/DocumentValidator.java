package com.wellsetups.wellui.core;

import com.wellsetups.wellui.api.document.ButtonComponent;
import com.wellsetups.wellui.api.document.ImageComponent;
import com.wellsetups.wellui.api.document.Layout;
import com.wellsetups.wellui.api.document.PanelComponent;
import com.wellsetups.wellui.api.document.Style;
import com.wellsetups.wellui.api.document.TextComponent;
import com.wellsetups.wellui.api.document.UiAction;
import com.wellsetups.wellui.api.document.UiComponent;
import com.wellsetups.wellui.api.document.UiDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation; returned codes are localization keys, never user-facing exception text. */
public final class DocumentValidator {
    public static final int MAX_COMPONENTS = 500;
    public static final int MAX_DEPTH = 16;
    public static final double MAX_DIMENSION = 16384;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ANCHORS = Set.of("top_left", "top_right", "center", "bottom_left", "bottom_right");
    private static final Set<String> ACTIONS = Set.of("send_message", "close_ui", "player_command");

    public record ValidationIssue(String code, String componentId) { }

    public List<ValidationIssue> validate(UiDocument document) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (document == null) return List.of(new ValidationIssue("validation.document", ""));
        if (document.schemaVersion() != 1) issue(issues, "schema", "");
        if (!validId(document.id())) issue(issues, "project_id", "");
        if (!validText(document.name(), 128) || document.name().isBlank()) issue(issues, "name", "");
        if (!size(document.width()) || !size(document.height())) issue(issues, "canvas", "");
        visit(document.children(), 1, new HashSet<>(), new int[1], issues);
        if (issues.isEmpty()) issues.addAll(new com.wellsetups.wellui.core.control.ControlValidator().validate(document));
        if (issues.isEmpty()) new com.wellsetups.wellui.core.logic.LogicValidator().validate(document, null, null).stream()
                .filter(issue -> !issue.warning()).forEach(issue -> issues.add(new ValidationIssue(issue.code(), issue.nodeId())));
        if (issues.isEmpty()) issues.addAll(new com.wellsetups.wellui.core.animation.AnimationValidator().validate(document));
        return List.copyOf(issues);
    }

    private void visit(List<UiComponent> components, int depth, Set<String> ids, int[] count,
                       List<ValidationIssue> issues) {
        if (depth > MAX_DEPTH) {
            issue(issues, "depth", "");
            return;
        }
        for (UiComponent component : components) {
            if (++count[0] > MAX_COMPONENTS) {
                issue(issues, "component_limit", "");
                return;
            }
            String id = component.id() == null ? "" : component.id();
            if (!validId(id)) issue(issues, "component_id", id);
            else if (!ids.add(id)) issue(issues, "duplicate_id", id);
            Layout layout = component.layout();
            if (layout == null || !coordinate(layout.x()) || !coordinate(layout.y())
                    || !size(layout.width()) || !size(layout.height()) || !ANCHORS.contains(layout.anchor() == null ? "" : layout.anchor()))
                issue(issues, "layout", id);
            Style style = component.style();
            if (style == null || !color(style.background()) || !color(style.color())
                    || !Double.isFinite(style.opacity()) || style.opacity() < 0 || style.opacity() > 1
                    || style.fontSize() < 1 || style.fontSize() > 256) issue(issues, "style", id);
            if (component instanceof TextComponent text && !validText(text.text(), 4096)) issue(issues, "text", id);
            if (component instanceof ImageComponent image && (image.asset() == null || !HASH.matcher(image.asset()).matches()))
                issue(issues, "asset", id);
            if (component instanceof ButtonComponent button) {
                if (!validText(button.text(), 256)) issue(issues, "text", id);
                validateAction(button.action(), id, issues);
            }
            if (component instanceof PanelComponent panel) visit(panel.children(), depth + 1, ids, count, issues);
            if (count[0] > MAX_COMPONENTS) return;
        }
    }

    private void validateAction(UiAction action, String id, List<ValidationIssue> issues) {
        if (action == null || !ACTIONS.contains(action.type() == null ? "" : action.type()) || !validText(action.value(), 2048)) {
            issue(issues, "action", id);
            return;
        }
        if (action.type().equals("player_command") && (action.value().isBlank() || action.value().startsWith("/")
                || action.value().codePoints().anyMatch(Character::isISOControl))) issue(issues, "command", id);
        if (action.type().equals("close_ui") && !action.value().isEmpty()) issue(issues, "action", id);
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value).matches()
                && !value.matches("(?:con|prn|aux|nul|com[1-9]|lpt[1-9])");
    }
    private static boolean size(double value) { return Double.isFinite(value) && value > 0 && value <= MAX_DIMENSION; }
    private static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= MAX_DIMENSION; }
    private static boolean color(String value) { return value != null && COLOR.matcher(value).matches(); }
    private static boolean validText(String value, int max) {
        return value != null && value.length() <= max && value.codePoints().noneMatch(c -> c == 0 || (Character.isISOControl(c) && c != '\n' && c != '\t'));
    }
    private static void issue(List<ValidationIssue> issues, String code, String id) {
        issues.add(new ValidationIssue("validation." + code, id));
    }
}
