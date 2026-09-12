import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 一次性工具：把结构方块导出的 .nbt 打成人类可读的样子，用来确认尺寸、方块清单与关键坐标。
 *
 * 运行：java -cp <游戏jar>;<依赖jar...> DumpStructure.java <文件.nbt>
 */
public final class DumpStructure {

    private static final String KEYS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        CompoundTag root;
        try (InputStream in = Files.newInputStream(path)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }

        ListTag sizeTag = root.getList("size", 3);
        int sx = sizeTag.getInt(0);
        int sy = sizeTag.getInt(1);
        int sz = sizeTag.getInt(2);
        System.out.println("file        = " + path);
        System.out.println("DataVersion = " + root.getInt("DataVersion"));
        System.out.println("size        = " + sx + " x " + sy + " x " + sz);
        System.out.println("size tag id = " + root.get("size").getId() + " (9=TAG_List 才对, 11=TAG_Int_Array 会静默失效)");

        ListTag palette = root.getList("palette", 10);
        System.out.println("palette     = " + palette.size());
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [%2d] %s", i, entry.getString("Name")));
            if (entry.contains("Properties")) {
                CompoundTag props = entry.getCompound("Properties");
                List<String> keys = new ArrayList<>(props.getAllKeys());
                keys.sort(String::compareTo);
                sb.append(" ");
                for (String k : keys) {
                    sb.append(k).append('=').append(props.getString(k)).append(',');
                }
            }
            System.out.println(sb);
        }

        ListTag blocks = root.getList("blocks", 10);
        System.out.println("blocks      = " + blocks.size());

        // pos -> (state, nbt)
        TreeMap<Long, int[]> index = new TreeMap<>();
        List<String> withNbt = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", 3);
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            int state = b.getInt("state");
            coords.add(new int[]{x, y, z, state});
            index.put(key(x, y, z, sx, sz), new int[]{x, y, z, state});
            if (b.contains("nbt")) {
                withNbt.add("  (" + x + "," + y + "," + z + ") state=" + state + " nbt=" + b.getCompound("nbt"));
            }
        }

        System.out.println();
        System.out.println("=== 分层图（每层从上往下看，行 = z，列 = x，字符 = palette 下标）===");
        for (int y = sy - 1; y >= 0; y--) {
            System.out.println("--- y = " + y + " ---");
            StringBuilder header = new StringBuilder("      ");
            for (int x = 0; x < sx; x++) {
                header.append(x % 10);
            }
            System.out.println(header);
            for (int z = 0; z < sz; z++) {
                StringBuilder row = new StringBuilder(String.format("z=%3d ", z));
                for (int x = 0; x < sx; x++) {
                    int[] v = index.get(key(x, y, z, sx, sz));
                    row.append(v == null ? '.' : KEYS.charAt(v[3]));
                }
                System.out.println(row);
            }
        }

        System.out.println();
        System.out.println("=== 带 NBT 的方块（宝箱/方块实体）===");
        if (withNbt.isEmpty()) {
            System.out.println("  (无)");
        } else {
            withNbt.forEach(System.out::println);
        }

        System.out.println();
        System.out.println("=== 各类型方块数量 ===");
        int[] counts = new int[palette.size()];
        for (int[] c : coords) {
            counts[c[3]]++;
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                System.out.printf("  [%2d] %-45s x%d%n", i, palette.getCompound(i).getString("Name"), counts[i]);
            }
        }

        System.out.println();
        System.out.println("=== 空列（某 x,z 上完全没方块）与可疑空腔 ===");
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                boolean any = false;
                for (int y = 0; y < sy && !any; y++) {
                    any = index.containsKey(key(x, y, z, sx, sz));
                }
                if (!any) {
                    System.out.println("  全空列 x=" + x + " z=" + z);
                }
            }
        }
    }

    private static long key(int x, int y, int z, int sx, int sz) {
        return ((long) y << 40) | ((long) z << 20) | (x & 0xFFFFFL);
    }

    private DumpStructure() {
    }
}
