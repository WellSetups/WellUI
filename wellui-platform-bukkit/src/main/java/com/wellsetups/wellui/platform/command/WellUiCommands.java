package com.wellsetups.wellui.platform.command;

import com.wellsetups.wellui.platform.PluginServices;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.core.screen.RendererSelection.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** Configurable subcommand dispatch; asynchronous work returns through the sender's scheduler. */
public final class WellUiCommands implements TabExecutor {
    private final PluginServices services;
    public WellUiCommands(PluginServices services) { this.services = services; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Transport is intentionally absent from command help/permissions: runtime verifies owner, token and UI permission.
        if (args.length > 0 && args[0].equals("_input")) {
            if (sender instanceof Player player && args.length == 3) services.runtime().hudInput(player, args[1], args[2]);
            return true;
        }
        String action = args.length == 0 ? "help" : canonical(args[0]);
        if (action == null) {
            reply(sender, "command.unknown");
            return true;
        }
        if (!permitted(sender, action)) {
            reply(sender, "command.denied");
            return true;
        }
        switch (action) {
            case "help" -> {
                for (String key : com.wellsetups.wellui.platform.config.RuntimeConfig.COMMANDS) {
                    if (permitted(sender, key)) reply(sender, "command.help_line", "label", services.config().commandLabels().get(key),
                            "description", services.messages().text("help." + key));
                }
            }
            case "editor" -> {
                String url = services.editorUrl();
                if (url == null) reply(sender, "editor.disabled");
                else if (sender instanceof Player player) {
                    TextComponent link = new TextComponent(services.messages().text("editor.open"));
                    link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                    player.spigot().sendMessage(link);
                } else reply(sender, "editor.link", "url", url);
            }
            case "list" -> reply(sender, "command.list", "projects", String.join(", ", services.runtime().projectDescriptions()));
            case "debug", "info" -> sender.sendMessage(Messages.color(sender instanceof Player p ? services.debug(p) : services.debug()));
            case "open" -> {
                if (args.length < 2) {
                    reply(sender, "command.open_usage");
                    break;
                }
                String name = null;
                String hash = "";
                Mode mode = services.config().screen().renderer();
                boolean rendererFlag = false;
                boolean valid = true;
                for (int index = 2; index < args.length; index++) {
                    if (args[index].equalsIgnoreCase("--renderer") && !rendererFlag && index + 1 < args.length) {
                        rendererFlag = true;
                        try { mode = Mode.valueOf(args[++index].toUpperCase(Locale.ROOT)); }
                        catch (IllegalArgumentException invalid) { valid = false; }
                    } else if (args[index].equalsIgnoreCase("--pack") && hash.isEmpty() && index + 1 < args.length) {
                        hash = args[++index];
                        if (!hash.matches("[0-9a-f]{64}")) valid = false;
                    } else if (!args[index].startsWith("--") && name == null) name = args[index];
                    else valid = false;
                }
                if (!valid || !hash.isEmpty() && mode != Mode.HUD) {
                    reply(sender, "command.open_usage");
                    break;
                }
                if (mode == Mode.HUD && hash.isEmpty()) {
                    reply(sender, "hud.preview_usage");
                    break;
                }
                Player target = target(sender, name);
                if (target != null) {
                    var result = mode == Mode.HUD ? services.runtime().preview(target.getUniqueId(), args[1], hash)
                            : services.runtime().open(target.getUniqueId(), args[1], mode);
                    result.whenComplete((opened, failure) -> deliver(sender,
                            failure == null ? "open." + opened.name().toLowerCase(Locale.ROOT) : "error.internal"));
                }
            }
            case "close" -> {
                if (args.length > 2) {
                    reply(sender, "command.close_usage");
                    break;
                }
                Player target = target(sender, args.length == 2 ? args[1] : null);
                if (target != null) services.runtime().close(target.getUniqueId()).whenComplete((result, failure) ->
                        deliver(sender, failure == null ? "command.closed" : "error.internal"));
            }
            case "preview" -> {
                if (!(sender instanceof Player player)) reply(sender, "command.player_required");
                else if (args.length != 3 || !args[2].matches("[0-9a-f]{64}")) reply(sender, "hud.preview_usage");
                else services.runtime().preview(player.getUniqueId(), args[1], args[2]).whenComplete((result, failure) ->
                        deliver(sender, failure == null ? "open." + result.name().toLowerCase(Locale.ROOT) : "hud.prepare"));
            }
            case "hudpack", "screenpack" -> {
                if (args.length != 2) {
                    reply(sender, action.equals("screenpack") ? "screen.pack_usage" : "hud.pack_usage");
                    break;
                }
                reply(sender, "command.working");
                try {
                    services.scheduler().async(() -> {
                        try {
                            if (action.equals("screenpack")) {
                                var pack = services.exportScreen(args[1]);
                                deliver(sender, "screen.packed", "path", pack.path(), "hash", pack.sha256(), "sha1", pack.sha1(),
                                        "bytes", pack.bytes(), "millis", 0, "url", services.screenPackUrl(pack.sha256()));
                            } else {
                                var pack = services.exportHud(args[1]);
                                deliver(sender, "hud.packed", "project", pack.document().id(), "hash", pack.sha256(),
                                        "bytes", pack.bytes(), "millis", pack.buildMillis());
                            }
                        } catch (Exception failure) {
                            services.logFailure(failure);
                            deliver(sender, "error.operation", "reason", services.messages().text(
                                    failure.getMessage() == null ? "error.internal" : failure.getMessage()));
                        }
                    });
                } catch (RejectedExecutionException busy) { reply(sender, "command.busy"); }
            }
            case "packload" -> {
                if (args.length > 2) { reply(sender, "screen.packload_usage");
                    break;
                }
                Player player = target(sender, args.length == 2 ? args[1] : null);
                if (player != null) services.scheduler().entity(player, () -> services.preload(player).whenComplete((loaded, failure) ->
                        deliver(sender, failure == null && Boolean.TRUE.equals(loaded) ? "screen.pack_ready" : "screen.pack_unavailable")), () -> { });
            }
            case "packverify", "packrepair" -> {
                if (args.length != 1) {
                    reply(sender, "screen.verify.usage");
                    break;
                }
                String locale = sender instanceof Player player ? player.getLocale() : services.config().locale();
                try {
                    services.scheduler().async(() -> {
                        try {
                            if (action.equals("packrepair")) {
                                services.repairMergedSource();
                                deliver(sender, "screen.verify.repaired");
                            } else {
                                services.verifyMergedPack();
                                deliver(sender, "screen.verify.passed", "report", services.integrationReport(locale));
                            }
                        } catch (Exception failure) { deliver(sender, "error.operation", "reason", services.packFailure(failure, locale)); }
                    });
                } catch (RejectedExecutionException busy) { reply(sender, "command.busy"); }
            }
            case "reload", "pack", "migrate" -> {
                reply(sender, "command.working");
                try {
                    services.scheduler().async(() -> {
                        try {
                            String result;
                            if (action.equals("reload")) result = services.reload() ? "command.reloaded" : "command.restart";
                            else if (action.equals("pack")) {
                                services.exportPacks();
                                result = "command.packed";
                            } else {
                                services.migrate();
                                result = "command.migrated";
                            }
                            deliver(sender, result);
                        } catch (Exception failure) {
                            services.logFailure(failure);
                            deliver(sender, "error.internal");
                        }
                    });
                } catch (RejectedExecutionException busy) { reply(sender, "command.busy"); }
            }
            default -> reply(sender, "command.unknown");
        }
        return true;
    }
    private Player target(CommandSender sender, String name) {
        if (name == null) {
            if (sender instanceof Player player) return player;
            reply(sender, "command.player_required");
            return null;
        }
        if (!sender.hasPermission("wellui.admin") && !sender.hasPermission("wellui.command.others")) {
            reply(sender, "command.denied");
            return null;
        }
        Player target = services.runtime().player(name);
        if (target == null) reply(sender, "open.offline");
        return target;
    }
    private String canonical(String label) {
        return services.config().commandLabels().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(label))
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
    }
    private boolean permitted(CommandSender sender, String action) {
        return sender.hasPermission("wellui.admin") || sender.hasPermission("wellui.command." + action);
    }
    private void reply(CommandSender sender, String key, Object... values) {
        String locale = sender instanceof Player player ? player.getLocale() : services.config().locale();
        sender.sendMessage(services.messages().prefixed(locale, key, values));
    }
    private void deliver(CommandSender sender, String key, Object... values) {
        if (sender instanceof Player player) services.scheduler().entity(player, () -> reply(sender, key, values), () -> { });
        else services.scheduler().global(() -> reply(sender, key, values));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            services.config().commandLabels().forEach((key, value) -> { if (permitted(sender, key)) candidates.add(value); });
        } else if (args.length > 1) {
            String action = canonical(args[0]);
            if (action == null || !permitted(sender, action)) return List.of();
            if (args.length == 2 && List.of("open", "preview", "hudpack", "screenpack").contains(action)) services.runtime().commandNames().stream()
                    .filter(id -> sender.hasPermission("wellui.admin") || sender.hasPermission("wellui.ui." + services.runtime().resolveDocument(id))).forEach(candidates::add);
            if ((args.length == 3 && action.equals("open") || args.length == 2 && action.equals("close"))
                    && (sender.hasPermission("wellui.admin") || sender.hasPermission("wellui.command.others"))) candidates.addAll(services.runtime().playerNames());
            if (action.equals("open") && args.length >= 3) {
                if (args[args.length - 2].equalsIgnoreCase("--renderer")) candidates.addAll(List.of("auto", "screen", "hud", "inventory"));
                else candidates.add("--renderer");
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
