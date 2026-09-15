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