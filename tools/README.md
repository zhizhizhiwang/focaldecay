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

### 失焦时钟怎么拨（回滚 / 加速）

失焦是 `(pos, worldSeed, period)` 的纯函数，没有逐方块存档，所以"世界此刻长什么样"全由 period
决定，拨指针就等于回滚——不需要保存任何历史。

```
/focaldecay period                 # 查询：storage=真实刻 display=显示刻 speed offset
/focaldecay period speed 4         # 四倍速（0.5 = 半速，0 = 冻结，负 = 倒带），上限 ±64
/focaldecay period offset -200     # 回滚 200 刻（时间继续走）
/focaldecay period set 5000        # 冻结在第 5000 刻；再用 offset 逐刻步进
/focaldecay period reset           # 回到 speed 1 / offset 0
/focaldecay period selftest        # 自测每一档，结束时自动恢复原档位
```

- 倍率同时作用于**方块失焦刻**与**实体/天气节拍**；**末日天数不受影响**（跳阶段用 `/focaldecay days`）。
- 档位**不落盘**，重启即恢复 `speed=1 / offset=0`。
- 只回滚"失焦外观"：玩家真实放置/破坏的方块、锚固化写进世界的方块、右键转换过的方块都回不去；
  实体/天气也不倒带（负倍率下它们停住）。
- 偏移超过 -128 时，超出出生周期表剪枝地平线的位置会显示成已崩坏而不是原样。
- **倍率 ≠ 1 时"保护期"是 1 个显示周期**：出生周期与闸门都在显示时钟上（2026-09-17 修正，
  见 `PROGRESS.md` §13.14）。所以 speed 7 时刚放下的方块只保护约 0.7 秒——这是刻意的，
  "一个周期"在这个档位下本来就只有那么长。以前两者不同域，倍率 >1 会完全没有保护期、
  <1 会永久不失焦。
- `/focaldecay trace` 是**服务端**开关：输出写在**服务端**日志里。联机时在服务器上执行，
  别在客户端日志里找（需要权限 2）。
- 日志里的中文是 log4j 用 JVM 默认 Locale 打的月份名，编码是 JVM 默认字符集（中文 Windows 上通常是 GBK）。
  读它用 GBK：`[System.IO.File]::ReadAllText($p, [Text.Encoding]::GetEncoding(936))`。

### 量"客户端与服务端的失焦刻差了多久"

失焦刻由 `gameTick / base_interval` 算，而客户端的 `gameTick` 是**本地自走**的
（服务端每 20 tick 才校一次），所以掉帧时客户端会跑到前面、差出一个周期——
那段时间里它会显示周期 N 而服务端按 N+1 解析。交互时客户端会把"我看到的刻"回报给服务端，
服务端在物理可能的偏差内采用它（见 `PROGRESS.md` §13.12）。

想看这个窗口实际有多大：

```
/focaldecay trace true      # 打开交互诊断
# 然后正常挖一会儿方块
/focaldecay trace false
```

日志里会出现（只在**回报确实改变了结果**时才打，所以它同时是"这个修复有没有在干活"的证据）：

```
[trace] left-click 12, 64, -30 stage=1 real=minecraft:stone target=minecraft:granite
[trace]   period echo: client period=151 server period=152 -> kept the client's, otherwise minecraft:diorite instead of minecraft:granite
```


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

`[stress]` 一项是**并发**压力测试（16 线程并发调用状态迁移映射，断言不抛异常、结果与单线程一致）。
它的规模是有讲究的：键空间必须**远超**缓存上限，否则并发阶段全是命中、什么都不验
（第一版就是这么假通过的）。有上限地等待 10 秒，退化时给出可读的 FAIL 而不是把服务器拖死。

`[sync]` 一项量的是**两端输入是否一致**（2026-09-17 新增，起因是一次真实的联机 bug）：

| 行 | 含义 |
|----|------|
| `settings packet round-trip (13 fields)` | 手写的 `MutationSettings` 编解码逐字段往返（字段串位会静默地让两端算出不同世界） |
| `prototype packet round-trip` | 原型机摘要的编解码往返（`bioActive` 由 int 改 boolean 时最容易串位） |
| `client entry (snapshot) == server entry (config)` | 服务端入口（读本端配置）与客户端入口（用同步快照）在 5 种源方块 × 3 阶段 × 64 周期上逐位比对 |
| `stage / clock / candidate-points parity` | 阶段划分、两根时钟、候选体训练点数的换算一致 |
| `client view echo accepted only within a physically possible skew` | 客户端回报的显示刻只在"时钟自走能造成的偏差"内被采用，谎报值（含 `Long.MIN_VALUE` 这类溢出陷阱）一律拒绝 |
| `client view echo decision (pos / age / skew)` | 回报的三条门槛：位置对得上、够新、偏差在界内 |
| `client view packet round-trip` | C→S 回报包的编解码往返 |

