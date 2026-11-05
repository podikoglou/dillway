package com.dillway;

import me.vaperion.blade.annotation.command.Command;
import me.vaperion.blade.annotation.command.Permission;
import me.vaperion.blade.annotation.parameter.Name;
import me.vaperion.blade.annotation.parameter.Sender;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Registers the /dill command tree with Blade. */
public final class DillCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DillListener listener;

    public DillCommand(DillListener listener) {
        this.listener = listener;
    }

    @Command("dill rec")
    @Permission("dillway.admin")
    public void rec(@Sender CommandSender sender,
                    @Name("player") Player target,
                    @Name("type") RecordingType type) {
        if (listener.startRecording(target, type)) {
            sender.sendMessage(MINI.deserialize(
                "<green>Recording <yellow><player></yellow> as <aqua><type></aqua>",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.parsed("type", type.name())
            ));
        } else {
            sender.sendMessage(MINI.deserialize(
                "<red><player> is already being recorded</red>",
                Placeholder.unparsed("player", target.getName())
            ));
        }
    }

    @Command("dill stop")
    @Permission("dillway.admin")
    public void stop(@Sender CommandSender sender,
                     @Name("player") Player target) {
        if (listener.stopRecording(target)) {
            sender.sendMessage(MINI.deserialize(
                "<green>Stopped recording <yellow><player></yellow>",
                Placeholder.unparsed("player", target.getName())
            ));
        } else {
            sender.sendMessage(MINI.deserialize(
                "<red><player> is not being recorded</red>",
                Placeholder.unparsed("player", target.getName())
            ));
        }
    }
}
