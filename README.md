# Focal Decay · 失焦

![Build](https://github.com/zhizhizhiwang/focaldecay/actions/workflows/build.yml/badge.svg)

一个基于 **SCP-CN-2999《Observator Ex Machina》** 的 Minecraft 模组。游戏内文本中英双语。

> 观测者阵列于 10/02/2021 下线。此后，事物的语义开始随机漂移，表现为物质形态的不可逆改变。

一块石头会变成别的东西，一只牛会变成别的生物，天气也会开始不讲道理。你看到的一切都可能是假的，但你能挖到、能打开、能拿走的东西又是真的。而且失焦只会越来越快。

你要在这个持续崩坏的世界里活下去，收集上一迭代留下的语义碎片，最后把一个新的观测者送上王座。

---

## 玩法大概

- **所见即所得** —— 世界的真实方块并没有变，变的是你看得见的那一层。掉落、右键界面、中键选取全部按「看得见的那个方块」结算：视觉、交互、产出三者一致，只是与「实际」无关。
- **失焦会加速** —— 方块按固定周期结算，阶段越高命中率越高；下界与末地各有自己的池子；生物与天气到后期也会加入。已经漂移的东西不会复原。
- **局部稳定** —— 观测者基座是现场唯一可部署的稳定装置。插进一枚 OBSR 模型，就能把一片区域锁在当下的形态。基座内外的对比是这个模组最直观的一幕。
- **训练分类器** —— 空白原型放进训练终端指定方向，取出来右键方块或生物记录目标，再回终端完成训练。终端会依据记录表解析出一个*概念*和它的完备度 q。
- **五种型号** —— 语义锁定、引导突变、生物稳定、完全稳定、候选观测者。功能互不相同，不可互换；后期阶段会侵蚀其中两种的效果。
- **七枚语义碎片** —— 每一枚都对应上一迭代发生过的一个事件。全部来自一次性里程碑，没有随机箱子。
- **王座与核心** —— 末地虚空中的王座。登座仪式激活 OBSR-EX；在观测者核心安装一枚已完成的候选观测者，失焦终止。
- **现场作业手册** —— 进入世界自动发放《失焦事件现场作业手册》。装上 Patchouli 就能读，体例是上一迭代的基金会档案。

---

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 或更高 |
| Java | 21 |

### 可选依赖

- **Patchouli** —— 游戏内手册。不装就拿不到那本书。
- **JEI** —— 配方、碎片来源与模型派生关系查询。

---

## 构建

```bash
./gradlew build          # 产物在 build/libs
./gradlew runClient      # 开发环境客户端
./gradlew runServer      # 开发环境服务端
./gradlew runData        # 改过 data/ 下的 Provider 后重新生成 JSON
```

（Windows 下把 `./gradlew` 换成 `gradlew.bat`。）

本仓库由 NeoForge MDK 起步，构建走 [ModDevGradle](https://github.com/neoforged/ModDevGradle)，文档见 <https://docs.neoforged.net/>。开发环境的坑（音频后端、结构验证脚手架、Patchouli 的加载陷阱、可选依赖的类加载隔离等）都记在 [`PROGRESS.md`](PROGRESS.md) 里，动手之前值得翻一下。

---

## 仓库里的其他文档

| 文件 | 内容 |
|---|---|
| [`PROXYAI.md`](PROXYAI.md) | 模组完整技术设计大纲 |
| [`PROGRESS.md`](PROGRESS.md) | 开发进度、踩坑记录与关键约定 |
| [`TRAILER.md`](TRAILER.md) | 宣传片分幕大纲 |
| [`tools/README.md`](tools/README.md) | 结构与数据包验证脚手架 |

---

## English

**Focal Decay** is a Minecraft mod based on **SCP-CN-2999 "Observator Ex Machina"**.

The observer array went offline on 10/02/2021. Since then the semantics of things drift at random: blocks and creatures mutate irreversibly into something else, and it only gets faster. What you see is what you get — drops, right-click interactions and pick-block all resolve against the block you can actually see, whatever is really there. Build Observer Bases to hold a patch of the world still, train blank classifiers into OBSR models, recover the seven Semantic Fragments, and install a Candidate Observer at the End Throne to end the defocus for good.

- **Minecraft** 1.21.1 · **NeoForge** 21.1.248+ · **Java** 21
- Optional soft dependencies: **Patchouli** (in-game manual), **JEI** (recipe and source lookup)

---

## License

This project uses a dual-license structure:

- **Source Code**: All source code files in this repository are licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the `LICENSE-GPL-3.0` file for the full license text.
- **Documentation and Art Assets**: All documentation (e.g., `.md` files) and artistic assets (e.g., images, graphics) are licensed under the **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** license. See the `LICENSE-CC-BY-SA-4.0` file for the full license text.

In-game text, the field manual and other creative content fall under the CC BY-SA 4.0 half of this split. Both licenses carry a share-alike clause: anything redistributed on top of this mod has to keep the same terms.

Note that the repository uses Mojang's official mappings (via Parchment). Those names are covered by a separate license — see <https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>.

### Attribution

The text and creative direction of this mod draw heavily on **SCP-CN-2999 "Observator Ex Machina"** by **DouglasLiu**, published on the SCP Foundation Chinese Branch under **CC BY-SA 3.0**.

- Original (Chinese): <https://scp-wiki-cn.wikidot.com/scp-cn-2999>
- English translation: <http://scp-int.wikidot.com/scp-cn-2999>

Terms (defocus, observer array, OBSR, semantic fragments, the Throne), characters (Dr. Cheng Jiuzhang, O5-1 Aaron Siegel) and several quoted lines are taken directly from that article. The adaptation is released under a later version of the same license, CC BY-SA 4.0, as CC BY-SA 3.0 permits.

This is a fan work and is not affiliated with the SCP Foundation or any of its branches.
