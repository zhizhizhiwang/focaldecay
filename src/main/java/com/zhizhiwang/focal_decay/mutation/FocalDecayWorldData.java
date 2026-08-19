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
 */
public class FocalDecayWorldData extends SavedData {
    private static final String DATA_NAME = FocalDecay.MODID + "_world_days";
    private static final String TAG_DAYS = "Days";
    private static final String TAG_PARTIAL_TICKS = "PartialTicks";

    public static final long TICKS_PER_DAY = 24000L;

    private long days;
    private long partialTicks;

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
        ModNetwork.sendWorldDataToAll(this.days);
    }

    /** 每 tick 调用：有玩家在线时累计，满一个游戏日后天数 +1 并广播给所有玩家。 */
    public void tick(MinecraftServer server) {
        if (server.getPlayerCount() > 0) {
            partialTicks++;
            if (partialTicks >= TICKS_PER_DAY) {
                partialTicks -= TICKS_PER_DAY;
                days++;
                setDirty();
                ModNetwork.sendWorldDataToAll(days);
            }
        }
    }

    private static FocalDecayWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        FocalDecayWorldData data = new FocalDecayWorldData();
        data.days = tag.getLong(TAG_DAYS);
        data.partialTicks = tag.getLong(TAG_PARTIAL_TICKS);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong(TAG_DAYS, days);
        tag.putLong(TAG_PARTIAL_TICKS, partialTicks);
        return tag;
    }
}
