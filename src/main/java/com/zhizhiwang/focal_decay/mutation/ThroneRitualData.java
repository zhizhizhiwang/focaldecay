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
 */
public class ThroneRitualData extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_throne_ritual";
    private static final String TAG_ACTIVE = "Active";
    private static final String TAG_THRONE = "Throne";
    private static final String TAG_PLAYER = "Player";
    private static final String TAG_TOTAL = "Total";
    private static final String TAG_REMAINING = "Remaining";
    private static final String TAG_WAVE = "Wave";
    private static final String TAG_NEXT_WAVE = "NextWave";

    private boolean active;
    private long thronePos;
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
    public void start(long thronePos, UUID playerId, int totalTicks, int waveIntervalTicks) {
        boolean resume = !this.active
                && this.playerId != null && this.playerId.equals(playerId)
                && this.remainingTicks > 0;
        this.active = true;
        this.thronePos = thronePos;
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
        setDirty();
    }

    public boolean isActive() {
        return active;
    }

    public long thronePos() {
        return thronePos;
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
