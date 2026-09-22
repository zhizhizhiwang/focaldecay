package com.zhizhiwang.focal_decay.data.recipe;

import com.mojang.serialization.MapCodec;
import com.zhizhiwang.focal_decay.data.ObserverModelData;
import com.zhizhiwang.focal_decay.item.ModItems;
import com.zhizhiwang.focal_decay.item.ObserverModelItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选观测者派生配方（OBSR-3，设计大纲 §3.3）：
 * <pre>
 *   O E O        O = 空白观测模型
 *   E X E        E = 末影之眼
 *   O S O        X = 已激活的 OBSR-EX（工作中的完全稳定模型）
 *                S = 下界之星
 * </pre>
 * <b>为什么是特殊配方而不是工作台 shaped 配方</b>（2026-09-17 修）：
 * shaped 配方只能产出"注册时的那个默认物品"，带不上任何组件——而这里必须把
 * <b>X 的复制代数</b>抄给结果。用副本 OBSR-EX 合成的 OBSR-3 要按代数递减训练增益
 * （见 {@link ObserverModelData#candidateGain}），代数丢了这条设计就完全不存在：
 * 以前无论用原件还是二代副本合成，练满都只要 100 点。
 * <p>
 * 代价是它不会出现在原版配方书里（与复制/注入两个特殊配方一致）；玩家在 JEI 的工作台类别里
 * 看到这条配方——由 {@code compat.jei.DeriveCandidateExtension} 提供网格，
 * 因为 JEI 默认的工作台扩展只接手非特殊配方（见该类注释）。
 */
public class DeriveCandidateRecipe extends CustomRecipe {
    private static final int SIZE = 3;

    public DeriveCandidateRecipe(CraftingBookCategory category) {
        super(category);
    }

    /** 取出参与合成的那个已激活 OBSR-EX（X 位）。 */
    private static ItemStack coreStack(CraftingInput input) {
        return input.getItem(1, 1);
    }

    /**
     * 网格上第 (x, y) 格要求的材料。
     * <p>
     * <b>这是这张配方唯一的形状定义</b>：{@link #matches} 与 JEI 展示的网格都从它取，
     * 所以"JEI 画出来的图"和"工作台真正接受的摆法"不可能对不上。
     */
    private static Ingredient materialAt(int x, int y) {
        if (x == 1 && y == 1) {
            return Ingredient.of(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());   // X
        }
        if (x == 1 && y == 2) {
            return Ingredient.of(Items.NETHER_STAR);                                // S
        }
        if ((x + y) % 2 == 1) {
            return Ingredient.of(Items.ENDER_EYE);                                  // E
        }
        return Ingredient.of(ModItems.OBSERVER_MODEL_BLANK.get());                  // O
    }

    /**
     * 供 JEI 展示的 3x3 网格（行优先，与 {@link CraftingInput} 的索引一致）。
     * <p>
     * 特殊配方默认返回空表，JEI 就只会画出一条"没有材料的配方"，也不建索引——于是
     * 对 OBSR-3 按 R、对已激活的 OBSR-EX 按 U 都查不到任何东西（2026-09-22 修）。
     * 给出真表之后，JEI 把产物放进输出槽、把材料铺进输入槽，两个方向都能查回来。
     */
    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(SIZE * SIZE, Ingredient.EMPTY);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                ingredients.set(x + y * SIZE, materialAt(x, y));
            }
        }
        return ingredients;
    }

    /** 配方表里的默认产物（JEI 的结果槽用）。真正带组件的产物在 {@link #assemble} 里生成。 */
    public ItemStack displayResult() {
        return new ItemStack(ModItems.OBSERVER_MODEL_CANDIDATE.get());
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return displayResult();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != SIZE || input.height() != SIZE) {
            return false;
        }
        // O E O / E X E / O S O：位置固定（3x3 网格里这个图形本来就占满整格）
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!materialAt(x, y).test(input.getItem(x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack core = coreStack(input);
        // 旧存档/其他途径造出的裸物品没有组件：按原件（0）处理，与复制配方同一取舍
        ObserverModelData coreData = ObserverModelItem.getData(core);
        int copies = coreData == null ? 0 : Math.max(0, coreData.copies());

        ItemStack result = new ItemStack(ModItems.OBSERVER_MODEL_CANDIDATE.get());
        ObserverModelItem.setData(result, ObserverModelData.candidate(copies));
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= SIZE && height >= SIZE;
    }

    @Override
    public RecipeSerializer<DeriveCandidateRecipe> getSerializer() {
        return (RecipeSerializer<DeriveCandidateRecipe>) ModRecipeSerializers.DERIVE_CANDIDATE.get();
    }

    public static class Serializer implements RecipeSerializer<DeriveCandidateRecipe> {
        private static final MapCodec<DeriveCandidateRecipe> CODEC =
                MapCodec.unit(() -> new DeriveCandidateRecipe(CraftingBookCategory.MISC));

        @Override
        public MapCodec<DeriveCandidateRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, DeriveCandidateRecipe> streamCodec() {
            return StreamCodec.of(
                    (buf, recipe) -> {
                    },
                    buf -> new DeriveCandidateRecipe(CraftingBookCategory.MISC)
            );
        }
    }

    /**
     * 配方自测（{@code /focaldecay mutation selftest} 的 {@code [recipe]} 一行）。
     * <p>
     * 钉住的就一件事：<b>结果必须带上所用 OBSR-EX 的复制代数</b>。这正是 2026-09-17 修掉的 bug——
     * 派生配方原本是工作台 shaped 配方，只能产出注册时的默认物品（copies 恒为 0），
     * 于是"副本合成的 OBSR-3 需要更多训练"这条设计从来没有生效过。
     */
    public static List<String> selfTest(ServerLevel level) {
        List<String> out = new ArrayList<>();
        ItemStack core = new ItemStack(ModItems.TOTAL_STABILITY_MODEL_ACTIVATED.get());
        // 二代副本的 OBSR-EX：合成出来的 OBSR-3 必须是"二代"的
        ObserverModelItem.setData(core, new ObserverModelData(ObserverModelData.TYPE_TOTAL,
                List.of(), List.of(), 1.0, "", 0, 0, true, 2));

        List<ItemStack> grid = new ArrayList<>(SIZE * SIZE);
        for (int i = 0; i < SIZE * SIZE; i++) {
            grid.add(ItemStack.EMPTY);
        }
        grid.set(0, new ItemStack(ModItems.OBSERVER_MODEL_BLANK.get()));
        grid.set(1, new ItemStack(Items.ENDER_EYE));
        grid.set(2, new ItemStack(ModItems.OBSERVER_MODEL_BLANK.get()));
        grid.set(3, new ItemStack(Items.ENDER_EYE));
        grid.set(4, core);
        grid.set(5, new ItemStack(Items.ENDER_EYE));
        grid.set(6, new ItemStack(ModItems.OBSERVER_MODEL_BLANK.get()));
        grid.set(7, new ItemStack(Items.NETHER_STAR));
        grid.set(8, new ItemStack(ModItems.OBSERVER_MODEL_BLANK.get()));

        DeriveCandidateRecipe recipe = new DeriveCandidateRecipe(CraftingBookCategory.MISC);
        CraftingInput input = CraftingInput.of(SIZE, SIZE, grid);
        boolean matches = recipe.matches(input, level);
        ItemStack result = matches ? recipe.assemble(input, level.registryAccess()) : ItemStack.EMPTY;
        ObserverModelData data = ObserverModelItem.getData(result);
        int copies = data == null ? -1 : data.copies();
        int needOriginal = ObserverModelData.requiredCandidatePoints(0);
        int needCopied = ObserverModelData.requiredCandidatePoints(2);
        boolean ok = matches && result.is(ModItems.OBSERVER_MODEL_CANDIDATE.get())
                && copies == 2 && needCopied > needOriginal;
        out.add("[recipe] OBSR-3 derivation carries the OBSR-EX copy generation: "
                + (ok ? "PASS" : "FAIL matches=" + matches + " copies=" + copies)
                + " (training needed: original " + needOriginal + ", generation 2 " + needCopied + ")");
        out.add(jeiGridSelfTest(level));
        return out;
    }

    /**
     * JEI 只认 {@link #getIngredients()} 与 {@link #getResultItem}：它照这两样画网格、建 R/U 索引。
     * 所以钉住"JEI 看到的形状 == {@link #matches} 真正接受的形状"——
     * 拿 JEI 网格里每种材料的代表物品摆一遍，必须真的能合成出来。
     */
    private static String jeiGridSelfTest(ServerLevel level) {
        DeriveCandidateRecipe recipe = new DeriveCandidateRecipe(CraftingBookCategory.MISC);
        NonNullList<Ingredient> shown = recipe.getIngredients();

        List<ItemStack> grid = new ArrayList<>(SIZE * SIZE);
        for (Ingredient ingredient : shown) {
            ItemStack[] representatives = ingredient.getItems();
            grid.add(representatives.length > 0 ? representatives[0] : ItemStack.EMPTY);
        }
        boolean matches = grid.size() == SIZE * SIZE
                && recipe.matches(CraftingInput.of(SIZE, SIZE, grid), level);
        boolean output = recipe.getResultItem(level.registryAccess())
                .is(ModItems.OBSERVER_MODEL_CANDIDATE.get());
        boolean ok = shown.size() == SIZE * SIZE && matches && output;
        return "[recipe] JEI grid == accepted pattern (9 slots shown, representative items craft,"
                + " output shown): "
                + (ok ? "PASS" : "FAIL slots=" + shown.size() + " matches=" + matches + " output=" + output);
    }
}
