# Runs once on world load (minecraft:load tag). Makes screenshots deterministic: full daylight,
# no day/night or weather cycle (Forge otherwise spawns into night, hiding the observed player).
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
weather clear
time set day
