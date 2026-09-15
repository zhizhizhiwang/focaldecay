# 数据包 / 结构自动验证脚手架

这两个工具是为了让"改了数据文件之后不用手动开游戏试"成为可能。
**为什么需要它**：数据包 JSON 的错误大多不是崩溃，而是**静默失效**——
结构 JSON 少一个必填字段会让整个存档拒绝加载（这个还算响），
而结构 NBT 里坐标标签类型写错会让所有方块堆到同一个点，
`placeInWorld` 照样返回 `true`，游戏里什么都不报。这类问题只有真跑一遍才能发现。

## 1. `structuregen/` — 结构模板 NBT 的生成与检查

### `GenStructure.java` — 生成结构模板 NBT

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

### `DumpStructure.java` — 把 `.nbt` 打成人类可读的样子

打印尺寸、调色板、逐层 ASCII 图（字符 = 调色板下标）、带 NBT 的方块、各类型数量、全空列。
拿不准某个结构里到底有什么、关键方块在哪个坐标时用它，比在游戏里数格子快得多。

```powershell
java -cp $cp "tools\structuregen\DumpStructure.java" "src\main\resources\data\focal_decay\structure\site_cn_25.nbt"
```

### `NbtTop.java` — 只看顶层键与实体表

`DumpStructure` 只打方块，**不打印 `entities`**。结构里带物品展示框/画/盔甲架时，
问题往往出在实体表上（例如"坐标对不上"的报错），这时用这个：

```powershell
java -cp $cp "tools\structuregen\NbtTop.java" "src\main\resources\data\focal_decay\structure\site_cn_25.nbt"
```

**坑（都真的踩过）**：

1. `size` 和每个方块的 `pos` 必须是 **TAG_List&lt;TAG_Int&gt;**，不是 int 数组 `[I; ...]`。
   写成 int 数组时 `CompoundTag.getList("pos", 3)` 返回空列表 → 所有方块静默堆到 (0,0,0)，
   `/place template` 还照样报成功。
2. `DataVersion` 写 1.21.1 的值 **3955**，否则会触发数据修复器。
3. 别用 `SharedConstants.getCurrentVersion()` 拿版本号 —— 那个类静态初始化要 FML，脱离游戏跑不起来。
4. **结构里带悬挂实体（物品展示框/画）时，放置会刷 ERROR。** 结构方块保存实体时记的是
   绝对坐标 `TileX/TileY/TileZ`，而放置时只重写 `Pos`，于是
   `BlockAttachedEntity.readAdditionalSaveData` 发现两者差 16 格以上就报
   `Block-attached entity at invalid position`。**实体位置是对的**（该分支只是不覆盖 `Pos`），
   属原版噪声，但它会在每个实例化点刷一行 ERROR。


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
| G | **Site-CN-25 地下站点**：`depth_range` 埋深、基座随机 OBSR-1/-2、容器战利品表（见下） |
| H | **突变系统自检**：`/focaldecay mutation audit` + `selftest`（见下） |

跑法：

```powershell
Copy-Item "tools\devtest-datapack" "run\world\datapacks\devtest" -Recurse -Force
.\gradlew.bat runServer
# 服务器会在 load 后 60 秒自己 stop（devtest:stop），所以这条命令会正常返回
Select-String -Path run\logs\latest.log -Pattern 'devtest\]|mutation\]|selftest\]'
```

> **自终止**：`load.mcfunction` 最后 schedule 了 `devtest:stop`（内容就是 `stop`），
> 让无头验证跑完自己退出，不用再手工找 Java 进程杀掉。这依赖
> `run/server.properties` 里的 `function-permission-level=4`（函数默认权限是 2，跑不了 `stop`）。
> 想让开发服务器一直开着，删掉那行 schedule 即可。

