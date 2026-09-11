package com.zhizhiwang.focal_decay.mutation;

import com.zhizhiwang.focal_decay.mixin.DisplayAccessor;
import com.zhizhiwang.focal_decay.mixin.TextDisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;

/**
 * 浮动文字彩蛋（"42ms"、"完备语义分类"等）：
 * 用原版 Text Display 实体在世界中渲染 3D 浮动文字，无重力、透明背景，到点由服务器任务自动移除。
 */
public final class FloatingText {

    private FloatingText() {
    }

    public static void spawn(ServerLevel level, BlockPos pos, Component text, int lifetimeTicks) {
        Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
        display.setPos(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
        ((TextDisplayAccessor) display).focaldecay$setText(text);
        ((DisplayAccessor) display).focaldecay$setBillboardConstraints(Display.BillboardConstraints.CENTER);
        ((TextDisplayAccessor) display).focaldecay$setBackgroundColor(0);
        display.setNoGravity(true);
        level.addFreshEntity(display);
        level.getServer().tell(new TickTask(
                level.getServer().getTickCount() + Math.max(1, lifetimeTicks),
                display::discard));
    }
}
