package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.menu.ObserverCoreMenu;
import com.zhizhiwang.focal_decay.mutation.FocalDecayWorldData;
import com.zhizhiwang.focal_decay.mutation.FloatingText;
import com.zhizhiwang.focal_decay.network.ModNetwork;
import com.zhizhiwang.focal_decay.network.ObserverCoreActivatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public class ObserverCoreBlock extends Block {
    /** powered=false 表示失效（失焦进行中），powered=true 表示已激活（失焦终止）。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public ObserverCoreBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(-1.0f, Float.MAX_VALUE)
                .noLootTable()
                .sound(SoundType.METAL)
                .lightLevel(state -> state.getValue(POWERED) ? 15 : 0));
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    /** 右键打开 GUI（离线/在线 + 激活按钮）；首次访问赠送一枚语义碎片。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
            if (!worldData.isCoreVisited()) {
                worldData.setCoreVisited(true);
                ItemStack fragment = new ItemStack(ModItems.FRAGMENT_SEMANTIC.get());
                if (!serverPlayer.getInventory().add(fragment)) {
                    serverPlayer.drop(fragment, false);
                }
                serverPlayer.displayClientMessage(
                        Component.translatable("message.focal_decay.core_first_visit"), true);
            }
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new ObserverCoreMenu(id, inv, state.getValue(POWERED), pos),
                    Component.translatable("container.focal_decay.observer_core")), pos);
        }
        return InteractionResult.CONSUME;
    }

    /** 激活完成（scheduleTick 回调）：核心发光、失焦终止、全服广播。 */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(POWERED)) {
            return;
        }
        level.setBlock(pos, state.setValue(POWERED, true), 3);
        FocalDecayWorldData worldData = FocalDecayWorldData.get(level.getServer());
        worldData.setObserverOnline(true);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                240, 1.5, 2.5, 1.5, 0.25);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.focal_decay.core_activated_broadcast"), false);
        // 彩蛋：新观测者就位时浮动 "完备语义分类"
        FloatingText.spawn(level, pos, Component.translatable("particle.focal_decay.complete_semantics"), 60);
        ModNetwork.sendToAllPlayers(new ObserverCoreActivatePacket(pos.immutable()));
    }
}
