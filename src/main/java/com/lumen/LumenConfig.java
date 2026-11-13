package com.lumen;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = Lumen.MODID, name = "lumen-lighting")
public class LumenConfig {

    @Config.Name("dynamic_lights")
    public static DynamicLights dynamicLights = new DynamicLights();
    @Config.Name("emissive")
    public static Emissive emissive = new Emissive();
    @Config.Name("block_light")
    public static Colored colored = new Colored();
    @Config.Name("shadows")
    public static Shadows shadows = new Shadows();

    public static class DynamicLights {
        @Config.Comment("Held torches, dropped glowing items, burning mobs, blazes, fireballs... light up the world.\n"
                + "Uses the game's own light engine, so it works with OptiFine, Nothirium, Vintagium, Celeritas and other renderers.")
        public boolean enabled = true;
        @Config.Comment("Update every N ticks (1 = smoothest, higher = less CPU)")
        @Config.RangeInt(min = 1, max = 10)
        public int updateInterval = 1;
        @Config.Comment("Max distance (blocks) for entities to emit dynamic light")
        @Config.RangeInt(min = 8, max = 128)
        public int range = 48;
        @Config.Comment("Max number of moving lights at once (nearest first)")
        @Config.RangeInt(min = 1, max = 256)
        public int maxSources = 48;
        @Config.Comment("Extra items that give light when held or dropped, as 'modid:item=level' (e.g. 'minecraft:blaze_rod=10').\n"
                + "Any item that places a light-emitting block already works automatically.")
        public String[] extraItems = {
                "minecraft:lava_bucket=15", "minecraft:blaze_rod=10", "minecraft:blaze_powder=8",
                "minecraft:glowstone_dust=8", "minecraft:nether_star=12", "minecraft:magma_cream=6",
                "minecraft:spectral_arrow=8"};
        @Config.Comment("Entities that always glow, as 'modid:entity=level'")
        public String[] glowingEntities = {
                "minecraft:blaze=10", "minecraft:magma_cube=8", "minecraft:fireball=14", "minecraft:small_fireball=12",
                "minecraft:dragon_fireball=12", "minecraft:spectral_arrow=8", "minecraft:tnt=10", "minecraft:xp_orb=4"};
        @Config.Comment("Torches and fire stop giving light underwater")
        public boolean waterSensitive = true;
    }

    public static class Emissive {
        @Config.Comment("Glowing ores and other emissive textures, drawn full-bright")
        public boolean enabled = true;
        @Config.Comment("Use emissive textures from resource packs (OptiFine format: 'diamond_ore_e.png' next to 'diamond_ore.png').\n"
                + "Any block with such a texture glows, not only ores.")
        public boolean resourcePackTextures = true;
        @Config.Comment("For ores without an emissive texture, generate one automatically (the ore bits, compared with stone)")
        public boolean autoOreMasks = true;
        @Config.Comment("Ores that should not get an automatic glow (registry name or just the path)")
        public String[] autoExclude = {"coal_ore"};
        @Config.Comment("Scan radius (blocks) for emissive blocks")
        @Config.RangeInt(min = 8, max = 64)
        public int radius = 32;
        @Config.Comment("Soft brightness pulse")
        public boolean pulse = true;
    }

