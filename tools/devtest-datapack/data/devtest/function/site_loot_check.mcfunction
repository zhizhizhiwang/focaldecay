# G3：箱子里的 LootTable 是否真的会被读取。
# 判定方式：漏斗会调用 getItem()，而 RandomizableContainerBlockEntity.getItem() 第一句就是
# unpackLootTable(null) —— 只要漏斗能把物品抽出来，就说明 LootTable 被识别并填进了容器。
# 物品被搬进下方的第二个箱子，所以这里查下面那个。

execute if block 17 99 3 minecraft:chest run execute if data block 17 99 3 {Items:[{}]} run say [devtest] G3_loot_rolled_ok
execute if block 17 101 3 minecraft:chest run execute if data block 17 101 3 {LootTable:"focal_decay:chests/site_cn_25"} run say [devtest] G3_UNEXPECTED_loottable_survived_unpack
data get block 17 99 3 Items
say [devtest] done
