# Runs once on world load (minecraft:load tag). Makes screenshots deterministic: full daylight,
# no day/night or weather cycle (Forge otherwise spawns into night, hiding the observed player).
# This is the 1.21+ ("function", singular) copy; the 1.20.1 copy lives under "functions/".
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
weather clear
time set day
