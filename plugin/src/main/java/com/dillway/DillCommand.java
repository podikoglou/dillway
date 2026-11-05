package com.dillway;

import me.vaperion.blade.annotation.command.Command;
import me.vaperion.blade.annotation.command.Permission;
import me.vaperion.blade.annotation.parameter.Name;
import me.vaperion.blade.annotation.parameter.Sender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Registers the /dill command tree with Blade. */
public final class DillCommand {

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
            sender.sendMessage("§a▶ Recording §e" + target.getName() + " §aas §b" + type);
        } else {
            sender.sendMessage("§c" + target.getName() + " is already being recorded.");
        }
    }

    @Command("dill stop")
    @Permission("dillway.admin")
    public void stop(@Sender CommandSender sender,
                     @Name("player") Player target) {
        if (listener.stopRecording(target)) {
            sender.sendMessage("§a■ Stopped recording §e" + target.getName() + " §aand saved to disk.");
        } else {
            sender.sendMessage("§c" + target.getName() + " is not currently being recorded.");
        }
    }
}
