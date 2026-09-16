package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络通道（设计大纲 §8）：NeoForge 21.1 使用 Payload API（SimpleChannel 已移除）。
 */
public final class ModNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // C→S：交互时回报"客户端看到的显示刻"（见 SyncClientViewPacket 与 InteractionHandler）
        registrar.playToServer(SyncClientViewPacket.TYPE, SyncClientViewPacket.STREAM_CODEC,
                SyncClientViewPacket::handle);
        registrar.playToClient(SyncMutationSettingsPacket.TYPE, SyncMutationSettingsPacket.STREAM_CODEC,
                SyncMutationSettingsPacket::handle);
        registrar.playToClient(SyncRegionDataPacket.TYPE, SyncRegionDataPacket.STREAM_CODEC, SyncRegionDataPacket::handle);
        registrar.playToClient(SyncPrototypePacket.TYPE, SyncPrototypePacket.STREAM_CODEC, SyncPrototypePacket::handle);
        registrar.playToClient(SyncBirthPeriodPacket.TYPE, SyncBirthPeriodPacket.STREAM_CODEC, SyncBirthPeriodPacket::handle);
        registrar.playToClient(SyncWorldDataPacket.TYPE, SyncWorldDataPacket.STREAM_CODEC, SyncWorldDataPacket::handle);
        registrar.playToClient(ThroneRitualPacket.TYPE, ThroneRitualPacket.STREAM_CODEC, ThroneRitualPacket::handle);
        registrar.playToClient(ObserverCoreActivatePacket.TYPE, ObserverCoreActivatePacket.STREAM_CODEC,
                ObserverCoreActivatePacket::handle);
    }

    /**
     * 向单个玩家发送服务端的失焦解析输入快照（世界种子 + SERVER 配置）。
     * <p>
     * 登录与换维度都要发：客户端在收到它之前不渲染任何幽灵（宁可看不到，也不能看错）。
     */
    public static void sendMutationSettings(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SyncMutationSettingsPacket(
                MutationSettings.fromConfig(player.server.overworld().getSeed())));
    }

    // ---- 区域数据 ----

    /**
     * 向单个玩家发送其当前维度的锚保护数据（登录/换维度时的<b>整表快照</b>）。
     * <p>
     * 注意这份快照里的原型机名单只包含"此刻已加载区块"里的那些（有效原型机列表不落盘，
     * 由方块实体加载时重建），所以它<b>不完整</b>；后续变化由
     * {@link #sendPrototype} 的单条增量补齐。
     */
    public static void sendRegionData(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, regionDataPacket(player.serverLevel()));
    }

    /** 广播给某个维度内的所有玩家（原型机列表整体变化时使用）。 */
    public static void sendRegionDataToDimension(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, regionDataPacket(level));
    }

    /** 广播<b>单条</b>原型机效果的增删改。 */
    public static void sendPrototype(ServerLevel level, BlockPos pos, SyncRegionDataPacket.PrototypeData data) {
        PacketDistributor.sendToPlayersInDimension(level, data == null
                ? SyncPrototypePacket.removed(level.dimension(), pos.asLong())
                : new SyncPrototypePacket(level.dimension(), pos.asLong(), true, data));
    }

    /**
     * 原型机效果的线格式（增量包与整表快照共用同一份编码）。
     * <p>
     * 只带客户端<b>能观察到</b>的量：生物稳定发的是"是否生效"而不是能量数值，
     * 于是这个 record 的 {@code equals} 就等于"客户端看到的东西变了没有"，
     * {@link MutationPoolManager} 靠它决定要不要发增量包。
     */
    public static SyncRegionDataPacket.PrototypeData prototypeData(MutationPoolManager.PrototypeEffect effect) {
        return new SyncRegionDataPacket.PrototypeData(
                effect.center().asLong(), effect.radius(), effect.data().type(),
                effect.data().trainedTargets(), effect.data().trainedEntities(),
                effect.data().bioEnergy() > 0, effect.data().concept(), effect.data().progress(),
                effect.data().stabilityStrength(), effect.data().copies());
    }

    /** 向单个玩家发送当前全局末日天数与调试时钟。 */
    public static void sendWorldData(ServerPlayer player) {
        FocalDecayWorldData data = FocalDecayWorldData.get(player.server);
        PacketDistributor.sendToPlayer(player, new SyncWorldDataPacket(
                data.getDays(), data.isObserverOnline(), data.getClockSpeed(), data.getClockOffset()));
    }

    /** 广播全局末日天数、观测者状态与调试时钟给所有玩家（任一变化时调用）。 */
    public static void sendWorldDataToAll(long days, boolean observerOnline, double clockSpeed, long clockOffset) {
        PacketDistributor.sendToAllPlayers(
                new SyncWorldDataPacket(days, observerOnline, clockSpeed, clockOffset));
    }

    /** 向所有玩家广播（王座仪式状态 / 观测者核心激活动画）。 */
    public static void sendToAllPlayers(CustomPacketPayload packet) {
        PacketDistributor.sendToAllPlayers(packet);
    }

    /**
     * 广播<b>单条</b>方块诞生周期变化（增量）。{@code period < 0} 表示删除。
     * <p>
     * 替代原来"每次放置/破坏都重发整张诞生周期表"的做法——那张表随建造无上限增长，
     * 整表广播既是带宽浪费，也是每次放置方块时的一次主线程序列化开销。
     */
    public static void sendBirthPeriod(ServerLevel level, BlockPos pos, long period) {
        PacketDistributor.sendToPlayersInDimension(level,
                new SyncBirthPeriodPacket(level.dimension(), pos.asLong(), period));
    }

    private static SyncRegionDataPacket regionDataPacket(ServerLevel level) {
        List<SyncRegionDataPacket.PrototypeData> prototypes = new ArrayList<>();
        for (MutationPoolManager.PrototypeEffect effect : MutationPoolManager.get(level).getPrototypeEffects()) {
            prototypes.add(prototypeData(effect));
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

        return new SyncRegionDataPacket(level.dimension(), prototypes, birthPositions, birthPeriods);
    }
}
