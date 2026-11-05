package com.dillway;

import me.vaperion.blade.Blade;
import me.vaperion.blade.paper.BladePaperPlatform;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class DillPlugin extends JavaPlugin {

    private DillListener listener;

    @Override
    public void onEnable() {
        // Ensure one output directory per recording type
        for (RecordingType type : RecordingType.values()) {
            new File(getDataFolder(), type.folder()).mkdirs();
        }

        listener = new DillListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        Blade.forPlatform(new BladePaperPlatform(this))
            .config(cfg -> cfg.defaultPermissionMessage(
                "You don't have permission to use this command."))
            .build()
            .register(new DillCommand(listener));
    }

    @Override
    public void onDisable() {
        listener.stopAll(); // flush any in-flight recordings
    }
}
