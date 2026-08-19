package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Set;
import java.util.Map;

/**
 * 网络通道（设计大纲 §8）：NeoForge 21.1 使用 Payload API（SimpleChannel 已移除）。
 */
public final class ModNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(SyncRegionDataPacket.TYPE, SyncRegionDataPacket.STREAM_CODEC, SyncRegionDataPacket::handle);
        registrar.playToClient(SyncWorldDataPacket.TYPE, SyncWorldDataPacket.STREAM_CODEC, SyncWorldDataPacket::handle);
    }

    /** 向单个玩家发送其当前维度的锚保护数据。 */
    public static void sendRegionData(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, regionDataPacket(player.serverLevel()));
    }

    /** 广播给某个维度内的所有玩家。 */
    public static void sendRegionDataToDimension(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, regionDataPacket(level));
    }

    /** 向单个玩家发送当前全局末日天数。 */
    public static void sendWorldData(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SyncWorldDataPacket(FocalDecayWorldData.get(player.server).getDays()));
    }

    /** 广播全局末日天数给所有玩家（天数变化时调用）。 */
    public static void sendWorldDataToAll(long days) {
        PacketDistributor.sendToAllPlayers(new SyncWorldDataPacket(days));
    }

    private static SyncRegionDataPacket regionDataPacket(ServerLevel level) {
        Set<BlockPos> anchors = MutationPoolManager.get(level).getAnchorPositions();
        long[] anchorLongs = new long[anchors.size()];
        int i = 0;
        for (BlockPos anchor : anchors) {
            anchorLongs[i++] = anchor.asLong();
        }

        Map<BlockPos, Long> births = MutationPoolManager.get(level).getBlockBirthPeriods();
        long[] birthPositions = new long[births.size()];
        long[] birthPeriods = new long[births.size()];
        int j = 0;
        for (Map.Entry<BlockPos, Long> entry : births.entrySet()) {
            birthPositions[j] = entry.getKey().asLong();
            birthPeriods[j] = entry.getValue();
            j++;
        }

        return new SyncRegionDataPacket(level.dimension(), FocalDecayConfig.ANCHOR_RADIUS.get(),
                anchorLongs, birthPositions, birthPeriods);
    }
}
