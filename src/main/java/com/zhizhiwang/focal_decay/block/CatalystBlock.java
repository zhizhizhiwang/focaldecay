package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.block.entity.CatalystBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import com.zhizhiwang.focal_decay.mutation.GuidedConcept;
import com.zhizhiwang.focal_decay.mutation.MutationEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * 语义催化剂（{@code DESIGN.md} §13.8 的"形式一"）。
 * <p>
 * 三件事，全部走右键，没有 GUI：
 * <ol>
 *   <li><b>插入/取下模型</b>：手持引导模型右键插入；潜行空手右键取回。域是"用哪个概念引导"的
 *       一次性决定，所以换模型会顺手熄火（见 {@code CatalystBlockEntity#setModel}）；</li>
 *   <li><b>点火</b>：手持火种右键。区域内每周期命中概率抬到 1（落点仍由模型的概念 + q 决定），
 *       域外一圈的 {@code wild_chance} 升高——这就是 R2「形可变、量不减」的代价；</li>
 *   <li><b>读状态</b>：空手右键报一行"概念 / 完备度 / 解锁档 / 还剩几周期"。</li>
 * </ol>
 * 设计原文里"界面显示当前区域的概念与 q"先用动作栏满足：要传的信息本来就只有这一行，
 * 独立 GUI 留给美术与交互打磨（这一段刻意不引入 Menu/Screen 与容器同步）。
 */
public class CatalystBlock extends Block implements EntityBlock {

    public CatalystBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.5f)
                .sound(SoundType.AMETHYST)
                .lightLevel(state -> 5));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CatalystBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return level.isClientSide ? null : (tickLevel, pos, tickState, blockEntity) -> {
            if (tickLevel instanceof ServerLevel serverLevel
                    && blockEntity instanceof CatalystBlockEntity catalyst) {
                catalyst.serverTick(serverLevel);
            }
        };
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof CatalystBlockEntity catalyst)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        ServerLevel serverLevel = (ServerLevel) level;

        // ---- 火种：点火 ----
        if (stack.is(ModItems.IGNITER.get())) {
            if (catalyst.isLit(MutationEventHandler.displayPeriodIndex(serverLevel))) {
                player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_already_lit"), true);
                return ItemInteractionResult.SUCCESS;
            }
            if (!catalyst.canCatalyse()) {
                player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_needs_model"), true);
                return ItemInteractionResult.SUCCESS;
            }
            catalyst.ignite(serverLevel);
            // 火种是有代价的：一次点火磨损一点耐久（设计里"消耗耐久或充能"取前者，
            // 因为耐久是玩家已经会读的一条信息）。
            if (!player.getAbilities().instabuild) {
                stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            }
            serverLevel.sendParticles(ParticleTypes.END_ROD,
                    pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 48, 1.2, 0.6, 1.2, 0.02);
            serverLevel.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 1.4f);
            player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_lit",
                    GuidedConcept.displayName(catalyst.concept()),
                    Math.round(catalyst.q() * 100),
                    FocalDecayConfig.CATALYST_DURATION_PERIODS.get()), false);
            return ItemInteractionResult.SUCCESS;
        }

        // ---- 引导模型：插入（潜行时取回，避免误插）----
        if (stack.getItem() instanceof ObserverModelItem) {
            long period = MutationEventHandler.displayPeriodIndex(serverLevel);
            if (player.isShiftKeyDown()) {
                ItemStack taken = catalyst.takeModel(serverLevel);
                if (taken.isEmpty()) {
                    player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_no_model"), true);
                } else if (!player.getInventory().add(taken)) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY() + 1, pos.getZ(), taken);
                }
                return ItemInteractionResult.SUCCESS;
            }
            if (catalyst.hasModel()) {
                player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_occupied"), true);
                return ItemInteractionResult.SUCCESS;
            }
            ItemStack mounted = stack.copyWithCount(1);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            catalyst.setModel(serverLevel, mounted);
            if (!catalyst.canCatalyse()) {
                player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_model_inert"), true);
            } else {
                player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_model_mounted",
                        GuidedConcept.displayName(catalyst.concept()), Math.round(catalyst.q() * 100)), true);
            }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 空手右键：报状态。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof CatalystBlockEntity catalyst)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        ServerLevel serverLevel = (ServerLevel) level;
        long period = MutationEventHandler.displayPeriodIndex(serverLevel);
        if (!catalyst.hasModel()) {
            player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_no_model"), true);
        } else if (!catalyst.canCatalyse()) {
            player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_model_inert"), true);
        } else if (catalyst.isLit(period)) {
            player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_status_lit",
                    GuidedConcept.displayName(catalyst.concept()),
                    Math.round(catalyst.q() * 100),
                    GuidedConcept.unlockFor(catalyst.q()).displayName(),
                    catalyst.remaining(period)), true);
        } else {
            player.displayClientMessage(Component.translatable("message.focal_decay.catalyst_status_idle",
                    GuidedConcept.displayName(catalyst.concept()),
                    Math.round(catalyst.q() * 100),
                    GuidedConcept.unlockFor(catalyst.q()).displayName()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 破坏时：熄火 + 掉出插着的模型。
     * <p>
     * 熄火这一步不能省——留下一片没有方块驱动的域，客户端会一直照着它画，
     * 而服务端下一次查找时那块方块已经没了（域的登记表挂在 {@code MutationPoolManager} 上，
     * 只有方块实体知道该清哪一条）。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CatalystBlockEntity catalyst) {
            ItemStack taken = catalyst.takeModel(serverLevel);
            if (!taken.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), taken);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents,
                                TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("tooltip.focal_decay.semantic_catalyst"));
    }
}
