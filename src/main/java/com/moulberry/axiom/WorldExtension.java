package com.moulberry.axiom;

import com.moulberry.axiom.annotations.ServerAnnotations;
import com.moulberry.axiom.marker.MarkerData;
import com.moulberry.axiom.paperapi.entity.ImplAxiomHiddenEntities;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorldExtension {

    private static final Map<ResourceKey<Level>, WorldExtension> extensions = new ConcurrentHashMap<>();

    public static WorldExtension get(ServerLevel serverLevel) {
        WorldExtension extension = extensions.computeIfAbsent(serverLevel.dimension(), k -> new WorldExtension());
        extension.level = serverLevel;
        return extension;
    }

    public static void onPlayerJoin(World world, Player player) {
        ServerLevel level = ((CraftWorld)world).getHandle();
        get(level).onPlayerJoin(player);

        if (AxiomPaper.PLUGIN.canUseAxiom(player)) {
            ServerAnnotations.sendAll(world, ((CraftPlayer)player).getHandle());
        }
    }

    /**
     * Called on the global region thread (Paper main thread / Folia global thread).
     * Scheduling is split per-region so world attempts only ever touch ownership
     * of their own chunks.
     */
    public static void tickAll(AxiomPaper plugin, boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        MinecraftServer server = MinecraftServer.getServer();
        Set<ResourceKey<Level>> levelKeys = server.levelKeys();

        var iterator = extensions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!levelKeys.contains(entry.getKey())) {
                iterator.remove();
                continue;
            }
            entry.getValue().tick(plugin, sendMarkers, maxChunkRelightsPerTick, maxChunkSendsPerTick);
        }
    }

    private void tick(AxiomPaper plugin, boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        if (sendMarkers) {
            this.scheduleMarkerTick(plugin);
            this.processMarkerResults();
        }
        this.scheduleChunkTick(plugin, maxChunkRelightsPerTick, maxChunkSendsPerTick);
    }

    private ServerLevel level;

    private final Set<Long> pendingChunksToSend = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingChunksToLight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MarkerData> previousMarkerData = new ConcurrentHashMap<>();
    private final Map<UUID, MarkerData> computedMarkerData = new ConcurrentHashMap<>();

    public void sendChunk(int cx, int cz) {
        this.pendingChunksToSend.add(ChunkPos.pack(cx, cz));
    }

    public void lightChunk(int cx, int cz) {
        this.pendingChunksToLight.add(ChunkPos.pack(cx, cz));
    }

    public void onPlayerJoin(Player player) {
        if (!this.previousMarkerData.isEmpty()) {
            List<MarkerData> markerData = new ArrayList<>(this.previousMarkerData.values());

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeCollection(markerData, MarkerData::write);
            buf.writeCollection(Set.<UUID>of(), (buffer, uuid) -> buffer.writeUUID(uuid));

            byte[] bytes = ByteBufUtil.getBytes(buf);
            VersionHelper.sendCustomPayload(player, "axiom:marker_data", bytes);
        }

        try {
            ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
            if (this.level.chunkPacketBlockController.shouldModify(serverPlayer, this.level.getChunkIfLoaded(serverPlayer.blockPosition()))) {
                Component text = Component.text("Axiom: Warning, anti-xray is enabled. This will cause issues when copying blocks. Please turn anti-xray off");
                player.sendMessage(text.color(NamedTextColor.RED));
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleChunkTick(AxiomPaper plugin, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        if (this.pendingChunksToSend.isEmpty() && this.pendingChunksToLight.isEmpty()) {
            return;
        }

        World world = this.level.getWorld();

        // Group chunks to send by the region that owns them, then run one task per region.
        if (!this.pendingChunksToSend.isEmpty()) {
            Map<Long, LongArrayList> byRegion = new HashMap<>();
            for (long packed : this.pendingChunksToSend) {
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                byRegion.computeIfAbsent(packRegion(cx, cz), k -> new LongArrayList()).add(packed);
            }

            for (Map.Entry<Long, LongArrayList> entry : byRegion.entrySet()) {
                long region = entry.getKey();
                int anchorChunkX = getRegionX(region) << 5;
                int anchorChunkZ = getRegionZ(region) << 5;
                LongArrayList chunks = entry.getValue();
                Environment.runOnRegion(plugin, world, anchorChunkX, anchorChunkZ, () -> sendChunks(chunks, maxChunkSendsPerTick));
            }
        }

        // Group chunks to relight by the region that owns them.
        if (!this.pendingChunksToLight.isEmpty()) {
            Map<Long, Set<ChunkPos>> byRegion = new HashMap<>();
            for (long packed : this.pendingChunksToLight) {
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                byRegion.computeIfAbsent(packRegion(cx, cz), k -> new HashSet<>()).add(new ChunkPos(cx, cz));
            }

            for (Map.Entry<Long, Set<ChunkPos>> entry : byRegion.entrySet()) {
                long region = entry.getKey();
                int anchorChunkX = getRegionX(region) << 5;
                int anchorChunkZ = getRegionZ(region) << 5;
                Set<ChunkPos> chunks = entry.getValue();
                Environment.runOnRegion(plugin, world, anchorChunkX, anchorChunkZ, () -> relightChunks(chunks, maxChunkRelightsPerTick));
            }
        }
    }

    private void sendChunks(LongArrayList packedChunks, int maxChunkSendsPerTick) {
        // Runs on the region that owns these chunks.
        ChunkMap chunkMap = this.level.getChunkSource().chunkMap;

        boolean sendAll = maxChunkSendsPerTick <= 0;
        LongSet sent = new LongOpenHashSet();

        LongIterator longIterator = packedChunks.longIterator();
        while (longIterator.hasNext()) {
            long packed = longIterator.nextLong();
            ChunkPos chunkPos = ChunkPos.unpack(packed);

            LevelChunk chunk = this.level.getChunkIfLoaded(chunkPos.x(), chunkPos.z());
            if (chunk == null) {
                continue;
            }

            List<ServerPlayer> players = chunkMap.getPlayers(chunkPos, false);
            if (players.isEmpty()) {
                continue;
            }

            var packet = new ClientboundLevelChunkWithLightPacket(chunk, this.level.getLightEngine(), null, null, false);
            for (ServerPlayer player : players) {
                player.connection.send(packet);
            }

            sent.add(packed);

            if (!sendAll) {
                maxChunkSendsPerTick -= 1;
                if (maxChunkSendsPerTick <= 0) {
                    break;
                }
            }
        }

        if (sendAll) {
            this.pendingChunksToSend.removeAll(packedChunks);
        } else if (!sent.isEmpty()) {
            this.pendingChunksToSend.removeAll(sent);
        }
    }

    private void relightChunks(Set<ChunkPos> chunks, int maxChunkRelightsPerTick) {
        // Runs on the region that owns these chunks.
        Set<ChunkPos> chunkSet = new HashSet<>();
        if (maxChunkRelightsPerTick <= 0) {
            chunkSet = chunks;
        } else {
            var iterator = chunks.iterator();
            while (iterator.hasNext() && maxChunkRelightsPerTick > 0) {
                chunkSet.add(iterator.next());
                maxChunkRelightsPerTick -= 1;
            }
        }

        if (chunkSet.isEmpty()) {
            return;
        }

        for (ChunkPos chunkPos : chunkSet) {
            this.pendingChunksToLight.remove(ChunkPos.pack(chunkPos.x(), chunkPos.z()));
        }

        this.level.getChunkSource().getLightEngine().starlight$serverRelightChunks(chunkSet, pos -> {}, count -> {});
    }

    private void scheduleMarkerTick(AxiomPaper plugin) {
        // The entity list itself is safe to iterate; marker data must be read on the
        // region thread that owns each marker on Folia, so we hop per-entity.
        try {
            for (Entity entity : this.level.getEntities().getAll()) {
                if (entity instanceof Marker marker) {
                    if (ImplAxiomHiddenEntities.isMarkerHidden(marker.getUUID())) {
                        continue;
                    }
                    Environment.runOnEntityRegion(plugin, marker, () -> {
                        if (marker.isRemoved()) {
                            return;
                        }
                        if (ImplAxiomHiddenEntities.isMarkerHidden(marker.getUUID())) {
                            return;
                        }
                        this.computedMarkerData.put(marker.getUUID(), MarkerData.createFrom(marker));
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void processMarkerResults() {
        if (this.computedMarkerData.isEmpty()) {
            return;
        }

        Map<UUID, MarkerData> computed = new HashMap<>(this.computedMarkerData);
        this.computedMarkerData.clear();

        List<MarkerData> changedData = new ArrayList<>();

        for (Map.Entry<UUID, MarkerData> entry : computed.entrySet()) {
            UUID uuid = entry.getKey();
            MarkerData currentData = entry.getValue();

            MarkerData previousData = this.previousMarkerData.get(uuid);
            if (!Objects.equals(currentData, previousData)) {
                this.previousMarkerData.put(uuid, currentData);
                changedData.add(currentData);
            }
        }

        Set<UUID> missingUuids = new HashSet<>(this.previousMarkerData.keySet());
        missingUuids.removeAll(computed.keySet());
        this.previousMarkerData.keySet().removeAll(missingUuids);

        if (changedData.isEmpty() && missingUuids.isEmpty()) {
            return;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeCollection(changedData, MarkerData::write);
        buf.writeCollection(missingUuids, (buffer, uuid) -> buffer.writeUUID(uuid));
        byte[] bytes = ByteBufUtil.getBytes(buf);

        List<ServerPlayer> players = new ArrayList<>();

        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (player.level() == this.level && AxiomPaper.PLUGIN.canUseAxiom(player.getUUID())) {
                players.add(player);
            }
        }

        VersionHelper.sendCustomPayloadToAll(players, "axiom:marker_data", bytes);
    }

    private static long packRegion(int chunkX, int chunkZ) {
        return ((long) (chunkX >> 5) << 32) | (chunkZ >> 5 & 0xFFFFFFFFL);
    }

    private static int getRegionX(long region) {
        return (int) (region >> 32);
    }

    private static int getRegionZ(long region) {
        return (int) region;
    }

}