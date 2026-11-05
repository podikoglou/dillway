package com.dillway

import me.vaperion.blade.Blade
import me.vaperion.blade.paper.BladePaperPlatform
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class DillPlugin : JavaPlugin() {

    // Eager fields: set before onEnable, so onDisable is always safe.
    private val listener = DillListener(this)
    val uploader = S3Uploader(this)

    override fun onEnable() {
        saveDefaultConfig()

        // Ensure one output directory per recording type
        RecordingType.entries.forEach { type ->
            File(dataFolder, type.folder).mkdirs()
        }

        if (uploader.start()) {
            logger.info("Uploading recordings to s3://${config.getString("s3.bucket")}")
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
        // Sessions flush (and upload) before the uploader's client closes.
        listener.stopAll()
        uploader.stop()
    }
}
