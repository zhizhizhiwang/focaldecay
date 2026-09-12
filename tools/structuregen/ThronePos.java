/**
 * 算出给定世界种子下末地王座的位置，用来在自动验证里定点探测。
 * 公式必须与 {@code ThroneStructure.thronePos} 完全一致（含 MutationHelper.mix64）。
 *
 * 运行（无游戏依赖，直接跑）：java tools/structuregen/ThronePos.java <世界种子>
 */
public final class ThronePos {

    private static final long THRONE_SALT = 0x5448524F4E45L;
    private static final int BASE_Y = 70;

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        long h = mix64(seed ^ THRONE_SALT);
        double angle = ((h >>> 32) & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        int distance = 650 + (int) ((h >>> 48) & 0xFF);
        int x = (int) Math.round(Math.cos(angle) * distance);
        int z = (int) Math.round(Math.sin(angle) * distance);

        System.out.println("seed      = " + seed);
        System.out.println("distance  = " + distance);
        System.out.println("throne    = " + x + " " + BASE_Y + " " + z + "   (原点；信标)");
        System.out.println("chunk     = " + (x >> 4) + " " + (z >> 4));
        System.out.println("template  = " + (x - 6) + " " + BASE_Y + " " + (z - 6) + "   (模板左上角)");
        System.out.println("core      = " + x + " " + (BASE_Y + 1) + " " + z + "   (observer_core)");
        System.out.println("chest     = " + x + " " + (BASE_Y + 1) + " " + (z + 2) + "   (王座碎片箱)");
        System.out.println("cornerrod = " + (x - 6) + " " + (BASE_Y + 17) + " " + (z - 6)
                + " / " + (x + 6) + " " + (BASE_Y + 17) + " " + (z + 6) + "   (四角柱顶末地棒)");
    }

    /** SplitMix64 雪崩混合，与 MutationHelper.mix64 逐字一致。 */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private ThronePos() {
    }
}
