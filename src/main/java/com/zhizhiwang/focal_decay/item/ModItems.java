package com.zhizhiwang.focal_decay.item;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FocalDecay.MODID);

    // 方块物品
    public static final DeferredItem<BlockItem> STABLE_ANCHOR =
            ITEMS.registerSimpleBlockItem("stable_anchor", ModBlocks.STABLE_ANCHOR);
    public static final DeferredItem<BlockItem> MUTATION_CONTROLLER =
            ITEMS.registerSimpleBlockItem("mutation_controller", ModBlocks.MUTATION_CONTROLLER);
    public static final DeferredItem<BlockItem> OBSERVER_CORE =
            ITEMS.registerSimpleBlockItem("observer_core", ModBlocks.OBSERVER_CORE);

    // 语义碎片
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_ROSE =
            ITEMS.register("semantic_fragment_rose", () -> fragment("lore.focal_decay.fragment_rose"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_THRONE =
            ITEMS.register("semantic_fragment_throne", () -> fragment("lore.focal_decay.fragment_throne"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_SEMANTIC =
            ITEMS.register("semantic_fragment_semantic", () -> fragment("lore.focal_decay.fragment_semantic"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_42MS =
            ITEMS.register("semantic_fragment_42ms", () -> fragment("lore.focal_decay.fragment_42ms"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_CRYSTAL =
            ITEMS.register("semantic_fragment_crystal", () -> fragment("lore.focal_decay.fragment_crystal"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_AARON =
            ITEMS.register("semantic_fragment_aaron", () -> fragment("lore.focal_decay.fragment_aaron"));
    public static final DeferredItem<SemanticFragmentItem> FRAGMENT_CHENG =
            ITEMS.register("semantic_fragment_cheng", () -> fragment("lore.focal_decay.fragment_cheng"));

    // 重建的观测协议
    public static final DeferredItem<Item> REBUILT_OBSERVER_PROTOCOL =
            ITEMS.register("rebuilt_observer_protocol", () -> new Item(new Item.Properties().stacksTo(1)));

    private static SemanticFragmentItem fragment(String loreKey) {
        return new SemanticFragmentItem(new Item.Properties().stacksTo(16), loreKey);
    }

    private ModItems() {
    }
}
