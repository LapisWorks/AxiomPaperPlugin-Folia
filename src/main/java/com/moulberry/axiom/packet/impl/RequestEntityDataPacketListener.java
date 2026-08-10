package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.Environment;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.*;

public class RequestEntityDataPacketListener implements PacketHandler {

    public static final Identifier RESPONSE_ID = VersionHelper.createIdentifier("axiom:response_entity_data");

    private final AxiomPaper plugin;
    public RequestEntityDataPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(org.bukkit.entity.Player bukkitPlayer, FriendlyByteBuf friendlyByteBuf) {
        ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();
        long id = friendlyByteBuf.readLong();

        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.ENTITY_REQUESTDATA) || this.plugin.isMismatchedDataVersion(bukkitPlayer.getUniqueId())) {
            // We always send an 'empty' response in order to make the client happy
            sendResponse(player, id, true, Map.of());
            return;
        }

        if (!this.plugin.canModifyWorld(bukkitPlayer, bukkitPlayer.getWorld())) {
            sendResponse(player, id, true, Map.of());
            return;
        }

        List<UUID> request = friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new), buf -> buf.readUUID());
        ServerLevel serverLevel = player.level();

        Set<UUID> visitedEntities = new HashSet<>();

        for (UUID uuid : request) {
            if (!visitedEntities.add(uuid)) {
                continue;
            }

            Entity entity = serverLevel.getEntity(uuid);
            if (entity == null || entity instanceof Player) {
                continue;
            }

            if (!this.plugin.canEntityBeManipulated(entity.getType())) {
                continue;
            }

            // Entity data must be read on the region that owns the entity (Folia).
            Environment.runOnEntityRegion(this.plugin, entity, () -> {
                if (entity.isRemoved()) {
                    return;
                }

                if (!Integration.canPlaceBlock(bukkitPlayer, new Location(bukkitPlayer.getWorld(),
                        entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))) {
                    return;
                }

                var valueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
                var entityTag = entity.save(valueOutput) ? valueOutput.buildResult() : null;
                if (entityTag != null) {
                    sendResponse(player, id, false, Map.of(uuid, entityTag));
                }
            });
        }

        sendResponse(player, id, true, Map.of());
    }

    private static void sendResponse(ServerPlayer player, long id, boolean finished, Map<UUID, CompoundTag> map) {
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        friendlyByteBuf.writeLong(id);
        friendlyByteBuf.writeBoolean(finished);
        friendlyByteBuf.writeMap(map, (buf, uuid) -> buf.writeUUID(uuid), (buf, nbt) -> buf.writeNbt(nbt));

        byte[] bytes = ByteBufUtil.getBytes(friendlyByteBuf);
        VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);
    }

}
