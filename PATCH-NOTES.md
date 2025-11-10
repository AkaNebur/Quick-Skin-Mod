# Update - 2.5.0

## News
* **__Support for Fabric__**
> Finally, the mod now supports Fabric, __and support for NeoForge (all versions) is coming soon.__
* **Configurable key to quickly open the menu**
> It is set to None by default to avoid conflicts with other mods. Since the menu is easy to open manually, this seemed like the best default, but you can configure it if you prefer.
* **Player preview shows your equipment**
> If you have items in your hand or armor on, it will now be visible in the player preview.
* **New background for the skins and capes menu**
> Added a new star background to the skins and capes menu.
* **3D Skin Layer support in Model Preview**
> Now the preview model shows the 3D skin layers effect if you have that mod installed.
> -# *Thx @brosquito for the idea!*
* **Ability to resize and move the player preview**
> Now you can grab the player preview by holding down the left mouse button to move it wherever you want, and you can use the mouse wheel to resize it.
> -# *Thx @anrelayouter for the idea!*
* **Server-side cooldown for skin changes**
> Now you can set a minimum number of seconds between skin changes at the server level to prevent players from spamming skin changes.
> -# *Thx @earlylover for the idea!*

## Fixes
* **Cape rendering bug**
> In some cases, custom capes were being cut off. This has been fixed.
> -# *Thx @disc_otherside for reporting the issue!*

## Internal Changes
* **Removed dependency on Geckolib**
> The mod no longer uses anything from the Geckolib dependency, so it is no longer required.
* ⚠️ **__Now the mod requires Architectury to work__**
> This library is now required so that I can continue developing the mod across all versions and mod loaders.
* ⚠️ **The configuration system has changed**
> Now it is no longer a .toml file, but a .json file, so it can be the same in Forge, Fabric, and Neoforge. This means you will need to reconfigure your settings if you had changed anything. I am also working to make all relevant configurations editable from the mod's own interface.

---

🎭 **Quick Skin**

## Download the Quick Skin mod → [Modrinth](https://modrinth.com/mod/quick-skin) or [Curseforge](https://www.curseforge.com/) *(Under manual review)*