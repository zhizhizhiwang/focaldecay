import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性工具：手写一份结构方块格式的 .nbt，用来验证 focal_decay:single_template 与
 * AnchorModelProcessor（含 /place structure）。生成的坐标原点 = 模板角点，
 * 与结构方块保存出来的文件格式一致（size / palette / blocks / entities / DataVersion）。
 *
 * 运行：java -cp build/moddev/artifacts/neoforge-21.1.248-merged.jar GenStructure.java <输出路径>
 */
public final class GenStructure {

    /** 1.21.1 的 world_version（见游戏 jar 的 version.json）。写对才不会触发数据修复器。 */
    private static final int DATA_VERSION = 3955;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);

        int sizeX = 7;
        int sizeY = 3;
        int sizeZ = 7;

        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<CompoundTag> blocks = new ArrayList<>();

        // 基座平台：7x7 黑曜石王座砖 + 中心放观测者基座
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                addBlock(blocks, paletteIndex, x, 0, z, "focal_decay:throne_block", null);
            }
        }
        // 中心：观测者基座（处理器会把随机模型塞进去）
        addBlock(blocks, paletteIndex, 3, 1, 3, "focal_decay:anchor_prototype", null);
        // 四角柱脚
        for (int[] c : new int[][]{{0, 0}, {0, 6}, {6, 0}, {6, 6}}) {
            addBlock(blocks, paletteIndex, c[0], 1, c[1], "focal_decay:throne_block", null);
            addBlock(blocks, paletteIndex, c[0], 2, c[1], "minecraft:end_rod", null);
        }

        ListTag palette = new ListTag();
        for (String name : paletteIndex.keySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", name);
            palette.add(entry);
        }

        ListTag blockList = new ListTag();
        blockList.addAll(blocks);

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", DATA_VERSION);
        root.put("size", intList(sizeX, sizeY, sizeZ));
        root.put("palette", palette);
        root.put("blocks", blockList);
        root.put("entities", new ListTag());

        Files.createDirectories(out.getParent());
        NbtIo.writeCompressed(root, out);
        System.out.println("wrote " + out + " (" + Files.size(out) + " bytes), DataVersion=" + DATA_VERSION);
    }

    private static void addBlock(List<CompoundTag> blocks, Map<String, Integer> paletteIndex,
                                 int x, int y, int z, String name, CompoundTag nbt) {
        int state = paletteIndex.computeIfAbsent(name, k -> paletteIndex.size());
        CompoundTag block = new CompoundTag();
        block.put("pos", intList(x, y, z));
        block.putInt("state", state);
        if (nbt != null) {
            block.put("nbt", nbt);
        }
        blocks.add(block);
    }

    /**
     * 关键：结构模板里的 {@code size} / {@code pos} 是 <b>TAG_List&lt;TAG_Int&gt;</b>，
     * 不是 int 数组 {@code [I; ...]}。
     * <p>
     * 用 int 数组时 {@code CompoundTag.getList("pos", 3)} 会返回空列表（类型对不上），
     * {@code getInt(0..2)} 全读成 0 —— 于是所有方块静默堆到同一个坐标，
     * 既不报错也不警告（{@code placeInWorld} 照样返回 true）。
     */
    private static ListTag intList(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }

    private GenStructure() {
    }
}
