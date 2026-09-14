package com.zhizhiwang.focal_decay.block;

import com.zhizhiwang.focal_decay.block.entity.ObserverCoreBlockEntity;
import com.zhizhiwang.focal_decay.config.FocalDecayConfig;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.particle.ModParticles;
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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ObserverCoreBlock extends Block implements EntityBlock {
    /** powered=false 表示失效（失焦进行中），powered=true 表示已激活（失焦终止）。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /**
     * 转子（含升起与上下浮动）的外接盒，模型像素坐标：x/z 为 5..11，
     * y 从静止贴底的 2 一直到升起+浮动的最高点 13。
     * <p>
     * 这一个形状同时决定三件事：选中轮廓、碰撞箱、以及"邻居要不要因为贴着自己而剔面"。
     * 底座（0..2 高、满 16x16 脚印）故意不纳入：它是纯装饰，不该有实体阻挡；而且这样一来
     * {@code isSolidRender()} 会变成 false，相邻方块就不会把自己贴着核心的那一面剔掉了。
     */
    private static final VoxelShape SHAPE = Block.box(5.0D, 2.0D, 5.0D, 11.0D, 13.0D, 11.0D);

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

    // ------------------------------------------------------------------
    // 形状：只取"完整覆盖转子"的最小盒
    // ------------------------------------------------------------------

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    // ------------------------------------------------------------------
    // 方块实体：转子的动画状态（纯客户端瞬态）
    // ------------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ObserverCoreBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 与原版附魔台一致：动画状态只在客户端推进，服务端不 tick，也就不需要任何同步
        if (!level.isClientSide) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof ObserverCoreBlockEntity core) {
                ObserverCoreBlockEntity.tick(tickLevel, tickPos, tickState, core);
            }
        };
    }

    // ------------------------------------------------------------------
    // 粒子：激活后每 5 tick 在转子四周的倾斜圆环上撒 2~4 个附魔粒子
    // ------------------------------------------------------------------

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // 只有激活后才撒粒子
        boolean powered = state.getValue(POWERED);
        int interval = FocalDecayConfig.OBSERVER_CORE_PARTICLE_INTERVAL.get();
        if (!powered || interval <= 0) {
            return;
        }
        long gameTime = level.getGameTime();
        if (gameTime % interval != 0L) {
            return;
        }
        int min = FocalDecayConfig.OBSERVER_CORE_PARTICLE_MIN.get();
        int max = Math.max(min, FocalDecayConfig.OBSERVER_CORE_PARTICLE_MAX.get());
        if (max <= 0) {
            return;
        }
        double radius = FocalDecayConfig.OBSERVER_CORE_PARTICLE_RADIUS.get();
        double tilt = FocalDecayConfig.OBSERVER_CORE_PARTICLE_TILT.get();
        double sweep = FocalDecayConfig.OBSERVER_CORE_PARTICLE_SWEEP.get();

        // 升起进度与浮动偏移按游戏时间推导，不读方块实体字段——animateTick 可能跑在
        // 方块实体还没 tick 过的时候（客户端刚同步到区块），那时字段还是默认值，
        // 粒子就会从底座高度往外撒、与已经升起来的转子对不上。
        float lift = ObserverCoreBlockEntity.liftFraction(gameTime, powered);
        double bob = ObserverCoreBlockEntity.bobOffsetAt(lift,
                ObserverCoreBlockEntity.bobPhaseAt(gameTime, powered));
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + ObserverCoreBlockEntity.ROTOR_CENTER_Y
                + ObserverCoreBlockEntity.LIFT_HEIGHT * lift + bob;
        double centerZ = pos.getZ() + 0.5D;

        int count = min + random.nextInt(max - min + 1);
        for (int i = 0; i < count; i++) {
            double from = random.nextDouble() * Mth.TWO_PI;
            // 沿同一方向（俯视顺时针）往前扫一段，粒子看起来就是绕着转子转
            double to = from + sweep;
            level.addParticle(ModParticles.OBSERVER_SPARK.get(),
                    centerX + ringX(from, radius), centerY + ringY(from, radius, tilt), centerZ + ringZ(from, radius, tilt),
                    ringX(to, radius) - ringX(from, radius),
                    ringY(to, radius, tilt) - ringY(from, radius, tilt),
                    ringZ(to, radius, tilt) - ringZ(from, radius, tilt));
        }
    }

    /** 倾斜圆环上的水平分量。 */
    private static double ringX(double angle, double radius) {
        return Math.cos(angle) * radius;
    }

    /** 倾斜圆环上的竖直分量：这就是"倾斜面"的来源。 */
    private static double ringY(double angle, double radius, double tilt) {
        return Math.sin(angle) * radius * Math.sin(tilt);
    }

    private static double ringZ(double angle, double radius, double tilt) {
        return Math.sin(angle) * radius * Math.cos(tilt);
    }
}