package com.dillway.agent

import com.dillway.agent.model.ModelManager
import me.vaperion.blade.Blade
import me.vaperion.blade.paper.BladePaperPlatform
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DwAgentPlugin : JavaPlugin() {

    /**
     * Paper derives the data folder from the plugin name. The plugin was called
     * Dillway before the rename, so carry that folder over once instead of
     * hiding the existing recordings, models and config.
     */
    override fun onLoad() {
        val legacy = File(server.pluginsFolder, "Dillway")
        val target = dataFolder
        if (!legacy.isDirectory) return
        if (target.exists() && target.list()?.isNotEmpty() == true) {
            logger.warning(
                "Both plugins/Dillway and plugins/${target.name} hold data; " +
                    "using plugins/${target.name} and leaving the old folder alone",
            )
            return
        }
        try {
            Files.deleteIfExists(target.toPath())
            Files.move(legacy.toPath(), target.toPath())
            logger.info("Moved plugins/Dillway to plugins/${target.name}")
        } catch (e: IOException) {
            logger.warning(
                "Could not move plugins/Dillway to plugins/${target.name}: ${e.message}. " +
                    "Move the folder manually to keep old recordings and config",
            )
        }
    }

    // Eager fields: set before onEnable, so onDisable is always safe.
    val metrics = Metrics()
    val watches = WatchService(this)
    private val listener = DillListener(this, watches)
    val storage = S3Storage(this)
    val models = ModelManager(this)
    val recordings = RecordingStore(this)
    val flow = RecordingFlow(this)
    val kit = RecordingKit(this, listener, flow)

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

    /** Recording sessions that still hold packets in memory. */
    fun activeRecordings(): Int = listener.activeRecordings()

    /** Probability at which a window counts as a cheat verdict. */
    fun threshold(): Double = config.getDouble("prediction.threshold", 0.5)

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
        server.pluginManager.registerEvents(kit, this)

        Blade.forPlatform(BladePaperPlatform(this))
            .config { config ->
                config.defaultPermissionMessage("You don't have permission to use this command.")
            }
            .build()
            .register(DillCommand(this, listener, flow, models, kit))
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
