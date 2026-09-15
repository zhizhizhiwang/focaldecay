package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 全局末日天数（设计大纲 §6.1）：
 * 每 20 分钟游戏日（24000 tick）+1，玩家数为 0 时暂停计时，随存档持久化。
 * <p>
 * 同时承载"世界级时钟状态"的广播（天数 / 观测者是否在线 / 调试时钟），
 * 因为这几样都满足同一个条件：<b>服务端与客户端必须逐位一致，否则失焦预览会和真实转换对不上</b>。
 */
public class FocalDecayWorldData extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_world_days";
    private static final String TAG_DAYS = "Days";
    private static final String TAG_PARTIAL_TICKS = "PartialTicks";
    private static final String TAG_OBSERVER_ONLINE = "ObserverOnline";
    private static final String TAG_CORE_VISITED = "CoreVisited";
    private static final String TAG_GRANTED_FRAGMENTS = "GrantedFragments";

    public static final long TICKS_PER_DAY = 24000L;

    /** 语义碎片里程碑位（首次达成发放，防重复）。 */
    public static final int BIT_FRAGMENT_ROSE = 1;
    public static final int BIT_FRAGMENT_42MS = 1 << 1;
    public static final int BIT_FRAGMENT_CHENG = 1 << 2;

    private long days;
    private long partialTicks;
    /** 观测者核心已激活：失焦终止（方块/实体突变停止）。 */
    private boolean observerOnline;
    /** 玩家是否已第一次右键过观测者核心（发过一次碎片）。 */
    private boolean coreVisited;
    /** 已发放过的碎片里程碑位掩码。 */
    private int grantedFragmentBits;

    // ---- 调试时钟（2026-09-16，/focaldecay period）----
    // 刻意<b>不落盘</b>：这是测试档位，忘了 reset 会让世界看起来"卡住了"，
    // 那是最难查的一类假 bug。重启即恢复 1.0 / 0。
    /** 失焦刻的流速倍率：1 = 正常，0 = 冻结，负 = 倒带，>1 = 加速。 */
    private double clockSpeed = 1.0;
    /** 失焦刻的偏移（刻）：负数 = 回滚。 */
    private long clockOffset;

    /** 倍率上限：gameTick * speed 必须留在 long 内，且再大也没有观测意义。 */
    public static final double CLOCK_SPEED_LIMIT = 64.0;
    /** 偏移上限：够用就好，避免 period 被推到荒唐的量级。 */
    public static final int CLOCK_OFFSET_LIMIT = 1_000_000;

    public static final Factory<FocalDecayWorldData> FACTORY = new Factory<>(
            FocalDecayWorldData::new,
            FocalDecayWorldData::load,
            null
    );

    private FocalDecayWorldData() {
    }

    public static FocalDecayWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public long getDays() {
        return days;
    }

    /** 测试/调试用：手动设定天数并广播给所有玩家（触发阶段重算）。 */
    public void setDays(long days) {
        this.days = Math.max(0L, days);
        setDirty();
        broadcastWorldData();
    }

    public boolean isObserverOnline() {
        return observerOnline;
    }

    /** 激活观测者核心：置在线并广播（客户端清空失焦预览）。 */
    public void setObserverOnline(boolean online) {
        if (this.observerOnline == online) {
            return;
        }
        this.observerOnline = online;
        setDirty();
        broadcastWorldData();
    }

    // ---- 调试时钟 ----

    public double getClockSpeed() {
        return clockSpeed;
    }

    public long getClockOffset() {
        return clockOffset;
    }

    /**
     * 设定失焦时钟（{@code /focaldecay period}）。<b>不落盘</b>：这是测试档位。
     * 变化后必须广播：客户端要按同一组参数算显示刻，否则预览与真实转换会对不上。
     *
     * @return 是否有变化
     */
    public boolean setClock(double speed, long offset) {
        double clampedSpeed = Math.max(-CLOCK_SPEED_LIMIT, Math.min(CLOCK_SPEED_LIMIT, speed));
        long clampedOffset = Math.max(-CLOCK_OFFSET_LIMIT, Math.min(CLOCK_OFFSET_LIMIT, offset));
        if (this.clockSpeed == clampedSpeed && this.clockOffset == clampedOffset) {
            return false;
        }
        this.clockSpeed = clampedSpeed;
        this.clockOffset = clampedOffset;
        // 注意：不 setDirty()。既然不落盘，就没有"待保存"这回事。
        broadcastWorldData();
        return true;
    }

    private void broadcastWorldData() {
        ModNetwork.sendWorldDataToAll(days, observerOnline, clockSpeed, clockOffset);
    }

    public boolean isCoreVisited() {
        return coreVisited;
    }

    public void setCoreVisited(boolean visited) {
        this.coreVisited = visited;
        setDirty();
    }

    /** 里程碑碎片：首次达成返回 true 并发放；重复返回 false。 */
    public boolean grantFragmentOnce(int bit) {
        if ((grantedFragmentBits & bit) != 0) {
            return false;
        }
        grantedFragmentBits |= bit;
        setDirty();
        return true;
    }

    /** 每 tick 调用：有玩家在线时累计，满一个游戏日后天数 +1 并广播给所有玩家。 */
    public void tick(MinecraftServer server) {
        if (server.getPlayerCount() > 0) {
            partialTicks++;
            if (partialTicks >= TICKS_PER_DAY) {
                partialTicks -= TICKS_PER_DAY;
                days++;
                setDirty();
                broadcastWorldData();
            }
        }
    }

    private static FocalDecayWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        FocalDecayWorldData data = new FocalDecayWorldData();
        data.days = tag.getLong(TAG_DAYS);
        data.partialTicks = tag.getLong(TAG_PARTIAL_TICKS);
        data.observerOnline = tag.getBoolean(TAG_OBSERVER_ONLINE);
        data.coreVisited = tag.getBoolean(TAG_CORE_VISITED);
        data.grantedFragmentBits = tag.getInt(TAG_GRANTED_FRAGMENTS);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong(TAG_DAYS, days);
        tag.putLong(TAG_PARTIAL_TICKS, partialTicks);
        tag.putBoolean(TAG_OBSERVER_ONLINE, observerOnline);
        tag.putBoolean(TAG_CORE_VISITED, coreVisited);
        tag.putInt(TAG_GRANTED_FRAGMENTS, grantedFragmentBits);
        return tag;
    }
}
