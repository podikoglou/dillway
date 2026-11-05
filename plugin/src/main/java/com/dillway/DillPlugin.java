package com.dillway;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class DillPlugin extends JavaPlugin {

    private DillListener listener;

    @Override
    public void onEnable() {
        // Ensure output directories exist alongside the plugin data folder
        new File(getDataFolder(), "legit").mkdirs();
        new File(getDataFolder(), "speed").mkdirs();
        new File(getDataFolder(), "recordings").mkdirs();

        listener = new DillListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getCommand("dill").setExecutor(new DillCommand(listener));

        getLogger().info("Dillway ready. Use /dill rec <player> [LEGIT|SPEED]");
    }

    @Override
    public void onDisable() {
        listener.stopAll(); // flush any in-flight recordings
    }
}
