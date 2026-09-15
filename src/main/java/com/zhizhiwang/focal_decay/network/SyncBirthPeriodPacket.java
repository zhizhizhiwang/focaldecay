package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：单条方块诞生周期的增量同步（2026-09-15）。
 * <p>
 * 旧实现每次放置/破坏方块都广播一整份 {@link SyncRegionDataPacket}，而其中的诞生周期表
 * 是随建造无上限增长的——盖一栋房子等于上千个"整表"包，这既是带宽问题也是主线程卡顿来源。
 * 现在单点变化只发这一条；整表仍然只在登录/切维度/原型机变化时发一次。
 * <p>
 * 失效与新增合成同一条消息：{@code period < 0} 表示删除该位置的记录（方块被破坏）。
 */
public record SyncBirthPeriodPacket(ResourceKey<Level> dimension, long pos, long period)
        implements CustomPacketPayload {

    public static final Type<SyncBirthPeriodPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_birth_period"));

    public static final StreamCodec<FriendlyByteBuf, SyncBirthPeriodPacket> STREAM_CODEC = StreamCodec.composite(
            StreamCodec.of((buf, key) -> buf.writeResourceKey(key), buf -> buf.readResourceKey(Registries.DIMENSION)),
            SyncBirthPeriodPacket::dimension,
            StreamCodec.of(FriendlyByteBuf::writeLong, FriendlyByteBuf::readLong),
            SyncBirthPeriodPacket::pos,
            StreamCodec.of(FriendlyByteBuf::writeLong, FriendlyByteBuf::readLong),
            SyncBirthPeriodPacket::period,
            SyncBirthPeriodPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.applyBirthPeriod(dimension, pos, period));
    }
}
