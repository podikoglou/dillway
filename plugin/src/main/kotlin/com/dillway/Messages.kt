package com.dillway

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender

private val MINI = MiniMessage.miniMessage()

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
