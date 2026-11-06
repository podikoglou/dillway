package com.dillway.agent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

private val MINI = MiniMessage.miniMessage()

/** Thousands-separated count, so long packet counts stay readable. */
fun count(value: Int): String = count(value.toLong())

fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

/** `2.0` becomes `2`: chat numbers without noise zeros. */
fun number(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Stable identity for a sender across chat-button commands. */
fun senderKey(sender: CommandSender): String =
    (sender as? Player)?.uniqueId?.toString() ?: "console:${sender.name}"

fun CommandSender.sendMini(template: String, vararg resolvers: TagResolver) {
    sendMessage(MINI.deserialize(template, *resolvers))
}

fun mini(template: String, vararg resolvers: TagResolver): Component =
    MINI.deserialize(template, *resolvers)

/** A chat button that runs [command] as the clicking player. */
fun chatButton(
    label: String,
    command: String,
    color: NamedTextColor = NamedTextColor.GREEN,
): Component = Component.text(label, color)
    .clickEvent(ClickEvent.runCommand(command))
    .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))

fun separator(): Component = Component.text("  ")

/** A dim dot that separates facts on one chat line. */
fun dot(): Component = Component.text(" · ", NamedTextColor.DARK_GRAY)