> 每个函数都以 `say [devtest] <探针名>` 结尾，是因为**函数里命令的输出是被抑制的**：
> `/data get` 之类只会返回结果、不会打到日志，所以只能用 `say` 把结论捅出来。
> 也正因如此，任何**抛异常**的命令（例如目标位置没有方块实体时的 `/data get block`）
> 会中断整个函数 —— 查 NBT 前先 `execute if block <pos> <方块>` 兜一层。
> 本模组自己的命令（`/focaldecay mutation ...`）走 `ModCommands#report`：
> 执行者是玩家就发聊天栏，没有玩家（函数/控制台）就写服务器日志，因此两者都能拿到结果。

### H 段：突变系统自检

`/focaldecay mutation audit` 量的是**结构性质**，全为 0 才算通过：

| 字段 | 含义 |
|------|------|
| `frozen` | 能作为目标出现、但候选集不足 2 个的方块数 —— 非 0 就意味着世界上会出现永久冻结方块 |
| `asymmetric` | `A→B` 成立但 `B→A` 不成立的边数 |
| `crossClass` | 目标与源形态类不同的边数（几何会被破坏） |
| `sources with no inbound edge` | 只能变出去、不会被变回来的源（单向不等于冻结，数量异常大说明某个形态族被孤立了） |

`/focaldecay mutation selftest` 量的是**行为**：确定性（重复求值逐位相同）、不收敛
（固定位置扫 256 个周期看不同目标数）、对称、水方块属性迁移、以及热路径 ns/次。
`/focaldecay mutation at` 打印脚下位置的真实方块、形态类、候选数量与当前可见目标，
排查"这个方块为什么不变 / 为什么变成了那个"时最直接。

同一段里还会跑一次 `/focaldecay refocus true` + `refocus false`：
用来确认这条调试命令已注册、翻转观测者状态不会抛异常。
**末尾故意回到 false**，所以不会把开发世界留在"已重聚焦"状态（那会让后续的失焦探针全部失效）。

### G 段：Site-CN-25 地下站点

覆盖三件新东西：`depth_range`（按地表高度埋到地下）、`focal_decay:random_training`
（给抽出来的 OBSR 模型补随机训练数据）、`focal_decay:container_loot`（给模板里的容器填战利品表）。

**G1/G5 的埋深验证怎么做的**：结构落点的 y 是算出来的，函数里没有变量，没法直接算差值。
所以先用 `devtest:surface_probe`（`y_offset: 0` 的 `single_template`，原点方块是
`focal_decay:throne_block`）把**同一个区块**的 `getFirstFreeHeight(WORLD_SURFACE_WG)` 读出来，
再扫站点锚所在的列，两个数字都落到日志里，人工做减法：

```
G1_surface_y=63   G1_anchor_y=53   → 模板原点 52 → 埋深 = 63 - 52 - 6 = 5   ∈ [1,8] ✓
G5_surface_y=63   G5_anchor_y=50   → 模板原点 49 → 埋深 = 63 - 49 - 6 = 8   ∈ [1,8] ✓
```

`site_scan.mcfunction` 就是这两列（共 4 条列）的逐格扫描，**是机器生成的**，
改了测试区坐标后按下面重新生成：

```powershell
$sb = [System.Text.StringBuilder]::new()
foreach ($y in -64..160) {
  [void]$sb.AppendLine("execute if block 0 $y 0 focal_decay:throne_block run say [devtest] G1_surface_y=$y")
  [void]$sb.AppendLine("execute if block 4 $y 4 focal_decay:anchor_prototype run say [devtest] G1_anchor_y=$y")
  [void]$sb.AppendLine("execute if block 0 $y 3200 focal_decay:throne_block run say [devtest] G5_surface_y=$y")
  [void]$sb.AppendLine("execute if block 4 $y 3204 focal_decay:anchor_prototype run say [devtest] G5_anchor_y=$y")
}
$path = "tools\devtest-datapack\data\devtest\function\site_scan.mcfunction"
[System.IO.File]::WriteAllText($path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
```

（必须用 `WriteAllText` + `UTF8Encoding($false)`：`Set-Content -Encoding utf8` 会写 BOM，
BOM 会让函数第一行解析失败。生成的 `.mcfunction` 前几个字节应该是 `23 20` = `# `。）

