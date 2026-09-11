package com.zhizhiwang.focal_decay.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露 TextDisplay 私有 setter（浮动文字彩蛋用）。 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("setText")
    void focaldecay$setText(Component text);

    @Invoker("setBackgroundColor")
    void focaldecay$setBackgroundColor(int color);
}
