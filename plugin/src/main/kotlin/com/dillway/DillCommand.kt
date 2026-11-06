package com.dillway

import com.dillway.model.ModelManager
import me.vaperion.blade.annotation.command.Command
import me.vaperion.blade.annotation.command.Permission
import me.vaperion.blade.annotation.parameter.Name
import me.vaperion.blade.annotation.parameter.Opt
import me.vaperion.blade.annotation.parameter.Sender
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Registers the /dill command tree with Blade. */
class DillCommand(
    private val plugin: DillPlugin,
    private val listener: DillListener,
    private val flow: RecordingFlow,
    private val modelManager: ModelManager,
) {

    @Command("dill rec start")
    @Permission("dillway.admin")
    fun start(
        @Sender sender: CommandSender,
        @Name("player") target: Player,
    ) {
        when (listener.startRecording(target)) {
            RecordingStart.ALREADY_RECORDING -> {
                sender.sendMini(
                    "<red><player> is already being recorded</red>",
                    Placeholder.unparsed("player", target.name),
                )
                return
            }

            RecordingStart.ALREADY_WATCHED -> {
                sender.sendMini(
                    "<red><player> is being watched. Stop the watch first.</red>",
                    Placeholder.unparsed("player", target.name),
                )
                return
            }

            RecordingStart.STARTED -> Unit
        }

        if (sender is Player) {
            sender.sendMessage(
                Component.text()
                    .append(
                        mini(
                            "<green>Recording <yellow><player></yellow>.</green>",
                            Placeholder.unparsed("player", target.name),
                        ),
                    )
                    .append(separator())
                    .append(
                        chatButton("[Stop]", "/dill rec stop ${target.name}", NamedTextColor.GRAY),
                    )
                    .build(),
            )
        } else {
            sender.sendMini(
                "<green>Recording <yellow><player></yellow>. " +
                    "Stop with <white>/dill rec stop <player></white>.</green>",
                Placeholder.unparsed("player", target.name),
            )
        }
    }

    @Command("dill rec stop")
    @Permission("dillway.admin")
    fun stop(
        @Sender sender: CommandSender,
        @Name("player") target: Player,
    ) {
        val stopped = listener.stopRecording(target)
        if (stopped == null) {
            sender.sendMini(
                "<red><player> is not being recorded</red>",
                Placeholder.unparsed("player", target.name),
            )
        } else {
            flow.offer(sender, stopped)
        }
    }

    @Command("dill rec train")
    @Permission("dillway.admin")
    fun train(@Sender sender: CommandSender) {
        flow.train(sender)
    }

    @Command("dill rec label")
    @Permission("dillway.admin")
    fun label(
        @Sender sender: CommandSender,
        @Name("label") label: String,
    ) {
        flow.toggleLabel(sender, label)
    }

    @Command("dill save")
    @Permission("dillway.admin")
    fun save(@Sender sender: CommandSender) {
        flow.save(sender)
    }

    @Command("dill watch")
    @Permission("dillway.admin")
    fun watch(
        @Sender sender: CommandSender,
        @Name("player") target: Player,
    ) {
        plugin.watches.start(sender, target)
    }

    @Command("dill watch stop")
    @Permission("dillway.admin")
    fun watchStop(
        @Sender sender: CommandSender,
        @Opt @Name("player") target: Player?,
    ) {
        plugin.watches.stop(sender, target)
    }

    @Command("dill rec predict")
    @Permission("dillway.admin")
    fun predict(@Sender sender: CommandSender) {
        flow.predict(sender)
    }

    @Command("dill rec discard")
    @Permission("dillway.admin")
    fun discard(@Sender sender: CommandSender) {
        flow.discard(sender)
    }

    @Command("dill models")
    @Permission("dillway.admin")
    fun models(@Sender sender: CommandSender) {
        val loaded = modelManager.summary()
        if (loaded.isEmpty()) {
            sender.sendMini(
                "<gray>No models loaded <dark_gray>·</dark_gray> " +
                    "run <white>/dill models reload</white></gray>",
            )
            return
        }

        val text = Component.text()
            .append(
                mini(
                    "<gray>Loaded models <dark_gray>(<count>)</dark_gray></gray>",
                    Placeholder.unparsed("count", loaded.size.toString()),
                ),
            )
        for (model in loaded) {
            text.append(Component.newline())
                .append(
                    mini(
                        "  <aqua><cheat></aqua> <gray><version></gray> " +
                            "<dark_gray>· <window>s window · <features> features</dark_gray>",
                        Placeholder.unparsed("cheat", model.cheat),
                        Placeholder.unparsed("version", model.version),
                        Placeholder.unparsed("window", number(model.windowSeconds)),
                        Placeholder.unparsed("features", model.featureCount.toString()),
                    ),
                )
        }
        sender.sendMessage(text.build())
    }

    @Command("dill models reload")
    @Permission("dillway.admin")
    fun reloadModels(@Sender sender: CommandSender) {
        if (!plugin.storage.enabled) {
            sender.sendMini(
                "<red>Remote storage is disabled in config.yml; " +
                    "only the local model cache is available.</red>",
            )
            return
        }

        sender.sendMini("<gray>Refreshing models from R2 ...</gray>")

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val report = modelManager.refresh()
            if (!plugin.isEnabled) return@Runnable

            plugin.server.scheduler.runTask(plugin, Runnable {
                val text = Component.text()
                    .append(mini("<gray>Models reloaded</gray>"))

                for (model in report.loaded) {
                    text.append(Component.newline())
                        .append(
                            mini(
                                "  <green><cheat></green> <gray><version></gray> " +
                                    "<dark_gray>· <window>s window · <features> features</dark_gray>",
                                Placeholder.unparsed("cheat", model.cheat),
                                Placeholder.unparsed("version", model.version),
                                Placeholder.unparsed("window", number(model.windowSeconds)),
                                Placeholder.unparsed("features", model.featureCount.toString()),
                            ),
                        )
                }

                for ((cheat, reason) in report.failed) {
                    text.append(Component.newline())
                        .append(
                            mini(
                                "  <red><cheat>: <reason></red>",
                                Placeholder.unparsed("cheat", cheat),
                                Placeholder.unparsed("reason", reason),
                            ),
                        )
                }

                if (report.loaded.isEmpty()) {
                    text.append(Component.newline())
                        .append(
                            mini(
                                "<yellow>Nothing loaded, " +
                                    "the cached models stay in memory.</yellow>",
                            ),
                        )
                }

                sender.sendMessage(text.build())
            })
        })
    }
}
