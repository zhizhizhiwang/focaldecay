import net.minecraft.nbt.*;
import java.io.InputStream;
import java.nio.file.*;
public class NbtTop {
  public static void main(String[] a) throws Exception {
    CompoundTag root;
    try (InputStream in = Files.newInputStream(Path.of(a[0]))) { root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()); }
    System.out.println("top-level keys: " + root.getAllKeys());
    ListTag l = root.getList("entities", 10);
    System.out.println("entities = " + l.size());
    for (int i = 0; i < l.size(); i++) System.out.println("  " + l.getCompound(i));
  }
}