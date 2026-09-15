# Mutation system self-check (2026-09-15). Function output is swallowed by the game,
# so ModCommands#report redirects to the server log when there is no player source.
say [devtest] mutation checks begin
focaldecay mutation audit
focaldecay mutation selftest
focaldecay mutation at
say [devtest] mutation checks end