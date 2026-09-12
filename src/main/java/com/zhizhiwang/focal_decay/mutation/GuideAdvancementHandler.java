package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.data.ModAdvancementProvider;
import com.zhizhiwang.focal_decay.structure.ThroneStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 手册"位置锁"的推进：玩家实际抵达末地王座附近时，解密《王座协议》与《重聚焦》两卷。
 * <p>
 * 手册里这两卷写着"抵达现场后自动解密"，此前只是个说法——条目 JSON 没有挂 {@code advancement}，
 * 所以谁都能看。这里补上真正的推进逻辑：
 * <ul>
 *   <li>王座位置由世界种子决定（{@link ThroneStructure#thronePos(long)}），与结构生成共用同一公式；</li>
 *   <li>玩家进入末地且距王座一定范围内即视为"抵达现场"，同时授予两个解锁条件；</li>
 *   <li>另提供 {@code /focaldecay unlock} 指令作为手动兜底。</li>
 * </ul>
 * 检查有节流（每 {@value #CHECK_INTERVAL_TICKS} tick、每玩家一次），开销可忽略。
 */
public final class GuideAdvancementHandler {

    /** 检查间隔（tick）。 */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** 视为"抵达现场"的距离（格）。王座结构本身覆盖约 ±12 格，留一倍余量。 */
    private static final double ARRIVAL_RADIUS_SQR = 32.0 * 32.0;

    private GuideAdvancementHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel end = event.getServer().getLevel(Level.END);
        if (end == null) {
            return;
        }
        BlockPos throne = ThroneStructure.thronePos(end.getSeed());

        for (ServerPlayer player : end.players()) {
            if (player.blockPosition().distSqr(throne) <= ARRIVAL_RADIUS_SQR) {
                unlock(player);
            }
        }
    }

    /** 授予两卷的解锁条件（幂等）。 */
    public static void unlock(ServerPlayer player) {
        ModAdvancementProvider.award(player, ModAdvancementProvider.UNLOCK_THRONE);
        ModAdvancementProvider.award(player, ModAdvancementProvider.UNLOCK_CORE);
    }
}
