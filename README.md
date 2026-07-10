# Minecraft Battle Music mod for Fabric
<iframe src="https://github.com/sponsors/Lemon553311-dev/button" title="Sponsor Lemon553311-dev" height="32" width="114" style="border: 0; border-radius: 6px;"></iframe>
This mod plays your imported music during mob/PVP battles.

**Version support: Minecraft 1.16.5 - 26.2 (Fabric/Forge/NeoForge)**

## Features

- **Mob battles:** starts when 5 hostile mobs are aggroed on you.
- **Heavy mode:** changes to a separate "heavy" playlist when the health drops low or a boss shows up.
- **PvP trigger:** Taking a set number damage from another player starts combat music. 
- **Boss detection:** Starts playing the heavy playlist when bosses are in range, plus optional mini-bosses (Elder Guardian, Ravager, Evoker, Piglin Brute) and any extra entity IDs configured.
- **Battle resume:** if a new fight starts right after the last, it picks the track back up where it faded.
- **Configurable everything:** detection range, thresholds, fade times, pools, and more, editable in-game (req. ModMenu and Cloth Config) or via the JSON config.

## Dependencies

1. Requires [Fabric Loader](https://fabricmc.net/use/)
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. (optional, very recommended) [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config)


## Adding your music

On first launch the mod makes a `battlemusic` folder inside your Minecraft instance directory with two subfolders:

```
Regular Battle/
Heavy Battle/
```

Add your music corresponding to the folder.

**Requires Vorbis (`.ogg`) audio files.**
## Music play conditions

- **Regular battle** starts when a number of mobs (default 5) in the detection radius (default 25) are attacking the player.
- **Heavy battle** crossfades when your health is at/below a threshold (default 6HP / 3 hearts), or a boss is nearby. Once heavy, it stays heavy until the whole fight ends.
- **PvP**: receiving damage (default 6HP / 3 hearts) within a time windows (default 5 seconds) plays combat music (the default music pool is heavy, configurable to regular, heavy or both). The timer resets every hit + the configured timeout for all battles (default 15s) 
- When there is no detected battle activity, the music continues to play for a set amount of time (default 15s) before beginning to fade out.

## License

MIT
