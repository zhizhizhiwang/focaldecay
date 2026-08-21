package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.mixin.LootTableAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.LootTableLoadEvent;

/**
 * 观测者核心修复路径（设计大纲 §11）：
 *  - GUI 按钮触发激活：消耗"重建的观测协议"，播放开始特效，调度完成 tick；
 *  - 完成 tick 由 {@link com.zhizhiwang.focal_decay.block.ObserverCoreBlock#tick} 处理（powered=true + 失焦终止广播）；
 *  - 语义碎片战利品注入：所有 `chests/*` 战利品表低概率追加一枚随机碎片（含村庄/末地城）。
 */
public final class ObserverCoreHandler {

    private ObserverCoreHandler() {
    }

    /** 玩家在 GUI 点击"激活"：校验协议 → 消耗 → 播放开始特效 → 调度完成 tick。 */
    public static boolean tryActivate(ServerLevel level, BlockPos pos, ServerPlayer player) {
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        if (worldData.isObserverOnline()
                || level.getBlockState(pos).getValue(BlockStateProperties.POWERED)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.core_already_online"), true);
            return false;
        }
        if (!consumeProtocol(player)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.core_need_protocol"), true);
            return false;
        }
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                120, 1.0, 2.0, 1.0, 0.2);
        level.scheduleTick(pos, level.getBlockState(pos).getBlock(),
                FocalDecayConfig.OBSERVER_CORE_ACTIVATION_TICKS.get());
        player.displayClientMessage(Component.translatable("message.focal_decay.core_activating"), true);
        return true;
    }

    private static boolean consumeProtocol(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(ModItems.REBUILT_OBSERVER_PROTOCOL.get())) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 所有战利品箱（含村庄/末地城）注入低概率语义碎片（设计大纲 §11 来源 2/3/6）。 */
    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        ResourceLocation name = event.getName();
        if (!name.getPath().startsWith("chests/")) {
            return;
        }
        double chance = name.getPath().contains("end_city") ? 0.12 : 0.05;
        LootPool.Builder pool = LootPool.lootPool()
                .setRolls(ConstantValue.exactly((float) chance))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_ROSE.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_THRONE.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_SEMANTIC.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_42MS.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_CRYSTAL.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_AARON.get()))
                .add(LootItem.lootTableItem(ModItems.FRAGMENT_CHENG.get()));
        // 原地向既有战利品表追加碎片池（保留 paramSet / randomSequence / functions）
        LootTable old = event.getTable();
        ((LootTableAccessor) old).focaldecay$getPools().add(pool.build());
    }
}
