package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * 末影龙掉落（设计大纲 §3.5.2）：第一枚未激活完全稳定模型。
 * 默认必掉（配置可调成稀有掉落）；掉落物携带完整 NBT，可被复制配方识别。
 */
public final class DragonDropHandler {

    private DragonDropHandler() {
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        double chance = FocalDecayConfig.ENDER_DRAGON_TOTAL_STABILITY_DROP_CHANCE.get();
        if (chance <= 0.0 || dragon.getRandom().nextDouble() >= chance) {
            return;
        }
        ItemStack stack = new ItemStack(ModItems.TOTAL_STABILITY_MODEL.get());
        event.getDrops().add(new ItemEntity(
                dragon.level(), dragon.getX(), dragon.getY() + 0.5, dragon.getZ(), stack));
    }
}
