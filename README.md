# Battle Music for Fabric

This mod plays your imported music during mob/PVP battles.
**Version support: Minecraft 26.1 and 26.2**

## Features

- **Mob battles:** starts when 5 hostile mobs are aggroed on you.
- **Heavy mode:** changes to a separate "heavy" playlist when the health drops low or a boss shows up.
- **PvP trigger:** Taking a set number damage from another player starts combat music. 
- **Boss detection:** Starts playing the heavy playlist when bosses are in range, plus optional mini-bosses (Elder Guardian, Ravager, Evoker, Piglin Brute) and any extra entity IDs configured.
- **Battle resume:** if a new fight starts right after the last, it picks the track back up where it faded.
- **Configurable everything:** detection range, thresholds, fade times, pools, and more, editable in-game (req. ModMenu and Cloth Config) or via the JSON config.

## Dependencies

1. Requires [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1 or 26.2.
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. (optional) [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config)


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

## Config

Edit in-game via Mod Menu, or by manually at `config/battlemusic.json`.

| Option                       | Default | What it does                                                                 |
|------------------------------|---------|------------------------------------------------------------------------------|
| `aggroMobCount`              | 5       | Aggroed mobs needed to start a regular battle                                |
| `detectionRadius`            | 25      | Blocks to scan for hostile mobs                                              |
| `heavyHealthThreshold`       | 6.0     | HP at/below which a battle goes heavy (2 HP = 1 heart)                       |
| `playerDamageThresholdHp`    | 6.0     | Player damage within the window that triggers PvP music                      |
| `playerDamageWindowSeconds`  | 5.0     | Rolling window the PvP damage is summed over                                 |
| `playerCombatTimeoutSeconds` | 15.0    | How long PvP music holds after the last player hit                           |
| `playerCombatMusicPool`      | HEAVY   | Music pool the PvP trigger uses: `HEAVY`, `REGULAR`, or `BOTH`               |
| `heavyAggroMobCount`         | 15      | Aggroed mobs that force heavy on their own (a big swarm is always heavy)     |
| `bossRadius`                 | 48      | Scan radius for bosses                                                       |
| `includeMiniBosses`          | true    | Treat mini-bosses (Ravager, Evoker, etc.) as bosses                          |
| `extraBossIds`               | []      | Extra entity IDs that force heavy                                            |
| `fadeOutDelaySeconds`        | 15.0    | Time without battle before the fade-out starts                               |
| `fadeOutDurationSeconds`     | 7.0     | Length of the fade-out                                                       |
| `fadeInDurationSeconds`      | 3.0     | Length of the fade-in when a battle starts                                   |
| `battleResumeEnabled`        | true    | Resume the last track if a new fight starts soon after                       |
| `resumeWithinSeconds`        | 30.0    | Time windows where the music can continue where it left off after fading out |
| `enabled`                    | true    | Disable or enable battle music on/off                                        |
| `debug`                      | false   | Enable debug logging                                                         |

## Credits

- Lemon553311
- user2378
- Dragon6555
- uxokpro1234

## License

MIT LICENSE

## Bragging
![img.png](bragging.png)
