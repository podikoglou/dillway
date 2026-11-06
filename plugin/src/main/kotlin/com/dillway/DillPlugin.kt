package com.dillway

import com.dillway.model.ModelManager
import me.vaperion.blade.Blade
import me.vaperion.blade.paper.BladePaperPlatform
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class DillPlugin : JavaPlugin() {

    // Eager fields: set before onEnable, so onDisable is always safe.
    val watches = WatchService(this)
    private val listener = DillListener(this, watches)
    val storage = S3Storage(this)
    val models = ModelManager(this)
    val recordings = RecordingStore(this)
    val flow = RecordingFlow(this)

    /** Cheats the admin can label, from config; models add themselves. */
    val cheatNames: List<String>
        get() = config.getStringList("cheats")
            .map(Labels::normalize)
            .filter { it != Labels.LEGIT && it != Labels.UNLABELED }
            .distinct()

    /** Labels offered by the stop flow: `legit` first, then every known cheat. */
    fun availableLabels(): List<String> =
        listOf(Labels.LEGIT) + (cheatNames + models.knownCheats()).distinct().sorted()

    /** True when a recording session holds this player's packets. */
    fun isRecording(player: Player): Boolean = listener.isRecording(player)

    override fun onEnable() {
        saveDefaultConfig()

        val remote = storage.start()
        if (remote) {
            logger.info("Remote storage enabled for s3://${config.getString("s3.bucket")}")
        } else {
            logger.info("Remote storage disabled; recordings stay local and only cached models load")
        }

        // Cache first: predictions must work even when R2 is unreachable.
        models.loadCached()
        if (remote && config.getBoolean("models.refresh-on-startup", true)) {
            server.scheduler.runTaskAsynchronously(this, Runnable {
                val report = models.refresh()
                if (report.failed.isNotEmpty()) {
                    logger.info(
                        "Model refresh failures: " +
                            report.failed.joinToString { (cheat, reason) -> "$cheat ($reason)" },
                    )
                }
            })
        }

        server.pluginManager.registerEvents(listener, this)

        Blade.forPlatform(BladePaperPlatform(this))
            .config { config ->
                config.defaultPermissionMessage("You don't have permission to use this command.")
            }
            .build()
            .register(DillCommand(this, listener, flow, models))
    }

    override fun onDisable() {
        // Active recordings are flushed locally as `unlabeled`; they cannot be
        // labeled during shutdown and are never uploaded.
        watches.stopAll()
        listener.stopAll()
        models.close()
        storage.stop()
    }
}