    public static class Colored {
        @Config.Comment("Better block light: colored light (torches orange, redstone red, sea lanterns cyan, modded lamps in\n"
                + "their own color) and real shadows from torches and lamps - traced from each light through the blocks.")
        public boolean enabled = true;
        @Config.Comment("How strongly light colors tint the world (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double strength = 1.0;
        @Config.Comment("Blocks cast shadows from torches, lamps and other lights")
        public boolean shadows = true;
        @Config.Comment("How dark the shadows of block lights are (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double shadowStrength = 0.7;   // light bouncing off nearby surfaces keeps shadows from going black
        @Config.Comment("Shadow edge sharpness: shadow edges are refined down to 1/N of a block (16 = pixel sharp,\n"
                + "4 = quarter-block). Only refined where an edge actually is; costs background-thread time and some geometry.")
        @Config.RangeInt(min = 1, max = 16)
        public int resolution = 16;
        @Config.Comment("Max number of lights that get color and shadows (nearest first)")
        @Config.RangeInt(min = 4, max = 2048)
        public int maxLights = 512;
        @Config.Comment("Trace light on the graphics card (needs OpenGL 3.0; falls back to the CPU automatically).\n"
                + "Much lighter on the CPU, pixel-sharp at any distance, and moving lights follow every frame.")
        public boolean gpu = true;
        @Config.Comment("Radius in chunks around you that the graphics card lights; further away the CPU path takes over.\n"
                + "Each step costs some video memory (5 = about 16 MB).")
        @Config.RangeInt(min = 2, max = 8)
        public int gpuRadius = 5;
        @Config.Comment("Light definitions as 'modid:block=RRGGBB' or 'modid:block=RRGGBB,level'. The color overrides the automatic\n"
                + "color; the optional level (0..15) changes how far Lumen's light from that block reaches (0 = no Lumen light).\n"
                + "Works for modded lamps too.")
        public String[] colors = {
                "minecraft:torch=FF8A2A", "minecraft:fire=FF7020", "minecraft:lava=FF5A10", "minecraft:flowing_lava=FF5A10",
                "minecraft:lit_pumpkin=FF9A2A", "minecraft:lit_furnace=FF8028", "minecraft:magma=FF4A10",
                "minecraft:redstone_torch=FF1A0A", "minecraft:lit_redstone_ore=FF2010", "minecraft:lit_redstone_lamp=FFB048",
                "minecraft:glowstone=FFC838", "minecraft:sea_lantern=50D8FF", "minecraft:beacon=70F0FF",
                "minecraft:end_rod=E0B8FF", "minecraft:portal=9A30FF", "minecraft:end_portal=4A40FF",
                "minecraft:end_gateway=4A40FF", "minecraft:ender_chest=7A40FF", "minecraft:brewing_stand=FFD850"};
        @Config.Comment("How saturated light colors are (1 = as listed / as the texture, higher = more vivid)")
        @Config.RangeDouble(min = 0.5, max = 3)
        public double vibrance = 1.6;
        @Config.Comment("How far (blocks) lights get color and shadows. 0 = your render distance")
        @Config.RangeInt(min = 0, max = 512)
        public int radius = 0;
    }

    public static class Shadows {
        @Config.Comment("Players and mobs cast real shadows from the sun and nearby lights (replaces the round blob)")
        public boolean entityShadows = true;
        @Config.Comment("Sun shadows: trees, buildings and cliffs cast shadows, sides facing away from the sun are shaded.\n"
                + "Traced through the blocks off the main thread; works with any renderer.")
        public boolean enabled = true;
        @Config.Comment("How dark full shadow is (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double strength = 0.75;
        @Config.Comment("Radius in chunks around you that gets shadows")
        @Config.RangeInt(min = 2, max = 16)
        public int radiusChunks = 6;
        @Config.Comment("How far (blocks) toward the sun to look for shadow casters")
        @Config.RangeInt(min = 8, max = 64)
        public int castDistance = 32;
        @Config.Comment("Shadow edge sharpness: shadow edges are refined down to 1/N of a block (16 = pixel sharp,\n"
                + "4 = quarter-block). Only refined where an edge actually is; costs background-thread time and some geometry.")
        @Config.RangeInt(min = 1, max = 16)
        public int resolution = 16;
        @Config.Comment("Shadows move when the sun has moved this many degrees")
        @Config.RangeDouble(min = 2, max = 30)
        public double updateDegrees = 5;
        @Config.Comment("At night the moon casts faint shadows too (stronger at full moon)")
        public boolean moonShadows = true;
        @Config.Comment("Colored sky light: warm golden light at sunrise and sunset, slightly warm daylight, cool blue\n"
                + "moonlight. Only the sky's share of the light is tinted; torch-lit spots keep their own color.")
        public boolean skyColor = true;
        @Config.Comment("How strongly the sky light is colored (0..1)")
        @Config.RangeDouble(min = 0, max = 1)
        public double skyColorStrength = 0.8;
    }

    @Mod.EventBusSubscriber(modid = Lumen.MODID)
    public static class Sync {
        @SubscribeEvent
        public static void onChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
            if (Lumen.MODID.equals(e.getModID())) {
                ConfigManager.sync(Lumen.MODID, Config.Type.INSTANCE);
                com.lumen.client.LumenClient.onConfigChanged();
            }
        }
    }
}
