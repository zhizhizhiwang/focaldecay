package com.zhizhiwang.focal_decay.attachment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 玩家挖掘锁定数据（设计大纲 §5.2）。
 * 挖掘开始时记录目标方块状态与周期索引；方块破坏时据此执行真实转换。
 * 以 Player attachment 形式挂载（NeoForge 21.1 的 attachment 系统替代旧 Capability）。
 * <p>
 * <b>本类不做 NBT 序列化，也不跨越死亡存活</b>（2026-09-25，BACKLOG P0-4 附带缺陷②）。
 * 它记录的是"这一次挖掘开始的那一刻，玩家看到的是哪个方块"——这是一次<b>会话内</b>的
 * 瞬时状态，没有任何理由跟着玩家存档活到下一次登录，更没有理由跟着玩家死亡重生。
 * <p>
 * 旧实现同时踩了两条：
 * <ul>
 *   <li>{@code .copyOnDeath()}（见 {@code AttachmentInternals#copyEntityAttachments}：
 *       死亡时只复制显式勾选 copyOnDeath 的 attachment）让锁定跨死亡存活，
 *       重生的玩家身上带着一份指向死亡地点的陈旧目标；</li>
 *   <li>因为它可序列化，锁定还会随玩家存档落盘，于是"跨会话的陈旧目标"也成立。</li>
 * </ul>
 * 现在把序列化整个去掉（{@code ModAttachments} 只保留 builder），两条路径一起消失：
 * 不可序列化 ⇒ 不落盘；不勾 copyOnDeath ⇒ 不跨死亡。
 * <p>
 * 顺带说明为什么这样做是安全的：{@code BreakData} 只由
 * {@code InteractionHandler#onLeftClickBlock} 写入，而它一定在破坏之前同会话发生。
 * 不落盘、不复制，就没有任何路径能拿到一份"不是本次挖掘产生的"数据。
 */
public class BreakData {

    private BlockState targetState;
    private long periodIndex;
    /** 锁定目标所在位置：破坏时校验，防止陈旧的锁定泄漏到其他方块。 */
    private BlockPos pos;
    /**
     * 锁定目标所在维度（BACKLOG P0-4 附带缺陷①）。
     * <p>
     * 以前只校验 {@code pos}，而 {@link BlockPos} 只是三个整数——同一组坐标在下界和主世界都会相等。
     * 玩家在主世界开始挖、然后走进传送门，落地后如果恰好有另一个方块位于<b>同一组坐标</b>，
     * 这次破坏就会拿主世界那份锁定去转换下界的方块（目标多半还是个不存在的方块）。
     * 加上维度校验之后，跨维度的锁定一律按"陈旧"处理。
     */
    private ResourceKey<Level> dimension;
    private boolean active;

    public BreakData() {
        this.active = false;
    }

    public void start(BlockState targetState, long periodIndex, BlockPos pos, ResourceKey<Level> dimension) {
        this.targetState = targetState;
        this.periodIndex = periodIndex;
        this.pos = pos.immutable();
        this.dimension = dimension;
        this.active = true;
    }

    public void clear() {
        this.active = false;
        this.targetState = null;
        this.pos = null;
        this.dimension = null;
    }

    public boolean isActive() {
        return active;
    }

    public BlockState getTargetState() {
        return targetState;
    }

    /**
     * 解析这个目标时用的显示刻。以前记了却从没被读过（死数据，附带缺陷③），
     * 现在由 {@code InteractionHandler} 在破坏时用来判"锁定是不是已经过期"，
     * 并出现在 {@code /focaldecay mutation at} 的诊断输出里。
     */
    public long getPeriodIndex() {
        return periodIndex;
    }

    public BlockPos getPos() {
        return pos;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }
}
