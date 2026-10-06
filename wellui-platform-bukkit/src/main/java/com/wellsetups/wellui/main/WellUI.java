package com.wellsetups.wellui.main;

import com.wellsetups.wellui.platform.PluginServices;
import com.wellsetups.wellui.platform.config.Messages;
import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;

/** Lifecycle entry point; implementation responsibilities live in composed services. */
public final class WellUI extends JavaPlugin {
    private PluginServices services;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().toPath().resolve("lang/en_US.yml").toFile().exists()) saveResource("lang/en_US.yml", false);
        try {
            services = new PluginServices(this);
            services.start();
        } catch (Exception failure) {
            try {
                Messages messages = Messages.load(getDataFolder().toPath().resolve("lang"), "en_US");
                getLogger().severe(messages.text("startup.failed", "reason", messages.text(failure.getMessage() == null
                        ? "error.internal" : failure.getMessage())));
            } catch (IOException localeFailure) {
                // If the locale itself is unreadable, retain the diagnostic code rather than inventing text.
                getLogger().severe("startup.failed: " + failure.getClass().getSimpleName());
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (services != null) services.close();
    }
}
