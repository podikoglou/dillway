package com.dillway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class DillListener implements Listener {

    private final DillPlugin plugin;
    private final Map<UUID, RecordingSession> sessions = new HashMap<>();
    private final Gson gson = new GsonBuilder().create();

    public DillListener(DillPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean startRecording(Player player, RecordingType type) {
        if (sessions.containsKey(player.getUniqueId())) return false;
        sessions.put(player.getUniqueId(), new RecordingSession(player.getUniqueId(), type));
        return true;
    }

    public boolean stopRecording(Player player) {
        RecordingSession session = sessions.remove(player.getUniqueId());
        if (session == null) return false;
        saveSession(player, session);
        return true;
    }

    public boolean isRecording(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Called on shutdown — flushes every active session. */
    public void stopAll() {
        for (UUID id : new HashSet<>(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) stopRecording(player);
        }
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        RecordingSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        boolean posChanged  = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        boolean lookChanged = from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch();
        if (!posChanged && !lookChanged) return;

        // Reconstruct the client packet type from what actually changed
        String packetType;
        if (posChanged && lookChanged) {
            packetType = "PacketPlayInPositionLook";
        } else if (posChanged) {
            packetType = "PacketPlayInPosition";
        } else {
            packetType = "PacketPlayInLook";
        }

        // data mirrors exactly the fields in the recorded JSON
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x",        to.getX());
        data.put("y",        to.getY());
        data.put("z",        to.getZ());
        data.put("yaw",      (double) to.getYaw());
        data.put("pitch",    (double) to.getPitch());
        data.put("f",        player.isOnGround());
        data.put("hasPos",   posChanged);
        data.put("hasLook",  lookChanged);

        // context is captured at the time of the packet (from = server's current state)
        session.packets.add(buildEntry(packetType, player, from, data));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        RecordingSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", System.currentTimeMillis());

        session.packets.add(buildEntry("PacketPlayInArmAnimation", player, player.getLocation(), data));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        RecordingSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemInHandIndex", event.getNewSlot());

        session.packets.add(buildEntry("PacketPlayInHeldItemSlot", player, player.getLocation(), data));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> buildEntry(String type, Player player, Location contextLoc, Map<String, Object> data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type",      type);
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("data",      data);
        entry.put("context",   buildContext(player, contextLoc));
        return entry;
    }

    private Map<String, Object> buildContext(Player player, Location loc) {
        Vector vel       = player.getVelocity();
        Vector dir       = loc.getDirection();
        double eyeHeight = player.getEyeHeight();

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("fly_speed",           (double) player.getFlySpeed());
        ctx.put("velocity_z",          vel.getZ());
        ctx.put("location_x",          loc.getX());
        ctx.put("location_y",          loc.getY());
        ctx.put("location_direction_y", dir.getY());
        ctx.put("location_direction_x", dir.getX());
        ctx.put("velocity_x",          vel.getX());
        ctx.put("velocity_y",          vel.getY());
        ctx.put("saturation",          (double) player.getSaturation());
        ctx.put("location_direction_z", dir.getZ());
        ctx.put("location_z",          loc.getZ());
        ctx.put("food_level",          player.getFoodLevel());
        ctx.put("eye_location_yaw",    (double) loc.getYaw());
        ctx.put("walk_speed",          (double) player.getWalkSpeed());
        ctx.put("eye_height",          eyeHeight);
        ctx.put("allow_flight",        player.getAllowFlight());
        ctx.put("exhaustion",          (double) player.getExhaustion());
        ctx.put("health",              player.getHealth());
        ctx.put("eye_location_pitch",  (double) loc.getPitch());
        ctx.put("location_pitch",      (double) loc.getPitch());
        ctx.put("fall_distance",       (double) player.getFallDistance());
        ctx.put("location_yaw",        (double) loc.getYaw());
        ctx.put("health_scale",        player.getHealthScale());
        ctx.put("sprinting",           player.isSprinting());
        ctx.put("eye_location_y",      loc.getY() + eyeHeight);
        ctx.put("eye_location_x",      loc.getX());
        ctx.put("flying",              player.isFlying());
        ctx.put("eye_location_z",      loc.getZ());
        ctx.put("sneaking",            player.isSneaking());
        ctx.put("on_ground",           player.isOnGround());
        return ctx;
    }

    private void saveSession(Player player, RecordingSession session) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id",      session.started);
        output.put("started", session.started);
        output.put("player",  session.playerId.toString());
        output.put("type",    session.type);
        output.put("packets", session.packets);

        // Route into the subfolder named by the label
        File dir = new File(plugin.getDataFolder(), session.type.folder());
        dir.mkdirs();
        File file = new File(dir, session.started + ".json");

        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(output, writer);
            plugin.getLogger().info(
                "Saved " + session.type + " recording for " + player.getName() +
                " → " + file.getName() + " (" + session.packets.size() + " packets)"
            );
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save recording: " + e.getMessage());
        }
    }
}
