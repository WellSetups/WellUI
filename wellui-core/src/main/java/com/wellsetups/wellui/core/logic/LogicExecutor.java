package com.wellsetups.wellui.core.logic;

import com.wellsetups.wellui.api.logic.LogicValue;
import com.wellsetups.wellui.api.logic.UiLogicGraph;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded DAG interpreter. Host performs authorized owner-thread operations; no platform or IO linkage here. */
public final class LogicExecutor {
    public interface Host {
        boolean active();
        boolean permission(String value);
        String placeholder(String value);
        boolean sneaking();
        boolean flying();
        String playerName();
        void message(String value);
        void command(String value);
        void close();
        void open(String project, int chainDepth);
        AutoCloseable delay(long ticks, Runnable continuation);
        default boolean before(String event, String node) { return true; }
        default void variableChanged(String name, LogicValue old, LogicValue value) { }
        default void refresh() { }
        default void animation(String operation, String id, int chain) { throw new IllegalArgumentException("animation.unavailable"); }
    }
    public static final int MAX_STEPS = 128, MAX_DELAYS = 8, MAX_OPEN_CHAIN = 8;
    public record Diagnostic(String event, int nodes, String action, int variables, int continuations, String failure) { }
    private static final class Run {
        final ArrayDeque<String> pending = new ArrayDeque<>();
        int budget = MAX_STEPS;
        int chain;
        String event;
    }
    private final CompiledLogic graph;
    private final UiSessionState state;
    private final Host host;
    private final Map<Long, AutoCloseable> delays = new LinkedHashMap<>();
    private long serial;
    private boolean closed;
    private int nodes;
    private String event = "NONE", action = "NONE", failure = "";
    private volatile Diagnostic diagnostic = new Diagnostic("NONE", 0, "NONE", 0, 0, "");
    public LogicExecutor(CompiledLogic graph, UiSessionState state, Host host) { this.graph = graph;
        this.state = state;
        this.host = host;
        }
    public Diagnostic diagnostic() { return diagnostic; }
    public synchronized void fire(String type, String component, int chain) {
        if (closed || !host.active()) return;
        String entry = graph.entry(type, component);
        if (entry == null) return;
        event = type;
        nodes = 0;
        failure = "";
        var run = new Run();
        run.chain = chain;
        run.event = type;
        run.pending.push(entry);
        execute(run);
    }
    private void publish() { diagnostic = new Diagnostic(event, nodes, action, state.variables().size(), delays.size(), failure); }
    private synchronized void execute(Run run) {
        event = run.event;
        nodes = MAX_STEPS - run.budget;
        try {
            while (!closed && host.active() && !run.pending.isEmpty()) {
                if (--run.budget < 0) throw new IllegalArgumentException("logic.budget");
                var node = graph.node(run.pending.pop());
                if (node == null) throw new IllegalArgumentException("logic.missing_node");
                nodes++;
                if (!host.before(event, node.id()) || closed || !host.active()) break;
                String next = "next";
                switch (node.type()) {
                    case "set_value", "scroll_to" -> state.controls().set(LogicCatalog.text(node, "component"), input(node, "value", 0));
                    case "set_disabled" -> state.controls().disabled(LogicCatalog.text(node, "component"), input(node, "value", 0).bool());
                    case "focus_component" -> state.controls().focus(LogicCatalog.text(node, "component"));
                    case "blur_component" -> {
                        if (state.controls().focused().equals(LogicCatalog.text(node, "component"))) state.controls().blur();
                    }
                    case "focus_next", "focus_previous" -> state.controls().focusNext(node.type().equals("focus_previous"));
                    case "reset_form" -> state.controls().reset(LogicCatalog.text(node, "component"));
                    case "submit_form" -> state.controls().submit(LogicCatalog.text(node, "component"));
                    case "clear_input" -> state.controls().set(LogicCatalog.text(node, "component"), LogicValue.string(""));
                    case "branch" -> next = input(node, "condition", 0).bool() ? "true" : "false";
                    case "sequence" -> { push(run, node, "third");
                        push(run, node, "second");
                        next = "first";
                        }
                    case "stop" -> { run.pending.clear();
                        return;
                        }
                    case "delay" -> {
                        push(run, node, "next");
                        if (delays.size() >= MAX_DELAYS) throw new IllegalArgumentException("logic.continuations");
                        long ticket = ++serial;
                        delays.put(ticket, () -> { });
                        AutoCloseable task;
                        try { task = host.delay((long) node.settings().get("ticks").number(), () -> {
                            resume(ticket, run);
                        });
                            } catch (RuntimeException error) { delays.remove(ticket);
                            throw error;
                            }
                        if (delays.containsKey(ticket)) delays.put(ticket, task);
                        else cancel(task);
                        return;
                    }
                    case "send_message" -> { action = node.type();
                        host.message(input(node, "value", 0).string());
                        }
                    case "player_command" -> {
                        String value = input(node, "value", 0).string();
                        if (value.isBlank() || value.startsWith("/") || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("logic.command");
                        action = node.type();
                        host.command(value);
                    }
                    case "close_ui" -> { action = node.type();
                        host.close();
                        run.pending.clear();
                        return;
                        }
                    case "open_ui" -> {
                        if (run.chain >= MAX_OPEN_CHAIN) throw new IllegalArgumentException("logic.open_chain");
                        action = node.type();
                        host.open(LogicCatalog.text(node, "project"), run.chain + 1);
                        run.pending.clear();
                        return;
                    }
                    case "play_animation", "stop_animation", "pause_animation", "resume_animation", "restart_animation" -> {
                        action = node.type();
                        host.animation(node.type(), LogicCatalog.text(node, "animation"), run.chain);
                    }
                    case "set_variable", "add_number", "toggle_boolean" -> {
                        String name = LogicCatalog.text(node, "variable");
                        var old = state.variable(name);
                        var value = switch (node.type()) {
                            case "add_number" -> LogicValue.number(old.number() + input(node, "value", 0).number());
                            case "toggle_boolean" -> LogicValue.bool(!old.bool());
                            default -> input(node, "value", 0);
                        };
                        state.variable(name, value);
                        host.variableChanged(name, old, value);
                        action = node.type();
                    }
                    case "set_visible" -> { state.visible(LogicCatalog.text(node, "component"), input(node, "value", 0).bool());
                        action = node.type();
                        }
                    case "set_text" -> { state.text(LogicCatalog.text(node, "component"), input(node, "value", 0).string());
                        action = node.type();
                        }
                    case "set_opacity" -> { state.opacity(LogicCatalog.text(node, "component"), input(node, "value", 0).number());
                        action = node.type();
                        }
                    default -> { if (!node.type().startsWith("on_")) throw new IllegalArgumentException("logic.node_type"); }
                }
                push(run, node, next);
            }
        } catch (RuntimeException error) { failure = error.getMessage() == null ? "logic.failed" : error.getMessage();
            }
        finally { if (!closed && host.active()) host.refresh();
            publish();
            }
    }
    private synchronized void resume(long ticket, Run run) {
        if (delays.remove(ticket) != null && !closed && host.active()) execute(run);
        publish();
    }
    private void push(Run run, UiLogicGraph.Node node, String port) { String next = graph.next(node.id(), port);
        if (next != null) run.pending.push(next);
        }
    private LogicValue input(UiLogicGraph.Node node, String port, int depth) {
        if (depth > 32) throw new IllegalArgumentException("logic.depth");
        var edge = graph.input(node.id(), port);
        if (edge == null) {
            var value = node.settings().get(port);
            if (value == null) throw new IllegalArgumentException("logic.input_missing");
            return value;
        }
        return value(graph.node(edge.from()), depth + 1);
    }
    private LogicValue value(UiLogicGraph.Node node, int depth) {
        if (node == null || depth > 32) throw new IllegalArgumentException("logic.depth");
        return switch (node.type()) {
            case "input_value", "checkbox_value", "toggle_value", "slider_value", "selected_value", "scroll_position", "form_valid" -> state.controls().value(LogicCatalog.text(node, "component"));
            case "validation_message" -> LogicValue.string(state.controls().error(LogicCatalog.text(node, "component")));
            case "constant_string", "constant_number", "constant_boolean" -> node.settings().get("value");
            case "variable" -> state.variable(LogicCatalog.text(node, "variable"));
            case "player_name" -> LogicValue.string(host.playerName());
            case "placeholder" -> { String text = host.placeholder(LogicCatalog.text(node, "placeholder"));
                if (text == null) throw new IllegalArgumentException("logic.placeholder_unavailable");
                yield LogicValue.string(text);
                }
            case "has_permission" -> LogicValue.bool(host.permission(LogicCatalog.text(node, "permission")));
            case "is_sneaking" -> LogicValue.bool(host.sneaking());
            case "is_flying" -> LogicValue.bool(host.flying());
            case "placeholder_equals", "placeholder_contains", "placeholder_numeric" -> {
                String text = host.placeholder(LogicCatalog.text(node, "placeholder"));
                if (text == null || text.equals(LogicCatalog.text(node, "placeholder"))) yield LogicValue.bool(false);
                var compare = input(node, "compare", depth + 1);
                boolean result;
                if (node.type().equals("placeholder_equals")) result = text.equals(compare.string());
                else if (node.type().equals("placeholder_contains")) result = text.contains(compare.string());
                else {
                    double number;
                    try { number = Double.parseDouble(text); }
                    catch (NumberFormatException invalid) { yield LogicValue.bool(false); }
                    if (!Double.isFinite(number)) yield LogicValue.bool(false);
                    double other = compare.number();
                    result = switch (LogicCatalog.text(node, "operator")) {
                        case "eq" -> number == other;
                        case "ne" -> number != other;
                        case "gt" -> number > other;
                        case "ge" -> number >= other;
                        case "lt" -> number < other;
                        case "le" -> number <= other;
                        default -> false;
                    };
                }
                yield LogicValue.bool(result);
            }
            default -> throw new IllegalArgumentException("logic.value_source");
        };
    }
    private void cancel(AutoCloseable task) { try { task.close();
            } catch (Exception ignored) { /* Validity guard still prevents continuation. */ } }
    public synchronized void close() { closed = true;
        List.copyOf(delays.values()).forEach(this::cancel);
        delays.clear();
        publish();
        }
}
