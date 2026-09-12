package com.zhizhiwang.focal_decay.compat.jei;

import com.zhizhiwang.focal_decay.FocalDecay;
import com.zhizhiwang.focal_decay.block.ModBlocks;
import com.zhizhiwang.focal_decay.item.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.List;

/**
 * JEI 插件（可选依赖，仅在 JEI 存在时由 JEI 自行发现并加载）。
 * <p>
 * 设计要点：<b>「怎么获得」用信息页，「能派生出什么」用自定义类别</b>。
 * <ul>
 *   <li><b>信息页</b>（{@code addItemStackInfo}）：碎片与各型号模型的获取途径。这是 JEI 的原生语义——
 *       对物品按 R 就是"来源"，信息页直接显示在该物品的配方页里，不依赖催化剂方向，
 *       因此不会像自定义类别那样被归到"用途(U)"。</li>
 *   <li><b>「观测模型派生」类别</b>：原型 → 各型号。结果模型放在<b>输出槽</b>，
 *       因此对型号按 R 能查到它的来路；若放成输入槽，JEI 会当成"用途"，方向就反了。</li>
 * </ul>
 * 原版配方（原型机、空白模型、生物稳定模型、训练终端、两个特殊配方等）由 JEI 自动读取配方表展示。
 */
@JeiPlugin
public final class FocalDecayJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(FocalDecay.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new ModelDerivationCategory(guiHelper, prototypeStack()),
                // 两个自定义序列化器的配方，JEI 不会自动展示，必须显式登记
                new SpecialRecipeCategory(guiHelper, SpecialRecipeCategory.COPY_MODEL,
                        "gui.focal_decay.jei.copy_model",
                        new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get())),
                new SpecialRecipeCategory(guiHelper, SpecialRecipeCategory.FEED_FRAGMENT,
                        "gui.focal_decay.jei.feed_fragment",
                        new ItemStack(ModItems.FRAGMENT_SEMANTIC.get())));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(ModelDerivationCategory.TYPE, derivations());
        registration.addRecipes(SpecialRecipeCategory.COPY_MODEL, copyModelRecipes());
        registration.addRecipes(SpecialRecipeCategory.FEED_FRAGMENT, feedFragmentRecipes());
        registerInfoPages(registration);
    }

    /** 复制配方：可用模型 + 空白模型 → 2 份副本。三种可复制模型各一条。 */
    private static List<SpecialRecipeCategory.Entry> copyModelRecipes() {
        ItemStack blank = prototypeStack();
        List<ItemStack> blanks = List.of(blank);
        return List.of(
                copyEntry(new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get()), blanks),
                copyEntry(new ItemStack(ModItems.SEMANTIC_LOCK_MODEL.get()), blanks),
                copyEntry(new ItemStack(ModItems.GUIDED_MUTATION_MODEL.get()), blanks));
    }

    private static SpecialRecipeCategory.Entry copyEntry(ItemStack trained, List<ItemStack> blanks) {
        ItemStack result = trained.copy();
        result.setCount(2);
        return new SpecialRecipeCategory.Entry(
                List.of(List.of(trained), blanks), result, "jei.focal_decay.note.copy_model");
    }

    /** 碎片喂食配方：候选观测者 + 任意碎片 → 进度增加。七枚碎片放在同一个槽里轮播。 */
    private static List<SpecialRecipeCategory.Entry> feedFragmentRecipes() {
        List<ItemStack> fragments = List.of(
                new ItemStack(ModItems.FRAGMENT_ROSE.get()),
                new ItemStack(ModItems.FRAGMENT_THRONE.get()),
                new ItemStack(ModItems.FRAGMENT_SEMANTIC.get()),
                new ItemStack(ModItems.FRAGMENT_42MS.get()),
                new ItemStack(ModItems.FRAGMENT_CRYSTAL.get()),
                new ItemStack(ModItems.FRAGMENT_AARON.get()),
                new ItemStack(ModItems.FRAGMENT_CHENG.get()));
        return List.of(new SpecialRecipeCategory.Entry(
                List.of(List.of(new ItemStack(ModItems.OBSERVER_MODEL_CANDIDATE.get())), fragments),
                new ItemStack(ModItems.OBSERVER_MODEL_CANDIDATE.get()),
                "jei.focal_decay.note.feed_fragment"));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // 派生关系发生在训练终端与王座之上
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.TRAINING_TERMINAL.get()),
                ModelDerivationCategory.TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.THRONE_BLOCK.get()),
                ModelDerivationCategory.TYPE);
    }

    /** 原型物品（所有派生关系的起点）。 */
    static ItemStack prototypeStack() {
        return new ItemStack(ModItems.OBSERVER_MODEL_BLANK.get());
    }

    /** 各型号的派生关系。输入槽随条目变化，不能统一写成原型。 */
    private static List<ModelDerivationCategory.Entry> derivations() {
        ItemStack prototype = prototypeStack();
        return List.of(
                derivation(prototype, ModItems.SEMANTIC_LOCK_MODEL.get(),
                        ModBlocks.TRAINING_TERMINAL.get(), "jei.focal_decay.derive.semantic_lock"),
                derivation(prototype, ModItems.GUIDED_MUTATION_MODEL.get(),
                        ModBlocks.TRAINING_TERMINAL.get(), "jei.focal_decay.derive.guided"),
                derivation(prototype, ModItems.BIO_STABILIZER_MODEL.get(),
                        Items.CRAFTING_TABLE, "jei.focal_decay.derive.bio"),
                // OBSR-EX：未激活 → 已激活（起点不是原型）
                derivation(new ItemStack(ModItems.TOTAL_STABILITY_MODEL.get()),
                        ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get(),
                        ModBlocks.THRONE_BLOCK.get(), "jei.focal_decay.derive.total_activated"),
                // OBSR-3：以已激活的 OBSR-EX 为材料合成（起点不是原型）
                derivation(activatedExStack(), ModItems.OBSERVER_MODEL_CANDIDATE.get(),
                        Items.CRAFTING_TABLE, "jei.focal_decay.derive.candidate"));
    }

    private static ModelDerivationCategory.Entry derivation(
            ItemStack input, ItemLike result, ItemLike workbench, String noteKey) {
        return new ModelDerivationCategory.Entry(
                input, new ItemStack(result), new ItemStack(workbench), noteKey);
    }

    /** 已激活的 OBSR-EX（OBSR-3 的合成材料）。 */
    private static ItemStack activatedExStack() {
        return new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());
    }

    /** 碎片与易混淆物品的信息页（JEI 对物品按 R 即可见）。 */
    private static void registerInfoPages(IRecipeRegistration registration) {
        info(registration, ModItems.FRAGMENT_ROSE, "jei.focal_decay.source.rose");
        info(registration, ModItems.FRAGMENT_THRONE, "jei.focal_decay.source.throne");
        info(registration, ModItems.FRAGMENT_SEMANTIC, "jei.focal_decay.source.semantic");
        info(registration, ModItems.FRAGMENT_42MS, "jei.focal_decay.source.42ms");
        info(registration, ModItems.FRAGMENT_CRYSTAL, "jei.focal_decay.source.crystal");
        info(registration, ModItems.FRAGMENT_AARON, "jei.focal_decay.source.aaron");
        info(registration, ModItems.FRAGMENT_CHENG, "jei.focal_decay.source.cheng");

        info(registration, ModItems.OBSERVER_MODEL_BLANK, "jei.focal_decay.info.prototype");
        info(registration, ModItems.TOTAL_STABILITY_MODEL, "jei.focal_decay.info.total_inactive");
        info(registration, ModItems.BIO_STABILIZER_MODEL, "jei.focal_decay.info.bio");
        info(registration, ModItems.ANCHOR_PROTOTYPE, "jei.focal_decay.info.anchor");
        info(registration, ModBlocks.TRAINING_TERMINAL, "jei.focal_decay.info.terminal");
    }

    private static void info(IRecipeRegistration registration, ItemLike item, String key) {
        registration.addItemStackInfo(new ItemStack(item), Component.translatable(key));
    }
}
