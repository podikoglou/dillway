package com.dillway

import me.vaperion.blade.annotation.command.Command
import me.vaperion.blade.annotation.command.Permission
import me.vaperion.blade.annotation.parameter.Name
import me.vaperion.blade.annotation.parameter.Sender
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Registers the /dill command tree with Blade. */
class DillCommand(private val listener: DillListener) {

    @Command("dill rec")
    @Permission("dillway.admin")
    fun rec(
        @Sender sender: CommandSender,
        @Name("player") target: Player,
        @Name("type") type: RecordingType,
    ) {
        if (listener.startRecording(target, type)) {
            sender.sendMini(
                "<green>Recording <yellow><player></yellow> as <aqua><type></aqua>",
                Placeholder.unparsed("player", target.name),
                Placeholder.parsed("type", type.name),
            )
        } else {
            sender.sendMini(
                "<red><player> is already being recorded</red>",
                Placeholder.unparsed("player", target.name),
            )
        }
    }

    @Command("dill stop")
    @Permission("dillway.admin")
    fun stop(
        @Sender sender: CommandSender,
        @Name("player") target: Player,
    ) {
        if (listener.stopRecording(target)) {
            sender.sendMini(
                "<green>Stopped recording <yellow><player></yellow>",
                Placeholder.unparsed("player", target.name),
            )
        } else {
            sender.sendMini(
                "<red><player> is not being recorded</red>",
                Placeholder.unparsed("player", target.name),
            )
        }
    }
}

private val MINI = MiniMessage.miniMessage()

private fun CommandSender.sendMini(template: String, vararg resolvers: TagResolver) {
    sendMessage(MINI.deserialize(template, *resolvers))
}
