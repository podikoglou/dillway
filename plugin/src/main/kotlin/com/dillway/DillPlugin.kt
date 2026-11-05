package com.dillway

import me.vaperion.blade.Blade
import me.vaperion.blade.paper.BladePaperPlatform
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class DillPlugin : JavaPlugin() {

    // Eager field: set before onEnable, so onDisable is always safe.
    private val listener = DillListener(this)

    override fun onEnable() {
        // Ensure one output directory per recording type
        RecordingType.entries.forEach { type ->
            File(dataFolder, type.folder).mkdirs()
        }

        server.pluginManager.registerEvents(listener, this)

        Blade.forPlatform(BladePaperPlatform(this))
            .config { config ->
                config.defaultPermissionMessage("You don't have permission to use this command.")
            }
            .build()
            .register(DillCommand(listener))
    }

    override fun onDisable() {
        listener.stopAll() // flush any in-flight recordings
    }
}
