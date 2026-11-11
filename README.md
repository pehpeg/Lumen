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
- **F7** toggles everything; every effect has its own switch and strength in
  Mods → Lumen → Config (or `config/lumen.cfg`)

## Building
Every push builds automatically on GitHub Actions — grab the jar from the latest run's
**Artifacts** (Actions tab → latest "Build" → Lumen). Locally: `./gradlew build` (jar in `build/libs/`).

Build setup is CleanroomMC's TemplateDevEnv (RetroFuturaGradle), see `LICENSE-TemplateDevEnv`.

## Compatibility
- Works with OptiFine as long as OptiFine shaders are **off** (that's the point).
- Fog tweaks are skipped underwater/in lava; grading/glow skip non-overworld skies where it'd look wrong.
