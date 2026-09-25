package com.zhizhiwang.focal_decay.attachment;

import com.zhizhiwang.focal_decay.FocalDecay;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, FocalDecay.MODID);

    /**
     * 玩家挖掘锁定数据。
     * <p>
     * <b>刻意不可序列化、刻意不 copyOnDeath</b>（2026-09-25，BACKLOG P0-4 附带缺陷②）。
     * 两者缺一不可，而且理由不同：
     * <ul>
     *   <li><b>不加 {@code .serialize(...)}</b> → 锁定不落盘。它只描述"这一次挖掘开始时
     *       玩家看到的是哪个方块"，跨会话没有任何意义；</li>
     *   <li><b>不加 {@code .copyOnDeath()}</b> → 死亡重生不带过去。注意
     *       {@code copyOnDeath} 只对"有序列化器"的 attachment 生效
     *       （{@code AttachmentType.Builder#copyOnDeath} 在 serializer 为 null 时直接抛异常），
     *       所以旧实现是先序列化、再勾 copyOnDeath，两条路径一起漏。</li>
     * </ul>
     * 现在序列化器整个去掉，两条路径一次性消失，而不是靠破坏时逐个校验去兜。
     */
    public static final Supplier<AttachmentType<BreakData>> BREAK_DATA =
            ATTACHMENT_TYPES.register("break_data", () -> AttachmentType.builder(BreakData::new).build());

    private ModAttachments() {
    }
}
