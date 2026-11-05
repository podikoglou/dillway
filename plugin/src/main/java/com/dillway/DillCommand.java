package com.dillway;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class DillCommand implements CommandExecutor {

    private final DillListener listener;

    public DillCommand(DillListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dillway.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        String sub    = args[0].toLowerCase();
        Player target = sender.getServer().getPlayer(args[1]);

        if (target == null) {
            sender.sendMessage("§cPlayer §e" + args[1] + " §cnot found or not online.");
            return true;
        }

        switch (sub) {
            case "rec" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: §f/dill rec <player> <LEGIT|SPEED>");
                    return true;
                }
                String type = args[2].toUpperCase(Locale.ROOT);
                if (listener.startRecording(target, type)) {
                    sender.sendMessage("§a▶ Recording §e" + target.getName() + " §aas §b" + type);
                } else {
                    sender.sendMessage("§c" + target.getName() + " is already being recorded.");
                }
            }
            case "stop" -> {
                if (listener.stopRecording(target)) {
                    sender.sendMessage("§a■ Stopped recording §e" + target.getName() + " §aand saved to disk.");
                } else {
                    sender.sendMessage("§c" + target.getName() + " is not currently being recorded.");
                }
            }
            default -> sendUsage(sender);
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§eUsage: §f/dill rec <player> <LEGIT|SPEED>");
        sender.sendMessage("§eUsage: §f/dill stop <player>");
    }
}
