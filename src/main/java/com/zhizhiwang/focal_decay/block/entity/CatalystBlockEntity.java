package com.zhizhiwang.focal_decay.block.entity;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.Catalysis;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import com.zhizhiwang.focal_decay.mutation.MutationPoolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 催化剂方块实体（2026-09-29，{@code DESIGN.md} §13.8 的"形式一"）。
 * <p>
 * <b>它解决什么</b>：引导模型只决定"变成什么"，不决定"变不变"；而阶段 1 每周期只有 1% 的命中率，
 * 玩家再怎么引导也看不见。催化剂把一片区域的命中率抬到 1——<b>必中</b>——落点仍由插在上面的模型决定。
 * <p>
 * <b>状态只有两样</b>：插着的模型（落盘）与点火状态（<b>不落盘</b>）。
 * 点火状态是一次"正在发生的事件"而不是世界状态：一片域只活几十个周期，
 * 服务器重启把它丢掉是合理的，落盘反而要处理"重启后倒计时怎么算"这种没人关心的问题
 * （理由与 {@link Catalysis.Field} 一致）。
 * <p>
 * <b>为什么没有 GUI</b>：模型用右键插入/取下，状态用右键读一行文字。这样整条路径不需要
 * Menu/Screen 与容器同步，而它要传的信息本来就只有"哪个概念、q 多少、还剩几周期"。
 * （设计的原文是"界面显示当前区域的概念与 q"；先用动作栏满足它，独立 GUI 留给美术与交互打磨。）
 */
public class CatalystBlockEntity extends BlockEntity {

    public static final String TAG_MODEL = "Model";

    private ItemStack modelStack = ItemStack.EMPTY;
    /** 生效到哪个<b>显示刻</b>为止；{@code < 0} = 未点火。刻意不落盘。 */
    private long until = -1L;
    /** 点火那一刻的几何快照（半径取自模型，壳厚与溢出强度取自配置）。 */
    private int radius;
    private int ringWidth;
    private double spill;

    public CatalystBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEMANTIC_CATALYST.get(), pos, state);
    }

    public ItemStack modelStack() {
        return modelStack;
    }

    public boolean hasModel() {
        return !modelStack.isEmpty();
    }

    /** 此刻是否点着（{@code period} 用调用方那一端的显示刻）。 */
    public boolean isLit(long period) {
        return until >= 0 && period <= until;
    }

    /** 还剩几个周期（未点火为 0）。 */
    public long remaining(long period) {
        return isLit(period) ? until - period + 1 : 0L;
    }

    /** 插着的模型解析出的概念（无模型/非引导模型时为空串）。 */
    public String concept() {
        ObserverModelData data = modelData();
        return data == null ? "" : data.concept();
    }

    /** 插着的模型的完备度 q。 */
    public double q() {
        ObserverModelData data = modelData();
        return data == null ? 0.0 : data.stabilityStrength();
    }

    /** 这个模型能不能驱动催化：只有引导模型（有概念、q > 0）才有"落点"可言。 */
    public boolean canCatalyse() {
        ObserverModelData data = modelData();
        return data != null && ObserverModelData.TYPE_GUIDED.equals(data.type())
                && !data.concept().isEmpty() && data.stabilityStrength() > 0.0;
    }

    private ObserverModelData modelData() {
        return modelStack.getItem() instanceof ObserverModelItem ? ObserverModelItem.getData(modelStack) : null;
    }

    /**
     * 装入模型。
     * <p>
     * <b>换模型会顺手熄火</b>：域是"用哪个概念引导"的一次性决定，换了词却继续烧着旧的火，
     * 玩家会以为新概念已经生效（而落点其实还是旧的）。熄火是可见的、可诊断的。
     */
    public void setModel(ServerLevel level, ItemStack stack) {
        this.modelStack = stack;
        if (until >= 0) {
            this.until = -1L;
            pushField(level);
        }
        setChanged();
    }

    /** 取下模型（同时熄火）。 */
    public ItemStack takeModel(ServerLevel level) {
        ItemStack taken = modelStack;
        modelStack = ItemStack.EMPTY;
        if (until >= 0) {
            until = -1L;
            pushField(level);
        }
        setChanged();
        return taken;
    }

    /**
     * 点火：登记一片催化域并广播给客户端。
     * <p>
     * 半径取自模型（与原型机的保护半径同一套规则），壳厚与溢出强度取自配置——
     * 这三样都会进 {@link Catalysis.Field} 一起同步，所以客户端不需要读任何配置就能算出同一个答案。
     *
     * @return 是否真的点着了（没有可用的引导模型时为 false）
     */
    public boolean ignite(ServerLevel level) {
        if (!canCatalyse()) {
            return false;
        }
        ObserverModelData data = modelData();
        this.radius = MutationPoolManager.radiusFor(data);
        this.ringWidth = Math.max(0, FocalDecayConfig.CATALYST_RING_WIDTH.get());
        this.spill = FocalDecayConfig.CATALYST_SPILL_BONUS.get();
        this.until = MutationEventHandler.displayPeriodIndex(level)
                + Math.max(1, FocalDecayConfig.CATALYST_DURATION_PERIODS.get());
        pushField(level);
        setChanged();
        return true;
    }

    /**
     * 每 tick：过期就熄火并广播移除。
     * <p>
     * 过期<b>本来就不影响正确性</b>（两端各自用 {@link Catalysis.Field#isActive} 判有效期，
     * 结论必然相同），这里做的是卫生工作：让服务端那张表与客户端那张表都别一直留着死条目。
     */
    public void serverTick(ServerLevel level) {
        if (until < 0) {
            return;
        }
        if (MutationEventHandler.displayPeriodIndex(level) > until) {
            until = -1L;
            pushField(level);
            setChanged();
        }
    }

    /** 把当前状态推给服务端登记表（它负责广播；{@code until < 0} 即清除）。 */
    private void pushField(ServerLevel level) {
        MutationPoolManager manager = MutationPoolManager.get(level);
        if (until < 0) {
            manager.setCatalystField(level, worldPosition, null);
        } else {
            manager.setCatalystField(level, worldPosition,
                    new Catalysis.Field(worldPosition.immutable(), radius, ringWidth, until, spill));
        }
    }

    // ---- NBT ----
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!modelStack.isEmpty()) {
            tag.put(TAG_MODEL, modelStack.saveOptional(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        modelStack = tag.contains(TAG_MODEL)
                ? ItemStack.parseOptional(registries, tag.getCompound(TAG_MODEL))
                : ItemStack.EMPTY;
        // 点火状态不读回来：它是瞬时事件，重启后就是没点着（见类注释）。
        until = -1L;
    }
}
