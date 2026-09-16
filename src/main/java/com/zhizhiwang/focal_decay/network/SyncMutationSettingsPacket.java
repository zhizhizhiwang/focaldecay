package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.client.ClientRenderCache;
import com.zhizhiwang.focal_decay.mutation.MutationSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：把服务端的{@link MutationSettings 失焦解析输入快照}整份交给客户端（2026-09-17）。
 * <p>
 * <b>为什么必须有这个包</b>：失焦预览是客户端自己算的（服务端不逐方块发包），而它要用的输入里有两样
 * 客户端<b>天然拿不到</b>：
 * <ul>
 *   <li><b>世界种子</b>——1.21 的登录包里只有给生物群系缩放用的<b>哈希</b>种子，
 *       真实种子只发给管理员（{@code /seed} 要权限）。以前客户端在多人模式下退回 {@code 0}，
 *       于是同一个方块，房主（集成服务器，拿得到真种子）和别人（拿 {@code 0}）看到的是两个东西，
 *       挖下去自然是"看到的和掉出来的对不上"。</li>
 *   <li><b>SERVER 配置</b>——{@code wild_chance}、每阶段概率、{@code base_interval}、阶段天数、
 *       语义锁定强度……客户端读的是<b>它自己那份</b> toml。专用服务器上两份文件毫无关系，
 *       局域网里两台客户端也可能各改各的。</li>
 * </ul>
 * 以前靠"这些配置会同步到客户端"这句注释撑着，但并没有任何代码在做这件事。
 * <p>
 * 快照是静态的（只在配置变更时变），所以只在登录/换维度时发一次；连续变化的量
 * （天数、观测者、调试时钟）仍在 {@link SyncWorldDataPacket} 里。客户端在收到本包之前
 * <b>不渲染任何幽灵</b>——显示不出来是可见的、可诊断的，"显示错了"才是真 bug。
 */
public record SyncMutationSettingsPacket(MutationSettings settings) implements CustomPacketPayload {

    public static final Type<SyncMutationSettingsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "sync_mutation_settings"));

    // 13 个分量超过 StreamCodec.composite 的重载上限，手写编解码（与 SyncRegionDataPacket 同一做法）
    public static final StreamCodec<FriendlyByteBuf, SyncMutationSettingsPacket> STREAM_CODEC = StreamCodec.of(
            SyncMutationSettingsPacket::encode, SyncMutationSettingsPacket::new);

    public SyncMutationSettingsPacket(FriendlyByteBuf buf) {
        this(new MutationSettings(
                buf.readLong(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt()));
    }

    private static void encode(FriendlyByteBuf buf, SyncMutationSettingsPacket packet) {
        MutationSettings s = packet.settings();
        buf.writeLong(s.worldSeed());
        buf.writeVarInt(s.baseInterval());
        buf.writeBoolean(s.stageSystem());
        buf.writeVarInt(s.stage2Day());
        buf.writeVarInt(s.stage3Day());
        buf.writeDouble(s.chanceStage1());
        buf.writeDouble(s.chanceStage2());
        buf.writeDouble(s.chanceStage3());
        buf.writeDouble(s.wildChance());
        buf.writeDouble(s.semanticLockStage3());
        buf.writeBoolean(s.guidedStage3Halve());
        buf.writeVarInt(s.candidatePoints());
        buf.writeVarInt(s.copyTrainPenalty());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientRenderCache.INSTANCE.setMutationSettings(settings));
    }
}
