package com.zhizhiwang.focal_decay.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhizhiwang.focal_decay.FocalDecay;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.NumberProviders;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 自定义战利品函数：{@code focal_decay:random_training}。
 * <p>
 * <b>为什么需要它</b>：原版战利品表能随机的是"掉什么、掉几个"，不能随机一个 DataComponent 内部的
 * 字符串列表。而"带有随机训练数据的 OBSR 模型"要的正是后者 —— 记录表里有哪几条、有多少条都得随机。
 * 靠多写几条权重不同的固定条目只能随机出很小的几种组合，做不出"每一枚都不一样"。
 * <p>
 * 用法（写在 {@code set_components} <b>之后</b>，否则读到的还是默认的空模型）：
 * <pre>{@code
 * {
 *   "function": "focal_decay:random_training",
 *   "count": { "type": "minecraft:uniform", "min": 6, "max": 12 },
 *   "pool":  [ "minecraft:stone", "minecraft:cobblestone" ],
 *   "extra": [ "minecraft:white_concrete" ],
 *   "extra_chance": 0.5
 * }
 * }</pre>
 * <ul>
 *   <li>{@code count} —— 从 {@code pool} 里抽几条。可以直接写整数，也可以写 {@code uniform} 之类；
 *       超过池子大小时自动截断。缺省 8。
 *       <p>
 *       用的是 {@link NumberProvider}（和 {@code minecraft:set_count} 同一套），所以
 *       {@code uniform} 的字段是 <b>{@code min} / {@code max}</b>，
 *       <b>不是</b> {@code IntProvider} 那套 {@code min_inclusive} / {@code max_inclusive}
 *       —— 两者在 JSON 里长得一样但字段名不同，写错会让整张战利品表解析失败
 *       （报 {@code No key max_inclusive}）。</li>
 *   <li>{@code pool} —— 候选方块 ID，<b>不放回</b>抽样（同一条记录不会重复出现）。缺省用内置的通用建材池。</li>
 *   <li>{@code extra} —— 附加记录；<b>每一条独立</b>以 {@code extra_chance} 概率写入，
 *       所以"三条里中一条"和"三条全中"都会出现。缺省为空。</li>
 * </ul>
 * 只认物品上的 {@code focal_decay:observer_model_data} 组件；其他物品原样放行。
 * 除记录表以外的字段（型号、q、概念、进度、代数……）全部保留，函数只改"训练数据"这一项。
 */
public final class ModLootFunctions {

    public static final DeferredRegister<LootItemFunctionType<?>> LOOT_FUNCTION_TYPES =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, FocalDecay.MODID);

    public static final DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<RandomTrainingFunction>> RANDOM_TRAINING =
            LOOT_FUNCTION_TYPES.register("random_training",
                    () -> new LootItemFunctionType<>(RandomTrainingFunction.CODEC));

    private ModLootFunctions() {
    }

    /** 随机训练数据的战利品函数。 */
    public static final class RandomTrainingFunction extends LootItemConditionalFunction {

        /** 缺省抽几条。 */
        public static final int DEFAULT_COUNT = 8;

        /** {@code pool} 缺省时的候选池：只放最通用的建材，保证任何结构里抽出来都不是废纸。 */
        public static final List<String> DEFAULT_POOL = List.of(
                "minecraft:stone",
                "minecraft:cobblestone",
                "minecraft:stone_bricks",
                "minecraft:smooth_stone",
                "minecraft:deepslate",
                "minecraft:cobbled_deepslate",
                "minecraft:andesite",
                "minecraft:diorite",
                "minecraft:granite",
                "minecraft:tuff",
                "minecraft:calcite",
                "minecraft:sandstone",
                "minecraft:bricks",
                "minecraft:oak_planks",
                "minecraft:glass",
                "minecraft:white_concrete"
        );

        public static final MapCodec<RandomTrainingFunction> CODEC = RecordCodecBuilder.mapCodec(inst ->
                commonFields(inst).and(inst.group(
                        NumberProviders.CODEC
                                .optionalFieldOf("count", ConstantValue.exactly(DEFAULT_COUNT))
                                .forGetter(f -> f.count),
                        Codec.STRING.listOf()
                                .optionalFieldOf("pool", List.of())
                                .forGetter(f -> f.pool),
                        Codec.STRING.listOf()
                                .optionalFieldOf("extra", List.of())
                                .forGetter(f -> f.extra),
                        Codec.DOUBLE
                                .optionalFieldOf("extra_chance", 0.0)
                                .forGetter(f -> f.extraChance)
                )).apply(inst, RandomTrainingFunction::new));

        private final NumberProvider count;
        private final List<String> pool;
        private final List<String> extra;
        private final double extraChance;

        private RandomTrainingFunction(List<LootItemCondition> predicates, NumberProvider count,
                                       List<String> pool, List<String> extra, double extraChance) {
            super(predicates);
            this.count = count;
            this.pool = pool;
            this.extra = extra;
            this.extraChance = extraChance;
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            ObserverModelData data = stack.get(ModDataComponents.OBSERVER_MODEL_DATA.get());
            if (data == null) {
                return stack;
            }
            List<String> candidates = this.pool.isEmpty() ? DEFAULT_POOL : this.pool;
            int want = Math.max(0, Math.min(this.count.getInt(context), candidates.size()));

            // 无放回抽样：整池洗牌取前 want 个。LinkedHashSet 去重顺带保住池子里的原始顺序，
            // 这样"抽到同一组记录"的两枚模型，物品说明里的排列也是一样的。
            List<String> shuffled = new ArrayList<>(candidates);
            Util.shuffle(shuffled, context.getRandom());
            LinkedHashSet<String> targets = new LinkedHashSet<>(shuffled.subList(0, want));
            for (String id : this.extra) {
                if (context.getRandom().nextDouble() < this.extraChance) {
                    targets.add(id);
                }
            }

            stack.set(ModDataComponents.OBSERVER_MODEL_DATA.get(), new ObserverModelData(
                    data.type(), List.copyOf(targets), data.trainedEntities(), data.stabilityStrength(),
                    data.concept(), data.progress(), data.bioEnergy(), data.totalStability(), data.copies()));
            return stack;
        }

        @Override
        public LootItemFunctionType<RandomTrainingFunction> getType() {
            return RANDOM_TRAINING.get();
        }
    }
}
