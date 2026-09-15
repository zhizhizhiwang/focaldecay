# G5：真实世界生成路径（WorldGenRegion）下的 Site-CN-25。
# 区块 0,200 是新生成的，site_everywhere（spacing=1）保证它里面必定有一座站点。
# 用同一个地表探针读出该区块的地表高度，再扫两列把站点埋深算出来。

forceload add 0 3200
place structure devtest:surface_probe 0 100 3200
function devtest:site_scan
say [devtest] G5_scanned
