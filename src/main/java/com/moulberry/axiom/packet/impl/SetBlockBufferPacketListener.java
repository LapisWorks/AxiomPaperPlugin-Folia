package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.Environment;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.operations.SetBlockBufferOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class SetBlockBufferPacketListener implements PacketHandler {

    private final AxiomPaper plugin;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    public void onReceive(Player player, FriendlyByteBuf friendlyByteBuf) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();

        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        friendlyByteBuf.readUUID(); // Discard, we don't need to associate buffers

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            BlockBuffer buffer = BlockBuffer.load(friendlyByteBuf, this.plugin.getBlockRegistry(serverPlayer.getUUID()), serverPlayer.getBukkitEntity());
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBlockBuffer(serverPlayer, buffer, worldKey, clientAvailableDispatchSends);
        } else if (type == 1) {
            BiomeBuffer buffer = BiomeBuffer.load(friendlyByteBuf);
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBiomeBuffer(serverPlayer, buffer, worldKey, clientAvailableDispatchSends);
        } else {
            throw new RuntimeException("Unknown buffer type: " + type);
        }
    }

    private record BiomeCell(int x, int y, int z, ResourceKey<Biome> biome) {
    }

    private void applyBlockBuffer(ServerPlayer player, BlockBuffer buffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        // Block buffers are applied through the operation queue, which ticks them on the
        // region thread of the executing player. Schedule the enqueue on that region so
        // player state can be touched safely on both Paper and Folia.
        var playerWorld = player.level().getWorld();
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        Environment.runOnRegion(this.plugin, playerWorld, chunkX, chunkZ, () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getSectionCount() + " chunk sections (blocks)");
                    if (buffer.getTotalBlockEntities() > 0) {
                        this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getTotalBlockEntities() + " block entities, compressed bytes = " +
                            buffer.getTotalBlockEntityBytes());
                    }
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), buffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                boolean allowNbt = this.plugin.hasPermission(player.getBukkitEntity(), AxiomPermission.BUILD_NBT);
                this.plugin.addPendingOperation(world, new SetBlockBufferOperation(player, buffer, allowNbt));
            } catch (Throwable t) {
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occured while processing block change: " + t.getMessage()));
            }
        });
    }

    private void applyBiomeBuffer(ServerPlayer player, BiomeBuffer biomeBuffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        // Biome changes touch chunks that may not all be in the player's region, so the
        // actual block writes are scheduled per-chunk-region below.
        var playerWorld = player.level().getWorld();
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        Environment.runOnRegion(this.plugin, playerWorld, chunkX, chunkZ, () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + biomeBuffer.getSectionCount() + " chunk sections (biomes)");
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), biomeBuffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                int minSection = world.getMinSectionY();
                int maxSection = world.getMaxSectionY();

                Optional<Registry<Biome>> registryOptional = world.registryAccess().lookup(Registries.BIOME);
                if (registryOptional.isEmpty()) return;

                Registry<Biome> registry = registryOptional.get();

                // Bucket biome entries by the chunk they belong to.
                Map<Long, List<BiomeCell>> chunks = new HashMap<>();
                biomeBuffer.forEachEntry((x, y, z, biome) -> {
                    int cy = y >> 2;
                    if (cy < minSection || cy > maxSection) {
                        return;
                    }

                    long chunkPos = ChunkPos.pack(x >> 2, z >> 2);
                    chunks.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(new BiomeCell(x, y, z, biome));
                });

                for (Map.Entry<Long, List<BiomeCell>> entry : chunks.entrySet()) {
                    long chunkPos = entry.getKey();
                    int cx = ChunkPos.getX(chunkPos);
                    int cz = ChunkPos.getZ(chunkPos);
                    List<BiomeCell> entries = entry.getValue();
                    Environment.runOnRegion(this.plugin, world.getWorld(), cx, cz, () -> applyBiomesToChunk(world, cx, cz, entries, registry, player));
                }
            } catch (Throwable t) {
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occured while processing biome change: " + t.getMessage()));
            }
        });
    }

    private void applyBiomesToChunk(ServerLevel world, int cx, int cz, List<BiomeCell> cells, Registry<Biome> registry, ServerPlayer player) {
        // Runs on the region that owns the chunk.
        try {
            LevelChunk chunk = (LevelChunk) world.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) return;

            int minSection = world.getMinSectionY();
            Set<LevelChunk> changedChunks = new HashSet<>();

            for (BiomeCell cell : cells) {
                var holder = registry.get(cell.biome());
                if (holder.isPresent()) {
                    var section = chunk.getSection((cell.y() >> 2) - minSection);
                    PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();

                    if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                        new Location(player.getBukkitEntity().getWorld(), (cell.x()<<2)+1, (cell.y()<<2)+1, (cell.z()<<2)+1))) return;

                    container.set(cell.x() & 3, cell.y() & 3, cell.z() & 3, holder.get());
                    changedChunks.add(chunk);
                }
            }

            if (changedChunks.isEmpty()) {
                return;
            }

            chunk.markUnsaved();
            ChunkPos chunkPos = chunk.getPos();

            var chunkMap = world.getChunkSource().chunkMap;
            HashMap<ServerPlayer, List<LevelChunk>> map = new HashMap<>();
            for (ServerPlayer serverPlayer2 : chunkMap.getPlayers(chunkPos, false)) {
                map.computeIfAbsent(serverPlayer2, serverPlayer -> new ArrayList<>()).add(chunk);
            }
            map.forEach((serverPlayer, list) -> serverPlayer.connection.send(ClientboundChunksBiomesPacket.forChunks(list)));
        } catch (Throwable t) {
            player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occured while processing biome change: " + t.getMessage()));
        }
    }

}
