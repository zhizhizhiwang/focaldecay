# 数据包 / 结构自动验证脚手架

这两个工具是为了让"改了数据文件之后不用手动开游戏试"成为可能。
**为什么需要它**：数据包 JSON 的错误大多不是崩溃，而是**静默失效**——
结构 JSON 少一个必填字段会让整个存档拒绝加载（这个还算响），
而结构 NBT 里坐标标签类型写错会让所有方块堆到同一个点，
`placeInWorld` 照样返回 `true`，游戏里什么都不报。这类问题只有真跑一遍才能发现。

## 1. `structuregen/GenStructure.java` — 生成结构模板 NBT

结构方块的 `.nbt` 格式是 gzip 压缩的 NBT，手写很容易踩坑（见下面的"坑"）。
这个单文件程序用游戏自己的 `NbtIo` 写，不会写错。

```powershell
# 依赖 jar 从 Gradle 缓存里凑（只需要几个 Mojang 库 + 游戏本体）
$want = @("gson-2.10.1.jar","guava-32.1.2-jre.jar","authlib-6.0.54.jar","brigadier-1.3.10.jar",
          "datafixerupper-8.0.16.jar","netty-buffer-4.1.97.Final.jar","netty-common-4.1.97.Final.jar",
          "fastutil-8.5.12.jar","commons-lang3-3.14.0.jar","slf4j-api-2.0.9.jar")
$jars = Get-ChildItem -Recurse "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" -Filter *.jar |
        Where-Object { $want -contains $_.Name } | Select-Object -ExpandProperty FullName -Unique
$cp = (@((Resolve-Path "build\moddev\artifacts\neoforge-21.1.248-merged.jar").Path) + $jars) -join ";"
java -cp $cp "tools\structuregen\GenStructure.java" "src\main\resources\data\focal_decay\structure\sample_anchor.nbt"
```

**坑（都真的踩过）**：

1. `size` 和每个方块的 `pos` 必须是 **TAG_List&lt;TAG_Int&gt;**，不是 int 数组 `[I; ...]`。
   写成 int 数组时 `CompoundTag.getList("pos", 3)` 返回空列表 → 所有方块静默堆到 (0,0,0)，
   `/place template` 还照样报成功。
2. `DataVersion` 写 1.21.1 的值 **3955**，否则会触发数据修复器。
3. 别用 `SharedConstants.getCurrentVersion()` 拿版本号 —— 那个类静态初始化要 FML，脱离游戏跑不起来。

## 2. `devtest-datapack/` — 端到端验证用数据包

放进开发服务器的世界目录：`run/world/datapacks/devtest/`（`run/` 在 .gitignore 里，
所以这里留一份母本）。

它做四件事，全部结果都通过 `say` 落到 `run/logs/latest.log`：

| 段 | 验证内容 |
|----|----------|
| A | `/place template` 单独放模板 —— 确认模板 NBT 本身能加载、坐标正确 |
| B | `/place structure devtest:anchor_test` —— 确认结构类型 + 处理器默认战利品表 |
| C | 处理器的 `loot_table` 字段 + `set_components` 稀有档（语义锁） |
| D | 唯一档（引导模型，带 `concept` / `stabilityStrength`） |
| E | **世界生成路径**：靠 `structure_set` 强制生成新区块，确认 `WorldGenRegion` 下也生效 |
| F | **末地王座**：`end_throne.nbt` 在真实世界生成里落点正确（见下） |

跑法：

```powershell
Copy-Item "tools\devtest-datapack\*" "run\world\datapacks\devtest\" -Recurse -Force
.\gradlew.bat runServer
# A~E 段看 "[devtest] done"，F 段（王座）晚几秒，等 "[devtest] F_no_legacy_platform_ok"
Select-String -Path run\logs\latest.log -Pattern 'devtest\]'
```

### F 段：王座（需要固定种子）

王座位置由世界种子决定，函数里没法算，所以**必须先把开发服务器世界固定到种子 `20260912`**：

```powershell
# run/server.properties 里 level-seed=20260912，并且删掉旧世界让它重新生成
Remove-Item "run\world" -Recurse -Force
```

坐标由 `structuregen/ThronePos.java` 算出（纯 Java，不需要游戏 jar）：

```powershell
java tools\structuregen\ThronePos.java 20260912
```

本次结果（与游戏日志 `End Throne structure start at ...` 完全一致）：

| 位置 | 坐标 | 模板内相对 |
|------|------|-----------|
| 原点（信标） | `-752 70 45` | (6,0,6) |
| `observer_core` | `-752 71 45` | (6,1,6) → 原点 +(0,1,0) |
| 王座碎片箱 | `-752 71 47` | (6,1,8) → 原点 +(0,1,2) |
| 四角柱顶末地棒 | `-758 87 39` / `-746 87 51` | (±6,17,±6) → 原点 +(±6,17,±6) |

**前三个相对位置是硬约束**：`ThroneRitualHandler` 用 `throne.offset(0,1,0)` 找核心、
`ThroneBeamRenderer` 用 ±6 / +17 画光束。重搭 `end_throne.nbt` 时必须保持，
否则那两处要同步改（`ThroneStructure` 的常量区有注释）。

**坑**：

1. **函数里抛异常会中断整个函数。** `/data get block`、`execute if data block` 在目标位置
   没有方块实体时会抛 `CommandSyntaxException`，后面的命令全部不执行。
   所以查 NBT 前一定要先 `execute if block <pos> <方块> run execute if data ...` 兜住。
   反过来说：**函数"跑一半停了"往往就是这一条**，先怀疑异常而不是逻辑。
2. `/place template` **永远不会带上自定义处理器**（`PlaceCommand` 只在 `integrity < 1.0` 时
   才 `clearProcessors().addProcessor(new BlockRotProcessor(...))`），
   所以处理器只能用 `/place structure` 或真实世界生成来验。
3. `latest.log` 在服务器进程还活着时删不掉（`Remove-Item -ErrorAction SilentlyContinue` 会
   静默失败），下一次读到的就是**上一次运行**的旧日志 —— 先杀掉 java 进程再删。
4. `server.properties` / `eula.txt` 在 `run/` 下，`eula.txt` 已置 `eula=true`。