**这一项是这次修复的回归网**：只要有人把 `resolve` 的某个参数接错线、或者在快照里少同步一个量，
它立刻变红。它**不能**替代真机验证——"包到底有没有发到客户端"只能在真客户端上看（见 §3）。

`[sync]` 之外，客户端日志里还有一行值得看（它量的是"幽灵缓存的有效期判据有没有在干活"）：

```
Focal Decay: period 178 — cleared 105474 ghost entries in 631 sections (130 dropped mid-period because the real block changed), scheduled recompile
```

括号里的数字是**本周期里因为真实方块变化而作废的幽灵条目数**（挖掉/放下方块都会让它涨）。
正常游玩时它应该有数；如果它长期是 0，说明缓存又开始只按周期判有效期了——
那正是"挖掉失焦方块后，洞里 1~3 秒不出现"的成因（见 `PROGRESS.md` §13.13）。

同一段里还会跑一次 `/focaldecay refocus true` + `refocus false`：
用来确认这条调试命令已注册、翻转观测者状态不会抛异常。
**末尾故意回到 false**，所以不会把开发世界留在"已重聚焦"状态（那会让后续的失焦探针全部失效）。

以及 `/focaldecay period` 的各档 + `period selftest`，最后 `period reset`：
自测内部有 `finally` 恢复原档位，外面再 reset 一次，双保险不让开发世界留在冻结状态。

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
.\gradlew.bat runClient > run\client.log 2>&1   # 会开一个窗口，验证完直接关掉
Select-String -Path run\client.log -Pattern 'Mixing .*focal_decay.mixins'
```

> ⚠️ **必须 grep 控制台重定向文件，不能 grep `latest.log`。**
> `latest.log` 的级别是 INFO，而 mixin 的 `[mixin/]: Mixing ...` 是 **DEBUG**——
> 它只在控制台（`logLevel = DEBUG` 那条运行配置）里出现。
> 以前这里写的是"grep `run/logs/latest.log`"，那条命令**永远匹配不到任何东西**，
> 却被当成"注入成功"的证据。

NeoForge 的开发环境默认开着 mixin 的 DEBUG 日志，所以每次注入成功都会留一行：

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

### 局域网 / 专用服务器下怎么验（2026-09-17 新增）

失焦预览是客户端自己算的，所以**凡是"两端必须一致"的东西（世界种子、SERVER 配置）出了问题，
都只在真正连服务器时暴露**：单人模式下客户端能从集成服务器拿到种子，看上去一切正常。

`build.gradle` 里为此注册了第二个客户端运行配置 `clientSecond`（独立目录 `run-client/`，
并自动 `--quickPlayMultiplayer 127.0.0.1:25565`）：

```powershell
# 先把 run/world/datapacks 里的 devtest 挪开：它的 load 函数会在 60 秒后 stop 服务器
Move-Item run\world\datapacks\devtest run\devtest.bak
.\gradlew.bat runServer          > run\lan-server.log 2>&1   # 一个终端
.\gradlew.bat runClientSecond    > run\lan-client.log  2>&1   # 另一个终端
# 进世界后看客户端日志里这几行（latest.log 就有，它们是 INFO）
Select-String -Path run-client\logs\latest.log -Pattern 'server mutation settings received|cleared .* ghost entries'
```

期望看到：

```
Focal Decay: server mutation settings received (seed=20260912 interval=100 wildChance=0.25 ...) — defocus preview enabled
Focal Decay: period 156 — cleared 81475 ghost entries in 506 sections, scheduled recompile
```

- **第一行里的种子必须等于服务端世界的真实种子**（`run/server.properties` 的 `level-seed`）。
  这正是 2026-09-17 修掉的那个 bug：客户端以前在多人模式下拿不到种子、退回 `0`，
  于是两个人看到的方块不一样、挖下去掉落也对不上。
- 第二行说明幽灵确实在产出（不是被"快照未到"的保守分支挡住）。
- 顺带一提，`mutation index[...]` 那行两侧都会打；**两边的数字必须完全一样**
  （池数 / wild 方块数 / 源数），那是最直接的"两端查表一致"证据。

> 两个进程**不能共用 `run/`**：会抢 `logs/latest.log` 与 `options.txt`。这就是 `run-client/` 存在的理由
> （`.gitignore` 已忽略它）。

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


