package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：单条催化域的登记与清除（2026-09-29，{@code DESIGN.md} §13.8）。
 * <p>
 * <b>为什么这条通道不能省</b>：催化改的是"<b>是否</b>发生失焦"，而不是"变成什么"。
 * 客户端不知道催化域时会继续按自然概率画幽灵，而服务端已经把整片区域点亮——
 * 玩家看到的是一片安静的世界，挖下去却每挖一块都不一样。这比"看不到"坏得多。
 * <p>
 * <b>为什么复用增量风格而不是塞进 {@code SyncRegionDataPacket}</b>：催化域是<b>短命</b>的
 * （几十个周期），而整表只在登录/换维度时发一次；把它挂在整表上等于要求"点火时重发整表"，
 * 那正是 BACKLOG `P1-6` 第 10 条修掉的毛病（盖一栋房子 = 上千个整表包）。
 * <p>
 * {@code until < 0} 表示清除；{@code until} 是<b>显示刻</b>，两端各用自己的时钟比较，
 * 所以"域什么时候结束"这件事本身不需要再同步一次——它由同一个纯函数判定
 * （{@code Catalysis.Field#isActive}）。
 */
public record SyncCatalystFieldPacket(ResourceKey<Level> dimension, long pos, int radius, int ringWidth,
                                      long until, double spill) implements CustomPacketPayload {

    public static final Type<SyncCatalystFieldPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_catalyst_field"));

    // 6 个分量，正好在 composite 的上限内
    public static final StreamCodec<FriendlyByteBuf, SyncCatalystFieldPacket> STREAM_CODEC =
            StreamCodec.composite(
                    StreamCodec.of((buf, key) -> buf.writeResourceKey(key),
                            buf -> buf.readResourceKey(Registries.DIMENSION)),
                    SyncCatalystFieldPacket::dimension,
                    StreamCodec.of(FriendlyByteBuf::writeLong, FriendlyByteBuf::readLong),
                    SyncCatalystFieldPacket::pos,
                    ByteBufCodecs.VAR_INT,
                    SyncCatalystFieldPacket::radius,
                    ByteBufCodecs.VAR_INT,
                    SyncCatalystFieldPacket::ringWidth,
                    StreamCodec.of(FriendlyByteBuf::writeLong, FriendlyByteBuf::readLong),
                    SyncCatalystFieldPacket::until,
                    ByteBufCodecs.DOUBLE,
                    SyncCatalystFieldPacket::spill,
                    SyncCatalystFieldPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.applyCatalystField(
                dimension, pos, radius, ringWidth, until, spill));
    }
}
