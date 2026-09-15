# devtest: fired once on world load, schedules delayed probes
say [devtest] load tag fired, scheduling probes
schedule function devtest:place 5s
schedule function devtest:throne 10s
schedule function devtest:site 15s
schedule function devtest:mutation 20s
schedule function devtest:stop 60s