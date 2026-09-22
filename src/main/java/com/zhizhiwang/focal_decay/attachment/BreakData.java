package com.zhizhiwang.focal_decay.attachment;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 玩家挖掘锁定数据（设计大纲 §5.2）。
 * 挖掘开始时记录目标方块状态与周期索引；方块破坏时据此执行真实转换。
 * 以 Player attachment 形式挂载（NeoForge 21.1 的 attachment 系统替代旧 Capability）。
 * <p>
 * <b>落盘一律用方块 ID 字符串，不用注册表数字 ID</b>（2026-09-21）：
 * 数字 ID 是"注册顺序"的函数，会随 MC/加载器/模组集合甚至玩家装的模组数量漂移，
 * 同一个数字在不同环境里指向不同方块。这条数据要跟着玩家存档跨会话存在，
 * 所以必须用稳定的 {@link ResourceLocation}。运行时数组下标那些 {@code getId} 不受影响——
 * 它们只在单次进程内活。
 */
public class BreakData {
    /** NBT 键：目标方块的完整 ID（{@code minecraft:stone} 这种）。 */
    private static final String TAG_TARGET = "Target";
    private static final String TAG_PERIOD = "PeriodIndex";
    private static final String TAG_POS = "Pos";

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
            tag.putString(TAG_TARGET, BuiltInRegistries.BLOCK.getKey(targetState.getBlock()).toString());
        }
        tag.putLong(TAG_PERIOD, periodIndex);
        if (pos != null) {
            tag.putLong(TAG_POS, pos.asLong());
        }
    }

    public void loadNBT(CompoundTag tag) {
        this.active = tag.getBoolean("Active");
        this.targetState = null;
        String target = tag.getString(TAG_TARGET);
        if (!target.isEmpty()) {
            // 方块可能已被移除（换版本、卸了模组）：这时解析成 AIR 或抛错，两种情况都当作"没有目标"，
            // 而不是把空气当成一个合法的转换目标。
            ResourceLocation id = ResourceLocation.tryParse(target);
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            if (block != Blocks.AIR) {
                this.targetState = block.defaultBlockState();
            }
        }
        this.periodIndex = tag.getLong(TAG_PERIOD);
        this.pos = tag.contains(TAG_POS) ? BlockPos.of(tag.getLong(TAG_POS)) : null;
    }
}
