package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * 王座仪式状态（设计大纲 §3.5.1）：每末地维度一份，进度持久化。
 * 玩家离开范围且配置允许时暂停（保留 remainingTicks），返回可续仪；否则失败。
 * <p>
 * 2026-09-17 起还记下<b>仪式所在的那座观测者基座</b>（{@code prototypePos}）：
 * 仪式的现场条件就是"那座基座还在原地、里面还是那枚未激活的 OBSR-EX"，
 * 而"半径内随便找一座"既不确定（多座基座时挑到哪座看坐标遍历顺序），
 * 也无法表达"拿掉这一座就中断"。
 */
public class ThroneRitualData extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_throne_ritual";
    private static final String TAG_ACTIVE = "Active";
    private static final String TAG_THRONE = "Throne";
    private static final String TAG_PROTOTYPE = "Prototype";
    private static final String TAG_PLAYER = "Player";
    private static final String TAG_TOTAL = "Total";
    private static final String TAG_REMAINING = "Remaining";
    private static final String TAG_WAVE = "Wave";
    private static final String TAG_NEXT_WAVE = "NextWave";

    private boolean active;
    private long thronePos;
    /** 举行仪式的观测者基座位置；{@code Long.MIN_VALUE} = 无（旧存档/异常状态）。 */
    private long prototypePos = Long.MIN_VALUE;
    private UUID playerId;
    private int totalTicks;
    private int remainingTicks;
    private int wave;
    private int nextWaveTicks;

    public static final Factory<ThroneRitualData> FACTORY = new Factory<>(
            ThroneRitualData::new, ThroneRitualData::load, null);

    private ThroneRitualData() {
    }

    public static ThroneRitualData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static ThroneRitualData load(CompoundTag tag, HolderLookup.Provider registries) {
        ThroneRitualData data = new ThroneRitualData();
        data.active = tag.getBoolean(TAG_ACTIVE);
        data.thronePos = tag.getLong(TAG_THRONE);
        data.prototypePos = tag.contains(TAG_PROTOTYPE) ? tag.getLong(TAG_PROTOTYPE) : Long.MIN_VALUE;
        data.playerId = tag.contains(TAG_PLAYER) ? UUID.fromString(tag.getString(TAG_PLAYER)) : null;
        data.totalTicks = tag.getInt(TAG_TOTAL);
        data.remainingTicks = tag.getInt(TAG_REMAINING);
        data.wave = tag.getInt(TAG_WAVE);
        data.nextWaveTicks = tag.getInt(TAG_NEXT_WAVE);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean(TAG_ACTIVE, active);
        tag.putLong(TAG_THRONE, thronePos);
        if (prototypePos != Long.MIN_VALUE) {
            tag.putLong(TAG_PROTOTYPE, prototypePos);
        }
        if (playerId != null) {
            tag.putString(TAG_PLAYER, playerId.toString());
        }
        tag.putInt(TAG_TOTAL, totalTicks);
        tag.putInt(TAG_REMAINING, remainingTicks);
        tag.putInt(TAG_WAVE, wave);
        tag.putInt(TAG_NEXT_WAVE, nextWaveTicks);
        return tag;
    }

    /** 开始或续仪：同玩家且剩余时间 >0 时保留进度，否则全新开始。 */
    public void start(long thronePos, long prototypePos, UUID playerId, int totalTicks, int waveIntervalTicks) {
        boolean resume = !this.active
                && this.playerId != null && this.playerId.equals(playerId)
                && this.remainingTicks > 0;
        this.active = true;
        this.thronePos = thronePos;
        this.prototypePos = prototypePos;
        this.playerId = playerId;
        if (!resume) {
            this.totalTicks = totalTicks;
            this.remainingTicks = totalTicks;
            this.wave = 0;
            this.nextWaveTicks = waveIntervalTicks;
        }
        setDirty();
    }

    /** 暂停（离开范围）：保留进度与玩家，等待续仪。 */
    public void pause() {
        this.active = false;
        setDirty();
    }

    /** 结束（完成/失败）：清空进行中状态。 */
    public void stop() {
        this.active = false;
        this.playerId = null;
        this.prototypePos = Long.MIN_VALUE;
        setDirty();
    }

    public boolean isActive() {
        return active;
    }

    public long thronePos() {
        return thronePos;
    }

    /** 仪式绑定的观测者基座位置；{@link Long#MIN_VALUE} 表示没有（此时现场条件不可能满足）。 */
    public long prototypePos() {
        return prototypePos;
    }

    public UUID playerId() {
        return playerId;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public int remainingTicks() {
        return remainingTicks;
    }

    public void setRemainingTicks(int remainingTicks) {
        this.remainingTicks = remainingTicks;
    }

    public int wave() {
        return wave;
    }

    public void setWave(int wave) {
        this.wave = wave;
    }

    public int nextWaveTicks() {
        return nextWaveTicks;
    }

    public void setNextWaveTicks(int nextWaveTicks) {
        this.nextWaveTicks = nextWaveTicks;
    }
}
