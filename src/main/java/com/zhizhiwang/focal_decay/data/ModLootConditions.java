package com.zhizhiwang.focal_decay.data;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义战利品条件：{@code focal_decay:patchouli_loaded}。
 * <p>
 * <b>为什么不能直接用 {@code neoforge:mod_loaded}：</b>NeoForge 的
 * {@code net.neoforged.neoforge.common.conditions.ModLoadedCondition} 是**数据包条件**
 * （注册在 {@code NeoForgeRegistries.CONDITION_SERIALIZERS}，配合加载器的
 * {@code neoforge:conditions} 键使用），它<b>不是</b> vanilla 的 {@code LootItemConditionType}。
 * 把它塞进战利品表的 {@code pools[].neoforge:conditions} 会让整张表解析失败，实测报错：
 * <pre>Couldn't parse element ...:focal_decay:grant_observer_manual
 * - Input does not contain a key [type]: MapLike[{"condition":"neoforge:mod_loaded","modid":"patchouli"}]</pre>
 * 后果是整套发书链路静默失效（书发不出来，全日志只有一条 ERROR）。
 * <p>
 * 因此这里注册一个货真价实的 vanilla 战利品条件类型，写在标准的
 * {@code pools[].conditions[]} 里。
 */
public final class ModLootConditions {

    public static final DeferredRegister<LootItemConditionType> LOOT_CONDITION_TYPES =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, FocalDecay.MODID);

    /** 无参条件的编解码器：{@code { "condition": "focal_decay:patchouli_loaded" }}。 */
    private static final MapCodec<PatchouliLoadedCondition> PATCHOULI_LOADED_CODEC =
            MapCodec.unit(PatchouliLoadedCondition.INSTANCE);

    public static final DeferredHolder<LootItemConditionType, LootItemConditionType> PATCHOULI_LOADED =
            LOOT_CONDITION_TYPES.register("patchouli_loaded",
                    () -> new LootItemConditionType(PATCHOULI_LOADED_CODEC));

    private ModLootConditions() {
    }

    /** 前置门控：只有装了 Patchouli 才产出这本书，避免留下一个打不开的空书。 */
    public record PatchouliLoadedCondition() implements LootItemCondition {
        public static final PatchouliLoadedCondition INSTANCE = new PatchouliLoadedCondition();

        @Override
        public LootItemConditionType getType() {
            return PATCHOULI_LOADED.get();
        }

        @Override
        public boolean test(LootContext context) {
            return ModList.get().isLoaded("patchouli");
        }
    }
}
