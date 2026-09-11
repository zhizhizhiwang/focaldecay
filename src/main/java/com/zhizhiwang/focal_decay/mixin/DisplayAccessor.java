package com.zhizhiwang.focal_decay.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露 Display 私有 setter（浮动文字彩蛋用）。 */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setBillboardConstraints")
    void focaldecay$setBillboardConstraints(Display.BillboardConstraints billboard);
}
