# Mutation system self-check (2026-09-15). Function output is swallowed by the game,
# so ModCommands#report redirects to the server log when there is no player source.
say [devtest] mutation checks begin
focaldecay mutation audit
focaldecay mutation selftest
focaldecay mutation at
say [devtest] mutation checks end
# Refocus toggle (2026-09-16): proves /focaldecay refocus is registered and that flipping
# the observer state is harmless. Deliberately ends back at false so the dev world is unchanged.
say [devtest] refocus toggle
focaldecay refocus true
focaldecay refocus false
say [devtest] refocus toggle done
# Period clock (2026-09-16): query, every knob, then the self-test which restores the clock itself.
say [devtest] period clock
focaldecay period
focaldecay period speed 4
focaldecay period offset -20
focaldecay period set 5000
focaldecay period selftest
focaldecay period reset
focaldecay period
say [devtest] period clock done