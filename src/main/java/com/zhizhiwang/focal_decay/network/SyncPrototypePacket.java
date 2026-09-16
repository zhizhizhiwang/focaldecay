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
 * S→C：<b>单条</b>原型机效果的增删改（2026-09-17）。
 * <p>
 * 与 {@link SyncBirthPeriodPacket} 同一个理由，只是对象换成了原型机：
 * {@link SyncRegionDataPacket} 是"整表快照"，只适合登录/换维度这种一次性场合。
 * <p>
 * <b>为什么单条增量是必需的（这不只是省带宽）</b>：{@code MutationPoolManager} 里的有效原型机列表
 * <b>不落盘</b>，它由方块实体的 {@code onLoad} 重建，而方块实体只在区块加载时存在。于是：
 * <ol>
 *   <li>玩家登录时，只有他附近已加载区块里的原型机在列表里 —— 全量包发出去的是一份<b>不完整</b>的名单；</li>
 *   <li>他走到远处某个原型机旁边时，那个区块加载、效果被登记，服务端开始保护那片区域，
 *       但客户端永远收不到通知。</li>
 * </ol>
 * 结果就是客户端在那片保护范围里继续画幽灵（它以为没人保护），玩家挖下去，服务端按硬保护返回原方块，
 * 掉落自然对不上。修法不是"每次区块加载都重发整表"（那会把随建造无上限增长的诞生周期表也带上），
 * 而是让效果的每次变化都走一条<b>单条</b>通道。
 * <p>
 * 服务端只在"客户端能观察到的字段真的变了"时发包（见 {@code MutationPoolManager#updatePrototypeEffect}）：
 * 生物稳定模型的能量每刻都在变，但客户端只关心"能量是否大于 0"，所以不能按能量值发包。
 */
public record SyncPrototypePacket(ResourceKey<Level> dimension, long packedPos, boolean present,
                                  SyncRegionDataPacket.PrototypeData data) implements CustomPacketPayload {

    public static final Type<SyncPrototypePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_prototype"));

    public static final StreamCodec<FriendlyByteBuf, SyncPrototypePacket> STREAM_CODEC = StreamCodec.of(
            SyncPrototypePacket::encode, SyncPrototypePacket::new);

    /** 删除事件：位置上的原型机效果消失（方块被拆/模型被取出）。 */
    public static SyncPrototypePacket removed(ResourceKey<Level> dimension, long packedPos) {
        return new SyncPrototypePacket(dimension, packedPos, false, null);
    }

    public SyncPrototypePacket(FriendlyByteBuf buf) {
        this(buf.readResourceKey(Registries.DIMENSION), buf.readLong(), buf.readBoolean(),
                buf.readBoolean() ? SyncRegionDataPacket.PrototypeData.STREAM_CODEC.decode(buf) : null);
    }

    private static void encode(FriendlyByteBuf buf, SyncPrototypePacket packet) {
        buf.writeResourceKey(packet.dimension());
        buf.writeLong(packet.packedPos());
        buf.writeBoolean(packet.present());
        buf.writeBoolean(packet.data() != null);
        if (packet.data() != null) {
            SyncRegionDataPacket.PrototypeData.STREAM_CODEC.encode(buf, packet.data());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.applyPrototype(dimension, packedPos, present, data));
    }
}
