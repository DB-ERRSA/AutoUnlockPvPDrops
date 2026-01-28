package com.errsa;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class AutoUnlockPvPDrops extends JavaPlugin implements Listener {

    // ---- PvPToggle reflection ----
    private Method isPvpEnabledMethod; // com.lukeonuke.pvptoggle.service.PvpService#isPvpEnabled(Player)

    // ---- GriefPrevention reflection ----
    private Plugin griefPrevention;
    private Field gpDataStoreField;           // GriefPrevention.dataStore
    private Method gpGetPlayerDataMethod;     // DataStore.getPlayerData(UUID)
    private Field gpDropsAreUnlockedField;    // PlayerData.dropsAreUnlocked

    @Override
    public void onEnable() {
        bindPvPToggle();
        bindGriefPrevention();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("AutoUnlockPvPDrops enabled. PvPToggleHook=" + (isPvpEnabledMethod != null)
                + " GPHook=" + (griefPrevention != null));
    }

    // ================= PvPToggle =================

    private void bindPvPToggle() {
        try {
            Class<?> pvpService = Class.forName("com.lukeonuke.pvptoggle.service.PvpService");
            isPvpEnabledMethod = pvpService.getMethod("isPvpEnabled", Player.class);
            getLogger().info("Hooked PvPToggle PvpService.isPvpEnabled(Player).");
        } catch (Throwable t) {
            isPvpEnabledMethod = null;
            getLogger().warning("PvPToggle not found or API changed. PvP checks disabled (no auto-unlock).");
        }
    }

    private boolean isPvpEnabled(Player victim) {
        if (isPvpEnabledMethod == null) return false;
        try {
            Object result = isPvpEnabledMethod.invoke(null, victim); // static method in your plugin
            return (result instanceof Boolean b) && b;
        } catch (Throwable t) {
            getLogger().warning("PvPToggle reflection call failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // ================= GriefPrevention =================

    private void bindGriefPrevention() {
        griefPrevention = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (griefPrevention == null || !griefPrevention.isEnabled()) {
            griefPrevention = null;
            getLogger().warning("GriefPrevention not found/enabled. Drop unlock will not work.");
            return;
        }

        try {
            // GP plugin instance has a 'dataStore' field
            gpDataStoreField = griefPrevention.getClass().getDeclaredField("dataStore");
            gpDataStoreField.setAccessible(true);

            Object dataStore = gpDataStoreField.get(griefPrevention);
            if (dataStore == null) throw new IllegalStateException("GP dataStore was null");

            gpGetPlayerDataMethod = dataStore.getClass().getMethod("getPlayerData", UUID.class);

            // PlayerData class/field are stable in GP 16.18.4
            Class<?> playerDataClass = Class.forName("me.ryanhamshire.GriefPrevention.PlayerData");
            gpDropsAreUnlockedField = playerDataClass.getDeclaredField("dropsAreUnlocked");
            gpDropsAreUnlockedField.setAccessible(true);

            getLogger().info("Hooked GriefPrevention DataStore + PlayerData.dropsAreUnlocked.");
        } catch (Throwable t) {
            griefPrevention = null;
            gpDataStoreField = null;
            gpGetPlayerDataMethod = null;
            gpDropsAreUnlockedField = null;

            getLogger().warning("Failed to hook GriefPrevention internals. Auto-unlock disabled. Reason: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private boolean setDropsUnlocked(Player victim) {
        if (griefPrevention == null) return false;

        try {
            Object dataStore = gpDataStoreField.get(griefPrevention);
            Object playerData = gpGetPlayerDataMethod.invoke(dataStore, victim.getUniqueId());
            if (playerData == null) return false;

            gpDropsAreUnlockedField.setBoolean(playerData, true);
            return true;
        } catch (Throwable t) {
            getLogger().warning("GP unlock failed for " + victim.getName() + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    // ================= Event =================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (griefPrevention == null || isPvpEnabledMethod == null) return;

        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        if (!isPvpEnabled(victim)) return;

        // Try quickly; if it didn't stick yet, retry a couple times
        scheduleUnlockAttempt(victim, 5L);
        scheduleUnlockAttempt(victim, 20L);
        scheduleUnlockAttempt(victim, 40L);
    }

    private void scheduleUnlockAttempt(Player victim, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            boolean ok = setDropsUnlocked(victim);
            if (ok) {
                // Keep logs minimal; comment out if you want silence:
                getLogger().info("[AutoUnlock] Unlocked GP death drops for " + victim.getName() + " (t+" + delayTicks + ")");
            }
        }, delayTicks);
    }
}

