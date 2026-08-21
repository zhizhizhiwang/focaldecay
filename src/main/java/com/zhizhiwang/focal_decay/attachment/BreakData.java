package com.zhizhiwang.focal_decay.attachment;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 玩家挖掘锁定数据（设计大纲 §5.2）。
 * 挖掘开始时记录目标方块状态与周期索引；方块破坏时据此执行真实转换。
 * 以 Player attachment 形式挂载（NeoForge 21.1 的 attachment 系统替代旧 Capability）。
 */
public class BreakData {
    private BlockState targetState;
    private long periodIndex;
    /** 锁定目标所在位置：破坏时校验，防止陈旧的锁定泄漏到其他方块。 */
    private BlockPos pos;
    private boolean active;

    public BreakData() {
        this.active = false;
    }

    public void start(BlockState targetState, long periodIndex, BlockPos pos) {
        this.targetState = targetState;
        this.periodIndex = periodIndex;
        this.pos = pos.immutable();
        this.active = true;
    }

    public void clear() {
        this.active = false;
        this.targetState = null;
        this.pos = null;
    }

    public boolean isActive() {
        return active;
    }

    public BlockState getTargetState() {
        return targetState;
    }

    public long getPeriodIndex() {
        return periodIndex;
    }

    public BlockPos getPos() {
        return pos;
    }

    // ---- NBT 序列化（attachment serializer） ----
    public void saveNBT(CompoundTag tag) {
        tag.putBoolean("Active", active);
        if (targetState != null) {
            tag.putString("Target", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(targetState.getBlock()).toString());
        }
        tag.putLong("PeriodIndex", periodIndex);
        if (pos != null) {
            tag.putLong("Pos", pos.asLong());
        }
    }

    public void loadNBT(CompoundTag tag) {
        this.active = tag.getBoolean("Active");
        String target = tag.getString("Target");
        if (!target.isEmpty()) {
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(target));
            if (block != net.minecraft.world.level.block.Blocks.AIR) {
                this.targetState = block.defaultBlockState();
            }
        }
        this.periodIndex = tag.getLong("PeriodIndex");
        this.pos = tag.contains("Pos") ? BlockPos.of(tag.getLong("Pos")) : null;
    }

    // ---- 网络序列化 ----
    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(targetState == null ? 0 : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(targetState.getBlock()));
        buf.writeVarLong(periodIndex);
        buf.writeLong(pos == null ? 0L : pos.asLong());
    }

    public static BreakData decode(RegistryFriendlyByteBuf buf) {
        BreakData data = new BreakData();
        data.active = buf.readBoolean();
        int id = buf.readVarInt();
        data.targetState = id == 0 ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
        data.periodIndex = buf.readVarLong();
        long p = buf.readLong();
        data.pos = p == 0 ? null : BlockPos.of(p);
        return data;
    }
}
