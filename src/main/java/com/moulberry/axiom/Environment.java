package com.moulberry.axiom;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Small compatibility layer between Paper and Folia.
 *
 * The modern Paper scheduler API (global/region/async schedulers) works identically
 * on both Paper and Folia, so all scheduling goes through those APIs instead of the
 * removed Bukkit scheduler. The only difference is that Folia has no "main thread":
 * world/entity work must run on the thread that owns the respective region.
 */
public final class Environment {

    private static final boolean FOLIA = detectFolia();

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedData");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private Environment() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Schedules a plugin-global task. On Paper this runs on the main thread,
     * on Folia this runs on the global region thread.
     */
    public static void runGlobal(Plugin plugin, Runnable runnable) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, runnable);
    }

    /**
     * Schedules a repeating plugin-global task.
     */
    public static void runGlobalFixedRate(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> runnable.run(), initialDelayTicks, periodTicks);
    }

    /**
     * Schedules a task on the region that owns the given chunk. On Paper this runs
     * on the main thread, on Folia it runs on the thread that owns the chunk's region.
     */
    public static void runOnRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
    }

    /**
     * Schedules a task on the region that owns the given entity's position.
     */
    public static void runOnEntityRegion(Plugin plugin, net.minecraft.world.entity.Entity entity, Runnable runnable) {
        var blockPos = entity.blockPosition();
        runOnRegion(plugin, entity.level().getWorld(), blockPos.getX() >> 4, blockPos.getZ() >> 4, runnable);
    }

    /**
     * Checks whether the current thread is the server main thread (Paper) or a
     * region/global tick thread (Folia).
     */
    public static boolean isServerThread() {
        if (FOLIA) {
            try {
                Class<?> tickThread = Class.forName("io.papermc.paper.util.TickThread");
                return (Boolean) tickThread.getMethod("isTickThread").invoke(null);
            } catch (Exception e) {
                return false;
            }
        }
        return Bukkit.isPrimaryThread();
    }

}