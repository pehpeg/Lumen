# Lumen — shader look, no shaders (Forge 1.12.2)

Client-only mod. Everything is drawn with a few fixed-function quads per frame and
tweaks to vanilla fog: no framebuffers, no shadow pass, no GLSL. Expect ~0–2 FPS cost.

## What it does
- **Shader-style lighting** – recolors Minecraft's lightmap (the 16×16 table every block's light comes from): warm orange torchlight, blue moonlit nights, deeper shadows. Costs 256 pixels per light update, nothing per block
- **Sun glow + light rays** – soft halo and slowly turning light shafts, orange at sunrise/sunset, hidden by terrain and rain
- **Moon halo** – faint blue glow at night
- **Color grading** – warm amber golden hour, cool blue nights, grey-blue rain, teal underwater, warm Nether
- **Atmospheric fog** – depth haze starts closer (configurable) and turns peach at dawn/dusk
- **Vignette** – darkened screen edges
- **Glow halos** – soft "fake bloom" around nearby torches, lava, glowstone, fire, sea lanterns, redstone and portals, fading out in daylight
- **Emissive ores** – ores glow in dark caves; glow masks are generated automatically by comparing each ore texture with stone/netherrack/end stone, so modded ores work too (coal excluded by default)
- **Ambient particles** – fireflies on warm nights, leaves drifting from trees, dust motes in caves (respects the vanilla particle setting)
- **Biome atmosphere** – green swamp haze, warm desert dust, cold snowy air and more via Forge's biome dictionary, blended smoothly as you walk
- **Shooting stars** – now and then on clear nights
- **Dynamic lights** – held torches, dropped glowing items, burning mobs, blazes and fireballs light up their surroundings, LambDynamicLights-style (raises block light during chunk meshing and re-meshes only the few sections around moving lights; speed setting SMOOTH / FAST / FASTEST)
- **Lens flare** – subtle ghosts when looking toward the sun, hidden when terrain or trees block it

**Requires [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter)** (used for dynamic lights).
- **F7** toggles everything; every effect has its own switch and strength in
  Mods → Lumen → Config (or `config/lumen.cfg`)

## Building
Every push builds automatically on GitHub Actions — grab the jar from the latest run's
**Artifacts** (Actions tab → latest "Build" → Lumen). Locally: `./gradlew build` (jar in `build/libs/`).

Build setup is CleanroomMC's TemplateDevEnv (RetroFuturaGradle), see `LICENSE-TemplateDevEnv`.

## Compatibility
- Works with OptiFine as long as OptiFine shaders are **off** (that's the point).
- Fog tweaks are skipped underwater/in lava; grading/glow skip non-overworld skies where it'd look wrong.