**`random_training` 怎么断言的**：NBT 匹配里**列表是"包含"语义**
（`NbtUtils.compareNbt` 的 `compareListTag=true` 分支：pattern 里每个元素只要在目标列表里
找得到就算命中），所以

- `{trainedTargets:["minecraft:stone","minecraft:dirt"]}` = 两条都在；
- `{trainedTargets:[]}` = **列表为空**（空 pattern 只匹配空列表）→ 反过来用 `unless data`
  就能断言"非空"；
- `{Items:[{...}]}` = 箱子里**任意一格**命中 → 一个箱子能一次性代表很多次抽样。

于是 G6c/G7 用 `/loot insert` 把生产表抽 20 次塞进一个箱子，再按上面三条语义断言
"八种物品都够得着""每一枚都带训练数据""三种站点建材都出现过"。

**G 段的日志噪声**：`site_everywhere` 是 `spacing: 1` 的 structure_set（不这样没法保证
新生成的区块里必定有一座站点），于是开发服务器启动时把新生成区块全铺上了站点 ——
日志里会出现成百上千条 `buried:` 与 `Block-attached entity at invalid position`。用
`Select-String -Path run\logs\latest.log -Pattern 'devtest\]'` 过滤即可。

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
5. **`runServer` 不一定随 Gradle 任务一起结束**：`job_kill` 杀掉的是 pwsh 包装进程，
   Java 子进程可能还活着并**锁着 `run/logs/*.log`**，下一次启动会以
   `Failed to start the minecraft server: IOException: 另一个程序已锁定文件的一部分` 失败。
   现在的 `devtest:stop` 会自动收尾；真遇到残留就 `Get-Process java | Stop-Process`（留下的是 Gradle 守护进程也无妨）。

## 3. 客户端/渲染侧怎么验证

面剔除、幽灵替换、mixin 注入这些只能在客户端上验，服务器端一行都跑不到。做法：

```powershell
.\gradlew.bat runClient   # 会开一个窗口，验证完直接关掉
Select-String -Path run\logs\latest.log -Pattern 'focal_decay.mixins|InvalidInjection|MixinApplyError'
```

NeoForge 的开发环境**默认开着 mixin 的 DEBUG 日志**，所以每次注入成功都会留一行：

```
[mixin/]: Mixing client.BlockShouldRenderFaceMixin from focal_decay.mixins.json into net.minecraft.world.level.block.Block
```

**没看到这一行 = 没注入**（`required: true` + `defaultRequire: 1` 时注不进去会直接崩，
所以"没崩但也没这行"通常意味着目标方法签名对不上，得去核对字节码）。

改 `@Redirect` / `@Inject` 之前，**先用 `javap` 确认目标方法真的长那样**，
别照着别人的教程写签名：

```powershell
javap -p -c -cp build\moddev\artifacts\neoforge-21.1.248-merged.jar net.minecraft.world.level.block.Block > build\block.txt
# 然后在 build\block.txt 里找目标方法与它的 INVOKE
```

本项目**没有启用 Mixin 注解处理器**（构建里没有 `*refmap*`），所以注入点写错在编译期
是发现不了的，只有客户端启动时才会炸。`javap` 那一步不能省。

### 重聚焦之后的客户端表现怎么验

遮罩淡出、失焦预览清空、实体突变停止这些都挂在"观测者是否在线"上，而正常流程要
练一个候选观测者模型 → 装进核心 → 等 100 tick 才能到达那个状态。测试时直接：

```
/focaldecay refocus          # 等于重聚焦；再执行一次 /focaldecay refocus false 回到失焦
```

客户端日志会留一行 `Focal Decay: observer veil removed after refocus`，
用来确认淡出确实走到了"卸载 PostChain"那一步（而不是只把强度设成 0）。
淡出时长由 `postProcessRefocusFadeTicks` 决定（默认 50 tick = 2.5 秒，0 = 立刻切掉），
想恢复"一直抖"的旧行为就把 `postProcessAfterRefocus` 置 true。


