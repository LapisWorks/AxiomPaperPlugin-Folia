package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.Environment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Queues world-affecting operations and ticks them on the region thread that owns
 * the executing player. On Paper this behaves like the original main-thread queue,
 * on Folia each operation runs on the region the player currently is in.
 */
public class OperationQueue {

    private final Lock queueLock = new ReentrantLock();
    private final Map<ServerLevel, List<PendingOperation>> newPendingOperations = new HashMap<>();
    private final Map<ServerLevel, List<PendingOperation>> pendingOperations = new HashMap<>();
    private final Set<ServerLevel> scheduledThisTick = new HashSet<>();
    private final Set<PendingOperation> failedOperations = ConcurrentHashMap.newKeySet();

    /**
     * Called on the global region thread (Paper main thread / Folia global thread).
     */
    public void tick(AxiomPaper plugin) {
        this.scheduledThisTick.clear();

        // Move newly added operations into the pending queue.
        this.queueLock.lock();
        try {
            for (Map.Entry<ServerLevel, List<PendingOperation>> entry : this.newPendingOperations.entrySet()) {
                List<PendingOperation> currentOperations = this.pendingOperations.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                currentOperations.addAll(entry.getValue());
            }
            this.newPendingOperations.clear();
        } finally {
            this.queueLock.unlock();
        }

        var worldIterator = this.pendingOperations.entrySet().iterator();
        while (worldIterator.hasNext()) {
            Map.Entry<ServerLevel, List<PendingOperation>> perWorldOperations = worldIterator.next();
            ServerLevel level = perWorldOperations.getKey();
            List<PendingOperation> operations = perWorldOperations.getValue();

            // Drop failed and finished operations.
            operations.removeIf(operation -> operation.isFinished() || this.failedOperations.remove(operation));
            if (operations.isEmpty()) {
                worldIterator.remove();
                continue;
            }

            PendingOperation operation = operations.get(0);
            ServerPlayer executor = operation.executor();
            if (executor.hasDisconnected()) {
                operations.remove(operation);
                continue;
            }

            // Schedule at most one region task per world per tick so operations never
            // run concurrently for the same world.
            if (this.scheduledThisTick.add(level)) {
                var world = level.getWorld();
                int chunkX = executor.getBlockX() >> 4;
                int chunkZ = executor.getBlockZ() >> 4;
                Environment.runOnRegion(plugin, world, chunkX, chunkZ, () -> this.tickOperation(level, operation));
            }
        }
    }

    private void tickOperation(ServerLevel level, PendingOperation operation) {
        // Runs on the region that owns the executing player.
        try {
            operation.tick(level);
        } catch (Throwable t) {
            ServerPlayer executor = operation.executor();
            executor.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing operation: " + t.getMessage()));
            this.failedOperations.add(operation);
        }
    }

    public void add(ServerLevel level, PendingOperation operation) {
        this.queueLock.lock();
        try {
            List<PendingOperation> operations = this.newPendingOperations.computeIfAbsent(level, k -> new ArrayList<>());
            operations.add(operation);
        } finally {
            this.queueLock.unlock();
        }
    }

}
