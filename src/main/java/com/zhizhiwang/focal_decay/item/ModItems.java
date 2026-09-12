package com.zhizhiwang.focal_decay.item;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.data.ModDataComponents;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FocalDecay.MODID);

    // 方块物品
    public static final DeferredItem<BlockItem> ANCHOR_PROTOTYPE =
            ITEMS.registerSimpleBlockItem("anchor_prototype", ModBlocks.ANCHOR_PROTOTYPE);
    public static final DeferredItem<BlockItem> TRAINING_TERMINAL =
            ITEMS.registerSimpleBlockItem("training_terminal", ModBlocks.TRAINING_TERMINAL);
    public static final DeferredItem<BlockItem> OBSERVER_CORE =
            ITEMS.registerSimpleBlockItem("observer_core", ModBlocks.OBSERVER_CORE);
    public static final DeferredItem<BlockItem> THRONE_BLOCK =
            ITEMS.registerSimpleBlockItem("throne_block", ModBlocks.THRONE_BLOCK);

    // 观测模型。
    // ⚠️ 每一件都必须在注册时带上默认 ObserverModelData：创造栏/JEI/`/give` 拿到的物品不会经过
    // 任何"设置数据"的流程，若组件为 null，往下走就会出事——例如把裸的已激活 EX 放进基座，
    // MutationPoolManager.radiusFor(null) 里的 data.type() 会直接 NPE 崩游戏。
    public static final DeferredItem<ObserverModelItem> OBSERVER_MODEL_BLANK =
            ITEMS.register("observer_model_blank", () -> observerModel(ObserverModelData.blank()));
    public static final DeferredItem<ObserverModelItem> SEMANTIC_LOCK_MODEL =
            ITEMS.register("semantic_lock_model", () -> observerModel(ObserverModelData.blank()));
    public static final DeferredItem<ObserverModelItem> GUIDED_MUTATION_MODEL =
            ITEMS.register("guided_mutation_model", () -> observerModel(ObserverModelData.blank()));
    public static final DeferredItem<ObserverModelItem> BIO_STABILIZER_MODEL =
            ITEMS.register("bio_stabilizer_model", () -> observerModel(ObserverModelData.bio()));
    public static final DeferredItem<ObserverModelItem> TOTAL_STABILITY_MODEL =
            ITEMS.register("total_stability_model", () -> observerModel(ObserverModelData.blank()));
    /** 已激活的完全稳定模型。默认数据即"原件"（copies = 0）。 */
    public static final DeferredItem<ObserverModelItem> TOTAL_STABILITY_MODEL_ACTIVATED =
            ITEMS.register("total_stability_model_activated",
                    () -> observerModel(ObserverModelData.activatedTotalStability()));
    public static final DeferredItem<ObserverModelItem> OBSERVER_MODEL_CANDIDATE =
            ITEMS.register("observer_model_candidate", () -> observerModel(ObserverModelData.candidate()));

    // 语义碎片（lore = 氛围文本，source = 获取来源，两者都走 lang key）
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_ROSE =
            ITEMS.register("semantic_fragment_rose", () -> fragment(
                    "lore.focal_decay.fragment_rose", "jei.focal_decay.source.rose"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_THRONE =
            ITEMS.register("semantic_fragment_throne", () -> fragment(
                    "lore.focal_decay.fragment_throne", "jei.focal_decay.source.throne"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_SEMANTIC =
            ITEMS.register("semantic_fragment_semantic", () -> fragment(
                    "lore.focal_decay.fragment_semantic", "jei.focal_decay.source.semantic"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_42MS =
            ITEMS.register("semantic_fragment_42ms", () -> fragment(
                    "lore.focal_decay.fragment_42ms", "jei.focal_decay.source.42ms"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_CRYSTAL =
            ITEMS.register("semantic_fragment_crystal", () -> fragment(
                    "lore.focal_decay.fragment_crystal", "jei.focal_decay.source.crystal"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_AARON =
            ITEMS.register("semantic_fragment_aaron", () -> fragment(
                    "lore.focal_decay.fragment_aaron", "jei.focal_decay.source.aaron"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_CHENG =
            ITEMS.register("semantic_fragment_cheng", () -> fragment(
                    "lore.focal_decay.fragment_cheng", "jei.focal_decay.source.cheng"));

    private static SemanticFragmentItem fragment(String loreKey, String sourceKey) {
        return new SemanticFragmentItem(new Item.Properties().stacksTo(16), loreKey, sourceKey);
    }

    /** 观测模型物品：一律带默认模型数据，见上面的注释。 */
    private static ObserverModelItem observerModel(ObserverModelData defaultData) {
        return new ObserverModelItem(new Item.Properties().stacksTo(1)
                .component(ModDataComponents.OBSERVER_MODEL_DATA.get(), defaultData));
    }

    private ModItems() {
    }
}
