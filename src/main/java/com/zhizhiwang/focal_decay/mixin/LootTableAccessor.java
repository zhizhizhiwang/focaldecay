package com.zhizhiwang.focal_decay.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 访问 LootTable 私有池列表，供战利品注入（ObserverCoreHandler）原地追加。 */
@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor("pools")
    List<LootPool> focaldecay$getPools();
}
