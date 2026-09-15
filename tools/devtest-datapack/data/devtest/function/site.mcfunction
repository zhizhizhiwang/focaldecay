# G 段：Site-CN-25（地下站点）端到端验证。
#
# 坐标表（模板内偏移见 docs/结构说明）：
#   区块(0,0)  原点(0,0)   —— 地表探针原点 (0,S,0)；站点原点 (0,y,0)，锚 (4,y+1,4)
#   区块(1,0)  原点(16,0)  —— site_fixed(y=100)：锚 (20,101,4)，箱子 (17,101,3)
#   区块(2,0)  原点(32,0)  —— 锚 (36,101,4)
#   区块(3,0)  原点(48,0)  —— 锚 (52,101,4)
#   区块(4,0)  原点(64,0)  —— 锚 (68,101,4)
#   区块(0,200) 原点(0,3200) —— 真实世界生成，锚 (4,y+1,3204)
#
# 这些区块都在出生点 11 区块半径内、上一轮已经生成过，所以不会被 structure_set
# （site_everywhere，spacing=1）反过来抢先放一座，/place 的结果是干净的。

forceload add 0 0
forceload add 16 0
forceload add 32 0
forceload add 48 0
forceload add 64 0

say [devtest] G1 /place structure：地表探针 + 站点，depth_range 换算
place structure devtest:surface_probe 0 100 0
place structure focal_decay:site_cn_25 0 100 0
function devtest:site_scan

say [devtest] G2 固定 y=100 的站点：基座模型 + 容器战利品表，定点检查
place structure devtest:site_fixed 16 100 0
execute if block 20 101 4 focal_decay:anchor_prototype run say [devtest] G2_anchor_ok
execute if block 17 101 3 minecraft:chest run say [devtest] G2_chest_ok
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{}} run say [devtest] G2_MODEL_PRESENT
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{id:"focal_decay:guided_mutation_model"}} run say [devtest] G2_id_obsr1_guided
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{id:"focal_decay:semantic_lock_model"}} run say [devtest] G2_id_obsr2_lock
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{components:{"focal_decay:observer_model_data":{concept:"focal_decay:concept/stone"}}}} run say [devtest] G2_obsr1_concept_ok
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{id:"focal_decay:observer_model_blank"}} run say [devtest] G2_UNEXPECTED_blank
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{id:"focal_decay:bio_stabilizer_model"}} run say [devtest] G2_UNEXPECTED_bio
execute if block 20 101 4 focal_decay:anchor_prototype run execute if data block 20 101 4 {Model:{id:"focal_decay:total_stability_model_activated"}} run say [devtest] G2_UNEXPECTED_ex
execute if block 17 101 3 minecraft:chest run execute if data block 17 101 3 {LootTable:"focal_decay:chests/site_cn_25"} run say [devtest] G2_loottable_ok
execute if block 17 101 3 minecraft:chest run execute if data block 17 101 3 {Items:[]} run say [devtest] G2_chest_untouched_ok
data get block 20 101 4 Model
data get block 17 101 3 LootTable

say [devtest] G3 漏斗抽走箱内战利品 —— 证明 LootTable 真被读取，而不只是写了个字符串
setblock 17 100 3 minecraft:hopper[facing=down]
setblock 17 99 3 minecraft:chest
schedule function devtest:site_loot_check 3s

say [devtest] G4 另外三座定点站点：看训练数据是不是每座都不一样
place structure devtest:site_fixed 32 100 0
place structure devtest:site_fixed 48 100 0
place structure devtest:site_fixed 64 100 0
execute if block 36 101 4 focal_decay:anchor_prototype run execute if data block 36 101 4 {Model:{}} run say [devtest] G4_anchor_2_model_ok
execute if block 52 101 4 focal_decay:anchor_prototype run execute if data block 52 101 4 {Model:{}} run say [devtest] G4_anchor_3_model_ok
execute if block 68 101 4 focal_decay:anchor_prototype run execute if data block 68 101 4 {Model:{}} run say [devtest] G4_anchor_4_model_ok
data get block 36 101 4 Model
data get block 52 101 4 Model
data get block 68 101 4 Model

say [devtest] G5 真实世界生成：新区块 0,200（site_everywhere 每区块一座），5 秒后查
forceload add 0 3200
schedule function devtest:site_worldgen 5s
function devtest:site_training
