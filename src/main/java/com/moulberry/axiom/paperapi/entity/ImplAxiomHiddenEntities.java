package com.moulberry.axiom.paperapi.entity;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Display;
import org.bukkit.entity.Marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ImplAxiomHiddenEntities {

    private static final Set<Marker> hiddenMarkers = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<UUID> hiddenMarkerUuids = ConcurrentHashMap.newKeySet();
    private static final Map<Object, UUID> hiddenDisplays = new WeakHashMap<>();
    private static final Set<UUID> lastSentHiddenDisplays = new HashSet<>();

    private static boolean resendIgnoredDisplays = false;
    private static boolean hasSentIgnoredDisplaysToAPlayer = false;

    public static boolean isMarkerHidden(Marker marker) {
        return hiddenMarkers.contains(marker);
    }

    public static boolean isMarkerHidden(UUID uuid) {
        return hiddenMarkerUuids.contains(uuid);
    }

    public static void hideMarkerGizmo(Marker marker) {
        hiddenMarkers.add(marker);
        hiddenMarkerUuids.add(marker.getUniqueId());
    }

    public static void hideDisplayGizmo(Display display) {
        hiddenDisplays.put(display, display.getUniqueId());
        if (hasSentIgnoredDisplaysToAPlayer && !lastSentHiddenDisplays.contains(display.getUniqueId())) {
            resendIgnoredDisplays = true;
        }
    }

    public static void hideCustomDisplayGizmo(Object object, UUID uuid) {
        hiddenDisplays.put(object, uuid);
        if (hasSentIgnoredDisplaysToAPlayer && !lastSentHiddenDisplays.contains(uuid)) {
            resendIgnoredDisplays = true;
        }
    }

    public static void tick() {
        if (resendIgnoredDisplays) {
            resendIgnoredDisplays = false;

            lastSentHiddenDisplays.clear();
            lastSentHiddenDisplays.addAll(hiddenDisplays.values());

            List<ServerPlayer> players = new ArrayList<>();

            for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
                if (AxiomPaper.PLUGIN.canUseAxiom(player.getUUID())) {
                    players.add(player);
                }
            }

            if (players.isEmpty()) {
                hasSentIgnoredDisplaysToAPlayer = false;
            } else {
                sendAll(players);
            }

        }
    }

    public static void sendAll(List<ServerPlayer> players) {
        if (players.isEmpty()) {
            return;
        }

        hasSentIgnoredDisplaysToAPlayer = true;

        if (!hiddenDisplays.isEmpty()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeCollection(hiddenDisplays.values(), (buffer, uuid) -> buffer.writeUUID(uuid));
            VersionHelper.sendCustomPayloadToAll(players, "axiom:ignore_display_entities", ByteBufUtil.getBytes(buf));
        }
    }

}
