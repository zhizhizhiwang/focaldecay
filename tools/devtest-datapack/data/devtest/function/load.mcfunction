# 世界加载时排一次延迟任务，等主世界真正就绪后再执行
say [devtest] load tag fired, scheduling placement
schedule function devtest:place 5s
schedule function devtest:throne 10s
schedule function devtest:site 15s
