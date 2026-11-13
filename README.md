# Lumen (Forge 1.12.2)

Client-side lighting mod built to work in modpacks: every feature either goes through the game's
own light engine or draws its own layer, so it works no matter which chunk renderer is installed
(vanilla, OptiFine, Nothirium, Vintagium, Celeritas...).

## Features
- **Dynamic lights** – held torches and light blocks, dropped glowing items, item frames, burning
  mobs, blazes, fireballs, flaming weapons light up the world. Like Moving Light Sources, the light
  goes into the real light data (so walls block it and every renderer shows it), but client-side:
  no fake blocks are placed in your world. Extra items/entities are configurable.
- **Emissive textures** – glowing ores. Uses resource-pack emissive textures in OptiFine format
  (`diamond_ore_e.png` next to `diamond_ore.png`, any block, custom suffix via
  `optifine/emissive.properties`); ores without one get an automatic glow mask. Reloads with packs.
- **Block light with color and shadows** – torches warm, redstone red, sea lanterns cyan, lava orange,
  portals purple; unlisted (modded) lamps get their color from their texture (`vibrance` makes colors
  more vivid). Each light is traced through the blocks on a background thread: surfaces that see the
  light get its color, blocks in the way cast real shadows, sides facing away go dark.
- **Sun shadows** – trees, buildings and cliffs cast shadows traced through the blocks on a
  background thread; partial blocks (slabs, stairs, fences) shade by their shape; faces turned from
  the sun are shaded; shade is slightly cool like sky light.

Press **K** to turn Lumen on and off (rebindable in Controls → Lumen).

**Requires [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter).** Settings: `config/lumen-lighting.cfg`.

## Building / testing
Every push builds on GitHub Actions and runs the release jar in the real game twice — plain, and
with Nothirium replacing the chunk renderer — taking Lumen on/off screenshots of test scenes. Results
land on the `test-results` and `test-results-modpack` branches; the jar is under the run's
**Artifacts**. Locally: `./gradlew build`.

Build setup is CleanroomMC's TemplateDevEnv (RetroFuturaGradle), see `LICENSE-TemplateDevEnv`.
The dynamic-light approach is inspired by Moving Light Sources (GPL-3.0); no code was copied.
