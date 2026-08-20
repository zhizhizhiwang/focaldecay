package com.zhizhiwang.focal_decay.network;

import com.zhizhiwang.focal_decay.FocalDecay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S→C：王座仪式状态同步（设计大纲 §8.1）。
 * state：0 开始 / 1 进度 / 2 波次 / 3 暂停 / 4 完成 / 5 失败。
 */
public record ThroneRitualPacket(int state, int remainingTicks, int totalTicks, int wave)
        implements CustomPacketPayload {
    public static final int STATE_STARTED = 0;
    public static final int STATE_PROGRESS = 1;
    public static final int STATE_WAVE = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_COMPLETE = 4;
    public static final int STATE_FAILED = 5;

    public static final Type<ThroneRitualPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "throne_ritual"));

    public static final StreamCodec<ByteBuf, ThroneRitualPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ThroneRitualPacket::state,
            ByteBufCodecs.VAR_INT, ThroneRitualPacket::remainingTicks,
            ByteBufCodecs.VAR_INT, ThroneRitualPacket::totalTicks,
            ByteBufCodecs.VAR_INT, ThroneRitualPacket::wave,
            ThroneRitualPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player == null) {
                return;
            }
            Component message = switch (state) {
                case STATE_STARTED -> Component.translatable("message.focal_decay.ritual_started");
                case STATE_PROGRESS -> Component.translatable(
                        "message.focal_decay.ritual_progress", remainingTicks / 20);
                case STATE_WAVE -> Component.translatable("message.focal_decay.ritual_wave", wave);
                case STATE_PAUSED -> Component.translatable("message.focal_decay.ritual_paused");
                case STATE_COMPLETE -> Component.translatable("message.focal_decay.ritual_complete");
                case STATE_FAILED -> Component.translatable("message.focal_decay.ritual_failed");
                default -> Component.empty();
            };
            Minecraft.getInstance().player.displayClientMessage(message, true);
        });
    }
}
